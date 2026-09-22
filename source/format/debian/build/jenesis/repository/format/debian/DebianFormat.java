package build.jenesis.repository.format.debian;

import module java.base;
import module org.apache.commons.compress;

import build.jenesis.repository.format.Listings;
import build.jenesis.repository.format.signing.OpenPgpSigner;
import build.jenesis.repository.blobs.BlobLayout;
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
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.walk.PagedTreeWalk;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.walk.ScreenedNames;
import build.jenesis.repository.walk.Traversal;
import build.jenesis.repository.walk.TraversalException;
import build.jenesis.repository.walk.Trees;

/**
 * The Debian/apt format, so {@code apt-get} installs and a {@code .deb} upload work over the same store. It owns
 * {@code /debian/...}. A push ({@code PUT /debian/<suite>/pool/<component>/<file>.deb}, the raw {@code .deb} as the
 * body) reads the package's {@code Package}, {@code Version} and {@code Architecture} from the {@code control} file
 * inside the {@code .deb} (an {@code ar} archive whose {@code control.tar[.gz|.xz|.zst]} is a compressed tar, both
 * read with Commons Compress), stores the file under {@code debian/<suite>/pool/<component>/<file>.deb} and a precomputed
 * {@code Packages} stanza - the control augmented with {@code Filename}, {@code Size} and checksums - under
 * {@code debian/<suite>/index/<component>/<arch>/<file>}. The binary {@code Packages}
 * ({@code GET /debian/dists/<suite>/<component>/binary-<arch>/Packages[.gz]}) and the {@code Release}
 * ({@code GET /debian/dists/<suite>/Release}) are stored listings the push maintains from those stanzas - never a
 * rewritten object - and a {@code .deb} is served from the pool. A hosted {@code Release} is OpenPGP-signed once a
 * signing key is provisioned; without one it is unsigned, {@code InRelease} and {@code Release.gpg} are absent and a
 * client trusts it with {@code [trusted=yes]}.
 *
 * As a proxy, an immutable {@code .deb} is fetched, cached and served; the mutable {@code Release}, {@code InRelease}
 * and {@code Packages} pass through from the upstream unchanged - the upstream's own signature stays valid because a
 * cached {@code .deb} is byte-for-byte the original, so a proxied Debian mirror verifies against Debian's key. What
 * the leg keeps of them is what lets this repository verify the same chain: the digest each {@code Packages}
 * declared for a package and the digest of that index as it went past, and the suite's {@code InRelease} whole
 * ({@link #indexCoverage}).
 */
