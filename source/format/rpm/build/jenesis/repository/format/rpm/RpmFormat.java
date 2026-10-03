package build.jenesis.repository.format.rpm;

import module java.base;
import module java.xml;
import module org.slf4j;

import build.jenesis.repository.format.LifecycleMark;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.format.Listings;
import build.jenesis.repository.format.signing.OpenPgpSigner;
import build.jenesis.repository.format.signing.SigningKeys;
import java.time.Duration;
import build.jenesis.repository.blobs.BlobExport;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.format.RepositoryExporter;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.blobs.Keys;
import build.jenesis.repository.blobs.ProxyLeg;
import build.jenesis.repository.blobs.ProxyRelay;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.walk.PagedTreeWalk;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.walk.ScreenedNames;
import build.jenesis.repository.walk.Traversal;
import build.jenesis.repository.walk.TraversalException;

/**
 * The RPM/yum format: {@code dnf} and {@code yum} install {@code .rpm} packages over the shared store, under
 * {@code /rpm/...}, where the first segment is a yum repository. A package is pushed with
 * {@code PUT /rpm/<repo>/<path>/<name>-<ver>-<rel>.<arch>.rpm} and served from the same path, and the {@code repodata}
 * ({@code /rpm/<repo>/repodata/repomd.xml} and {@code primary.xml[.gz]}) is a stored listing the publish maintains.
 *
 * <p><b>Streaming publish.</b> Only the RPM header at the front of the upload is materialised; the cpio payload streams
 * through {@link ArtifactStore#writeBlob} into the content-addressed store, and the SHA-256 the store returns is both
 * the blob hash and the package's {@code pkgid}. A {@code <package>} stanza is stored per package, so
 * {@code primary.xml} is joined from stanzas rather than by reopening every {@code .rpm}.
 *
 * <p><b>Self-healing index.</b> The stanza is derived from the durable {@code .rpm} pointers. When a hosted
 * repository's {@code repodata} is read and no stanzas exist, the read re-derives them from each pointer's header
 * ({@link #backfillStanzas}). Only on that recovery path, and only for a hosted repository: a pull-through proxy keeps
 * falling through to the upstream's {@code repodata} rather than shadowing it with a partial local index.
 *
 * <p><b>Pull-through proxy.</b> A local miss is served from an upstream yum repository: an immutable {@code .rpm}
 * streams into the content-addressed store and is cached, the mutable {@code repodata} streams through. RPM has no
 * canonical upstream, so {@link #defaultUpstream()} is empty.
 *
 * <p><b>Signed metadata.</b> With a signing key provisioned ({@code POST /rpm/keyring}), {@code repomd.xml} is
 * OpenPGP-signed as it is derived: the detached signature is served at {@code /rpm/<repo>/repodata/repomd.xml.asc} and
 * the public key at {@code /rpm/keyring/public.asc}, for a client with {@code repo_gpgcheck=1}. A near-expiry key
 * rotates on use, its retiring public half staying in the keyring during the overlap; without a key
 * {@code repomd.xml.asc} is a {@code 404}. The publisher's own signature in the package's signature header, what
 * {@code gpgcheck=1} verifies, is read through the {@link ArtifactSignatures} seam ({@link #expects},
 * {@link #evidence}, {@link RpmSignature}).
 *
 * <p>The ecosystem is {@code "RPM"}. Package pointers live in the shared {@code Blobs} namespace, so {@link #paths} is
 * empty and a coordinate is reached through {@link #blobKeys} and {@link #servedPaths}, which walk the pool for a
 * version's {@code .rpm} pointers; {@link #describe} resolves a {@code .rpm} path to its NEVRA coordinate.
 */
