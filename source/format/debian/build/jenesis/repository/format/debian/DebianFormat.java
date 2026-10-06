package build.jenesis.repository.format.debian;

import module java.base;
import module org.apache.commons.compress;

import io.airlift.compress.v3.zstd.ZstdInputStream;
import build.jenesis.repository.format.LifecycleMark;
import build.jenesis.repository.format.Listings;
import build.jenesis.repository.format.debian.keys.DebianKeyring;
import build.jenesis.repository.format.signing.OpenPgpSigner;
import build.jenesis.repository.format.signing.SigningKeys;
import build.jenesis.repository.blobs.VersionFiles;
import build.jenesis.repository.blobs.BlobExport;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.format.RepositoryExporter;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.blobs.Keys;
import build.jenesis.repository.blobs.ProxyLeg;
import build.jenesis.repository.blobs.ProxyRelay;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.icon.IconResource;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArchiveInflation;
import build.jenesis.repository.store.ArchiveWalk;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.store.StoreCache;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.walk.PagedTreeWalk;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.walk.ScreenedNames;
import build.jenesis.repository.walk.Traversal;
import build.jenesis.repository.walk.TraversalException;
import build.jenesis.repository.walk.Trees;

/**
 * The Debian/apt format: {@code apt-get} installs and {@code .deb} uploads over the same store, under
 * {@code /debian/...}. A push ({@code PUT /debian/<suite>/pool/<component>/<file>.deb}) reads {@code Package},
 * {@code Version} and {@code Architecture} from the {@code control} file inside the {@code .deb} (an {@code ar} archive
 * whose {@code control.tar[.gz|.xz|.zst]} is a compressed tar), stores the file under
 * {@code debian/<suite>/pool/<component>/<file>.deb} and a {@code Packages} stanza - the control plus {@code Filename},
 * {@code Size} and checksums - under {@code debian/<suite>/index/<component>/<arch>/<file>}. The {@code Packages}
 * ({@code GET /debian/dists/<suite>/<component>/binary-<arch>/Packages[.gz]}) and {@code Release}
 * ({@code GET /debian/dists/<suite>/Release}) are stored listings the push maintains. A hosted {@code Release} is
 * OpenPGP-signed once a signing key is provisioned; without one {@code InRelease} and {@code Release.gpg} are absent
 * and a client trusts it with {@code [trusted=yes]}.
 *
 * <p>As a proxy, an immutable {@code .deb} is fetched, cached and served; {@code Release}, {@code InRelease} and
 * {@code Packages} pass through unchanged, so a proxied mirror verifies against the upstream's key - the release
 * documents remembered together for the upstream ttl and each index fetched by the digest they name
 * ({@link DebianSuiteMemory}). The leg keeps the
 * digest each {@code Packages} declared per package, the digest of that index as relayed, and the suite's
 * {@code InRelease} whole, so this repository can verify the same chain ({@link #indexCoverage}). A cached package
 * that is held stays listed in the relayed {@code Packages}, since leaving its stanza out would break the signature
 * the client verifies the index by; the package itself is refused when it is fetched, and that refusal is the hold.
 */