public final class DebianFormat implements RepositoryFormat, ProxyLeg, BlobLayout, ArtifactSignatures,
        RepositoryImporter {

    /** How many per-package digests one relayed index may record - a bound on the work a hostile upstream can ask
     *  for, well past any real suite (Debian main/amd64 carries some sixty thousand packages). */
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

    @Override
    public String ecosystem() {
        return "Debian";
    }

    /**
     * Debian's inbound signature story: the debsig {@code _gpgorigin} member embedded in a {@code .deb}, and
     * <em>optional</em> rather than expected.
     *
     * <p>Optional is the honest answer, and it differs from Maven's deliberately. An apt client's trust runs through
     * the signed {@code Release} index, which commits to the hashes of the {@code Packages} file, which commits to
     * each package - so the ordinary Debian package carries no signature of its own and demanding one would report
     * every well-run archive as unsigned. debsig exists, some publishers use it, and where it is present it is worth
     * checking; that is exactly what {@code OPTIONAL} says.
     *
     * <p>What is Debian's alone is <em>what</em> the signature covers: the concatenation of the archive's other
     * {@code ar} members in archive order, never the file. Composing that stream is this format's job; checking it is
     * not, which is why the signature is handed over rather than verified here.
     */
    @Override
    public List<ArtifactSignatures.Expectation> expects(String path) {
        // Required once trusted signers are provisioned, optional until then: apt's trust runs through the signed
        // Release index, so an ordinary package carries no signature and reporting every well-run archive as
        // unsigned would be worse than saying nothing - but a keyring of trusted signers (the keyring/trusted
        // endpoint) is an operator saying per-package signatures are expected here, and from then on an unsigned
        // package is a finding for signature-missing to decide. The declaration stays a pure function of the path;
        // whether a keyring stands is the Debian keyring trust's answer, read by the inspector that holds it.
        // And, optional, coverage by the mirror's signed index (indexCoverage): a proxied package may be named by a
        // Packages index the archive's clearsigned InRelease commits to; a hosted one never is.
        return path.endsWith(".deb")
                ? List.of(ArtifactSignatures.Expectation.requiredWhenTrusted(ArtifactSignatures.Scheme.OPENPGP_DETACHED),
                          ArtifactSignatures.Expectation.optional(ArtifactSignatures.Scheme.OPENPGP_CLEARSIGNED))
                : List.of();
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
     * Coverage by the mirror's signed index, for a proxied {@code .deb}: apt's trust runs through the clearsigned
     * {@code InRelease}, which commits to the digest of each {@code Packages} index, which commits to the digest of
     * each package. Two hops, and the middle document is tens of megabytes - so nothing here reads it. The proxy leg
     * took its digest as it streamed past and recorded, per pool path, the package digest its stanza declared
     * ({@link #recordIndex}), and it kept the suite's {@code InRelease} whole, which is small enough to read under
     * the signature bound. The evidence is that document, clearsigned by the archive, and its {@link
     * ArtifactSignatures.Named} makes the second hop: the index the record came from must be named by the digest
     * that streamed - a line {@code <sha256> <size> <component>/binary-<arch>/Packages[.gz|.xz]} - and then the
     * digest the index declared for this package is what the document names for it. An {@code InRelease} that does
     * not name the streamed index vouches for nothing here - the two were relayed either side of a mirror refresh -
     * and yields no evidence rather than a mismatch, since nothing was tampered with.
     *
     * <p>The signer is the archive's, never the package's: the location says which index the coverage came through,
     * and the outcome is judged against the trust the deployment holds for that key - the Debian keyring, or one it
     * configured. Verified once per assessment of a package, off the request path (the hardened leg's screen, the
     * rescreen pass), never on a serving read; the record it leaves is what a sweep re-judges.
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

    /** Whether a clearsigned {@code InRelease} names an index by this digest: a {@code <sha256> <size> <path>} line
     *  whose path is a {@code Packages} index, plain or compressed. Matched by digest rather than by name, since a
     *  by-hash fetch carries only the digest; the armour and the signature block match no such line. */
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
     * The package version a stored Debian pointer serves - the backwards direction the inventory back-fill rebuilds
     * a lost {@code published/} row from.
     *
     * <p>Debian is the first format here whose pair lives in a <em>filename</em> rather than in path segments, and
     * the only reason that is safe is a rule of the ecosystem rather than of this store: a {@code .deb} is named
     * {@code <name>_<version>_<arch>.deb}, and Debian policy forbids an underscore in a package name or a version.
     * So the split is exact where npm's {@code <shortName>-<version>.tgz} and Cargo's {@code <crate>-<version>}
     * were not, and those are left undecoded for precisely the reason this one is decoded.
     *
     * <p>It is the same parse {@link #describe} performs on the request path, which is what makes the row this
     * rebuilds match the row the accept path wrote - and what the shared round-trip property checks over a really
     * published package.
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
        // Debian keys each .deb on its request pool path (debian/<suite>/pool/<component>/.../<file>.deb), where the
        // file is <package>_<version>_<arch>.deb and the filename version omits any epoch the control Version carries.
        // Recover the coordinate-scoped keys by scanning each suite's pool tree for every .deb whose package and
        // (epoch-stripped) version match - a version may sit under several suites, components or architectures, and all
        // are the version's keys - so blobHashes/eviction/servedPaths reach a hosted Debian version. The suite level is
        // the shared flat bounded enumeration and each pool subtree the shared bounded tree walk - one
        // hardened iterative descent, never a hand-rolled stack and never self-recursion over an unpaged list().
        if (!BlobLayout.addressable(coordinate, version)) {
            return List.of();   // a traversal-shaped coordinate maps nowhere - these keys are what an eviction DELETES
        }
        String fileVersion = stripEpoch(version);
        List<String> keys = new ArrayList<>();
        SUITES.scan(store, "debian", suite -> {
            // The reverse index a push writes answers without a walk; a suite from before it (no by/ container at
            // all) is walked as before, until the rebuild pass has backfilled it.
            if (store.isEmpty("debian/" + suite + "/by")) {
                collectDebs(store, "debian/" + suite + "/pool", coordinate, fileVersion, keys);
                return;
            }
            for (String file : store.list("debian/" + suite + "/by/" + coordinate + "/" + fileVersion)) {
                String note = DebianListings.reverseKey(suite, coordinate, fileVersion, file);
                Optional<ArtifactStore.Versioned> pool = store.readVersioned(note);
                if (pool.isEmpty()) {
                    continue;
                }
                String key = "debian/" + new String(pool.get().content(), StandardCharsets.UTF_8).trim();
                if (store.readVersioned(key).isPresent()) {
                    keys.add(key);
                } else {
                    store.delete(note);   // evicted: the note is stale
                }
            }
        });
        return keys;
    }

    /** Walk one suite's pool subtree through the shared bounded tree walk, adding every stored {@code .deb} leaf whose
     *  {@code <package>_<version>_<arch>} filename matches the requested package and epoch-stripped version. The pool
     *  nests components (and, in a full mirror, the first-letter/source dirs), so the descent is arbitrary-depth; the
     *  primitive keeps it iterative (O(depth) frames, never a call stack an attacker-planted depth can overflow) and
     *  pages every level, so an arbitrarily wide level streams page by page.
     *
     *  <p>The leaf test moves from an inference to a fact: this used to call a {@code .deb}-suffixed prefix that paged
     *  EMPTY a pool pointer, so a {@code .deb}-named directory left empty by a partial delete was collected as a stored
     *  {@code .deb} that eviction would then fail to find. {@link Trees} decides leaf-ness with
     *  {@link ArtifactStore#exists}, so only a key that really holds bytes is matched. */
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

    /** The suite level: an operator-configured, bounded set, but enumerated for a compliance read that must see EVERY
     *  suite a version sits in - a suite silently dropped here is a hold that never marks that suite's {@code .deb}.
     *  The entry cap is therefore off and the binding bound is the primitive's step budget (1000 page round-trips),
     *  which THROWS rather than answering short. */
    private static final BoundedChildren SUITES = BoundedChildren.bounded().entries(Integer.MAX_VALUE);

    /** The pool descent's budget, spent one probe per opened node. {@code blobKeys} feeds holds, eviction and
     *  {@code servedPaths}: a pool leaf it does not report is a KEV-listed {@code .deb} that keeps serving, so a
     *  truncated answer is not a page of a right answer, it is a wrong one. The entry cap is therefore pinned to the
     *  same number as the step budget so it can never bind first - what bounds this walk is the STEP cap, the one that
     *  raises a named {@link TraversalException} instead of returning. Depth stays at the default
     *  {@link ArtifactStore#MAX_SEGMENTS} ceiling, which every key the store would accept fits inside. */
    private static final int POOL_NODES = 1_000_000;

    private static final PagedTreeWalk POOL = PagedTreeWalk.bounded().steps(POOL_NODES).entries(POOL_NODES)
            .page(BoundedChildren.DRAIN_PAGE);

    /** The filename version - the control {@code Version} with any {@code epoch:} prefix removed, the same token the
     *  {@code <package>_<version>_<arch>.deb} filename (and {@link #describe}) carries, so a coordinate enumerated from
     *  a filename matches the keys stored under it. */
    private static String stripEpoch(String version) {
        int colon = version.indexOf(':');
        return colon < 0 ? version : version.substring(colon + 1);
    }

    /** The request paths this package version serves at - its {@code .deb} pool key(s) mapped back to their request
     *  path ({@code /debian/<suite>/pool/<component>/<file>.deb} = {@code /} + the store key), the inverse of
     *  {@link #describe} - a retroactive hold links a {@code /quarantine} review handle at each. Derived from
     *  {@link #blobKeys}, so it is non-empty exactly when the coordinate-scoped pool scan is wired. */
    @Override
    public List<String> servedPaths(String coordinate, String version, ArtifactStore store) throws IOException {
        List<String> paths = new ArrayList<>();
        for (String key : blobKeys(coordinate, version, store)) {
            paths.add("/" + key);
        }
        return paths;
    }

    /** The coordinate a {@code .deb} request path carries, from the {@code <package>_<version>_<arch>.deb} filename
     *  convention (Debian policy allows {@code _} in neither a package name nor a version, so the split is exact) -
     *  the package name alone as the coordinate, the {@code Package} the Debian compliance inspector reads from the
     *  control stanza. The filename version omits any epoch the control's {@code Version} carries, the one
     *  path-underivable piece. Feeds the {@code published/} sidecar the retroactive enforcement sweeps enumerate the
     *  version by. The generated {@code Release}/{@code Packages} indexes and the keyring endpoints name no package
     *  and stay empty; a filename off the convention describes coordinate-less rather than guessing. */
    @Override
    public Optional<ArtifactDescriptor> describe(String path) {
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

    // An original CC0 line glyph (a two-arc swirl) drawn for this project.
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
        return Optional.of(URI.create("http://deb.debian.org/debian/"));
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
     * Whether a suite has anything left to show - the membership question {@code GET /debian/dists/} is actually
     * asking, answered from the suite's own manifest where that manifest is current.
     *
     * <p>The screened enumeration this used to ask could not answer it. It probes {@code debian/<suite>/index} for a
     * disclosable child, but the children there are component <em>containers</em>, not pointers, so there is no
     * {@code withheld/<hash>} marker for the screen to find and the answer degrades to "does this suite carry an index
     * at all". A suite whose every package is held therefore still listed, and its own {@code Release} then announced
     * no components (D-262).
     *
     * <p>The manifest answers it exactly, and for free: it holds one line per component/architecture index that
     * carries <b>at least one servable package</b> - {@code generateManifest} skips an index whose {@code Packages}
     * document is empty, and a hold empties one through {@code DebianListingObserver}. So an empty manifest is
     * precisely "every package in this suite is held".
     *
     * <p><b>Only when it is current</b>, which is the part that makes this safe. The manifest is a deferred
     * derivation, so a suite whose {@code Packages} landed a moment ago has none yet - and listing off a stale
     * manifest would drop a freshly published suite from the autoindex, a failure the old screen did not have. Inside
     * that window this falls back to the old probe: no worse than today, exact outside it. It deliberately does
     * <em>not</em> call {@code announce} to catch the suite up the way a {@code Release} read may: that read is one
     * suite, and doing it here is a write on a read (&sect;10) fanned out over every suite in the repository.
     */
    private static boolean disclosable(DebianListings listings, Blobs blobs, String suite) throws IOException {
        // header(), not read(): read() is specified to MATERIALISE a listing that is absent, so asking it whether a
        // manifest exists writes one - and on a suite whose indexes were never published through the listing path
        // (a store seeded directly, which is a real shape in this product's own suites) the generated manifest is
        // empty, which this method would then read as "every package is held" and hide a suite that serves. The
        // stored header answers the question without generating anything.
        if (listings.current(suite) && StoredListing.header(blobs.store(), DebianListings.manifest(suite)).isPresent()) {
            Optional<StoredListing.Document> manifest = StoredListing.read(blobs.store(), listings.manifestSpec(suite));
            if (manifest.isPresent()) {
                return manifest.get().body().length > 0;
            }
        }
        // No derivation has run for this suite yet (or it lags): fall back to the screened probe. Inside that window
        // the answer is exactly today's - "does this suite carry an index" - which is no worse than the status quo.
        return ScreenedNames.keys(blobs.servableNames(), ServableNames.Policy.HIDE_WITHHELD)
                .any(blobs.store(), "debian/" + suite + "/index");
    }

    /** An autoindex of the hosted suites ({@code GET /debian/dists/}), each a link as a mirror's httpd would render
     *  it - the page real apt mirrors expose and the one an enumeration (jenesis's own index walk included)
     *  discovers suites from, since the apt protocol itself never lists them. */
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
            // HTML-escape the suite in BOTH the href and the text: it is the first path segment of a publish
            // (debian/<suite>/...), gated only by Keys.unsafe, which blocks '/' and control chars but permits < > " &.
            // Concatenated raw into this text/html page it is a stored XSS (a suite like a"><img src=x onerror=...>
            // executes in the gateway origin for anyone who opens /debian/dists/). Every sibling HTML index (PyPI, raw)
            // escapes via XMLStreamWriter; this one must too.
            String escaped = htmlEscape(suite);
            page.append("<a href=\"").append(escaped).append("/\">").append(escaped).append("/</a>");
        }
        exchange.setResponseHeader("Content-Type", "text/html");
        exchange.respond(200, page.append("</body></html>").toString().getBytes(StandardCharsets.UTF_8));
    }

    /** The index fields the server appends to a control to build the served {@code Packages} stanza; a source control
     *  must not declare any of them (dpkg-scanpackages, not the packager, adds them), or the stanza would carry a
     *  duplicate whose apt resolution is undefined. */
    private static final Set<String> RESERVED_INDEX_FIELDS = Set.of("filename", "size", "md5sum", "sha1", "sha256");

    /** Whether {@code control} declares one of the {@linkplain #RESERVED_INDEX_FIELDS reserved index fields}. A field
     *  header starts a line (a folded continuation begins with a space or tab and is skipped) and is matched
     *  case-insensitively, as Debian field names are. */
    private static boolean declaresReservedIndexField(String control) {
        return control.lines().anyMatch(line -> {
            int colon = line.indexOf(':');
            if (colon <= 0 || Character.isWhitespace(line.charAt(0))) {
                return false;
            }
            return RESERVED_INDEX_FIELDS.contains(line.substring(0, colon).trim().toLowerCase(Locale.ROOT));
        });
    }

    /** HTML-escape a value for safe inclusion in both an attribute and element text: the five characters that could
     *  otherwise break out of the surrounding markup. */
    private static String htmlEscape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** Ensure a signing key exists (generate an unprotected RSA key on first call) and return its public key. */
    private void provisionKey(Blobs blobs, FormatExchange exchange) throws IOException {
        if (!blobs.exists("debian/keyring/secret.asc")) {
            // Established rather than written, and the published half derived from whichever secret won: two first
            // publishes racing would otherwise store one pair's secret beside another pair's public, and every
            // signature this repository makes would be refused by a client doing its job.
            OpenPgpSigner signer = new OpenPgpSigner(
                    blobs.establish("debian/keyring/secret.asc", () -> OpenPgpSigner.generate(IDENTITY, KEY_VALIDITY).secretKey()));
            blobs.write("debian/keyring/public.asc", signer.publicKeyring());
            // The signed Release twins are derived on index writes; a suite indexed before the key existed gets
            // them now rather than on its next push.
            DebianListings listings = listings(blobs);
            for (String suite : blobs.list("debian")) {
                if (!blobs.isEmpty("debian/" + suite + "/index")) {
                    listings.rederiveRelease(suite);
                }
            }
        }
        servePublicKey(blobs, exchange);
    }

    private void servePublicKey(Blobs blobs, FormatExchange exchange) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (!blobs.read("debian/keyring/public.asc", buffer)) {
            exchange.respond(404);
            return;
        }
        exchange.setResponseHeader("Content-Type", "application/pgp-keys");
        exchange.respond(200, buffer.toByteArray());
    }

    /** The largest trusted-signers key upload accepted: an armored PGP public key (or a small bundle of them) is a few
     *  kilobytes, so a megabyte is generous. The body is read through a bounded {@code readNBytes} so an oversize
     *  upload is refused up front and never buffered whole in heap - the same cap every other format upload applies. */
    private static final int MAX_TRUSTED_KEY = 1024 * 1024;

    /** Where the trusted-signers keyring is stored, under the repository's scope - the material the signature
     *  dimension's Debian trust reads. */
    public static final String TRUSTED_KEYRING = "debian/keyring/trusted.asc";

    /** The {@link StoreCache} the dimension reads that keyring through, named here beside the write that changes it
     *  so provisioning takes effect on this node at once rather than after the cache's ttl; a peer sees it within
     *  the ttl, as it sees every other cached document. One lowercase word, because a cache's name is a signal
     *  segment ({@code jenreg.cache.<name>}): the hyphenated name it had before 2026-09-12 broke that grammar, and
     *  the trust provider's fail-closed catch hid the throw, so the keyring an operator provisioned never verified a
     *  package through the dimension - a defect only the end-to-end push test could see. */
    public static final String TRUSTED_KEYRING_CACHE = "debiankeyring";

    /** Provision a trusted-signers key: an armored public key uploaded here is merged into the trusted keyring, and
     *  from then on the signature dimension expects every pushed {@code .deb} to carry an embedded signature that
     *  verifies against one of these keys - an unsigned package is a {@code signature-missing} finding, one signed
     *  by another key {@code signature-untrusted}, a signature that does not stand {@code signature-invalid}, each
     *  decided by its dial. This format itself judges nothing on push: it used to refuse such a package with a
     *  {@code 403} beside the dimension's verdict, two judgements of one upload that disagreed in shape (a refusal
     *  stores nothing to review and nothing to release), and the keyring this writes is what the dimension's Debian
     *  trust reads. */
    private void addTrustedKey(Blobs blobs, FormatExchange exchange) throws IOException {
        byte[] key = exchange.requestStream().readNBytes(MAX_TRUSTED_KEY + 1);
        if (key.length > MAX_TRUSTED_KEY) {
            exchange.respond(413);   // an armored public key is small; refuse an oversize body rather than buffer it
            return;
        }
        ByteArrayOutputStream existing = new ByteArrayOutputStream();
        byte[] merged = blobs.read(TRUSTED_KEYRING, existing)
                ? OpenPgpSigner.mergePublicKeyrings(existing.toByteArray(), key, Instant.EPOCH)
                : key;
        blobs.write(TRUSTED_KEYRING, merged);
        StoreCache.of(TRUSTED_KEYRING_CACHE, blobs.store(), StoreCache.configuredTtl()).invalidate(TRUSTED_KEYRING);
        exchange.respond(201);
    }

    private OpenPgpSigner signer(Blobs blobs) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (!blobs.read("debian/keyring/secret.asc", buffer)) {
            return null;
        }
        OpenPgpSigner signer = new OpenPgpSigner(buffer.toByteArray());
        return signer.dueForRotation(Instant.now(), ROTATION_WINDOW) ? rotate(blobs) : signer;
    }

    /** Rotate to a fresh signing key: it signs from now on, while the retiring key's public half stays in the served
     *  keyring until it expires, so a client that already trusts it still verifies an {@code InRelease} it signed
     *  during the overlap. Best-effort under concurrency - a lost race simply re-rotates on the next read. */
    private OpenPgpSigner rotate(Blobs blobs) throws IOException {
        OpenPgpSigner.KeyMaterial fresh = OpenPgpSigner.generate(IDENTITY, KEY_VALIDITY);
        ByteArrayOutputStream existing = new ByteArrayOutputStream();
        byte[] published = blobs.read("debian/keyring/public.asc", existing)
                ? OpenPgpSigner.mergePublicKeyrings(existing.toByteArray(), fresh.publicKey(), Instant.now())
                : fresh.publicKey();
        blobs.write("debian/keyring/secret.asc", fresh.secretKey());
        blobs.write("debian/keyring/public.asc", published);
        return new OpenPgpSigner(fresh.secretKey());
    }

    private void push(String rest, FormatExchange exchange, Blobs blobs, ArtifactStore store) throws IOException {
        String[] segments = rest.split("/");
        if (segments.length < 4 || !segments[1].equals("pool")) {
            exchange.respond(400);
            return;
        }
        // Stream the .deb straight into the content-addressed store (hash-on-write), never buffering the whole
        // (unbounded) package into heap; then reopen the stored blob to parse its control and digest it - the
        // store-then-gate publish the gems/cocoapods formats use. The SHA-256 the store returns is the package's
        // SHA256 checksum, so it is not recomputed. Its signature is not judged here: the edge ran the discovered
        // interceptor chain over the body before this was called, and the signature dimension is where an embedded
        // signature is verified against the trusted keyring and its absence decided (expects() says when).
        String hash = blobs.store(exchange.requestStream());
        String control;
        try (InputStream deb = blobs.open(hash)) {
            control = control(deb);
        } catch (RuntimeException | IOException malformed) {
            // A control that cannot be read within the bounded scan (a decompression bomb the bound truncated, or an
            // otherwise unreadable ar/tar) is a malformed upload, not a server error: fall through to the 400 below
            // rather than let the parse exception escape handle() as a 500. The store-open IOException itself is rare;
            // treating an unreadable .deb as a rejected publish is the safe outcome (nothing was indexed).
            control = null;
        }
        String architecture = control == null ? null : DebianListings.field(control, "Architecture");
        if (architecture == null) {
            exchange.respond(400);
            return;
        }
        if (control.stripTrailing().lines().anyMatch(String::isBlank)) {
            // A .deb's control is a SINGLE paragraph. The control blob is echoed verbatim into this package's stored
            // stanza and later concatenated into the served Packages index, where a blank line separates stanzas - so a
            // control carrying an internal blank line would splice a second, fully attacker-chosen stanza (a phantom
            // package whose Filename: points at any blob) into the shared index: repository-wide apt poisoning that the
            // single-line field()/Package-vs-filename checks do not catch. Refuse a multi-paragraph control outright.
            // (Debian folded/continuation lines begin with a space or tab and are not blank, so a legitimate single
            // stanza never trips this.)
            exchange.respond(400);
            return;
        }
        if (declaresReservedIndexField(control)) {
            // The server appends the AUTHORITATIVE Filename/Size/MD5sum/SHA1/SHA256 to the control to build this
            // package's Packages stanza. A control that already declares one of these would produce a stanza with a
            // DUPLICATE field, and apt's resolution of a duplicate is undefined - an injected Filename could steer apt
            // at a different blob path than the one published. These fields are never present in a source .deb control
            // (dpkg-scanpackages adds them at index time), so refuse a control that carries one.
            exchange.respond(400);
            return;
        }
        String suite = segments[0], component = segments[2], file = segments[segments.length - 1];
        if (Keys.unsafe(suite) || Keys.unsafe(component) || Keys.unsafe(architecture) || Keys.unsafe(file)
                || component.startsWith("@")) {
            // A control-supplied architecture (or a path segment) must not forge a pointer key, and a component
            // beginning with @ would collide with the suite's stamp beside its component listings.
            exchange.respond(400);
            return;
        }
        // The .deb filename deploys the package under <pkg>_<version>_<arch>.deb (the coordinate the importer screens
        // on); the served Packages stanza's Package: comes from the embedded control. They MUST agree - otherwise a
        // .deb screened under one package name would be served under the control's own name (a screen-label bypass),
        // the way Composer/CocoaPods refuse a manifest that disagrees with the deploy path.
        String pathPackage = null;
        if (file.endsWith(".deb")) {
            String[] nameParts = file.substring(0, file.length() - ".deb".length()).split("_");
            if (nameParts.length == 3 && !nameParts[0].isEmpty()
                    && !nameParts[1].isEmpty() && Character.isDigit(nameParts[1].charAt(0))) {
                pathPackage = nameParts[0];
            }
        }
        String declaredPackage = DebianListings.field(control, "Package");
        if (pathPackage != null && declaredPackage != null && !declaredPackage.equals(pathPackage)) {
            exchange.respond(400);   // control Package disagrees with the screened filename package - refuse the mismatch
            return;
        }
        long size = store.size("blobs/" + hash);
        String[] md5sha1 = digests(blobs, hash);
        // Point the pool path at the stored blob through Blobs.link, which clears any gc/condemned marker on a blob a
        // collector already judged unreferenced (re-pushing byte-identical content dedupes to that same blob).
        blobs.link("debian/" + rest, hash);
        String stanza = control.stripTrailing() + "\n"
                + "Filename: " + rest + "\n"
                + "Size: " + size + "\n"
                + "MD5sum: " + md5sha1[0] + "\n"
                + "SHA1: " + md5sha1[1] + "\n"
                + "SHA256: " + hash + "\n";
        blobs.write(DebianListings.stanzaKey(suite, component, architecture, file),
                stanza.getBytes(StandardCharsets.UTF_8));
        if (pathPackage != null) {
            // The reverse index a coordinate's pool keys are found through without walking the pool: the file's
            // version is the filename's (epoch-less), the one describe() reports.
            String[] nameParts = file.substring(0, file.length() - ".deb".length()).split("_");
            blobs.note(DebianListings.reverseKey(suite, pathPackage, nameParts[1], file), rest);
        }
        // The served index is written here, on the push, rather than generated on every read: the stanza joins the
        // component/architecture Packages document (if the package is servable), which re-derives Packages.gz and
        // the suite's Release family.
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

    /** The {@code MD5sum} and {@code SHA1} of a stored blob, computed in a single reopened streaming pass (apt's
     *  {@code Packages} stanza carries both alongside the content-addressed SHA256), so the package is never buffered
     *  whole to digest it. */
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
        Optional<Blobs.Located> located = blobs.locate("debian/" + rest);
        if (located.isEmpty()) {
            exchange.respond(404);
            return;
        }
        long size = located.get().size();
        exchange.setResponseHeader("Content-Type", "application/vnd.debian.binary-package");
        if (exchange.method().equals("HEAD")) {
            // Answer HEAD from the stored blob size (Content-Length, 200, no body) rather than streaming the whole
            // .deb just to discard it - apt issues HEADs to probe a package's size and existence.
            if (size >= 0) {
                exchange.setResponseHeader("Content-Length", Long.toString(size));
            }
            exchange.respond(200, -1L).close();
            return;
        }
        blobs.serve(located.get(), exchange);
    }

    private void metadata(String rest, Blobs blobs, FormatExchange exchange) throws IOException {
        String[] segments = rest.split("/");
        DebianListings listings = listings(blobs);
        if (segments.length == 3 && (segments[2].equals("Release") || segments[2].equals("InRelease")
                || segments[2].equals("Release.gpg"))) {
            String suite = segments[1];
            // The Release family is derived from the suite manifest off the index write. A read that finds it absent
            // (a suite read before its listings were materialised, or inside the window of the deferred derivation)
            // or behind the newest index write brings it up to the indexes itself, once, rather than serve a Release
            // older than a Packages it names; a suite with no index at all stays a 404.
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
                // The twin is derived off the index write; a read compares its sequence with the index's (one
                // header read) and derives it itself, once, when it arrived inside that window.
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

    /** Stream a stored listing, with the cheap revalidation apt's refresh path relies on: the ETag is the stored
     *  document's digest, so a matching {@code If-None-Match} answers {@code 304} from the header alone, and a
     *  {@code HEAD} answers from the stored length without streaming the body. */
    private static void respondListing(FormatExchange exchange, StoredListing.Served served, String contentType)
            throws IOException {
        Listings.serve(exchange, served, contentType);
    }

    /**
     * Proxy a Debian miss to the upstream apt repository (deb.debian.org). A {@code .deb} is immutable, so it is
     * fetched, cached and served; a {@code Release}, {@code InRelease} or {@code Packages} is mutable and is streamed
     * through - the upstream's paths are relative to its root, which maps to this repository's {@code /debian/}, so
     * the index needs no rewrite and the upstream's signature stays valid over byte-for-byte cached packages.
     */
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
            // The digest this leg could not name at fetch time, recorded when the INDEX went past (see indexDigests).
            // A pool path is exactly the Filename a Packages stanza declares, so the one key the request DOES carry
            // is the key the record was written under.
            ProxyRelay.Declared declared = recordedDigest(rest, store);
            // Point-integrity, by recording rather than locating. Debian publishes a per-.deb SHA256 only
            // inside a `Packages` index, keyed by `Filename: pool/.../x.deb` under a particular
            // `dists/<suite>/<component>/binary-<arch>/` path, while a `.deb` lives in a SHARED `pool/` tree that
            // many suites reference - so at THIS moment, holding only a pool path, the declaring index cannot be
            // named. It does not have to be: apt fetches the index before the package and the index streams through
            // this same leg, so the digest is written down on the way past and read here by the one key the pool
            // path does carry (see recordDigests / recordedDigest).
            //
            // What that changes: a corrupted body is now refused and NOT cached, instead of being stored and served
            // to every client until eviction. The client was already safe - the signed Release -> Packages chain is
            // relayed byte for byte and apt verifies each .deb against it - but the cache was not, and neither was a
            // consumer that fetches a pool URL without apt (a script, a container build step).
            //
            // Where no record exists yet, the fill still declares NONE and the bytes are served unverified, exactly
            // as before. That is the earlier third case stated out loud: not "the document declares no digest" and not
            // "the document could not be read", but "no index has told us yet".
            // A .deb is an immutable artifact of unbounded size: stream it from the network straight into the
            // content-addressed store rather than buffering the whole body, then re-serve it locally.
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
        // A non-.deb path is a mutable index (Release/InRelease/Packages) or a large index/source body (Contents-*.gz,
        // *.orig.tar.gz): stream it through from upstream rather than buffering the whole body with fetch(). The bytes
        // pass through unchanged, so the upstream's signature over a byte-for-byte body stays valid. Forward the
        // client's conditional-request validators so a 304-capable apt's revalidation reaches the upstream, and relay
        // the upstream's validators back so its next refresh can revalidate rather than re-pulling the whole index.
        //
        // The one streaming leg carries both of the earlier classes, so it is split by the archive layout that separates
        // them. Under dists/ live the documents apt RESOLVES against - InRelease/Release name the components and their
        // index digests, Packages lists every package and version in a component, Contents-* lists their files - and
        // there an absence is an answer ("this suite has no such component", "this component is empty") that apt acts
        // on, so a fetch this repository could not make must not be rendered as one. Under pool/ live already-resolved
        // bodies apt reached BY name out of one of those indexes (a source .orig.tar.gz, a .dsc, a .diff.gz), and there
        // the contract's 404 keeps its "not cached here, re-pull" meaning exactly as it does for the .deb above.
        ProxyRelay.Document document = rest.startsWith("pool/")
                ? ProxyRelay.Document.PINNED
                : ProxyRelay.Document.ENUMERATION;
        // A Packages index is the only place Debian publishes a per-.deb SHA256, and it goes past here on its way to
        // apt. Read it as it streams - whichever of the plain, .gz, .xz or by-hash forms apt asked for, told apart
        // by the bytes - and record what it declares, so the pool fetch above has a digest to hold the body to,
        // with the digest of the index as relayed beside it. And keep the suite's InRelease, which is what commits
        // to that digest: together they are what lets a proxied package be judged by the archive's signature
        // (indexCoverage). A deployment whose client fetches neither records nothing and degrades to the unverified
        // fill, which the fill states rather than hides.
        ProxyRelay.Tap tap = null;
        if (packagesIndex(rest)) {
            tap = body -> recordIndex(body, store, rest);
        } else if (rest.matches("dists/[^/]+/InRelease")) {
            tap = body -> keepInRelease(body, store, rest);
        }
        return ProxyRelay.streamFresh(fetcher, URI.create(root + rest), null, exchange, document, tap);
    }

    /** The store key a relayed index's declaration for one pool path is recorded under. */
    private static String digestKey(String poolPath) {
        return "debian/index-digest/" + poolPath;
    }

    /** The store keys the digest of a relayed index, as its bytes went past, is recorded under - by the index's own
     *  request path under {@code dists/}, which is what a package's record names as its source. */
    private static final String INDEX_DIGESTS = "debian/index-sha256/";

    /** The store keys a suite's relayed {@code InRelease} is kept whole under, by suite. */
    private static final String INDEX_COPIES = "debian/index/";

    /** The most of a small record - a package's declaration, an index's digest - a reader takes back. */
    private static final int SMALL_RECORD = 4096;

    /**
     * What a relayed {@code Packages} index declared for the {@code .deb} at {@code poolPath}, as the fill's digest
     * claim - or {@link ProxyRelay.Declared#NONE} when no index has passed through yet.
     *
     * <p>Absence is not a refusal. apt fetches the index before the package, so in the ordinary flow the record is
     * there; a first-ever pool fetch by something that skipped the index (a script, a container build step) still
     * gets the bytes, unverified, exactly as it did before this existed. Refusing it instead would turn a cache miss
     * into an outage for a client the registry has no complaint about.
     */
    private static ProxyRelay.Declared recordedDigest(String poolPath, ArtifactStore store) {
        try {
            Optional<ArtifactStore.Versioned> recorded = store.readVersioned(digestKey(poolPath));
            if (recorded.isEmpty()) {
                return ProxyRelay.Declared.NONE;
            }
            String sha256 = properties(recorded.get().content()).getProperty("sha256");
            // of(), not text(): a Packages index states the digest in hex, and the fill compares raw bytes.
            // text() is the Go h1: shape, where the declaration IS a string and is compared as one - passing
            // hex through it makes every body mismatch, which reads as a corrupted upstream.
            return sha256 == null
                    ? ProxyRelay.Declared.NONE
                    : ProxyRelay.Declared.of("SHA-256", HexFormat.of().parseHex(sha256.trim()));
        } catch (IOException | RuntimeException unreadable) {
            return ProxyRelay.Declared.NONE;
        }
    }

    /** A relayed {@code Packages} index under {@code dists/}: the plain document, its {@code .gz} and {@code .xz}
     *  twins, or a by-hash fetch of one of them, which names no suffix. apt takes whichever the {@code Release}
     *  advertises, and for years that has been a compressed one, by hash. */
    private static boolean packagesIndex(String rest) {
        return rest.startsWith("dists/") && (rest.endsWith("/Packages") || rest.endsWith("/Packages.gz")
                || rest.endsWith("/Packages.xz")
                || rest.matches("dists/[^/]+/[^/]+/binary-[^/]+/by-hash/SHA256/[0-9a-fA-F]{64}"));
    }

    /**
     * Read a relayed {@code Packages} index as it streams - plain, or decompressed by its leading bytes, since a
     * by-hash fetch names no suffix - recording each stanza's {@code Filename} -> {@code SHA256} with the suite and
     * the index it came from, and at the end the digest of the bytes exactly as they were relayed: the digest the
     * suite's {@code InRelease} names for this index, which is how a package's coverage by that signed index is
     * later established ({@link #indexCoverage}). Decompressing costs one pass over the index per refresh, and buys
     * the point-integrity hold and the coverage for the index apt actually fetches, which is never the plain one.
     */
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

    /**
     * Keep a suite's clearsigned {@code InRelease} as it streams, whole, so a package proxied from the suite can be
     * judged against the index the archive signed. Held to the signature bound, since that is what a verifier reads
     * it under: one past the bound is not kept and a stale copy is dropped, so nothing vouches for a package on the
     * strength of a document that cannot be verified.
     */
    private static void keepInRelease(InputStream body, ArtifactStore store, String rest) throws IOException {
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

    /**
     * Read a {@code Packages} index as it streams and record each stanza's {@code Filename} -> {@code SHA256}.
     *
     * <p>Line by line, holding one stanza's two fields at a time: an index is tens of megabytes and this must not
     * grow with it. A record is written only when it differs from what is already stored, so a daily index refresh
     * over an unchanged suite writes nothing.
     */
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
                // Stop rather than grow without bound. Past this the remaining packages proxy unverified, which is
                // the behaviour that predates this recording - a cap degrades to the old answer, it does not refuse.
                return;
            }
        }
        record(filename, sha256, store, suite, index);   // an index whose last stanza has no trailing blank line
    }

    /** Store one declaration - {@code sha256}, {@code suite} and {@code index}, a properties document written by
     *  hand so an unchanged declaration compares equal and is not rewritten - or nothing when the stanza carried no
     *  pair or the store already agrees. */
    private static int record(String filename, String sha256, ArtifactStore store, String suite, String index)
            throws IOException {
        if (filename == null || sha256 == null || Keys.unsafe(filename.replace("/", ""))) {
            return 0;
        }
        String document = "sha256=" + sha256 + "\nsuite=" + suite + "\nindex=" + index + "\n";
        return writeRecord(store, digestKey(filename), document.getBytes(StandardCharsets.UTF_8)) ? 1 : 0;
    }

    /** Write a record unless the store already holds these bytes: compare-and-set on the token, since two nodes
     *  relaying the same index refresh concurrently is ordinary and a lost race means the other node wrote the same
     *  bytes. A refused write is not an error here. */
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

    /** Read the {@code control} stanza from a {@code .deb}: find the {@code control.tar[.gz|.xz|.zst]} member of the
     *  {@code ar} archive, decompress it, and return the {@code ./control} entry of that tar - all via Commons
     *  Compress rather than hand-parsed. */
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

    /** The decompressed control tar for an {@code ar} member by name, or null when the member is not the control
     *  archive (so iteration skips {@code debian-binary} and {@code data.tar.*}). */
    private static InputStream controlTar(String name, InputStream member) throws IOException {
        return switch (name) {
            case "control.tar.gz" -> new GzipCompressorInputStream(member);
            case "control.tar.xz" -> new XZCompressorInputStream(member);
            case "control.tar.zst" -> new ZstdCompressorInputStream(member);
            case "control.tar" -> member;
            default -> null;
        };
    }

    // The decompressed control.tar is attacker-supplied, so its ./control member is read under the product's one
    // archive-inflation ceiling, ArchiveInflation.largestEntry(), settable at jenreg.archive.largest-entry - not
    // under a private constant of this format's (RepositoryFormat contract clause 15 /).

    // How far the decompressed control.tar is walked to reach ./control is the product's one archive-walk bound,
    // ArchiveWalk.largestWalk(), settable at jenreg.archive.largest-walk. It is a different bound from the inflation
    // ceiling above and both are needed: capping only the stanza read does not bound the walk PAST a preceding entry,
    // so a control.tar.gz whose first member is a giant run would inflate unbounded while getNextEntry() skips it.
    // A bomb before ./control leaves it unfound - an unparsable control, a rejected publish.

    private static String controlEntry(InputStream stream) throws IOException {
        return ArchiveWalk.walk(stream, DebianFormat::controlStanza).orNull();
    }

    /** The {@code ./control} stanza inside an already-bounded control tar, or {@code null} when it carries none. */
    private static String controlStanza(InputStream stream) throws IOException {
        try (TarArchiveInputStream tar = new TarArchiveInputStream(stream, "UTF-8")) {
            for (TarArchiveEntry entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry()) {
                if (entry.getName().equals("./control") || entry.getName().equals("control")) {
                    // The stanza carries the .deb's coordinate and every field the generated Packages index echoes,
                    // so a read the ceiling stopped fails closed (the caller's 400) and SAYS SO, rather than being
                    // returned as the same null a.deb with no control member yields - the conflation.
                    return new String(ArchiveInflation.entry(tar).required("Debian .deb", "./control stanza"),
                            StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    /** The migration-import capability (WSPI.2 (c)), delegated to the layout-only {@link DebianImporter} - the format IS the
     *  discovered importer now (an {@code instanceof} capability), and the importer class stays as its delegate. */
    private final DebianImporter importer = new DebianImporter();

    @Override
    public boolean imports(String sourceFormat) {
        return importer.imports(sourceFormat);
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String sourcePath) {
        return importer.importTarget(sourcePath);
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        importer.importArtifact(path, content, store);
    }
}
