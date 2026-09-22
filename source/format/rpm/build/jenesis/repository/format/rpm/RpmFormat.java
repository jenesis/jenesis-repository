package build.jenesis.repository.format.rpm;

import module java.base;
import module java.xml;
import module org.slf4j;

import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.format.Listings;
import build.jenesis.repository.format.signing.OpenPgpSigner;
import java.time.Duration;
import build.jenesis.repository.blobs.BlobLayout;
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
import build.jenesis.repository.walk.PagedTreeWalk;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.walk.ScreenedNames;
import build.jenesis.repository.walk.Traversal;
import build.jenesis.repository.walk.TraversalException;

/**
 * The RPM/yum format, so {@code dnf} and {@code yum} install {@code .rpm} packages over the shared store. It owns
 * {@code /rpm/...}, where the first path segment is a yum repository: a package is pushed with
 * {@code PUT /rpm/<repo>/<path>/<name>-<ver>-<rel>.<arch>.rpm} (the raw {@code .rpm} as the body) and served back from
 * the same path, and the {@code repodata} a client reads ({@code /rpm/<repo>/repodata/repomd.xml} and a
 * {@code primary.xml[.gz]}) is served from a stored listing the publish maintains.
 *
 * <p><b>Streaming publish.</b> Only the RPM header at the front of the upload is materialised (the small metadata parse
 * the streaming principle allows); the (arbitrarily large) cpio payload streams straight through
 * {@link ArtifactStore#writeBlob} into the content-addressed store, hashed on the way, and never buffered. The SHA-256
 * the store returns is both the pointer's blob hash and the package's {@code pkgid} checksum in {@code primary.xml}, so
 * the file is read once. A precomputed {@code <package>} stanza is stored per package, exactly as the Debian format
 * stores a {@code Packages} stanza, so the stored {@code primary.xml} is joined from the stanzas rather than by
 * reopening every {@code .rpm}. The metadata revision is stamped on write, so a re-read of {@code repomd.xml} is
 * byte-stable and cacheable between pushes (read-first).
 *
 * <p><b>Self-healing index.</b> The {@code <package>} stanza is a derived cache over the durable {@code .rpm} pointers,
 * so it reconstructs itself rather than assume it was always kept. When a hosted repository's {@code repodata} is read
 * but no stanzas exist for it - {@code .rpm} pointers that predate the stanza logic, an index enabled over packages a
 * prior build already stored, or a derived tree lost after the bytes were written - the read re-derives every stanza
 * from the live pointers by re-reading each package's header out of the content-addressed store, so the repository
 * converges to a complete {@code repodata} on its own, a config flip rather than a re-import or wipe-and-rebuild
 * ({@link #backfillStanzas}). It is bounded to that recovery path (an empty index on a hosted repository, never the
 * steady-state read once stanzas exist) and keyed on the hosted revision stamp, so a pull-through proxy repository -
 * which never stamps a revision - is untouched and still falls its local miss through to the authoritative upstream
 * {@code repodata} rather than shadowing it with a partial local index.
 *
 * <p><b>Pull-through proxy.</b> The same layout is also a {@link ProxyFormat}: a local miss on a proxy repository is
 * served from an upstream yum repository - an immutable {@code .rpm} streams from upstream straight into the CAS and is
 * cached (a later read is a local hit), while the mutable {@code repodata} is streamed through fresh. RPM has no single
 * canonical upstream, so a deployment names one per repository ({@link #defaultUpstream()} stays empty).
 *
 * <p><b>Signed metadata.</b> When a signing key is provisioned ({@code POST /rpm/keyring}, a deployment-global RSA key
 * mirroring the Debian one), the {@code repomd.xml} is OpenPGP-signed as it is derived: a detached armored signature is
 * served at {@code /rpm/<repo>/repodata/repomd.xml.asc} and the public key at {@code /rpm/keyring/public.asc}, so a
 * client with {@code repo_gpgcheck=1} and {@code gpgkey=} pointing at that key verifies the metadata - the yum
 * counterpart of apt's {@code Release.gpg}. Signing uses Bouncy Castle (the JDK has no OpenPGP) via
 * {@link OpenPgpSigner}, and a near-expiry key rotates the next time it is used, its retiring public half staying
 * in the served keyring during the overlap. Without a provisioned key the repository is unsigned and
 * {@code repomd.xml.asc} is a {@code 404}. That is the signature this deployment <em>puts on</em> its metadata; the
 * one the publisher put on the package - in the package's own signature header, what {@code gpgcheck=1} verifies -
 * is read through the {@link ArtifactSignatures} seam ({@link #expects}, {@link #evidence}, composed by
 * {@link RpmSignature}), so the gate's signature dimension judges a pushed or proxied {@code .rpm} as it judges a
 * Maven jar or a {@code .deb}.
 *
 * <p>The layout declares its ecosystem ({@code "RPM"}) so a compliance inspector, the console and download tracking key
 * on it. Package pointers live in the shared {@code Blobs} namespace like the other language formats, so the
 * {@code publish/}-namespace eviction ({@link #paths}) stays empty; coordinate-scoped enforcement instead runs through
 * the {@code BlobLayout} seam - {@link #blobKeys}/{@link #servedPaths} walk the pool tree for a version's {@code .rpm}
 * pointers, so a retroactive KEV/license hold withholds their content hashes and retracts serving. {@link #describe}
 * resolves a {@code .rpm} path to its NEVRA coordinate for read-side download tracking and the enforcement round-trip.
 */