public final class DebianFormat implements RepositoryFormat, ProxyLeg, BlobLayout, ArtifactSignatures,
        RepositoryImporter.Delegating, RepositoryExporter {

    /** How many per-package digests one relayed index may record: a bound on a hostile upstream, well past a real suite
     *  (Debian main/amd64 carries some sixty thousand packages). */
    private static final int MAX_RECORDED_DIGESTS = 200_000;

    private static final String IDENTITY = "Jenesis Repository <repository@jenesis.build>";
    /** How long a generated signing key is valid before it must be rotated. */
    private static final Duration KEY_VALIDITY = Duration.ofDays(730);
    /** How long before expiry a fresh key takes over, leaving an overlap for clients to refetch the keyring. */
    private static final Duration ROTATION_WINDOW = Duration.ofDays(90);

    @Override
    public String name() {
        return "debian";
    }

    /** The marks this format's clients see, each by its own word. */
    @Override
    public Map<LifecycleMark, String> lifecycleMarks() {
        return LifecycleMark.shown(LifecycleMark.YANKED);
    }

    @Override
    public String ecosystem() {
        return "Debian";
    }

    /**
     * Debian's inbound signature: the debsig {@code _gpgorigin} member embedded in a {@code .deb}, optional rather than
     * expected, since apt's trust runs through the signed {@code Release}, which commits to each {@code Packages},
     * which commits to each package; demanding one would report every well-run archive as unsigned.
     *
     * <p>The signature covers the concatenation of the archive's other {@code ar} members in archive order, never the
     * file. Composing that stream is this format's job; verifying it is the shared inspector's.
     */
    @Override
    public List<ArtifactSignatures.Expectation> expects(String path) {
        // Required once a trusted keyring is provisioned (the keyring/trusted endpoint), optional until then; whether
        // one stands is the inspector's answer, so this stays a function of the path. A proxied package may also be
        // covered by the mirror's signed index (indexCoverage); a hosted one never is.
        return path.endsWith(".deb")
                ? List.of(ArtifactSignatures.Expectation.requiredWhenTrusted(ArtifactSignatures.Scheme.OPENPGP_DETACHED),
                          ArtifactSignatures.Expectation.optional(ArtifactSignatures.Scheme.OPENPGP_CLEARSIGNED))
                : List.of();
    }

    /** A package is published at the path it is served from, so the claim probe needs no declaration to reach it:
     *  that is only for a format whose publish endpoint is not its serving path. */
    @Override
    public boolean embedsEvidence(String path) {
        return false;
    }

    /** No signature material here has a request path of its own, so none covers another path. */
    @Override
    public Optional<String> covers(String path) {
        return Optional.empty();
    }

    @Override
    public List<ArtifactSignatures.Evidence> evidence(String path, ArtifactSignatures.Material material)
            throws IOException {
        Optional<ArtifactSignatures.Signed> body = material.body();
        if (body.isEmpty()) {
            return List.of();
        }
        List<ArtifactSignatures.Evidence> evidence = new ArrayList<>(DebianSignature.evidence(body.get()::open));
        indexCoverage(path, material).ifPresent(evidence::add);
        return List.copyOf(evidence);
    }

    /**
     * Coverage by the mirror's signed index, for a proxied {@code .deb}: the clearsigned {@code InRelease} commits to
     * each {@code Packages} digest, which commits to each package's. The middle document is tens of megabytes, so
     * nothing here reads it: the proxy leg recorded, per pool path, the digest its stanza declared and the digest of
     * the index as it streamed ({@link #recordIndex}), and kept the suite's {@code InRelease} whole. The evidence is
     * that document, and its {@link ArtifactSignatures.Named} makes the second hop: the document must name the streamed
     * index's digest in a line {@code <sha256> <size> <component>/binary-<arch>/Packages[.gz|.xz]}. An
     * {@code InRelease} that does not, relayed the other side of a mirror refresh, yields no evidence rather than a
     * mismatch.
     *
     * <p>The signer is the archive's, judged against the trust the deployment holds for that key. Verified off the
     * request path, once per assessment; the record it leaves is what a sweep re-judges.
     */
    private static Optional<ArtifactSignatures.Evidence> indexCoverage(String path, ArtifactSignatures.Material material)
            throws IOException {
        if (!path.startsWith("/debian/pool/") || !path.endsWith(".deb")) {
            return Optional.empty();
        }
        String pool = path.substring("/debian/".length());
        Optional<Properties> record = properties(material.recorded(digestKey(pool), SMALL_RECORD));
        if (record.isEmpty()) {
            return Optional.empty();
        }
        String sha256 = record.get().getProperty("sha256");
        String suite = record.get().getProperty("suite");
        String index = record.get().getProperty("index");
        if (sha256 == null || suite == null || index == null) {
            return Optional.empty();
        }
        Optional<Properties> streamed = properties(material.recorded(INDEX_DIGESTS + index, SMALL_RECORD));
        if (streamed.isEmpty() || streamed.get().getProperty("sha256") == null) {
            return Optional.empty();
        }
        String indexDigest = streamed.get().getProperty("sha256");
        Optional<PublishInterceptor.Content.Bounded> copy = material.recorded(INDEX_COPIES + suite + "/InRelease",
                ArtifactSignatures.Material.LARGEST_SIGNATURE);
        if (copy.isEmpty() || copy.get().truncated()) {
            return Optional.empty();
        }
        byte[] inRelease = copy.get().content();
        if (!namesIndex(inRelease, indexDigest)) {
            return Optional.empty();
        }
        String location = "dists/" + suite + "/InRelease (the mirror's signed index, via " + index + ")";
        return Optional.of(ArtifactSignatures.Evidence.covering(ArtifactSignatures.Scheme.OPENPGP_CLEARSIGNED,
                inRelease, () -> new ByteArrayInputStream(inRelease), location,
                document -> namesIndex(document, indexDigest) ? Optional.of(sha256) : Optional.empty()));
    }

    /** Whether a clearsigned {@code InRelease} names a {@code Packages} index by this digest, plain or compressed.
     *  Matched by digest, since a by-hash fetch carries only the digest. */
    static boolean namesIndex(byte[] inRelease, String digest) {
        Matcher line = INDEX_LINE.matcher(new String(inRelease, StandardCharsets.UTF_8));
        while (line.find()) {
            if (line.group(1).equalsIgnoreCase(digest)) {
                return true;
            }
        }
        return false;
    }

    private static final Pattern INDEX_LINE =
            Pattern.compile("(?m)^\\s*([0-9a-fA-F]{64})\\s+\\d+\\s+\\S+/Packages(?:\\.gz|\\.xz)?\\s*$");

    private static Optional<Properties> properties(Optional<PublishInterceptor.Content.Bounded> read)
            throws IOException {
        if (read.isEmpty() || read.get().truncated()) {
            return Optional.empty();
        }
        return Optional.of(properties(read.get().content()));
    }

    /**
     * The package version a stored Debian pointer serves, from which the inventory back-fill rebuilds a lost
     * {@code published} record.
     *
     * <p>A {@code .deb} is named {@code <name>_<version>_<arch>.deb} and Debian policy forbids an underscore in a
     * package name or a version, so the split is exact. It is the parse {@link #describe} performs on the request path,
     * so the row rebuilt matches the row the publish wrote.
     */
    @Override
    public Optional<ArtifactDescriptor> describePointer(String key) {
        if (!key.startsWith("debian/") || !key.endsWith(".deb")) {
            return Optional.empty();
        }
        String file = key.substring(key.lastIndexOf('/') + 1, key.length() - ".deb".length());
        String[] parts = file.split("_");
        if (parts.length < 2) {
            return Optional.empty();
        }
        String coordinate = parts[0], version = parts[1];
        if (!BlobLayout.addressable(coordinate, version)) {
            return Optional.empty();
        }
        return Optional.of(new ArtifactDescriptor("Debian", coordinate, version, key,
                "application/octet-stream", version.contains("~"), null, 0L));
    }

    @Override
    public List<String> blobRoots() {
        return List.of("debian");
    }

    @Override
    public List<String> blobKeys(String coordinate, String version, ArtifactStore store) throws IOException {
        // Each .deb is keyed on its pool path, its filename <package>_<version>_<arch>.deb without the control
        // Version's epoch. A version may sit under several suites, components or architectures, and all are its keys,
        // found through each suite's reverse index or the bounded pool walk.
        if (!BlobLayout.addressable(coordinate, version)) {
            return List.of();   // a traversal-shaped coordinate maps nowhere - these keys are what an eviction DELETES
        }
        String fileVersion = stripEpoch(version);
        List<String> keys = new ArrayList<>();
        // The files the version's record lists - under the filename's epoch-less version, as describe() names it -
        // answer without a walk; a version recorded before it listed them is walked. A listed file whose pointer is
        // gone was evicted, and is passed over.
        Optional<List<String>> listed = VersionFiles.installed().listed(store, ecosystem(), coordinate, fileVersion);
        if (listed.isPresent()) {
            for (String key : listed.get()) {
                if (store.readVersioned(key).isPresent()) {
                    keys.add(key);
                }
            }
            return keys;
        }
        SUITES.scan(store, "debian", suite -> collectDebs(store, "debian/" + suite + "/pool", coordinate, fileVersion,
                keys));
        return keys;
    }

    /** Walk one suite's pool subtree through the bounded tree walk, adding every {@code .deb} leaf whose filename
     *  matches the package and epoch-stripped version. The descent is arbitrary-depth, iterative and paged. A leaf is a
     *  key that {@link ArtifactStore#exists exists} ({@link Trees}), so a {@code .deb}-named directory emptied by a
     *  partial delete is never collected for eviction to miss. */
    private static void collectDebs(ArtifactStore store, String root, String coordinate, String fileVersion,
                                    List<String> keys) throws IOException {
        POOL.walk(store, root, key -> {
            if (!key.endsWith(".deb")) {
                return;
            }
            String file = key.substring(key.lastIndexOf('/') + 1);
            String[] parts = file.substring(0, file.length() - ".deb".length()).split("_");
            if (parts.length == 3 && parts[0].equals(coordinate) && parts[1].equals(fileVersion)) {
                keys.add(key);
            }
        });
    }

    /** The suite level, enumerated for compliance reads that must see every suite a version sits in. The entry cap is
     *  off; the step budget (1000 page round-trips) binds, and throws rather than answering short. */
    private static final BoundedChildren SUITES = BoundedChildren.bounded().entries(Integer.MAX_VALUE);

    /** The pool descent's budget, one probe per opened node. {@code blobKeys} feeds holds, eviction and
     *  {@code servedPaths}, so a truncated answer is a wrong one: the entry cap equals the step budget so only the step
     *  cap, which raises a {@link TraversalException}, can bind. Depth stays at {@link ArtifactStore#MAX_SEGMENTS}. */
    private static final int POOL_NODES = 1_000_000;

    private static final PagedTreeWalk POOL = PagedTreeWalk.bounded().steps(POOL_NODES).entries(POOL_NODES)
            .page(BoundedChildren.DRAIN_PAGE);

    /** The filename version: the control {@code Version} without its {@code epoch:} prefix, as the {@code .deb}
     *  filename and {@link #describe} carry it. */
    private static String stripEpoch(String version) {
        int colon = version.indexOf(':');
        return colon < 0 ? version : version.substring(colon + 1);
    }

    /** The request paths this package version serves: its pool keys from {@link #blobKeys} as request paths, where a
     *  retroactive hold links its {@code /quarantine} handles. */
    @Override
    public List<String> servedPaths(String coordinate, String version, ArtifactStore store) throws IOException {
        List<String> paths = new ArrayList<>();
        for (String key : blobKeys(coordinate, version, store)) {
            paths.add("/" + key);
        }
        return paths;
    }

    /** A {@code .deb} is served from the pool key its path names. */
    @Override
    public Optional<String> servingKey(String requestPath, ArtifactStore store) throws IOException {
        return describedVersion(requestPath).isEmpty() ? Optional.empty()
                : BlobLayout.stored(requestPath.substring(1), store);
    }

    /** The coordinate a {@code .deb} request path carries, from its {@code <package>_<version>_<arch>.deb} filename:
     *  the package name, as the compliance inspector reads it from the control. The filename version lacks any epoch.
     *  A source package's files - {@code <source>_<version>.dsc}, its {@code .debian.tar.*} or {@code .diff.gz} and
     *  its {@code .orig.tar.*} - carry the source package and version, so they are screened by coordinate as a
     *  {@code .deb} is; an {@code .orig} tarball names the upstream version alone, as the archive spells it. The
     *  generated indexes and the keyring endpoints name no package and stay empty, as does a filename off the
     *  convention. */
    @Override
    public Optional<ArtifactDescriptor> describe(String path) {
        if (path.startsWith("/debian/")) {
            Optional<ArtifactDescriptor> source = source(path);
            if (source.isPresent()) {
                return source;
            }
        }
        if (!path.startsWith("/debian/") || !path.endsWith(".deb")) {
            return Optional.empty();
        }
        String file = path.substring(path.lastIndexOf('/') + 1);
        String[] parts = file.substring(0, file.length() - ".deb".length()).split("_");
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || !Character.isDigit(parts[1].charAt(0))) {
            return Optional.of(ArtifactDescriptor.at("Debian", path));
        }
        return Optional.of(new ArtifactDescriptor("Debian", parts[0], parts[1], path,
                "application/vnd.debian.binary-package", false, null, -1L));
    }

    /** A source package's file, by the suffix the archive gives each, where the path names one. */
    private static Optional<ArtifactDescriptor> source(String path) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        String stem = null;
        for (String suffix : SOURCE_SUFFIXES) {
            int at = file.indexOf(suffix);
            if (at > 0 && SOURCE_TAILS.contains(file.substring(at + suffix.length()))) {
                stem = file.substring(0, at);
                break;
            }
        }
        if (stem == null) {
            return Optional.empty();
        }
        String[] parts = stem.split("_");
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty() || !Character.isDigit(parts[1].charAt(0))) {
            return Optional.of(ArtifactDescriptor.at("Debian", path));
        }
        return Optional.of(new ArtifactDescriptor("Debian", parts[0], parts[1], path, "application/octet-stream",
                false, null, -1L));
    }

    /** The suffixes a source package's files carry before their compression, the {@code .dsc} with none. */
    private static final List<String> SOURCE_SUFFIXES = List.of(".dsc", ".debian.tar", ".orig.tar", ".diff");

    /** What may follow a {@link #SOURCE_SUFFIXES source suffix}: nothing, or a compression's extension. */
    private static final Set<String> SOURCE_TAILS = Set.of("", ".gz", ".xz", ".bz2", ".lzma", ".zst");

    // An original CC0 line glyph (a two-arc swirl).
    private static final IconResource ICON = IconResource.svg("""
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round">
              <path d="M15 4.5a8 8 0 1 0 3 12.7"/><path d="M13 8.5a4 4 0 1 0 1.5 6.2"/>
            </svg>""");

    @Override
    public Optional<IconResource> icon() {
        return Optional.of(ICON);
    }

    @Override
    public Optional<URI> defaultUpstream() {
        return Optional.of(URI.create("https://deb.debian.org/debian/"));
    }

    @Override
    public boolean handles(String path) {
        return path.startsWith("/debian/");
    }

    @Override
    public void serve(FormatExchange exchange, ArtifactStore store) throws IOException {
        Blobs blobs = new Blobs(store);
        String rest = exchange.path().substring("/debian/".length());
        String method = exchange.method();
        if (method.equals("POST") && rest.equals("keyring")) {
            provisionKey(blobs, exchange);
        } else if (method.equals("POST") && rest.equals("keyring/trusted")) {
            addTrustedKey(blobs, exchange);
        } else if (method.equals("PUT") && rest.endsWith(".deb")) {
            push(rest, exchange, blobs, store);
        } else if (!method.equals("GET") && !method.equals("HEAD")) {
            exchange.respond(405);
        } else if (rest.equals("keyring/public.asc")) {
            servePublicKey(blobs, exchange);
        } else if (rest.endsWith(".deb")) {
            serve(rest, blobs, exchange);
        } else if (rest.equals("dists") || rest.equals("dists/")) {
            suites(blobs, exchange);
        } else if (rest.startsWith("dists/")) {
            metadata(rest, blobs, exchange);
        } else {
            exchange.respond(404);
        }
    }

    /**
     * Whether a suite has anything left to show, the membership question {@code GET /debian/dists/} asks.
     *
     * <p>The screened enumeration cannot answer it: the children of {@code debian/<suite>/index} are component
     * containers, not pointers, so a suite whose every package is held would still list. The suite manifest answers it
     * exactly, since it holds a line only for an index with at least one servable package; an empty manifest means
     * every package is held.
     *
     * <p>Only while the manifest is current: it is a deferred derivation, and a stale one would drop a freshly
     * published suite. Inside that window this falls back to the screened probe. It does not call {@code announce} to
     * catch up, which would be a write on a read fanned out over every suite.
     */
    private static boolean disclosable(DebianListings listings, Blobs blobs, String suite) throws IOException {
        // header(), not read(): read() materialises an absent listing, and a suite whose indexes were never published
        // through the listing path would get an empty manifest and be hidden while it serves.
        if (listings.current(suite) && StoredListing.header(blobs.store(), DebianListings.manifest(suite)).isPresent()) {
            Optional<StoredListing.Document> manifest = StoredListing.read(blobs.store(), listings.manifestSpec(suite));
            if (manifest.isPresent()) {
                return manifest.get().body().length > 0;
            }
        }
        // No derivation has run for this suite yet, or it lags: fall back to the screened probe.
        return ScreenedNames.keys(blobs.servableNames(), ServableNames.Policy.HIDE_WITHHELD)
                .any(blobs.store(), "debian/" + suite + "/index");
    }

    /** An autoindex of the hosted suites ({@code GET /debian/dists/}), linked as a mirror's httpd renders it: the page
     *  an enumeration discovers suites from, since apt never lists them. */
    private void suites(Blobs blobs, FormatExchange exchange) throws IOException {
        List<String> suites = new ArrayList<>();
        DebianListings listings = listings(blobs);
        for (String child : blobs.list("debian")) {
            if (disclosable(listings, blobs, child)) {
                suites.add(child);
            }
        }
        if (suites.isEmpty()) {
            exchange.respond(404);
            return;
        }
        Collections.sort(suites);
        StringBuilder page = new StringBuilder("<html><body><h1>Index of /dists/</h1><a href=\"../\">../</a>");
        for (String suite : suites) {
            // The suite is a publish path segment that may hold < > " &, so it is escaped in both the href and the
            // text, or it would be stored cross-site scripting on this page.
            String escaped = Listings.html(suite);
            page.append("<a href=\"").append(escaped).append("/\">").append(escaped).append("/</a>");
        }
        exchange.setResponseHeader("Content-Type", "text/html");
        exchange.respond(200, page.append("</body></html>").toString().getBytes(StandardCharsets.UTF_8));
    }

    /** The index fields the server appends to a control to build the served stanza. A control must not declare any of
     *  them, or the stanza would carry a duplicate whose apt resolution is undefined. */
    private static final Set<String> RESERVED_INDEX_FIELDS = Set.of("filename", "size", "md5sum", "sha1", "sha256");

    /** Whether {@code control} declares a {@linkplain #RESERVED_INDEX_FIELDS reserved index field}: a field header
     *  starts a line (a folded continuation does not) and is matched case-insensitively. */
    private static boolean declaresReservedIndexField(String control) {
        return control.lines().anyMatch(line -> {
            int colon = line.indexOf(':');
            if (colon <= 0 || Character.isWhitespace(line.charAt(0))) {
                return false;
            }
            return RESERVED_INDEX_FIELDS.contains(line.substring(0, colon).trim().toLowerCase(Locale.ROOT));
        });
    }

    /** Provisioning the signing key and naming the signers the repository trusts are its operator's acts, not
     *  publishes. */
    @Override
    public boolean administers(String method, String path) {
        return method.equals("POST") && (path.equals("/debian/keyring") || path.equals("/debian/keyring/trusted"));
    }

    /** Ensure a signing key exists, generating one on the first call, and answer its public key ({@link #keys}). Suites
     *  indexed before the key existed are re-signed on the node's derivation thread, a page at a time, so the answer
     *  costs one key and never a walk. */
    private void provisionKey(Blobs blobs, FormatExchange exchange) throws IOException {
        if (keys(blobs).signer().isEmpty()) {
            keys(blobs).provision();
            DebianListings listings = listings(blobs);
            StoredListing.later(blobs.store().identity() + "|debian/keyring/signing.rederive", () -> {
                try {
                    rederiveSigned(blobs, listings);
                } catch (IOException failed) {
                    throw new UncheckedIOException(failed);
                }
            });
        }
        servePublicKey(blobs, exchange);
    }

    /** How many suites a re-signing reads the names of at a time. */
    private static final int RESIGN_PAGE = 1_000;

    /** Re-derive the signed {@code Release} family of every indexed suite, paging through their names. */
    private static void rederiveSigned(Blobs blobs, DebianListings listings) throws IOException {
        String after = "";
        while (true) {
            List<String> page = new ArrayList<>();
            blobs.page("debian", after, RESIGN_PAGE, page::add);
            for (String suite : page) {
                if (!blobs.isEmpty("debian/" + suite + "/index")) {
                    listings.rederiveRelease(suite);
                }
            }
            if (page.size() < RESIGN_PAGE) {
                return;
            }
            after = page.getLast();
        }
    }

    private void servePublicKey(Blobs blobs, FormatExchange exchange) throws IOException {
        Optional<byte[]> keyring = keys(blobs).publicKeyring();
        if (keyring.isEmpty()) {
            exchange.respond(404);
            return;
        }
        exchange.setResponseHeader("Content-Type", "application/pgp-keys");
        exchange.respond(200, keyring.get());
    }

    /** This repository's signing key and served keyring, one document beside its suites. */
    private static SigningKeys keys(Blobs blobs) {
        return new SigningKeys(blobs.store(), "debian/keyring/signing", IDENTITY, KEY_VALIDITY, ROTATION_WINDOW);
    }

    /** The largest trusted-signers key upload, read through a bounded {@code readNBytes} so an oversize upload is
     *  refused and never buffered; an armored key is a few kilobytes. */
    private static final int MAX_TRUSTED_KEY = 1024 * 1024;

    /** Provision a trusted-signers key: an armored public key merged into the trusted keyring. From then on the
     *  signature dimension expects every pushed {@code .deb} to carry an embedded signature verifying against one of
     *  these keys, each outcome decided by its dial. This format judges nothing on push, so one upload never gets two
     *  judgements. */
    private void addTrustedKey(Blobs blobs, FormatExchange exchange) throws IOException {
        byte[] key = exchange.requestStream().readNBytes(MAX_TRUSTED_KEY + 1);
        if (key.length > MAX_TRUSTED_KEY) {
            exchange.respond(413);   // an armored public key is small; refuse an oversize body rather than buffer it
            return;
        }
        ByteArrayOutputStream existing = new ByteArrayOutputStream();
        byte[] merged = blobs.read(DebianKeyring.KEY, existing)
                ? OpenPgpSigner.mergePublicKeyrings(existing.toByteArray(), key, Instant.EPOCH)
                : key;
        blobs.write(DebianKeyring.KEY, merged);
        StoreCache.of(DebianKeyring.CACHE, blobs.store(), StoreCache.configuredTtl())
                .invalidate(DebianKeyring.KEY);
        exchange.respond(201);
    }

    /** The current signer, or {@code null} when none is provisioned. A near-expiry key rotates as it is asked for, in
     *  the write that publishes its successor beside it, so a client trusting the retiring key still verifies during
     *  the overlap. */
    private OpenPgpSigner signer(Blobs blobs) throws IOException {
        return keys(blobs).signer().orElse(null);
    }

    private void push(String rest, FormatExchange exchange, Blobs blobs, ArtifactStore store) throws IOException {
        String[] segments = rest.split("/");
        if (segments.length < 4 || !segments[1].equals("pool")) {
            exchange.respond(400);
            return;
        }
        // Streamed into the content-addressed store, then the stored blob is reopened to parse its control; the store's
        // SHA-256 is the package's SHA256 checksum. The signature is judged by the signature dimension, not here.
        String hash = blobs.store(exchange.requestStream());
        String control;
        try (InputStream deb = blobs.open(hash)) {
            control = control(deb);
        } catch (RuntimeException | IOException malformed) {
            // An unreadable control - a bomb the bound truncated, a broken ar/tar - is a malformed upload: the 400
            // below rather than a 500.
            control = null;
        }
        String architecture = control == null ? null : DebianListings.field(control, "Architecture");
        if (architecture == null) {
            exchange.respond(400);
            return;
        }
        if (control.stripTrailing().lines().anyMatch(String::isBlank)) {
            // A control is a single paragraph. It is echoed into the served Packages, where a blank line separates
            // stanzas, so an internal blank line would splice an attacker-chosen stanza into the shared index. Folded
            // lines begin with a space or tab and are not blank.
            exchange.respond(400);
            return;
        }
        if (declaresReservedIndexField(control)) {
            // The server appends the authoritative Filename, Size and checksums; a control declaring one would produce
            // a duplicate field, which could steer apt at another blob. A source control never carries them.
            exchange.respond(400);
            return;
        }
        String suite = segments[0], component = segments[2], file = segments[segments.length - 1];
        if (Keys.unsafe(suite) || Keys.unsafe(component) || Keys.unsafe(architecture) || Keys.unsafe(file)
                || component.startsWith("@")) {
            // A control-supplied architecture must not forge a pointer key, and a component beginning with @ would
            // collide with the suite's stamp.
            exchange.respond(400);
            return;
        }
        // The filename's package is the coordinate the edge screens, and the stanza's Package comes from the control.
        // They must agree, or a package screened under one name would be served under another.
        String pathPackage = null;
        String pathVersion = null;
        if (file.endsWith(".deb")) {
            String[] nameParts = file.substring(0, file.length() - ".deb".length()).split("_");
            if (nameParts.length == 3 && !nameParts[0].isEmpty()
                    && !nameParts[1].isEmpty() && Character.isDigit(nameParts[1].charAt(0))) {
                pathPackage = nameParts[0];
                pathVersion = nameParts[1];
            }
        }
        String declaredPackage = DebianListings.field(control, "Package");
        if (pathPackage != null && declaredPackage != null && !declaredPackage.equals(pathPackage)) {
            exchange.respond(400);   // control Package disagrees with the screened filename package - refuse the mismatch
            return;
        }
        long size = store.size("blobs/" + hash);
        String[] md5sha1 = digests(blobs, hash);
        // Blobs.link clears any gc/condemned marker on a blob a collector judged unreferenced.
        if (pathPackage != null) {
            // The version's record lists the file before its pointer is linked, so no linked file is missing from the
            // list an eviction deletes by.
            VersionFiles.installed().record(store, ecosystem(), pathPackage, pathVersion, "debian/" + rest);
        }
        try {
            blobs.linkRelease("debian/" + rest, hash, -1L);
        } catch (Publication.RepublishConflict taken) {
            exchange.respond(409, Blobs.alreadyPublished(rest));
            return;
        }
        String stanza = control.stripTrailing() + "\n"
                + "Filename: " + rest + "\n"
                + "Size: " + size + "\n"
                + "MD5sum: " + md5sha1[0] + "\n"
                + "SHA1: " + md5sha1[1] + "\n"
                + "SHA256: " + hash + "\n";
        blobs.write(DebianListings.stanzaKey(suite, component, architecture, file),
                stanza.getBytes(StandardCharsets.UTF_8));
        // The served index is maintained here, on the push: the stanza joins its Packages document if servable, which
        // re-derives Packages.gz and the suite's Release family.
        listings(blobs).published(suite, component, architecture, file, stanza);
        exchange.respond(201);
    }

    DebianListings listings(Blobs blobs) {
        return new DebianListings(blobs, this::signerOrNull);
    }

    private OpenPgpSigner signerOrNull(Blobs blobs) {
        try {
            return signer(blobs);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The {@code MD5sum} and {@code SHA1} of a stored blob, computed in one streaming pass. */
    private static String[] digests(Blobs blobs, String hash) throws IOException {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            try (InputStream in = blobs.open(hash)) {
                byte[] buffer = new byte[8192];
                for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                    md5.update(buffer, 0, read);
                    sha1.update(buffer, 0, read);
                }
            }
            return new String[]{HexFormat.of().formatHex(md5.digest()), HexFormat.of().formatHex(sha1.digest())};
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void serve(String rest, Blobs blobs, FormatExchange exchange) throws IOException {
        blobs.answer("debian/" + rest, exchange, "application/vnd.debian.binary-package");
    }

    private void metadata(String rest, Blobs blobs, FormatExchange exchange) throws IOException {
        String[] segments = rest.split("/");
        DebianListings listings = listings(blobs);
        if (segments.length == 3 && (segments[2].equals("Release") || segments[2].equals("InRelease")
                || segments[2].equals("Release.gpg"))) {
            String suite = segments[1];
            // The Release family is derived off the index write. A read that finds it absent or behind the newest index
            // write derives it once, rather than serve a Release older than a Packages it names; a suite with no index
            // stays a 404.
            Optional<StoredListing.Served> served = StoredListing.openDerived(blobs.store(),
                    DebianListings.release(suite, segments[2]));
            if ((served.isEmpty() && !blobs.isEmpty("debian/" + suite + "/index"))
                    || (served.isPresent() && !listings.current(suite))) {
                served.ifPresent(DebianFormat::closeQuietly);
                listings.announce(suite);
                served = StoredListing.openDerived(blobs.store(), DebianListings.release(suite, segments[2]));
            }
            if (served.isEmpty()) {
                exchange.respond(404);
                return;
            }
            try (StoredListing.Served release = served.get()) {
                if (release.header().size() == 0 && segments[2].equals("Release")) {
                    exchange.respond(404);
                    return;
                }
                respondListing(exchange, release, segments[2].equals("Release.gpg")
                        ? "application/pgp-signature" : "text/plain; charset=utf-8");
            }
        } else if (segments.length == 5 && segments[3].startsWith("binary-")
                && (segments[4].equals("Packages") || segments[4].equals("Packages.gz"))) {
            String suite = segments[1], component = segments[2], architecture = segments[3].substring("binary-".length());
            if (Keys.unsafe(suite) || Keys.unsafe(component) || Keys.unsafe(architecture)
                    || (!StoredListing.present(blobs.store(), DebianListings.packages(suite, component, architecture))
                            && blobs.isEmpty("debian/" + suite + "/index/" + component + "/" + architecture))) {
                exchange.respond(404);   // a raw-empty container, the structural probe a proxy repository needs
                return;
            }
            StoredListing.Spec spec = listings.packagesSpec(suite, component, architecture);
            Optional<StoredListing.Served> served;
            String contentType;
            if (segments[4].endsWith(".gz")) {
                // A twin read inside the derivation's lag window derives it once.
                Optional<StoredListing.Header> index = StoredListing.header(blobs.store(), spec.listing());
                served = StoredListing.openDerived(blobs.store(), spec.listing() + ".gz");
                if (served.isEmpty() || index.isEmpty() || served.get().header().seq() < index.get().seq()) {
                    if (served.isPresent()) {
                        served.get().close();
                    }
                    StoredListing.open(blobs.store(), spec).ifPresent(DebianFormat::closeQuietly);
                    listings.compress(suite, component, architecture);
                    served = StoredListing.openDerived(blobs.store(), spec.listing() + ".gz");
                }
                contentType = "application/gzip";
            } else {
                served = StoredListing.open(blobs.store(), spec);
                contentType = "text/plain; charset=utf-8";
            }
            if (served.isEmpty()) {
                exchange.respond(404);
                return;
            }
            try (StoredListing.Served packages = served.get()) {
                respondListing(exchange, packages, contentType);
            }
        } else {
            exchange.respond(404);
        }
    }

    private static void closeQuietly(StoredListing.Served served) {
        try {
            served.close();
        } catch (IOException ignored) {
            // nothing was read from it
        }
    }

    /** Stream a stored listing with the revalidation apt relies on: the ETag is the document's digest, so a matching
     *  {@code If-None-Match} answers {@code 304} from the header, and {@code HEAD} answers from the stored length. */
    private static void respondListing(FormatExchange exchange, StoredListing.Served served, String contentType)
            throws IOException {
        Listings.serve(exchange, served, contentType);
    }

    /** Proxy a Debian miss to the upstream apt repository. A {@code .deb} is immutable, so it is fetched, cached and
     *  served; a suite's release documents are remembered together and its indexes fetched by the digest they name
     *  ({@link DebianSuiteMemory}), or all streamed fresh from an upstream that offers no by-hash path - needing no
     *  rewrite since the upstream's root maps to this repository's {@code /debian/}. */
    @Override
    public boolean pullThrough(FormatExchange exchange, ArtifactStore store, URI upstream,
                               ProxyFormat.Fetcher fetcher) throws IOException {
        String path = exchange.path();
        String rest = path.substring("/debian/".length());
        String root = upstream.toString();
        if (!root.endsWith("/")) {
            root += "/";
        }
        if (rest.endsWith(".deb")) {
            // The digest recorded when the index went past (recordDigests), under the pool path, which is the stanza's
            // Filename.
            ProxyRelay.Declared declared = recordedDigest(rest, store);
            // Debian publishes a .deb's SHA256 only inside a Packages index, and a pool path cannot name its index; but
            // apt fetches the index first and it streams through this leg, so the digest was recorded on the way past.
            // A corrupted body is refused and not cached. With no record yet the fill declares NONE and serves
            // unverified: no index has told us.
            //
            // Streamed from the network into the content-addressed store, since a .deb is unbounded.
            URI target = URI.create(root + rest);
            try (ProxyFormat.Download download = fetcher.download(target, Map.of()).orElse(null)) {
                if (download == null || download.status() != 200) {
                    return false;
                }
                if (!ProxyRelay.fill(new Blobs(store), "debian/" + rest, target, download.body(), declared)) {
                    return false;
                }
            }
            handle(exchange, store);
            return true;
        }
        // Anything else is a mutable index or a large source body, streamed through unchanged so the upstream's
        // signature stays valid, with conditional-request validators forwarded both ways so apt can revalidate.
        //
        // Under dists/ live the documents apt resolves against, where an absence is an answer, so a fetch that could
        // not be made must not render as one; under pool/ live bodies reached by name, where a 404 means "re-pull".
        ProxyRelay.Document document = rest.startsWith("pool/")
                ? ProxyRelay.Document.PINNED
                : ProxyRelay.Document.ENUMERATION;
        // A Packages index - plain, .gz, .xz or by-hash, told apart by its bytes - is read as it streams and what it
        // declares is recorded with its relayed digest, and the suite's InRelease is kept: together they let a proxied
        // package be held to its digest and judged by the archive's signature (indexCoverage).
        ProxyRelay.Tap tap = null;
        if (packagesIndex(rest)) {
            tap = body -> recordIndex(body, store, rest);
        } else if (rest.matches("dists/[^/]+/InRelease")) {
            tap = body -> keepInRelease(body, store, rest);
        }
        // A suite's release documents are remembered together, and an index the remembered Release names is fetched
        // by its digest, so what a client is given always agrees with the Release it was given (DebianSuiteMemory).
        Optional<String> suite = DebianSuiteMemory.releaseOf(rest);
        if (suite.isPresent()) {
            return DebianSuiteMemory.relayRelease(fetcher, root, suite.get(), rest, exchange, store, document, tap);
        }
        URI target = DebianSuiteMemory.pinned(root, rest, store).orElse(URI.create(root + rest));
        return ProxyRelay.streamFresh(fetcher, target, null, exchange, document, tap);
    }

    /** The store key a relayed index's declaration for one pool path is recorded under. */
    private static String digestKey(String poolPath) {
        return "debian/index-digest/" + poolPath;
    }

    /** The keys the digest of a relayed index is recorded under, by the index's request path under {@code dists/}. */
    private static final String INDEX_DIGESTS = "debian/index-sha256/";

    /** The store keys a suite's relayed {@code InRelease} is kept whole under, by suite. */
    private static final String INDEX_COPIES = "debian/index/";

    /** The most of a small record - a package's declaration, an index's digest - a reader takes back. */
    private static final int SMALL_RECORD = 4096;

    /** What a relayed {@code Packages} index declared for the {@code .deb} at {@code poolPath}, or
     *  {@link ProxyRelay.Declared#NONE} when no index has passed through yet. Absence is not a refusal: a client that
     *  skipped the index still gets the bytes, unverified. */
    private static ProxyRelay.Declared recordedDigest(String poolPath, ArtifactStore store) {
        try {
            Optional<ArtifactStore.Versioned> recorded = store.readVersioned(digestKey(poolPath));
            if (recorded.isEmpty()) {
                return ProxyRelay.Declared.NONE;
            }
            String sha256 = properties(recorded.get().content()).getProperty("sha256");
            // of(), not text(): Packages states hex and the fill compares bytes; text() is for a declaration compared
            // as a string.
            return sha256 == null
                    ? ProxyRelay.Declared.NONE
                    : ProxyRelay.Declared.of("SHA-256", HexFormat.of().parseHex(sha256.trim()));
        } catch (IOException | RuntimeException unreadable) {
            return ProxyRelay.Declared.NONE;
        }
    }

    /** A relayed {@code Packages} index under {@code dists/}: plain, {@code .gz}, {@code .xz}, or a by-hash fetch of
     *  one, which names no suffix. */
    private static boolean packagesIndex(String rest) {
        return rest.startsWith("dists/") && (rest.endsWith("/Packages") || rest.endsWith("/Packages.gz")
                || rest.endsWith("/Packages.xz")
                || rest.matches("dists/[^/]+/[^/]+/binary-[^/]+/by-hash/SHA256/[0-9a-fA-F]{64}"));
    }

    /** Read a relayed {@code Packages} index as it streams, decompressed by its leading bytes, recording each stanza's
     *  {@code Filename} -> {@code SHA256} with its suite and index, and at the end the digest of the bytes as relayed,
     *  which the suite's {@code InRelease} names ({@link #indexCoverage}). Decompressing costs a pass per refresh and
     *  covers the compressed index apt actually fetches. */
    private static void recordIndex(InputStream body, ArtifactStore store, String rest) throws IOException {
        String suite = rest.split("/")[1];
        MessageDigest sha256 = sha256();
        DigestInputStream relayed = new DigestInputStream(body, sha256);
        recordDigests(decompressed(relayed), store, suite, rest);
        relayed.transferTo(OutputStream.nullOutputStream());   // whatever the parse left: the digest is of the whole
        writeRecord(store, INDEX_DIGESTS + rest,
                ("sha256=" + HexFormat.of().formatHex(sha256.digest()) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /** The index as text: gzip and xz are told by their leading bytes, since a by-hash fetch names no suffix. */
    private static InputStream decompressed(InputStream raw) throws IOException {
        PushbackInputStream peek = new PushbackInputStream(raw, 6);
        byte[] head = peek.readNBytes(6);
        peek.unread(head);
        if (head.length >= 2 && (head[0] & 0xff) == 0x1f && (head[1] & 0xff) == 0x8b) {
            return new GzipCompressorInputStream(peek);
        }
        if (head.length >= 6 && (head[0] & 0xff) == 0xfd && head[1] == '7' && head[2] == 'z' && head[3] == 'X'
                && head[4] == 'Z' && head[5] == 0) {
            return new XZCompressorInputStream(peek);
        }
        return peek;
    }

    /** Keep a suite's clearsigned {@code InRelease} whole as it streams. Held to the signature bound a verifier reads
     *  it under: one past it is not kept and a stale copy is dropped, so nothing vouches on an unverifiable
     *  document. */
    static void keepInRelease(InputStream body, ArtifactStore store, String rest) throws IOException {
        String key = INDEX_COPIES + rest.split("/")[1] + "/InRelease";
        byte[] copy = body.readNBytes(ArtifactSignatures.Material.LARGEST_SIGNATURE + 1);
        body.transferTo(OutputStream.nullOutputStream());
        if (copy.length > ArtifactSignatures.Material.LARGEST_SIGNATURE) {
            if (store.exists(key)) {
                store.delete(key);
            }
            return;
        }
        writeRecord(store, key, copy);
    }

    /** Record each stanza's {@code Filename} -> {@code SHA256} from a streaming {@code Packages} index, holding one
     *  stanza's two fields at a time. A record is written only when it differs, so a refresh of an unchanged suite
     *  writes nothing. */
    private static void recordDigests(InputStream body, ArtifactStore store, String suite, String index)
            throws IOException {
        BufferedReader lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        String filename = null;
        String sha256 = null;
        int recorded = 0;
        for (String line = lines.readLine(); line != null; line = lines.readLine()) {
            if (line.isEmpty()) {
                recorded += record(filename, sha256, store, suite, index);
                filename = null;
                sha256 = null;
            } else if (line.startsWith("Filename:")) {
                filename = line.substring("Filename:".length()).trim();
            } else if (line.startsWith("SHA256:")) {
                sha256 = line.substring("SHA256:".length()).trim();
            }
            if (recorded >= MAX_RECORDED_DIGESTS) {
                // Past the cap the remaining packages proxy unverified, as an unrecorded package does.
                return;
            }
        }
        record(filename, sha256, store, suite, index);   // an index whose last stanza has no trailing blank line
    }

    /** Store one declaration - {@code sha256}, {@code suite} and {@code index} as properties written so an unchanged
     *  one compares equal - or nothing when the stanza carried no pair or the store already agrees. */
    private static int record(String filename, String sha256, ArtifactStore store, String suite, String index)
            throws IOException {
        if (filename == null || sha256 == null || Keys.unsafe(filename.replace("/", ""))) {
            return 0;
        }
        String document = "sha256=" + sha256 + "\nsuite=" + suite + "\nindex=" + index + "\n";
        return writeRecord(store, digestKey(filename), document.getBytes(StandardCharsets.UTF_8)) ? 1 : 0;
    }

    /** Write a record unless the store already holds these bytes, by compare-and-set: two nodes relaying the same
     *  refresh write the same bytes, so a lost race is not an error. */
    private static boolean writeRecord(ArtifactStore store, String key, byte[] content) throws IOException {
        Optional<ArtifactStore.Versioned> current = store.readVersioned(key);
        if (current.isPresent() && Arrays.equals(current.get().content(), content)) {
            return false;
        }
        store.writeVersioned(key, content, current.map(ArtifactStore.Versioned::token).orElse(null));
        return true;
    }

    private static Properties properties(byte[] document) throws IOException {
        Properties properties = new Properties();
        properties.load(new StringReader(new String(document, StandardCharsets.UTF_8)));
        return properties;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Read the {@code control} stanza from a {@code .deb}: the {@code ./control} entry of the decompressed
     *  {@code control.tar[.gz|.xz|.zst]} member of the {@code ar} archive. */
    private static String control(InputStream deb) throws IOException {
        try (ArArchiveInputStream archive = new ArArchiveInputStream(deb)) {
            for (ArArchiveEntry entry = archive.getNextEntry(); entry != null; entry = archive.getNextEntry()) {
                InputStream tar = controlTar(entry.getName(), archive);
                if (tar != null) {
                    return controlEntry(tar);
                }
            }
        }
        return null;
    }

    /** The decompressed control tar for an {@code ar} member, or null when the member is not the control archive. */
    private static InputStream controlTar(String name, InputStream member) throws IOException {
        return switch (name) {
            case "control.tar.gz" -> new GzipCompressorInputStream(member);
            case "control.tar.xz" -> new XZCompressorInputStream(member);
            case "control.tar.zst" -> new ZstdInputStream(member);
            case "control.tar" -> member;
            default -> null;
        };
    }

    // The control tar is attacker-supplied: the walk to ./control is bounded by ArchiveWalk.largestWalk() and the entry
    // by ArchiveInflation.largestEntry() (RepositoryFormat clause 15). Both are needed, since bounding only the entry
    // leaves a giant member before ./control to inflate while it is skipped; a bomb there leaves ./control unfound.

    private static String controlEntry(InputStream stream) throws IOException {
        return ArchiveWalk.walk(stream, DebianFormat::controlStanza).orNull();
    }

    /** The {@code ./control} stanza inside an already-bounded control tar, or {@code null} when it carries none. */
    private static String controlStanza(InputStream stream) throws IOException {
        try (TarArchiveInputStream tar = new TarArchiveInputStream(stream, "UTF-8")) {
            for (TarArchiveEntry entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry()) {
                if (entry.getName().equals("./control") || entry.getName().equals("control")) {
                    // A read the ceiling stopped fails closed and says so, rather than returning the null a missing
                    // member yields.
                    return new String(ArchiveInflation.entry(tar).required("Debian .deb", "./control stanza"),
                            StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    /** The migration-import capability, delegated to {@link DebianImporter}. */
    private final DebianImporter importer = new DebianImporter();

    @Override
    public RepositoryImporter importer() {
        return importer;
    }

    /** Each {@code .deb} of the version is put at its pool path; the target derives its own {@code Packages} and
     *  {@code Release} and signs them with its own key. */
    @Override
    public Exported export(ArtifactStore repository, String coordinate, String version, ExportTarget target)
            throws IOException {
        return BlobExport.put(repository, mount(), blobKeys(coordinate, version, repository), target);
    }

    /**
     * Every artifact the upstream rooted at {@code upstream} publishes, walked through its own index by
     * {@link DebianEnumeration}: what an import from that index lays out. A link off the upstream's own origin must be
     * {@code https} and public: an import screens the source's host before it starts, and trusts nothing beyond it.
     */
    @Override
    public Stream<ProxyFormat.Coordinate> enumerate(ProxyFormat.Fetcher fetcher, URI upstream) throws IOException {
        return DebianEnumeration.enumerate(fetcher, upstream, false)
                .map(entry -> new ProxyFormat.Coordinate(entry.getKey(), entry.getValue()));
    }
}