public final class RpmFormat implements RepositoryFormat, ArtifactLayout, ProxyLeg, BlobLayout, RepositoryImporter,
        ArtifactSignatures, RepositoryExporter {

    /** The ecosystem name this format's artifacts report, distinct from {@link #name()}, the routing id. */
    public static final String ECOSYSTEM = "RPM";

    private static final String PREFIX = "/rpm/";
    private static final String NS_COMMON = "http://linux.duke.edu/metadata/common";
    private static final String NS_RPM = "http://linux.duke.edu/metadata/rpm";
    private static final String NS_REPO = "http://linux.duke.edu/metadata/repo";
    private static final String REPODATA = "/repodata/";
    /** The identity the generated signing key carries. */
    private static final String IDENTITY = "Jenesis Repository <repository@jenesis.build>";
    /** How long a generated signing key is valid before it must be rotated. */
    private static final Duration KEY_VALIDITY = Duration.ofDays(730);
    /** How long before expiry a fresh key takes over, leaving an overlap for clients to refetch the keyring. */
    private static final Duration ROTATION_WINDOW = Duration.ofDays(90);

    @Override
    public String name() {
        return "rpm";
    }

    /** The marks this format's clients see, each by its own word. */
    @Override
    public Map<LifecycleMark, String> lifecycleMarks() {
        return LifecycleMark.shown(LifecycleMark.YANKED);
    }

    @Override
    public String ecosystem() {
        return ECOSYSTEM;
    }

    @Override
    public List<String> blobRoots() {
        return List.of("rpm");
    }

    /** RPM's inbound signature: the publisher's OpenPGP signature in the package's signature header, optional rather
     *  than expected, since dnf's trust runs through {@code repo_gpgcheck} over the signed {@code repomd.xml}, which
     *  commits to {@code primary.xml}, which commits to each package; unsigned packages behind signed metadata are
     *  ordinary. A {@code .rpm} under a repository's pool is signable; the generated {@code repodata} carries no
     *  expectation. */
    @Override
    public List<ArtifactSignatures.Expectation> expects(String path) {
        return path.startsWith(PREFIX) && path.endsWith(".rpm") && !path.contains(REPODATA)
                ? List.of(ArtifactSignatures.Expectation.optional(ArtifactSignatures.Scheme.OPENPGP_DETACHED))
                : List.of();
    }

    @Override
    public List<ArtifactSignatures.Evidence> evidence(String path, ArtifactSignatures.Material material)
            throws IOException {
        Optional<ArtifactSignatures.Signed> body = material.body();
        return body.isEmpty() ? List.of() : RpmSignature.evidence(body.get());
    }

    @Override
    public List<String> blobKeys(String coordinate, String version, ArtifactStore store) throws IOException {
        // A package is keyed on its pool path rpm/<repo>/<path>/<name>-<version>-<release>.<arch>.rpm; describe() maps
        // it to <repo>/<name> and <version>-<release>.<arch>. A NEVRA may sit at several pool locations, and all are
        // the version's keys, found through the reverse index or the pool walk.
        if (!BlobLayout.addressable(coordinate, version)) {
            // A traversal-shaped repo, name or NEVRA maps nowhere: these keys are what an eviction deletes, unscreened.
            // Each part of the two-segment coordinate is judged on its own.
            return List.of();
        }
        int slash = coordinate.indexOf('/');
        if (slash < 0) {
            return List.of();   // describe reports <repo>/<name>; a coordinate carrying no repo resolves no pool
        }
        String repo = coordinate.substring(0, slash);
        String name = coordinate.substring(slash + 1);
        if (Keys.unsafe(repo)) {
            return List.of();   // the repo is spliced into the walk root prefix - guard it with the format's key guard
        }
        String base = "rpm/" + repo;
        List<String> keys = new ArrayList<>();
        if (!store.isEmpty(base + REPODATA + "by")) {
            // The reverse index a publish writes answers without a walk; a repository without one is walked until the
            // rebuild pass has written it.
            for (String encoded : store.list(base + REPODATA + "by/" + name + "/" + version)) {
                String key = base + "/" + URLDecoder.decode(encoded, StandardCharsets.UTF_8);
                if (store.readVersioned(key).isPresent()) {
                    keys.add(key);
                } else {
                    store.delete(base + REPODATA + "by/" + name + "/" + version + "/" + encoded);   // evicted: stale note
                }
            }
            return keys;
        }
        eachPoolLocation(store, base, location -> {
            String file = location.substring(location.lastIndexOf('/') + 1);
            String[] nevra = nevra(file);
            // Exactly describe()'s mapping, so every coordinate it produces resolves here.
            if (nevra != null && nevra[0].equals(name)
                    && (nevra[1] + "-" + nevra[2] + "." + nevra[3]).equals(version)) {
                keys.add(base + "/" + location);
            }
        });
        return keys;
    }

    /** The request paths one RPM coordinate version serves: its pool keys from {@link #blobKeys} as request paths,
     *  where a retroactive hold links its {@code /quarantine} handles. */
    @Override
    public List<String> servedPaths(String coordinate, String version, ArtifactStore store) throws IOException {
        List<String> paths = new ArrayList<>();
        for (String key : blobKeys(coordinate, version, store)) {
            paths.add("/" + key);
        }
        return paths;
    }

    /** Deliver every {@code .rpm} pool location under a repository - relative to {@code rpm/<repo>/}, the
     *  {@code repodata} subtree excluded - to {@code locations}, driving {@link #POOL} to exhaustion. The one descent
     *  of a pool tree, for the stanza back-fill and the {@link BlobLayout} seam alike; the tree is publish-plantable to
     *  any depth and width, so the descent is the shared iterative, paged and bounded one. */
    private static void eachPoolLocation(ArtifactStore store, String base, PoolLocations locations)
            throws IOException {
        String cursor = null;
        while (true) {
            Traversal.Result result = POOL.walk(store, base, cursor, key -> {
                if (!key.startsWith(base + "/")) {
                    return;   // the repository root itself, were it ever a stored leaf: not a pool location
                }
                String location = key.substring(base.length() + 1);
                if (location.endsWith(".rpm") && !location.startsWith(REPODATA.substring(1))) {
                    locations.accept(location);
                }
            });
            if (result.exhausted()) {
                return;
            }
            cursor = result.cursor().orElseThrow();
        }
    }

    /** One delivered {@code .rpm} pool location, relative to {@code rpm/<repo>/}. */
    @FunctionalInterface
    private interface PoolLocations {

        void accept(String location) throws IOException;
    }

    @Override
    public boolean handles(String path) {
        return path.startsWith(PREFIX);
    }

    @Override
    public void serve(FormatExchange exchange, ArtifactStore store) throws IOException {
        String rest = exchange.path().substring(PREFIX.length());
        String method = exchange.method();
        Blobs blobs = new Blobs(store);
        if (method.equals("POST") && rest.equals("keyring")) {
            provisionKey(blobs, exchange);
        } else if (method.equals("PUT") && rest.endsWith(".rpm")) {
            publish(rest, exchange, store);
        } else if (!method.equals("GET") && !method.equals("HEAD")) {
            exchange.respond(405);
        } else if (rest.equals("keyring/public.asc")) {
            servePublicKey(blobs, exchange);
        } else if (rest.endsWith(".rpm")) {
            serve(rest, blobs, exchange);
        } else if (rest.contains(REPODATA)) {
            metadata(rest, blobs, exchange, store);
        } else {
            exchange.respond(404);
        }
    }

    /** Provisioning the signing key is the repository's operator's act, not a publish. */
    @Override
    public boolean administers(String method, String path) {
        return method.equals("POST") && path.equals(PREFIX + "keyring");
    }

    /** Ensure a signing key exists, generating one on the first call, and answer its public key. The key signs the
     *  {@code repomd.xml} of every hosted RPM repository under this one ({@link #keys}); those indexed before it
     *  existed are re-signed on the node's derivation thread, a page at a time, so the answer never costs a walk. */
    private void provisionKey(Blobs blobs, FormatExchange exchange) throws IOException {
        if (keys(blobs).signer().isEmpty()) {
            keys(blobs).provision();
            RpmListings listings = listings(blobs);
            StoredListing.later(blobs.store().identity() + "|rpm/keyring/signing.rederive", () -> {
                try {
                    rederiveSigned(blobs, listings);
                } catch (IOException failed) {
                    throw new UncheckedIOException(failed);
                }
            });
        }
        servePublicKey(blobs, exchange);
    }

    /** How many repositories a re-signing reads the names of at a time. */
    private static final int RESIGN_PAGE = 1_000;

    /** Re-derive the signed metadata of every indexed repository under this one, paging through their names. */
    private static void rederiveSigned(Blobs blobs, RpmListings listings) throws IOException {
        String after = "";
        while (true) {
            List<String> page = new ArrayList<>();
            blobs.page("rpm", after, RESIGN_PAGE, page::add);
            for (String repo : page) {
                if (!repo.equals("keyring") && !blobs.isEmpty(indexPrefix(repo))) {
                    listings.rederive(repo);
                }
            }
            if (page.size() < RESIGN_PAGE) {
                return;
            }
            after = page.getLast();
        }
    }

    /** Serve the public signing keyring ({@code gpgkey=} target), or {@code 404} when the repository is unsigned. */
    private void servePublicKey(Blobs blobs, FormatExchange exchange) throws IOException {
        Optional<byte[]> keyring = keys(blobs).publicKeyring();
        if (keyring.isEmpty()) {
            exchange.respond(404);
            return;
        }
        exchange.setResponseHeader("Content-Type", "application/pgp-keys");
        exchange.answer(keyring.get());
    }

    /** The current signer, or {@code null} when none is provisioned. A near-expiry key rotates as it is asked for, in
     *  the write that publishes its successor. */
    private OpenPgpSigner signer(Blobs blobs) throws IOException {
        return keys(blobs).signer().orElse(null);
    }

    /** This repository's signing key and served keyring, one document beside its RPM repositories. */
    private static SigningKeys keys(Blobs blobs) {
        return new SigningKeys(blobs.store(), "rpm/keyring/signing", IDENTITY, KEY_VALIDITY, ROTATION_WINDOW);
    }

    /** Stream the upload into the CAS while parsing only its header, then record the pointer and its repodata stanza. */
    private void publish(String rest, FormatExchange exchange, ArtifactStore store) throws IOException {
        int slash = rest.indexOf('/');
        if (slash < 0) {
            exchange.respond(400);
            return;
        }
        String repo = rest.substring(0, slash);
        String location = rest.substring(slash + 1);
        if (Keys.unsafe(repo) || unsafeLocation(location)) {
            // Validated before any store write: an unsafe segment cannot splice a key, and a refusal never leaves a
            // pointer without its stanza.
            exchange.respond(400);
            return;
        }
        InputStream in = exchange.requestStream();
        byte[] header;
        RpmHeader.Package pkg;
        try {
            header = RpmHeader.readHeaderRegion(in);
            pkg = RpmHeader.parse(header);
        } catch (IOException e) {
            exchange.respond(400);
            return;
        }
        String[] nevra = nevra(location.substring(location.lastIndexOf('/') + 1));
        if (nevra != null && !nevra[0].equals(pkg.name())) {
            // The header must name the package the filename deploys: the edge screens the filename, and primary.xml
            // serves the header's name.
            exchange.respond(400);
            return;
        }
        if (hasControlChar(pkg.summary()) || hasControlChar(pkg.description()) || hasControlChar(pkg.license())
                || hasControlChar(pkg.group()) || hasControlChar(pkg.version()) || hasControlChar(pkg.release())
                || hasControlChar(pkg.epoch()) || hasControlChar(pkg.sourceRpm())) {
            // XMLStreamWriter escapes < > & but not control characters, and every package's stanza is joined into one
            // primary.xml, so a header field with an XML-illegal control character would make the repository's metadata
            // unparseable for everyone.
            exchange.respond(400);
            return;
        }
        String hash;
        try (InputStream full = new SequenceInputStream(new ByteArrayInputStream(header), in)) {
            hash = store.writeBlob(full);
        }
        long size = store.size("blobs/" + hash);
        // Blobs.link retries the compare-and-set and clears any gc/condemned marker on a byte-identical blob, so a
        // republish is not collected after its 201.
        Blobs blobs = new Blobs(store);
        try {
            blobs.linkRelease("rpm/" + rest, hash, size);
        } catch (Publication.RepublishConflict taken) {
            exchange.respond(409, Blobs.alreadyPublished(rest));
            return;
        }
        byte[] stanza = primaryPackage(pkg, hash, size, location).getBytes(StandardCharsets.UTF_8);
        blobs.write(indexKey(repo, location), stanza);
        if (nevra != null) {
            // The reverse index a coordinate's pool keys are found through without walking the pool.
            blobs.note(RpmListings.reverseKey(repo, nevra[0], nevra[1] + "-" + nevra[2] + "." + nevra[3], location),
                    location);
        }
        if (!store.exists("rpm/" + repo + REPODATA + "hosted")) {
            store.write("rpm/" + repo + REPODATA + "hosted", new ByteArrayInputStream(new byte[0]));
        }
        // The served metadata is maintained here, on the publish: the stanza joins primary.xml if servable, which
        // re-derives primary.xml.gz, repomd.xml and its signature.
        listings(blobs).published(repo, location, stanza);
        exchange.respond(201);
    }

    RpmListings listings(Blobs blobs) {
        return new RpmListings(blobs, this::signerOrNull);
    }

    private OpenPgpSigner signerOrNull(Blobs blobs) {
        try {
            return signer(blobs);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Whether any {@code /}-separated segment of a location is unsafe to splice into an {@code rpm/...} key. */
    private static boolean unsafeLocation(String location) {
        for (String segment : location.split("/")) {
            if (Keys.unsafe(segment)) {
                return true;
            }
        }
        return false;
    }

    /** Serve a stored {@code .rpm}: HEAD from the pointer's recorded size, GET streamed from its blob. */
    private void serve(String rest, Blobs blobs, FormatExchange exchange) throws IOException {
        if (unsafeLocation(rest)) {
            exchange.respond(404);   // a traversal path names no served .rpm
            return;
        }
        Optional<Blobs.Located> located = blobs.locate("rpm/" + rest);
        if (located.isEmpty()) {
            exchange.respond(404);
            return;
        }
        long size = located.get().size();
        exchange.setResponseHeader("Content-Type", "application/x-rpm");
        if (exchange.method().equals("HEAD")) {
            if (size >= 0) {
                exchange.setResponseHeader("Content-Length", Long.toString(size));
            }
            exchange.respond(200, -1L).close();
            return;
        }
        blobs.serve(located.get(), exchange);
    }

    /**
     * Proxy an RPM/yum miss to an upstream yum repository. A {@code .rpm} is immutable, so it streams into the
     * content-addressed store and is cached; the {@code repodata} is mutable and streams through, needing no rewrite
     * since a {@code <location>} href is relative to the repository root. RPM has no canonical upstream, so a
     * deployment names one per repository.
     *
     * <h2>Upstream integrity ({@code ProxyFormat} clause 5)</h2>
     * A cached {@code .rpm} is held to the checksum its declaring index publishes:
     * {@code <upstream>/<repo>/repodata/repomd.xml} names the {@code primary} index, whose {@code <package>} at this
     * {@code <location href>} carries the {@code <checksum type="sha256" pkgid="YES">} of these bytes
     * ({@link RpmPackageDigests}). The body is digested as it streams and the pointer linked only on a match; on a
     * mismatch nothing is cached or served, the local {@code 404} stands, and the refusal is logged with both digests.
     *
     * <p>Three shapes stay unverified, each logged, each the upstream answering that it declares nothing:
     * <ul>
     *   <li>{@code <repo>/repodata/repomd.xml} answers {@code 404}/{@code 410}, as a plain file mirror does;</li>
     *   <li>the index lists no {@code <package>} at the pool path;</li>
     *   <li>the listed {@code <checksum>} is absent, malformed, or of an algorithm this JVM cannot compute.</li>
     * </ul>
     * An index that could not be read - unreached, a {@code 429}/{@code 5xx}/challenge, malformed, past the
     * decompression bound, or refused by the outbound screen - declines the fill with a {@code WARN} instead, since a
     * bound, a blip or a screen must never answer "this package is not in the index".
     */
    @Override
    public boolean pullThrough(FormatExchange exchange, ArtifactStore store, URI upstream,
                               ProxyFormat.Fetcher fetcher) throws IOException {
        String path = exchange.path();
        String rest = path.substring(PREFIX.length());
        String root = upstream.toString();
        if (!root.endsWith("/")) {
            root += "/";
        }
        URI target = URI.create(root + rest);
        if (rest.endsWith(".rpm")) {
            return cache(rest, target, exchange, store, upstream, fetcher);
        }
        // repodata is an enumeration dnf resolves against, where an absent repomd means "not a repository", so a fetch
        // that could not be made must not be served as one; only an upstream that answered 404/410 reaches the client
        // as a 404.
        return ProxyRelay.streamFresh(fetcher, target, null, exchange, ProxyRelay.Document.ENUMERATION);
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(RpmFormat.class);

    /** Fetch, verify and cache one upstream {@code .rpm}, then serve it. The declared digest is read first, a bounded
     *  metadata read remembered per index revision, so the body is one streamed pass with the pointer written only on a
     *  match. */
    private boolean cache(String rest, URI target, FormatExchange exchange, ArtifactStore store, URI upstream,
                          ProxyFormat.Fetcher fetcher) throws IOException {
        Blobs blobs = new Blobs(store);
        int slash = rest.indexOf('/');
        // Every unreadable shape of the declaring index declines the fill rather than caching unverified.
        ProxyRelay.Declared declared = slash <= 0 || Keys.unsafe(rest.substring(0, slash))
                ? ProxyRelay.Declared.NONE
                : RpmPackageDigests.declared(fetcher, upstream, rest.substring(0, slash),
                        rest.substring(slash + 1), blobs, ProxyLeg.allowInternalTargets(exchange));
        if (!declared.readable()) {
            return ProxyRelay.unverifiable(target, declared);
        }
        if (!declared.verifiable()) {
            LOGGER.debug("The repodata of {} declares no checksum for {}: caching it unverified.", upstream, rest);
        }
        try (ProxyFormat.Download download = fetcher.download(target, Map.of()).orElse(null)) {
            if (download == null || download.status() != 200) {
                return false;
            }
            if (!ProxyRelay.fill(blobs, "rpm/" + rest, target, download.body(), declared)) {
                return false;
            }
        }
        serve(rest, blobs, exchange);
        return true;
    }

    private void metadata(String rest, Blobs blobs, FormatExchange exchange, ArtifactStore store) throws IOException {
        int at = rest.indexOf(REPODATA);
        String repo = rest.substring(0, at);
        String file = rest.substring(at + REPODATA.length());
        if (Keys.unsafe(repo)) {
            exchange.respond(404);
            return;
        }
        boolean stanzas = StoredListing.present(store, RpmListings.primary(repo))
                || !blobs.isEmpty(indexPrefix(repo));
        if (!stanzas && hosted(repo, store)) {
            backfillStanzas(repo, blobs, store);
            stanzas = !blobs.isEmpty(indexPrefix(repo));
        }
        if (!stanzas) {
            exchange.respond(404);
            return;
        }
        // primary.xml is a stored listing the publish maintains and the rest its derived twins; a repository read
        // before its listing exists generates it once.
        RpmListings listings = listings(blobs);
        StoredListing.Spec spec = listings.spec(repo);
        Optional<StoredListing.Served> served;
        String contentType;
        if (file.endsWith("primary.xml")) {
            served = StoredListing.open(store, spec);
            contentType = "text/xml; charset=utf-8";
        } else if (file.endsWith("primary.xml.gz") || file.equals("repomd.xml") || file.equals("repomd.xml.asc")) {
            String derived = file.endsWith("primary.xml.gz") ? spec.listing() + ".gz" : RpmListings.repomd(repo, file);
            served = StoredListing.openDerived(store, derived);
            if (served.isEmpty() && !StoredListing.present(store, spec.listing())) {
                StoredListing.open(store, spec).ifPresent(RpmFormat::closeQuietly);   // materialising derives the twins
                served = StoredListing.openDerived(store, derived);
            }
            contentType = file.endsWith(".gz") ? "application/gzip"
                    : file.endsWith(".asc") ? "application/pgp-signature" : "text/xml; charset=utf-8";
        } else {
            exchange.respond(404);
            return;
        }
        if (served.isEmpty()) {
            exchange.respond(404);
            return;
        }
        try (StoredListing.Served document = served.get()) {
            Listings.serve(exchange, document, contentType);
        }
    }

    private static void closeQuietly(StoredListing.Served served) {
        try {
            served.close();
        } catch (IOException ignored) {
            // nothing was read from it
        }
    }

    /** The {@code repomd.xml}: the {@code primary} data with its checksums, sizes and the metadata revision, stamped on
     *  write so a re-read is byte-stable. */
    static byte[] repomd(long revision, StoredListing.Header primary, StoredListing.Header gzip) throws IOException {
        String href = "repodata/" + gzip.sha256() + "-primary.xml.gz";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            XMLStreamWriter xml = XMLOutputFactory.newInstance().createXMLStreamWriter(out, "UTF-8");
            xml.writeStartDocument("UTF-8", "1.0");
            xml.setDefaultNamespace(NS_REPO);
            xml.setPrefix("rpm", NS_RPM);
            xml.writeStartElement(NS_REPO, "repomd");
            xml.writeDefaultNamespace(NS_REPO);
            xml.writeNamespace("rpm", NS_RPM);
            element(xml, NS_REPO, "revision", Long.toString(revision));
            xml.writeStartElement(NS_REPO, "data");
            xml.writeAttribute("type", "primary");
            checksum(xml, "checksum", gzip.sha256());
            checksum(xml, "open-checksum", primary.sha256());
            xml.writeEmptyElement(NS_REPO, "location");
            xml.writeAttribute("href", href);
            element(xml, NS_REPO, "timestamp", Long.toString(revision));
            element(xml, NS_REPO, "size", Long.toString(gzip.size()));
            element(xml, NS_REPO, "open-size", Long.toString(primary.size()));
            xml.writeEndElement(); // data
            xml.writeEndElement(); // repomd
            xml.writeEndDocument();
            xml.close();
        } catch (XMLStreamException e) {
            throw new IOException(e);
        }
        return out.toByteArray();
    }

    private static void checksum(XMLStreamWriter xml, String element, String value) throws XMLStreamException {
        xml.writeStartElement(NS_REPO, element);
        xml.writeAttribute("type", "sha256");
        xml.writeCharacters(value);
        xml.writeEndElement();
    }

    /** One {@code primary.xml} {@code <package>} stanza, precomputed at publish from the header and stored blob and
     *  written with {@link XMLStreamWriter}, which escapes the header text. A fragment: the {@code rpm:} prefix is
     *  declared by the {@code <metadata>} wrapper. */
    private static String primaryPackage(RpmHeader.Package pkg, String hash, long size, String location) {
        StringWriter out = new StringWriter();
        try {
            XMLStreamWriter xml = XMLOutputFactory.newInstance().createXMLStreamWriter(out);
            xml.setPrefix("rpm", NS_RPM);
            xml.writeStartElement("package");
            xml.writeAttribute("type", "rpm");
            element(xml, "name", pkg.name());
            element(xml, "arch", pkg.arch());
            xml.writeEmptyElement("version");
            xml.writeAttribute("epoch", pkg.epoch());
            xml.writeAttribute("ver", pkg.version());
            xml.writeAttribute("rel", pkg.release());
            xml.writeStartElement("checksum");
            xml.writeAttribute("type", "sha256");
            xml.writeAttribute("pkgid", "YES");
            xml.writeCharacters(hash);
            xml.writeEndElement();
            element(xml, "summary", pkg.summary());
            element(xml, "description", pkg.description());
            element(xml, "packager", "");
            element(xml, "url", "");
            xml.writeEmptyElement("time");
            xml.writeAttribute("file", Long.toString(pkg.buildTime()));
            xml.writeAttribute("build", Long.toString(pkg.buildTime()));
            xml.writeEmptyElement("size");
            xml.writeAttribute("package", Long.toString(size));
            xml.writeAttribute("installed", Long.toString(pkg.installedSize()));
            xml.writeAttribute("archive", Long.toString(pkg.installedSize()));
            xml.writeEmptyElement("location");
            xml.writeAttribute("href", location);
            xml.writeStartElement("format");
            element(xml, NS_RPM, "license", pkg.license());
            element(xml, NS_RPM, "vendor", "");
            element(xml, NS_RPM, "group", pkg.group());
            element(xml, NS_RPM, "buildhost", "localhost");
            if (pkg.sourceRpm() != null) {
                element(xml, NS_RPM, "sourcerpm", pkg.sourceRpm());
            }
            xml.writeEmptyElement(NS_RPM, "header-range");
            xml.writeAttribute("start", Long.toString(pkg.headerStart()));
            xml.writeAttribute("end", Long.toString(pkg.headerEnd()));
            xml.writeStartElement(NS_RPM, "provides");
            xml.writeEmptyElement(NS_RPM, "entry");
            xml.writeAttribute("name", pkg.name());
            xml.writeAttribute("flags", "EQ");
            xml.writeAttribute("epoch", pkg.epoch());
            xml.writeAttribute("ver", pkg.version());
            xml.writeAttribute("rel", pkg.release());
            xml.writeEndElement(); // provides
            xml.writeEndElement(); // format
            xml.writeEndElement(); // package
            xml.close();
        } catch (XMLStreamException e) {
            throw new IllegalStateException(e);
        }
        return out.toString();
    }

    /** Whether a header text field carries an XML-1.0-illegal C0 control character (below {@code 0x20} except tab,
     *  newline and carriage return), which {@link XMLStreamWriter} would emit raw into primary.xml. */
    private static boolean hasControlChar(String value) {
        return value != null && value.chars().anyMatch(c -> c < 0x20 && c != '\t' && c != '\n' && c != '\r');
    }

    private static void element(XMLStreamWriter xml, String name, String text) throws XMLStreamException {
        xml.writeStartElement(name);
        xml.writeCharacters(text);
        xml.writeEndElement();
    }

    private static void element(XMLStreamWriter xml, String namespace, String name, String text)
            throws XMLStreamException {
        xml.writeStartElement(namespace, name);
        xml.writeCharacters(text);
        xml.writeEndElement();
    }

    @Override
    public Optional<ArtifactDescriptor> describe(String path) {
        if (!path.startsWith(PREFIX) || !path.endsWith(".rpm")) {
            return Optional.empty();
        }
        String rest = path.substring(PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return Optional.empty();
        }
        String repo = rest.substring(0, slash);
        String[] nevra = nevra(rest.substring(rest.lastIndexOf('/') + 1));
        if (nevra == null) {
            return Optional.of(ArtifactDescriptor.at(ECOSYSTEM, path));
        }
        return Optional.of(new ArtifactDescriptor(ECOSYSTEM, repo + "/" + nevra[0],
                nevra[1] + "-" + nevra[2] + "." + nevra[3], path, "application/x-rpm", false, null, -1L));
    }

    /**
     * The package version a stored RPM pointer serves, from which the inventory back-fill rebuilds a lost
     * {@code published} record.
     *
     * <p>An RPM file is {@code <name>-<version>-<release>.<arch>.rpm} and neither version nor release may contain a
     * hyphen, so {@link #nevra}'s right-to-left split is exact even for {@code python3-requests-2.31.0-1.noarch.rpm}.
     * It is {@link #describe} run on the request path this key serves, so the row rebuilt is the row the publish wrote.
     *
     * <p>Only a {@code .rpm} pool pointer is claimed: the repodata documents and the {@code by/} reverse index name no
     * version, and a filename that is no NEVRA is left unnamed, since a coordinate-less row would be aged by retention.
     */
    @Override
    public Optional<ArtifactDescriptor> describePointer(String key) {
        String root = PREFIX.substring(1);      // the store-key form of the request prefix: "rpm/"
        if (!key.startsWith(root) || !key.endsWith(".rpm")) {
            return Optional.empty();
        }
        int slash = key.indexOf('/', root.length());
        if (slash < 0 || key.startsWith(key.substring(0, slash) + REPODATA)) {
            // A by/ reverse-index leaf is a URL-encoded pool location that ends in .rpm and parses as a NEVRA, so it is
            // excluded here as eachPoolLocation excludes it.
            return Optional.empty();
        }
        return describe("/" + key)
                .filter(named -> named.coordinate() != null && named.version() != null)
                .filter(named -> BlobLayout.addressable(named.coordinate(), named.version()))
                .map(named -> new ArtifactDescriptor(named.ecosystem(), named.coordinate(), named.version(), key,
                        named.contentType(), named.prerelease(), null, 0L));
    }

    @Override
    public List<String> paths(String coordinate, String version, ArtifactStore store) {
        // Package pointers live in the shared Blobs namespace, so the coordinate enumerates nothing in publish/.
        return List.of();
    }

    /** Split an RPM filename {@code <name>-<version>-<release>.<arch>.rpm} into {name, version, release, arch}. */
    static String[] nevra(String file) {
        if (!file.endsWith(".rpm")) {
            return null;
        }
        String stem = file.substring(0, file.length() - ".rpm".length());
        int arch = stem.lastIndexOf('.');
        int release = stem.lastIndexOf('-');
        if (arch < 0 || release < 0 || release > arch) {
            return null;
        }
        int version = stem.lastIndexOf('-', release - 1);
        if (version < 0) {
            return null;
        }
        return new String[]{stem.substring(0, version), stem.substring(version + 1, release),
                stem.substring(release + 1, arch), stem.substring(arch + 1)};
    }

    /** Whether a repository has taken a hosted publish: it carries the marker {@link #publish} writes or a revision
     *  stamp, which a pull-through proxy never does. The stanza back-fill runs only for a hosted repository. */
    private static boolean hosted(String repo, ArtifactStore store) throws IOException {
        return store.exists("rpm/" + repo + REPODATA + "hosted")
                || store.readVersioned("rpm/" + repo + REPODATA + "revision").isPresent();
    }

    /** Rebuild a hosted repository's missing {@code primary.d} stanzas from its {@code .rpm} pointers, re-reading each
     *  header from the content-addressed store. Idempotent: an existing stanza is left alone and a concurrent identical
     *  rebuild dedupes on the compare-and-set. A pointer whose blob is gone or whose header no longer parses is
     *  skipped, so that package drops out of the index and the rest still serves. */
    private void backfillStanzas(String repo, Blobs blobs, ArtifactStore store) throws IOException {
        String base = "rpm/" + repo;
        eachPoolLocation(store, base, location -> backfillStanza(repo, blobs, store, base, location));
    }

    /** The bounds every descent of a repository's pointer tree runs under ({@link #eachPoolLocation}). Both callers
     *  need a complete answer - a short back-fill leaves a package out of {@code primary.xml}, a short {@code blobKeys}
     *  a hold partial - so the entry cap is only a continuation followed to exhaustion, and the step budget (one
     *  {@link ArtifactStore#exists} probe per node) raises a {@link TraversalException} rather than answering short. */
    private static final PagedTreeWalk POOL = PagedTreeWalk.bounded().steps(1_000_000).page(BoundedChildren.DRAIN_PAGE);

    /** Write the missing {@code primary.d} stanza of one {@code .rpm} pointer, or nothing when it exists, the blob is
     *  gone or the header no longer parses. */
    private void backfillStanza(String repo, Blobs blobs, ArtifactStore store, String base, String location)
            throws IOException {
        String stanzaKey = indexKey(repo, location);
        if (blobs.exists(stanzaKey)) {
            return;
        }
        String hash = blobs.hash(base + "/" + location).orElse(null);
        if (hash == null) {
            return;
        }
        RpmHeader.Package pkg;
        try (InputStream in = blobs.open(hash)) {
            pkg = RpmHeader.parse(RpmHeader.readHeaderRegion(in));
        } catch (IOException e) {
            return;
        }
        long size = store.size("blobs/" + hash);
        blobs.write(stanzaKey, primaryPackage(pkg, hash, size, location).getBytes(StandardCharsets.UTF_8));
    }

    static String indexPrefix(String repo) {
        return "rpm/" + repo + REPODATA + "primary.d";
    }

    static String indexKey(String repo, String location) {
        // A reversible encoding of the location into one traversal-free name: injective, so '~' in a location (a '~rc1'
        // pre-release) cannot collide with another's. The location is in the stanza body, so the key is never decoded.
        return indexPrefix(repo) + "/" + URLEncoder.encode(location, StandardCharsets.UTF_8);
    }

    static byte[] gzip(byte[] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(content);
        }
        return out.toByteArray();
    }

    /** The migration-import capability, delegated to {@link RpmImporter}. */
    private final RpmImporter importer = new RpmImporter();

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

    /** Each {@code .rpm} of the version is put at its location; the target derives its own repodata. */
    @Override
    public Exported export(ArtifactStore repository, String coordinate, String version, ExportTarget target)
            throws IOException {
        return BlobExport.put(repository, mount(), blobKeys(coordinate, version, repository), target);
    }
}