public final class RpmFormat implements RepositoryFormat, ArtifactLayout, ProxyLeg, BlobLayout, RepositoryImporter,
        ArtifactSignatures {

    /** The OSV-style ecosystem name this format's artifacts report (distinct from {@link #name()}, the routing id). */
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

    @Override
    public String ecosystem() {
        return ECOSYSTEM;
    }

    @Override
    public List<String> blobRoots() {
        return List.of("rpm");
    }

    /**
     * RPM's inbound signature story: the publisher's OpenPGP signature in the package's own signature header, and
     * <em>optional</em> rather than expected, for Debian's reason with RPM's names. A dnf client's trust runs
     * through {@code repo_gpgcheck} over the signed {@code repomd.xml}, which commits to the hashes of the
     * {@code primary.xml} that commits to each package - the signature this format itself writes - so a repository
     * of unsigned packages behind signed metadata is an ordinary, well-run one, and demanding a per-package
     * signature would report every such archive as unsigned. {@code gpgcheck=1} exists, distributions sign every
     * package they ship, and where a package carries one it is worth checking; that is exactly what
     * {@code OPTIONAL} says. A {@code .rpm} is signable wherever it lives under a repository's pool; the generated
     * {@code repodata} is not a package and carries no expectation.
     */
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
        // An RPM package is keyed on its request pool path (rpm/<repo>/<path>/<file>.rpm), where the filename NEVRA is
        // <name>-<version>-<release>.<arch>.rpm. describe() maps that path to the coordinate (<repo>/<name>) and version
        // (<version>-<release>.<arch>); recover the coordinate-scoped keys by walking the repo's pool tree for every
        // .rpm whose filename NEVRA re-describes to the SAME (coordinate, version) - a NEVRA may sit under several pool
        // locations, and all are the version's keys - so blobHashes/servedPaths/eviction reach a hosted RPM version and
        // a retroactive KEV/license hold actually retracts serving (before this, an unconditional empty made the hold a
        // silent no-op; finding #1).
        if (!BlobLayout.addressable(coordinate, version)) {
            // A traversal-shaped repo, package name or NEVRA maps nowhere: these keys are what an eviction DELETES,
            // and ArtifactStore.delete is not screened. The shared per-part screen, so the two-segment <repo>/<name>
            // coordinate is judged part by part rather than as a whole.
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
            // The reverse index a publish writes answers without a walk; a repository from before it is walked as
            // before, until the rebuild pass has backfilled it.
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
            // Mirror describe() EXACTLY: coordinate <repo>/<name> (name = nevra[0]), version
            // <version>-<release>.<arch>, so a coordinate describe() produced is one blobKeys resolves.
            if (nevra != null && nevra[0].equals(name)
                    && (nevra[1] + "-" + nevra[2] + "." + nevra[3]).equals(version)) {
                keys.add(base + "/" + location);
            }
        });
        return keys;
    }

    /** The served request paths one RPM coordinate version occupies - each {@code .rpm} pool pointer key mapped back to
     *  its download request path ({@code /rpm/<repo>/<location>} = {@code /} + the store key, the inverse of
     *  {@link #describe}), so a retroactive hold links a {@code /quarantine} review handle at each and serving retracts.
     *  Derived from {@link #blobKeys}, so it is non-empty exactly when the coordinate-scoped pool walk resolves keys. */
    @Override
    public List<String> servedPaths(String coordinate, String version, ArtifactStore store) throws IOException {
        List<String> paths = new ArrayList<>();
        for (String key : blobKeys(coordinate, version, store)) {
            paths.add("/" + key);
        }
        return paths;
    }

    /**
     * Deliver every {@code .rpm} pool location under a repository - the key's tail below {@code rpm/<repo>/}, with the
     * generated {@code repodata} subtree excluded because it is metadata rather than a package - to {@code locations},
     * driving {@link #POOL} to exhaustion.
     *
     * <p>This is the format's <em>only</em> descent of a pool tree. It has two consumers with nothing else in common -
     * the stanza back-fill, which re-reads each package's header, and the {@link BlobLayout} coordinate seam, which
     * matches each filename's NEVRA - and found them as two descents of the same tree: the back-fill already ran
     * on the shared {@link PagedTreeWalk} primitive while {@code blobKeys} still hand-rolled an explicit cursor stack
     * over {@code store.page}. The hand-rolled one was bounded and paged, so it was never a correctness defect; it was
     * a second implementation of a shared mechanism, and the bounds it did <em>not</em> have are the reason folding it
     * in is worth doing - it had no step budget and no depth ceiling, so a pathological pool tree cost an unbounded
     * number of store round-trips silently, where {@link #POOL} now refuses it by name.
     *
     * <p>The pool tree is publish-plantable to arbitrary depth and width, so the descent stays the shared iterative,
     * paged one - never self-recursion over an unpaged {@code list()}. A caller accumulating what it is handed is
     * bounded by its own filter rather than by the tree: {@code blobKeys} keeps only the locations one NEVRA occupies,
     * which is the handful of pool paths one package version was published under.
     */
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

    /** Ensure a signing key exists (generate an unprotected RSA key on first call) and return its public key. The key is
     *  deployment-global (like the Debian signing key) and signs the {@code repomd.xml} of every hosted RPM repository. */
    private void provisionKey(Blobs blobs, FormatExchange exchange) throws IOException {
        if (!blobs.exists("rpm/keyring/secret.asc")) {
            // Established rather than written, and the published half derived from whichever secret won: two first
            // publishes racing would otherwise store one pair's secret beside another pair's public, and every
            // signature this repository makes would be refused by a client doing its job.
            OpenPgpSigner signer = new OpenPgpSigner(
                    blobs.establish("rpm/keyring/secret.asc", () -> OpenPgpSigner.generate(IDENTITY, KEY_VALIDITY).secretKey()));
            blobs.write("rpm/keyring/public.asc", signer.publicKeyring());
            // The signed repomd.xml.asc is derived on metadata writes; a repository indexed before the key existed
            // gets it now rather than on its next publish.
            RpmListings listings = listings(blobs);
            for (String repo : blobs.list("rpm")) {
                if (!repo.equals("keyring") && !blobs.isEmpty(indexPrefix(repo))) {
                    listings.rederive(repo);
                }
            }
        }
        servePublicKey(blobs, exchange);
    }

    /** Serve the public signing key ({@code gpgkey=} target), or {@code 404} when the repository is unsigned. */
    private void servePublicKey(Blobs blobs, FormatExchange exchange) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (!blobs.read("rpm/keyring/public.asc", buffer)) {
            exchange.respond(404);
            return;
        }
        exchange.setResponseHeader("Content-Type", "application/pgp-keys");
        respondBody(exchange, buffer.toByteArray());
    }

    /** The current signer, or {@code null} when no key is provisioned (the repository serves unsigned metadata and no
     *  {@code repomd.xml.asc}). Reading rotates a near-expiry key, mirroring the Debian on-read rotation. */
    private OpenPgpSigner signer(Blobs blobs) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (!blobs.read("rpm/keyring/secret.asc", buffer)) {
            return null;
        }
        OpenPgpSigner signer = new OpenPgpSigner(buffer.toByteArray());
        return signer.dueForRotation(Instant.now(), ROTATION_WINDOW) ? rotate(blobs) : signer;
    }

    /** Rotate to a fresh signing key: it signs from now on, while the retiring key's public half stays in the served
     *  keyring until it expires, so a client that already trusts it still verifies a {@code repomd.xml.asc} it signed
     *  during the overlap. Best-effort under concurrency - a lost race simply re-rotates on the next read. */
    private OpenPgpSigner rotate(Blobs blobs) throws IOException {
        OpenPgpSigner.KeyMaterial fresh = OpenPgpSigner.generate(IDENTITY, KEY_VALIDITY);
        ByteArrayOutputStream existing = new ByteArrayOutputStream();
        byte[] published = blobs.read("rpm/keyring/public.asc", existing)
                ? OpenPgpSigner.mergePublicKeyrings(existing.toByteArray(), fresh.publicKey(), Instant.now())
                : fresh.publicKey();
        blobs.write("rpm/keyring/secret.asc", fresh.secretKey());
        blobs.write("rpm/keyring/public.asc", published);
        return new OpenPgpSigner(fresh.secretKey());
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
            // Validate the repo and each location segment BEFORE any store write, so an unsafe coordinate cannot splice
            // a key (the .rpm pointer goes through the local pointer() helper, which does not pass Blobs.requireSafeKey)
            // and a rejected key can never land the pointer first and then fail the stanza write, leaving a dangling
            // pointer. Mirrors the segment guard every sibling format applies to its coordinate.
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
            // The RPM header names a different package than the filename it deploys under: refuse rather than let it be
            // screened under the filename NEVRA yet served (the primary.xml <name>) under the header name (a screen-
            // label bypass), the way Composer/CocoaPods refuse a manifest that disagrees with the deploy path. The
            // importer screens on the filename coordinate, so the two must agree.
            exchange.respond(400);
            return;
        }
        if (hasControlChar(pkg.summary()) || hasControlChar(pkg.description()) || hasControlChar(pkg.license())
                || hasControlChar(pkg.group()) || hasControlChar(pkg.version()) || hasControlChar(pkg.release())
                || hasControlChar(pkg.epoch()) || hasControlChar(pkg.sourceRpm())) {
            // An RPM header text field carrying an XML-1.0-illegal control char would be emitted raw by XMLStreamWriter
            // into this package's primary.xml <package> stanza (the writer escapes < > & but passes control chars
            // through unescaped). Because primary() concatenates EVERY package's stanza into one served primary.xml, a
            // single such field makes the whole repo's metadata unparseable to dnf/yum - a repo-wide availability DoS
            // from one crafted upload. Refuse at publish, before the poison is stored, the way the Debian Packages
            // injection is refused at push.
            exchange.respond(400);
            return;
        }
        String hash;
        try (InputStream full = new SequenceInputStream(new ByteArrayInputStream(header), in)) {
            hash = store.writeBlob(full);
        }
        long size = store.size("blobs/" + hash);
        // Route the .rpm pointer through Blobs.link (not the local pointer() helper): besides the compare-and-set retry,
        // link clears any gc/condemned/<hash> marker a collector set, so republishing a package byte-identical to a
        // condemned one un-condemns it before the sweep deletes it - otherwise a 201 publish is GC-deleted to a
        // permanent 404. The repo/location segments are validated above, so the key is safe.
        Blobs blobs = new Blobs(store);
        blobs.link("rpm/" + rest, hash, size);
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
        // The served metadata is written here, on the publish, rather than generated on every read: the stanza joins
        // the stored primary.xml (if the package is servable), which re-derives primary.xml.gz, repomd.xml and its
        // signature.
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

    /** Whether a coordinate segment must not be spliced into a store key - empty, a dot segment, or carrying a path
     *  separator or control character. Mirrors the guard the sibling formats (debian/npm/…) apply. */

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
     * Proxy an RPM/yum miss to an upstream yum repository. A {@code .rpm} package is immutable, so it streams from
     * upstream straight into the content-addressed store (never buffered) and is served, so a later read is a local
     * hit that never touches the upstream. The {@code repodata} a client reads ({@code repomd.xml},
     * {@code primary.xml[.gz]}, {@code filelists}, ...) is mutable and is streamed through fresh - a package's
     * {@code <location>} href is relative to the repo root, which maps onto this repository's {@code /rpm/} prefix,
     * so the index needs no rewrite. RPM has no single canonical upstream (CentOS, Fedora, Rocky, EPEL, ...), so a
     * deployment always names one per repository and {@link #defaultUpstream()} stays empty.
     *
     * <h2>Upstream integrity ({@code ProxyFormat} clause 5)</h2>
     * A cached {@code .rpm} is held to the checksum its own declaring index publishes for it. The request path names
     * its repository, so that index is addressable: {@code <upstream>/<repo>/repodata/repomd.xml} names the
     * {@code primary} index, whose {@code <package>} at this {@code <location href>} carries the
     * {@code <checksum type="sha256" pkgid="YES">} of exactly these bytes ({@link RpmPackageDigests}, which reads the
     * index once per revision and remembers it). The body is digested as it streams into the content-addressed store
     * and the serving pointer is linked only on a match; on a <b>mismatch nothing is linked and nothing is served</b>,
     * the local {@code 404} stands so a later pull re-hits the upstream, and the refusal is logged with the location
     * and both digests.
     *
     * <p>Three request shapes stay unverified, and each one says so in the log line it emits. All three are the
     * upstream <em>answering</em> that it declares nothing here:
     * <ul>
     * <li>an upstream root that <b>answers {@code 404}/{@code 410} for {@code <repo>/repodata/repomd.xml}</b> - a plain
     *     file mirror declares nothing, and this leg refuses to fabricate a check;</li>
     * <li>a pool path the index <b>lists no {@code <package>} for</b> - a package that outlived its index entry, or one
     *     the repository never offered;</li>
     * <li>a listed package whose {@code <checksum>} is <b>absent, malformed, or of an algorithm</b> this JVM has no
     *     digest for.</li>
     * </ul>
     * An index this repository <b>could not read</b> is not one of them and never downgrades the fill: a
     * {@code repomd.xml} the transport never reached or that answered a {@code 429}/{@code 5xx}/challenge, a
     * {@code repomd.xml} or {@code primary} index that is malformed or ran past the decompression bound, a primary
     * index that does not answer, and a primary index URL the outbound screen refuses. Each of those declines
     * the fill with a {@code WARN} - nothing cached, nothing served, the local {@code 404} standing - because a bound,
     * a blip or a screen must never be able to answer "this package is not in the index".
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
        // Everything that is not a .rpm is repodata - repomd.xml and the primary/filelists/other indexes it names -
        // and that is an ENUMERATION: it is the document dnf resolves against, listing which packages a repository
        // carries and at which versions. An absent repomd is the answer "this is not a repository / it is empty", so a
        // fetch this deployment could not make must not be served as one; only an upstream that ANSWERED 404/410
        // reaches the client as a 404. The hand-written streaming loop this replaces was the shared one line
        // for line - forward the client's validators, relay a 304, relay the upstream Content-Type with no default of
        // its own - so it now runs the shared one and gains the split with it.
        return ProxyRelay.streamFresh(fetcher, target, null, exchange, ProxyRelay.Document.ENUMERATION);
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(RpmFormat.class);

    /**
     * Fetch, verify and cache one upstream {@code .rpm}, then serve it - the integrity half of {@link #proxy}. The
     * digest its declaring index publishes is read first (a bounded metadata read, remembered per index revision), so
     * the body itself is one streamed pass into the content-addressed store with the digest computed on the way and
     * the serving pointer written only once it matches.
     */
    private boolean cache(String rest, URI target, FormatExchange exchange, ArtifactStore store, URI upstream,
                          ProxyFormat.Fetcher fetcher) throws IOException {
        Blobs blobs = new Blobs(store);
        int slash = rest.indexOf('/');
        // gave this leg the refusal shape for one of the ways the declaring index cannot be read (an index URL
        // the outbound screen rejects); makes every one of them reach it, so a repomd behind a shared-egress 429
        // and a primary index past its decompression bound decline the fill exactly as a refused target does instead
        // of returning the "declares no checksum" that caches the package unverified.
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
        // The metadata is a stored listing the publish maintains: primary.xml is the document, the rest its derived
        // twins, all streamed as stored. A repository read before its listing was materialised generates it now, once.
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

    /** The {@code repomd.xml} index of indices: the {@code primary} data with its compressed and open checksums, sizes
     *  and the metadata revision - stamped on write so a re-read is byte-stable and revalidatable. */
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

    /**
     * One {@code primary.xml} {@code <package>} stanza, precomputed at publish from the header and stored blob. Built
     * with {@link XMLStreamWriter} (the writer {@code maven-metadata} uses), so
     * arbitrary header text is escaped by the writer rather than a hand-rolled escaper. The stanza is a fragment: the
     * {@code rpm:} prefix is bound but left undeclared here, since the {@code <metadata>} wrapper that concatenates the
     * stanzas declares {@code xmlns:rpm}.
     */
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

    /** Whether a header text field carries an XML-1.0-illegal C0 control char - anything below {@code 0x20} except tab,
     *  newline and carriage return - that {@link XMLStreamWriter} would emit raw into primary.xml, corrupting the
     *  concatenated repo-wide index. */
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
     * The package version a stored RPM pointer serves - the backwards direction the inventory back-fill rebuilds a
     * lost {@code published/} row from.
     *
     * <p>Like Debian's, the pair lives in a <em>filename</em> rather than in path segments, and like Debian's the
     * only thing that makes decoding it safe is a rule of the ecosystem rather than of this store: an RPM file is
     * {@code <name>-<version>-<release>.<arch>.rpm}, and neither a version nor a release may contain a hyphen. So
     * {@link #nevra}'s right-to-left split is exact even where the package name holds several of them
     * ({@code python3-requests-2.31.0-1.noarch.rpm}), which is what npm's {@code <shortName>-<version>.tgz} and
     * Cargo's {@code <crate>-<version>} cannot say, and why those two are deliberately left undecoded.
     *
     * <p>It is {@link #describe} run on the request path this key serves - {@code servedPaths} is exactly
     * {@code "/" + key} - so the row this rebuilds is the row the accept path wrote rather than a second parse
     * that has to be kept in step with it.
     *
     * <p>Only a {@code .rpm} pool pointer is claimed. The repodata documents and the {@code by/} reverse index
     * under the same root name no version, and a filename that does not parse as a NEVRA is left unnamed: there
     * {@code describe} answers a coordinate-less descriptor, which is the right answer for a request and the wrong
     * one for a durable row that retention would then age by.
     */
    @Override
    public Optional<ArtifactDescriptor> describePointer(String key) {
        String root = PREFIX.substring(1);      // the store-key form of the request prefix: "rpm/"
        if (!key.startsWith(root) || !key.endsWith(".rpm")) {
            return Optional.empty();
        }
        int slash = key.indexOf('/', root.length());
        if (slash < 0 || key.startsWith(key.substring(0, slash) + REPODATA)) {
            // The by/ reverse index stores a URL-ENCODED pool location, so its leaf really does end in .rpm and
            // really does parse as a NEVRA - describing one would record a row whose package name is the encoded
            // path. eachPoolLocation excludes the same subtree for the same reason: a pointer is a pool location
            // or it is nothing, and the difference is invisible in the filename alone.
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
        // RPM package pointers live in the shared Blobs namespace (like npm/pypi/go/debian), not the Publication
        // namespace coordinate-based eviction walks, so nothing is enumerable from the coordinate alone here.
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

    /** Whether a repository has ever taken a hosted publish - it then carries the marker {@link #publish} writes (or
     *  the revision stamp earlier publishes wrote), which a pull-through proxy repository (whose {@code .rpm} bytes are
     *  cached, not published) never does.
     *  The self-healing stanza back-fill keys on this so it reconstructs a hosted repo's lost or late index but never
     *  shadows a proxy repo's authoritative upstream repodata with a partial local one. */
    private static boolean hosted(String repo, ArtifactStore store) throws IOException {
        return store.exists("rpm/" + repo + REPODATA + "hosted")
                || store.readVersioned("rpm/" + repo + REPODATA + "revision").isPresent();
    }

    /** Rebuild the per-package {@code primary.d} stanzas of a hosted repo from its live {@code .rpm} pointers: each
     *  package's header is re-read from the content-addressed store (the same bounded metadata parse a publish makes)
     *  and its stanza written where one is missing. Idempotent - a stanza that already exists is left alone and the
     *  compare-and-set pointer write dedupes a concurrent reader's identical rebuild - so a repository converges on a
     *  read with no re-import. A pointer whose blob is gone or whose header no longer parses is skipped rather than
     *  failing the whole read: that one package degrades out of the index, the rest of the repository still serves. */
    private void backfillStanzas(String repo, Blobs blobs, ArtifactStore store) throws IOException {
        String base = "rpm/" + repo;
        eachPoolLocation(store, base, location -> backfillStanza(repo, blobs, store, base, location));
    }

    /** The bounds every descent of a repository's pointer tree runs under - the stanza back-fill's and the
     *  {@link BlobLayout} coordinate seam's alike, through {@link #eachPoolLocation}. Both are answers that must be
     *  <em>complete</em>: a back-fill that stopped early would leave a package permanently out of
     *  {@code primary.xml} while reporting a rebuilt index, and a short {@code blobKeys} would make a retroactive hold
     *  a silent partial no-op. So the entry cap is only a per-call continuation the caller follows to exhaustion, and
     *  the binding bound is the step budget (one {@link ArtifactStore#exists} probe per opened node), which raises a
     *  named {@link TraversalException} rather than answering short. Replaces a self-recursion that also buffered
     *  every {@code .rpm} location of the whole repository into one list before writing the first stanza. */
    private static final PagedTreeWalk POOL = PagedTreeWalk.bounded().steps(1_000_000).page(BoundedChildren.DRAIN_PAGE);

    /** Write the missing {@code primary.d} stanza of one {@code .rpm} pointer, or leave the repository as it is when
     *  the stanza already exists, the blob is gone, or the header no longer parses - that one package degrades out of
     *  the index, the rest of the repository still serves. */
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
        // Flatten the location into one traversal-free stanza-object name with a reversible encoding, not a raw
        // '/'->'~' swap: that swap collided any location already containing a '~' (a legal .rpm character, e.g. a
        // '~rc1' pre-release) with a different slashed location, so one package's stanza overwrote another's in the
        // primary.d index. URL-encoding is injective, and the location is carried inside the stanza body, so the key
        // never needs decoding back.
        return indexPrefix(repo) + "/" + URLEncoder.encode(location, StandardCharsets.UTF_8);
    }

    private static void respondBody(FormatExchange exchange, byte[] body) throws IOException {
        if (exchange.method().equals("HEAD")) {
            exchange.setResponseHeader("Content-Length", Integer.toString(body.length));
            exchange.respond(200, -1L).close();
        } else {
            exchange.respond(200, body);
        }
    }

    static byte[] gzip(byte[] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(content);
        }
        return out.toByteArray();
    }

    /** The migration-import capability (WSPI.2 (c)), delegated to the layout-only {@link RpmImporter} - the format IS the
     *  discovered importer now (an {@code instanceof} capability), and the importer class stays as its delegate. */
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
}
