package build.jenesis.repository.format.cargo;

import module java.base;
import module tools.jackson.databind;

import build.jenesis.repository.format.Listings;
import build.jenesis.repository.blobs.RequestBase;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.blobs.Keys;
import build.jenesis.repository.blobs.OutboundTargets;
import build.jenesis.repository.blobs.ProxyLeg;
import build.jenesis.repository.blobs.ProxyRelay;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.Withheld;

/**
 * The Cargo registry format (the sparse-index protocol), so {@code cargo publish} and {@code cargo build} resolve Rust
 * crates over the shared store. It owns {@code /cargo/...}, where the first path segment is a registry: a crate is
 * pushed with {@code PUT /cargo/<repo>/api/v1/crates/new} (Cargo's length-prefixed publish frame - a little-endian
 * {@code u32} JSON-metadata length, the metadata, a {@code u32} {@code .crate} length, then the {@code .crate} bytes)
 * and downloaded from {@code /cargo/<repo>/api/v1/crates/<name>/<version>/download}. The sparse index a client reads
 * ({@code /cargo/<repo>/config.json} and the per-crate index file at Cargo's name-sharded path, e.g.
 * {@code /cargo/<repo>/se/rd/serde}) is generated on read ({@code config.json}) or streamed from a stored listing
 * the publish maintains.
 *
 * <p><b>Streaming publish, unwrapped at the choke point.</b> Only the JSON metadata at the front of the publish frame
 * is materialised (the small, bounded index parse the streaming principle allows); the (arbitrarily large)
 * {@code .crate} archive that follows it is handed to the shared hosted-publish operation as the accepted body, so it
 * streams hash-on-write into the content-addressed store and is never buffered. Because the frame is unwrapped
 * <em>before</em> the artifact reaches the screen, the bytes the interceptor chain hashes and assesses are the crate's
 * own - not the frame that carried it ({@link #screened()}, (a)). The SHA-256 the operation returns is both the
 * crate pointer's blob hash and the {@code cksum} Cargo's index records, so the archive is read once. A precomputed
 * index line is stored per version, exactly as the Debian and RPM formats store a per-package stanza, so the publish
 * joins the stored lines into the crate's index file and a read streams it rather than reopening every {@code .crate}
 * (read-first).
 *
 * <p><b>Pull-through proxy.</b> The same layout is also a {@link ProxyFormat}: a local miss on a proxy registry is
 * served from an upstream sparse-index registry (crates.io by default, {@link #defaultUpstream()}). A per-crate index
 * file is mutable, so it streams through fresh on every read (never cached, and it needs no rewrite - the sparse index
 * carries no download URLs; a client builds those from the {@code config.json} this registry generates, which points
 * downloads back through here). A {@code .crate} archive is immutable, so it streams from upstream straight into the
 * CAS and is cached (a later read is a local hit). The upstream download URL is resolved by reading the upstream's
 * {@code config.json} {@code dl} template (Cargo's {@code {crate}}/{@code {version}}/{@code {prefix}} markers, or the
 * {@code /{crate}/{version}/download} default), so the proxy honours whatever download layout the upstream declares.
 * The local {@code config.json} is always generated (never proxied), so the client fetches every crate through here.
 *
 * <p>The layout declares its ecosystem ({@code "crates.io"}, the OSV package-ecosystem name for Rust crates) so a
 * compliance inspector, the console and download tracking key on it; {@link #describe} resolves a crate download path
 * to its {@code name}/{@code version} coordinate. Crate pointers live in the shared {@code Blobs} namespace like the
 * other language formats, so the {@code publish/}-namespace eviction ({@link #paths}) stays empty; coordinate-scoped
 * enforcement runs through the {@code BlobLayout} seam ({@link #blobKeys}/{@link #servedPaths}) instead.
 */
public final class CargoFormat implements RepositoryFormat, ArtifactLayout, ProxyLeg, BlobLayout, RepositoryImporter {

    /** The OSV package-ecosystem name this format's artifacts report (distinct from {@link #name()}, the routing id). */
    public static final String ECOSYSTEM = "crates.io";

    static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PREFIX = "/cargo/";
    private static final String NEW = "api/v1/crates/new";
    private static final String API_CRATES = "api/v1/crates/";
    private static final String DOWNLOAD = "/download";
    private static final String CONFIG = "config.json";

    /** Cargo's publish response - the empty warning envelope every registry returns on a successful upload. */
    private static final byte[] WARNINGS =
            "{\"warnings\":{\"invalid_categories\":[],\"invalid_badges\":[],\"other\":[]}}"
                    .getBytes(StandardCharsets.UTF_8);

    /** A hostile publish frame cannot force a large metadata allocation: the JSON crate metadata is small and bounded. */
    private static final int MAX_METADATA = 32 * 1024 * 1024;

    @Override
    public String name() {
        return "cargo";
    }

    @Override
    public String ecosystem() {
        return ECOSYSTEM;
    }

    @Override
    public boolean handles(String path) {
        return path.startsWith(PREFIX);
    }

    @Override
    public void serve(FormatExchange exchange, ArtifactStore store) throws IOException {
        String rest = exchange.path().substring(PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            exchange.respond(404);
            return;
        }
        String repo = rest.substring(0, slash);
        String sub = rest.substring(slash + 1);
        String method = exchange.method();
        if (method.equals("PUT") && sub.equals(NEW)) {
            publish(repo, exchange, store);
        } else if (!method.equals("GET") && !method.equals("HEAD")) {
            exchange.respond(405);
        } else if (sub.equals(CONFIG)) {
            config(repo, exchange);
        } else if (sub.startsWith(API_CRATES) && sub.endsWith(DOWNLOAD)) {
            download(repo, sub, new Blobs(store), exchange);
        } else {
            index(repo, sub, store, exchange);
        }
    }

    /**
     * Cargo's publish protocol wraps its artifact, so this format is <b>not</b> edge-screened ((a)): a
     * {@code PUT /cargo/<repo>/api/v1/crates/new} body is a length-prefixed <em>frame</em>
     * ({@code [u32 json-len][json][u32 crate-len][.crate]}), not the {@code .crate} itself. Gating that frame at the
     * shared single-body edge would hash and assess the envelope while the bytes that later serve are the
     * {@code .crate} inside it - a second content-addressed object under a hash no interceptor ever saw, which is
     * {@code RepositoryFormat} clause 14's fail-open direction. The shared edge ({@code ScreenedDispatch}) takes the
     * request body verbatim and offers no seam to unwrap one, so this format is in the {@code screened() == false}
     * case the clause names and screens at its own documented choke point: {@link #publish} unwraps the frame while
     * it streams and drives the shared {@code Publication.commit} - with the <em>discovered</em> interceptor chain and
     * observers - over the {@code .crate}'s own bytes, under the crate's own download path. There is exactly one
     * choke point (this endpoint is the only way a crate is hosted-published here), so declaring {@code false} does
     * not leave the format unscreened.
     */
    @Override
    public boolean screened() {
        return false;
    }

    /**
     * The republish conflict policy this format hands the hosted-publish operation as <em>data</em>: {@code OVERWRITE},
     * last-writer-wins - exactly what a {@code cargo publish} did before (a), since a crate pointer lives in the
     * {@code cargo/} blobs namespace rather than in {@code publish/}.
     */
    private static final Publication.Republish REPUBLISH = Publication.Republish.overwrite();

    /**
     * Unwrap the publish frame and run the {@code .crate} it carries through the one shared hosted-publish
     * choreography ({@code Publication.commit}). The frame is {@code [u32 json-len][json][u32 crate-len][.crate]};
     * only the small, bounded JSON metadata at the front is materialised, and the {@code .crate} that follows it is
     * handed to the operation as the accepted body, so it streams hash-on-write into the content-addressed store and
     * <b>the hash the chain assesses is the hash the download later serves</b>. That is (a)'s whole content: the
     * former code stored the crate with a raw {@link ArtifactStore#writeBlob} while the edge gated the surrounding
     * frame, so an artifact whose <em>content</em> a screen would refuse published anyway.
     *
     * <p>The metadata is read <em>before</em> the crate, so unlike NuGet this format knows its coordinate up front:
     * the descriptor carries the real {@code name}/{@code vers} and the crate's own download path, which is what a
     * deny-list keys on, what a {@code /quarantine} hold pointer is linked at, and what an inspector's artifact leg
     * parses.
     *
     * <p>Pointer-last, and the ordering is now the operation's rather than hand-written ((c) for this format
     * falls out with it): the precomputed sparse-index line - which needs the accepted hash as its {@code cksum} - is
     * content-addressed inside the layout, before anything serves, and the two visibility writes are declared so the
     * crate pointer lands first and the index line that advertises it only after. A crash between them leaves a
     * downloadable crate no index lists, never an indexed crate with no bytes.
     */
    private void publish(String repo, FormatExchange exchange, ArtifactStore store) throws IOException {
        InputStream in = exchange.requestStream();
        JsonNode metadata;
        try {
            long jsonLength = readLength(in);
            if (jsonLength <= 0 || jsonLength > MAX_METADATA) {
                exchange.respond(400);
                return;
            }
            byte[] json = in.readNBytes((int) jsonLength);
            if (json.length != jsonLength) {
                exchange.respond(400);
                return;
            }
            metadata = MAPPER.readTree(json);
        } catch (IOException e) {
            exchange.respond(400);
            return;
        }
        String name = text(metadata, "name");
        String version = text(metadata, "vers");
        if (name == null || version == null || Keys.unsafe(name) || Keys.unsafe(version)) {
            exchange.respond(400);
            return;
        }
        long crateLength;
        try {
            crateLength = readLength(in);
        } catch (IOException e) {
            exchange.respond(400);
            return;
        }
        String canonical = canonical(name);
        Blobs blobs = new Blobs(store);
        Bounded crate = new Bounded(in, crateLength);
        Publication.Commit commit;
        try (crate) {
            commit = new Publication(store).commit(
                    new ArtifactDescriptor(ECOSYSTEM, canonical, version, downloadPath(repo, canonical, version),
                            "application/gzip", version.contains("-"), null, -1L),
                    crate, REPUBLISH,
                    accepted -> {
                        if (crate.remaining() > 0) {
                            // The body ended before the declared crate-length bytes arrived (a short or chunked frame):
                            // the store hashed only the truncated bytes, which is self-consistent but not the crate the
                            // client meant to publish. Declare nothing rather than serve a silently truncated crate
                            // under a 200 (the orphan blob is unreferenced and GC'd), the way RpmHeader.readInto throws
                            // on a short read. This is checked inside the layout, which is the first moment the body has
                            // been read to its end AND nothing servable has been written yet.
                            return Publication.Visibility.declined();
                        }
                        // The precomputed sparse-index line, content-addressed before any pointer exists. Its cksum is
                        // the accepted hash, so the index vouches for exactly the bytes the chain assessed and the
                        // download serves. Written through Blobs rather than the operation's sidecar seam because a
                        // blobs-namespace format stores its derived documents in the same pointer -> blob representation
                        // as its artifacts (the index read resolves them with blobs.read); the ordering guarantee is the
                        // same, since this runs inside the layout, strictly before any declared visibility step.
                        String line = blobs.store(new ByteArrayInputStream(
                                indexLine(metadata, name, version, accepted.hash())
                                        .getBytes(StandardCharsets.UTF_8)));
                        return Publication.Visibility
                                // The crate pointer, in this format's own namespace rather than publish/ - so it is
                                // declared through a Serving step, not named with at(). Routed through Blobs.link (not a
                                // bare writeVersioned): besides the compare-and-set retry, link clears any
                                // gc/condemned/<hash> marker a collector set, so republishing a crate byte-identical to
                                // a condemned one un-condemns it before the sweep deletes it.
                                .through((hash, _, _) -> blobs.link(crateKey(repo, canonical, version), hash))
                                // The index line that advertises it, after it - never before, so a crash never leaves a
                                // sparse index naming a crate no download can serve.
                                .andThrough((_, _, _) -> blobs.link(indexKey(repo, canonical, version), line))
                                // The served sparse-index file is written here, on the publish: the version's line
                                // joins the crate's stored index rather than being concatenated on every read.
                                .andThrough((_, _, _) -> new CargoListings(blobs).refresh(repo, canonical, version));
                    });
        }
        switch (commit.disposition()) {
            case ACCEPT -> {
                if (commit.visible()) {
                    exchange.setResponseHeader("Content-Type", "application/json");
                    exchange.respond(200, WARNINGS);
                } else {
                    exchange.respond(400);   // a truncated frame: nothing was declared and nothing serves
                }
            }
            // The chain HELD the crate. Its layout is written all the same, behind the withhold marker
            // (see {@link #held}), so a review release is the marker clear rather than a replay of a publish whose
            // envelope no longer exists.
            case QUARANTINE -> {
                held(repo, canonical, version, name, metadata, crate, blobs, store, commit.hash());
                exchange.respond(202);
            }
            // Refused outright: nothing is linked and no marker is set, so the sparse index never names it and the
            // stored blob is the usual unreferenced content-addressed object a collection reclaims. A refusal is never
            // released, so it is never laid out.
            case REJECT -> exchange.respond(422);
        }
    }

    /**
     * Lay a <em>held</em> crate out behind its withhold marker, so the review release that follows is the same
     * marker clear a retroactive KEV/licence hold's release is - one hold-release mechanism for this format, not two.
     * The shared commit operation runs its accepted layout only on {@code ACCEPT}, so a screen-time {@code QUARANTINE}
     * would otherwise store the crate, link nothing and index nothing: {@code HoldLifecycle.release} would then resolve
     * the hold and materialise no version at all, which is the regression this closes.
     *
     * <p>The order is the load-bearing part. The derived sparse-index line is content-addressed first (a blob, not a
     * pointer - nothing serves it), then {@link Withheld#mark} retracts the crate's own hash, and only then are the two
     * pointers linked - so at no instant is the held crate downloadable or its version listed. Cargo's sparse-index read
     * screens every version on that same marker, exactly as the download does, so the held version is stored, reviewable
     * and invisible until the release lifts it. A truncated frame lays out nothing, for the reason the accepted layout
     * declines it: the store holds self-consistent but wrong bytes, and a release must never materialise those.
     */
    private static void held(String repo, String canonical, String version, String name, JsonNode metadata,
                             Bounded crate, Blobs blobs, ArtifactStore store, String hash) throws IOException {
        if (crate.remaining() > 0) {
            return;
        }
        String line = blobs.store(new ByteArrayInputStream(
                indexLine(metadata, name, version, hash).getBytes(StandardCharsets.UTF_8)));
        Withheld.mark(store, hash, new ArtifactDescriptor(ECOSYSTEM, canonical, version,
                downloadPath(repo, canonical, version), null, false, null, -1L));
        blobs.link(crateKey(repo, canonical, version), hash);
        blobs.link(indexKey(repo, canonical, version), line);
        new CargoListings(blobs).refresh(repo, canonical, version);   // held: the stored index keeps it out
    }

    /** The request path a published crate downloads from - the artifact's own served path, which is the path the
     *  screen assesses it under, the path {@link #describe} parses back, and the path a {@code /quarantine} review
     *  handle is linked at. */
    private static String downloadPath(String repo, String canonical, String version) {
        return PREFIX + repo + "/" + API_CRATES + canonical + "/" + version + DOWNLOAD;
    }

    /**
     * Import one migrated crate: stream its {@code .crate} archive into the content-addressed store and record the
     * same crate pointer and sparse-index line a {@link #publish} performs, so the migrated registry serves and
     * indexes the crate as its own rather than copying the source's index. The archive streams straight through
     * {@link ArtifactStore#writeBlob} (never buffered, like the RPM importer and unlike the buffered {@code .gem} /
     * {@code .nupkg} language importers), and the SHA-256 the store returns is the {@code cksum} the index line
     * records. Called by {@link CargoImporter}; the caller owns and closes the stream.
     *
     * <p>The crate's dependency edges are not reconstructed here - that would mean parsing the crate's embedded
     * {@code Cargo.toml}, and the pull-through proxy is the dependency-faithful route, mirroring the upstream index.
     * The line is otherwise complete (name, version, checksum, empty deps and features), so a migrated crate
     * resolves and downloads.
     */
    void importCrate(String repo, String name, String version, InputStream crate, ArtifactStore store)
            throws IOException {
        if (Keys.unsafe(name) || Keys.unsafe(version)) {
            // A crafted <name>-<version>.crate filename (e.g. `..-1.0.0.crate`) cannot key the crate outside its
            // namespace: skip it rather than mis-file it, the way CargoImporter skips an unparseable filename.
            return;
        }
        String hash = store.writeBlob(crate);
        String canonical = canonical(name);
        new Blobs(store).link(crateKey(repo, canonical, version), hash);
        new Blobs(store).write(indexKey(repo, canonical, version),
                indexLine(MAPPER.createObjectNode(), name, version, hash).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The sparse-index {@code config.json}: where a client downloads crates ({@code dl}) and reaches the API
     * ({@code api}), and whether the client must present its token on reads ({@code auth-required}). Cargo sends a
     * registry token on index and download requests only when the registry says so; an enforcing deployment that
     * grants keyless callers nothing therefore has to say so, or a read-only token is never presented and every
     * read answers 401.
     */
    private void config(String repo, FormatExchange exchange) throws IOException {
        String base = repoBase(repo, exchange);
        ObjectNode config = MAPPER.createObjectNode();
        config.put("dl", base + "/api/v1/crates");
        config.put("api", base);
        if (authRequired(exchange)) {
            config.put("auth-required", true);
        }
        String json = MAPPER.writeValueAsString(config);
        exchange.setResponseHeader("Content-Type", "application/json");
        respondBody(exchange, json.getBytes(StandardCharsets.UTF_8));
    }

    /** Whether a read needs a credential: authorization is enforced (the default) and no anonymous rights are granted. */
    private static boolean authRequired(FormatExchange exchange) {
        String auth = exchange.setting("auth");
        String anonymous = exchange.setting("anonymous-rights");
        return (auth == null || !auth.equalsIgnoreCase("false")) && (anonymous == null || anonymous.isBlank());
    }

    /** Serve a crate archive from the CAS. The path is {@code api/v1/crates/<name>/<version>/download}. */
    private void download(String repo, String sub, Blobs blobs, FormatExchange exchange) throws IOException {
        String middle = sub.substring(API_CRATES.length(), sub.length() - DOWNLOAD.length());
        int last = middle.lastIndexOf('/');
        if (last < 0) {
            exchange.respond(404);
            return;
        }
        String crate = canonical(middle.substring(0, last));
        String version = middle.substring(last + 1);
        String key = crateKey(repo, crate, version);
        Optional<Blobs.Located> located = blobs.locate(key);
        if (located.isEmpty()) {
            exchange.respond(404);
            return;
        }
        long size = located.get().size();
        exchange.setResponseHeader("Content-Type", "application/gzip");
        if (exchange.method().equals("HEAD")) {
            if (size >= 0) {
                exchange.setResponseHeader("Content-Length", Long.toString(size));
            }
            exchange.respond(200, -1L).close();
            return;
        }
        blobs.serve(located.get(), exchange);
    }

    /** The canonical public sparse index this format mirrors when a deployment enables proxying without naming one. */
    @Override
    public Optional<URI> defaultUpstream() {
        return Optional.of(URI.create("https://index.crates.io/"));
    }

    /**
     * Proxy a Cargo miss to an upstream sparse-index registry. A per-crate index file is mutable, so it streams
     * through fresh on every read (never cached), and needs no rewrite - the sparse index carries no download URLs,
     * so a client builds them from this registry's own {@code config.json} (served locally, pointing downloads back
     * through here). A {@code .crate} archive is immutable, so it streams from upstream straight into the CAS
     * ({@link ProxyRelay#fill}, never buffered) and is served, so a later read is a local hit. The
     * upstream download URL is resolved from the upstream's {@code config.json} {@code dl} template, so the proxy
     * honours whatever download layout the upstream declares rather than assuming crates.io's.
     */
    @Override
    public boolean pullThrough(FormatExchange exchange, ArtifactStore store, URI upstream,
                               ProxyFormat.Fetcher fetcher) throws IOException {
        String path = exchange.path();
        String rest = path.substring(PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return false;
        }
        String repo = rest.substring(0, slash);
        String sub = rest.substring(slash + 1);
        String root = upstream.toString();
        if (!root.endsWith("/")) {
            root += "/";
        }
        if (sub.equals(CONFIG)) {
            // The local config.json is always generated (it points downloads back through here), so it never misses
            // and is never proxied; declining lets the local response stand.
            return false;
        }
        if (sub.startsWith(API_CRATES) && sub.endsWith(DOWNLOAD)) {
            return proxyCrate(repo, sub, exchange, store, root, fetcher);
        }
        return proxyIndex(root + sub, exchange, fetcher);
    }

    /** Fetch, cache and serve an immutable {@code .crate}: resolve the upstream download URL from its {@code dl}
     *  template, stream the archive into the CAS, then serve it from the local hit. */
    private boolean proxyCrate(String repo, String sub, FormatExchange exchange, ArtifactStore store, String root,
                               ProxyFormat.Fetcher fetcher) throws IOException {
        String middle = sub.substring(API_CRATES.length(), sub.length() - DOWNLOAD.length());
        int last = middle.lastIndexOf('/');
        if (last < 0) {
            return false;
        }
        String crate = canonical(middle.substring(0, last));
        String version = middle.substring(last + 1);
        URI target = downloadUrl(root, crate, version, fetcher);
        if (target == null) {
            return false;
        }
        if (!OutboundTargets.mayFollow(target, URI.create(root), ProxyLeg.allowInternalTargets(exchange))) {
            // SSRF guard: the download URL is built from the upstream index's config.json `dl` template, which a
            // compromised or third-party sparse index the operator added controls - and it is fetched as an INITIAL
            // request the fetcher's redirect-only screen never inspects. A CROSS-ORIGIN target at a private/loopback/
            // metadata host (169.254.169.254, an internal control plane) must not be reached server-side, and neither
            // may a plaintext one: decline so the local 404 stands. A target on the operator's own upstream ORIGIN
            // (where the index itself lives) is admitted. The old comment here claimed the same-origin shape was what
            // "the NuGet/PyPI/Composer/CocoaPods proxy legs" do while three of those four refused it outright; 
            // settled it, and the claim is now true by construction - every one of them makes THIS call.
            return false;
        }
        // Point-integrity: the sparse index publishes the .crate's SHA-256 as its `cksum`, so read that sibling line
        // and verify the streamed archive against it, refusing a mismatch (the Maven proxy leg's checksum parity). The
        // index is a SEPARATE document from the download - the target above comes from config.json's `dl` template -
        // so an index this repository could not read is not "this registry publishes no cksum".
        ProxyRelay.Declared expected = crateChecksum(root, crate, version, fetcher);
        if (!expected.readable()) {
            return ProxyRelay.unverifiable(target, expected);
        }
        try (ProxyFormat.Download download = fetcher.download(target, Map.of()).orElse(null)) {
            if (download == null || download.status() != 200) {
                return false;
            }
            if (!ProxyRelay.fill(new Blobs(store), crateKey(repo, crate, version), target, download.body(), expected)) {
                return false;
            }
        }
        download(repo, sub, new Blobs(store), exchange);
        return true;
    }

    /** The SHA-256 the upstream sparse index records as a version's {@code cksum} (Cargo's own {@code .crate} checksum),
     *  read from the crate's name-sharded index file so a proxied archive can be verified against it. The index is a
     *  small bounded metadata document (one line per version), fetched buffered and only on a crate miss.
     *
     *  <p>{@link ProxyRelay.Declared#NONE} - cache without a point check, exactly as Maven serves a proxied jar whose
     *  {@code .sha1} sibling is missing - when the index <em>answered</em> and declares nothing: a {@code 404}/
     *  {@code 410} (no such crate here), no line for this version, or a line with no 64-hex {@code cksum}.
     *  {@linkplain ProxyRelay.Declared#unreadable Unreadable} when the index could not be read at all, which is not
     *  the same fact and must not downgrade the fill. */
    private static ProxyRelay.Declared crateChecksum(String root, String crate, String version,
            ProxyFormat.Fetcher fetcher) throws IOException {
        ProxyRelay.Sidecar sidecar =
                ProxyRelay.declaring(fetcher, URI.create(root + prefix(crate) + "/" + crate), Map.of());
        if (!sidecar.answered()) {
            return sidecar.verdict();
        }
        for (String line : new String(sidecar.document().body(), StandardCharsets.UTF_8).split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (MAPPER.readTree(trimmed.getBytes(StandardCharsets.UTF_8)) instanceof ObjectNode entry
                    && version.equals(text(entry, "vers"))) {
                byte[] cksum = hex(text(entry, "cksum"), 32);
                return cksum == null ? ProxyRelay.Declared.NONE : ProxyRelay.Declared.of("SHA-256", cksum);
            }
        }
        return ProxyRelay.Declared.NONE;
    }

    /** Decode a lower/upper-case hex digest of exactly {@code bytes} bytes to its raw bytes, or {@code null} when it is
     *  absent or not a well-formed digest of that length - so a malformed checksum falls back to plain caching rather
     *  than refusing every fetch. */
    static byte[] hex(String value, int bytes) {
        if (value == null || value.length() != bytes * 2) {
            return null;
        }
        try {
            return HexFormat.of().parseHex(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Stream a mutable upstream index file (or any other GET) through fresh, never cached - no rewrite is needed. The
     *  client's conditional-request validators are forwarded and an upstream {@code 304}/validators relayed, so a
     *  304-capable cargo is not forced to re-download an unchanged sparse index on every read.
     *
     *  <p>ENUMERATION: a sparse-index file is the crate's version list, one JSON line per version, and it is exactly
     *  what {@code cargo update} resolves a dependency against - so its absence is an answer ("no such crate") and a
     *  transport failure rendered as one would silently change a resolution. Only an upstream that ANSWERED
     *  {@code 404}/{@code 410} reaches the client as one. This leg's other two shapes are classified where they
     *  are routed: the generated {@code config.json} is never proxied, and a {@code .crate} download is PINNED. */
    private boolean proxyIndex(String target, FormatExchange exchange, ProxyFormat.Fetcher fetcher) throws IOException {
        return ProxyRelay.streamFresh(fetcher, URI.create(target), "text/plain; charset=utf-8", exchange,
                ProxyRelay.Document.ENUMERATION);
    }

    /** Resolve the upstream {@code .crate} URL by reading the upstream {@code config.json} {@code dl} template. Cargo's
     *  markers ({@code {crate}}, {@code {version}}, {@code {prefix}}, {@code {lowerprefix}}) are substituted; a template
     *  with none of them takes the {@code /{crate}/{version}/download} default. ({@code {sha256-checksum}} depends on the
     *  index checksum this proxy does not retain and is unused by crates.io, so a template using it is left unresolved.)
     *  The config.json is a small, bounded metadata read, so it is fetched buffered; it is only read on a crate miss,
     *  which happens once per crate since the archive is then cached. */
    private static URI downloadUrl(String root, String crate, String version, ProxyFormat.Fetcher fetcher)
            throws IOException {
        Optional<ProxyFormat.Fetched> config = fetcher.fetch(URI.create(root + CONFIG), Map.of());
        if (config.isEmpty() || config.get().status() != 200) {
            return null;
        }
        String dl = text(MAPPER.readTree(config.get().body()), "dl");
        if (dl == null || dl.isEmpty()) {
            return null;
        }
        String url;
        if (dl.contains("{sha256-checksum}")) {
            // This proxy does not retain the index checksum, so it cannot resolve Cargo's {sha256-checksum} download
            // template. Decline (the local 404 then stands) rather than emit a URL with the literal marker left in -
            // the substitution chain below never replaces it, so URI.create would throw an uncaught
            // IllegalArgumentException on the leftover '{'.
            return null;
        }
        if (dl.contains("{crate}") || dl.contains("{version}") || dl.contains("{prefix}")
                || dl.contains("{lowerprefix}")) {
            url = dl.replace("{crate}", crate)
                    .replace("{version}", version)
                    .replace("{prefix}", prefix(crate))
                    .replace("{lowerprefix}", prefix(crate));
        } else {
            url = dl + "/" + crate + "/" + version + "/download";
        }
        return URI.create(url);
    }

    /** Cargo's index-path shard for a (lower-cased) crate name: {@code 1}/{@code 2}/{@code 3/x} for 1-3 chars, else
     *  {@code xx/yy} from the first four characters - the {@code {prefix}} / {@code {lowerprefix}} download markers. */
    private static String prefix(String crate) {
        return switch (crate.length()) {
            case 0 -> "";
            case 1 -> "1";
            case 2 -> "2";
            case 3 -> "3/" + crate.substring(0, 1);
            default -> crate.substring(0, 2) + "/" + crate.substring(2, 4);
        };
    }

    /** The per-crate index file: the stored index lines for every published version, one JSON object per line. The
     *  request path is Cargo's name-sharded index path, whose last segment is the (lower-cased) crate name. */
    private void index(String repo, String sub, ArtifactStore store, FormatExchange exchange) throws IOException {
        Blobs blobs = new Blobs(store);
        String crate = canonical(sub.substring(sub.lastIndexOf('/') + 1));
        if (Keys.unsafe(repo) || Keys.unsafe(crate) || (!StoredListing.present(store, CargoListings.index(repo, crate))
                && blobs.list(indexPrefix(repo, crate)).isEmpty())) {
            exchange.respond(404);
            return;
        }
        // The sparse-index file is a stored listing the publish maintains, streamed as is.
        Optional<StoredListing.Served> served = StoredListing.open(store, new CargoListings(blobs).spec(repo, crate));
        if (served.isEmpty()) {
            exchange.respond(404);
            return;
        }
        try (StoredListing.Served document = served.get()) {
            Listings.serve(exchange, document, "text/plain; charset=utf-8");
        }
    }

    @Override
    public Optional<ArtifactDescriptor> describe(String path) {
        if (!path.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String rest = path.substring(PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return Optional.empty();
        }
        String sub = rest.substring(slash + 1);
        if (!sub.startsWith(API_CRATES) || !sub.endsWith(DOWNLOAD)) {
            return Optional.empty();
        }
        String middle = sub.substring(API_CRATES.length(), sub.length() - DOWNLOAD.length());
        int last = middle.lastIndexOf('/');
        if (last < 0) {
            return Optional.of(ArtifactDescriptor.at(ECOSYSTEM, path));
        }
        String crate = middle.substring(0, last);
        String version = middle.substring(last + 1);
        return Optional.of(new ArtifactDescriptor(ECOSYSTEM, crate, version, path,
                "application/gzip", version.contains("-"), null, -1L));
    }

    @Override
    public List<String> paths(String coordinate, String version, ArtifactStore store) {
        // Cargo crate pointers live in the shared Blobs namespace (like npm/pypi/go/rpm), not the Publication
        // namespace coordinate-based eviction walks - they are enumerated through BlobLayout below instead.
        return List.of();
    }

    @Override
    public List<String> blobRoots() {
        return List.of("cargo");
    }

    @Override
    public List<String> blobKeys(String coordinate, String version, ArtifactStore store) throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            // A traversal-shaped coordinate or version maps nowhere: these keys are what an eviction DELETES, and
            // ArtifactStore.delete is not screened. The shared per-part screen, so a legitimately
            // multi-segment coordinate still resolves.
            return List.of();
        }
        // The .crate pointer and its precomputed sparse-index line, both keyed by the canonical (lower-cased) crate
        // name the store uses; the <repo> registry segment is not derivable from the coordinate, so it is discovered
        // by listing. The describe() coordinate is not canonicalised, so canonicalise here to match the stored keys.
        String crate = canonical(coordinate);
        List<String> keys = new ArrayList<>();
        for (String repo : store.list("cargo")) {
            String crateKey = crateKey(repo, crate, version);
            if (store.readVersioned(crateKey).isPresent()) {
                keys.add(crateKey);
            }
            String indexKey = indexKey(repo, crate, version);
            if (store.readVersioned(indexKey).isPresent()) {
                keys.add(indexKey);
            }
        }
        return keys;
    }

    /** The served download path the crate archive occupies for one coordinate version - the request that streams the
     *  {@code .crate} blob {@link #blobKeys} resolves, so a retroactive hold retracts it (a {@code /quarantine} review
     *  handle per path) exactly as {@code ArtifactLayout.paths} does for a {@code publish/}-namespace layout. The
     *  {@code <repo>} registry segment is not derivable from the coordinate, so it is discovered by listing, the same
     *  way {@link #blobKeys} finds the crate pointer; the download route canonicalises the crate name, so the served
     *  path carries the coordinate as {@link #describe} reports it (the reverse of the download handler). Only the crate
     *  archive is a served artifact - the sparse-index line is metadata and carries no {@code /quarantine} handle. */
    @Override
    public List<String> servedPaths(String coordinate, String version, ArtifactStore store) throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            return List.of();
        }
        String crate = canonical(coordinate);
        List<String> paths = new ArrayList<>();
        for (String repo : store.list("cargo")) {
            if (store.readVersioned(crateKey(repo, crate, version)).isPresent()) {
                paths.add(PREFIX + repo + "/" + API_CRATES + coordinate + "/" + version + DOWNLOAD);
            }
        }
        return paths;
    }

    /** One sparse-index line for a version, precomputed at publish from the publish metadata and the stored crate's
     *  checksum. The publish {@code deps} shape ({@code version_req}, {@code explicit_name_in_toml}) is rewritten to the
     *  index {@code deps} shape ({@code req}, {@code name}/{@code package}). */
    private static String indexLine(JsonNode metadata, String name, String version, String cksum) {
        ObjectNode entry = MAPPER.createObjectNode();
        entry.put("name", name);
        entry.put("vers", version);
        entry.set("deps", indexDeps(metadata.get("deps")));
        entry.put("cksum", cksum);
        entry.set("features", metadata.get("features") instanceof ObjectNode features
                ? features : MAPPER.createObjectNode());
        entry.put("yanked", false);
        JsonNode links = metadata.get("links");
        if (links != null && !links.isNull()) {
            entry.put("links", links.asString());
        } else {
            entry.putNull("links");
        }
        return MAPPER.writeValueAsString(entry);
    }

    private static ArrayNode indexDeps(JsonNode deps) {
        ArrayNode out = MAPPER.createArrayNode();
        if (deps instanceof ArrayNode array) {
            for (JsonNode dep : array) {
                ObjectNode entry = MAPPER.createObjectNode();
                String explicit = dep.path("explicit_name_in_toml").asString(null);
                if (explicit != null && !explicit.isEmpty()) {
                    entry.put("name", explicit);
                    entry.put("package", text(dep, "name"));
                } else {
                    entry.put("name", text(dep, "name"));
                    entry.putNull("package");
                }
                entry.put("req", text(dep, "version_req"));
                entry.set("features", dep.get("features") instanceof ArrayNode features
                        ? features : MAPPER.createArrayNode());
                entry.put("optional", dep.path("optional").asBoolean(false));
                entry.put("default_features", dep.path("default_features").asBoolean(true));
                put(entry, "target", dep.path("target").asString(null));
                entry.put("kind", dep.path("kind").asString("normal"));
                put(entry, "registry", dep.path("registry").asString(null));
                out.add(entry);
            }
        }
        return out;
    }

    private static void put(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static String text(JsonNode node, String field) {
        return node == null ? null : node.path(field).asString(null);
    }

    /** The external base URL of this registry ({@code <scheme>://<host><prefix>/cargo/<repo>}), for the config URLs. */
    private static String repoBase(String repo, FormatExchange exchange) {
        String uri = exchange.requestUri();
        String path = exchange.path();
        String external = uri.length() >= path.length() && uri.endsWith(path)
                ? uri.substring(0, uri.length() - path.length()) : "";
        return RequestBase.of(exchange) + external + PREFIX + repo;
    }

    /** Read Cargo's little-endian {@code u32} length prefix as an unsigned value. */
    private static long readLength(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(4);
        if (bytes.length != 4) {
            throw new EOFException("truncated length prefix");
        }
        return (bytes[0] & 0xFFL)
                | (bytes[1] & 0xFFL) << 8
                | (bytes[2] & 0xFFL) << 16
                | (bytes[3] & 0xFFL) << 24;
    }

    /** A view of {@code in} that ends after {@code limit} bytes, so the crate streams into the CAS without buffering
     *  and without reading past its frame (java.base has no public bounded stream, and this format is otherwise
     *  library-backed - Jackson for the metadata). After the stream is drained, {@link #remaining()} reports how many of
     *  the declared bytes never arrived, so a short/chunked frame that ended early is caught rather than stored as a
     *  self-consistent truncated crate. */
    private static final class Bounded extends InputStream {

        private final InputStream in;
        private long remaining;

        private Bounded(InputStream in, long limit) {
            this.in = in;
            this.remaining = limit;
        }

        /** The declared bytes not yet consumed: {@code 0} once exactly the frame was read, positive when the body ended
         *  short of the declared length. */
        long remaining() {
            return remaining;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int read = in.read();
            if (read >= 0) {
                remaining--;
            }
            return read;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int read = in.read(b, off, (int) Math.min(len, remaining));
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }

        // The underlying request stream is owned by the exchange, not this view (as the previous anonymous bounded
        // stream left it), so closing the view does not close the request body.
        @Override
        public void close() {
        }
    }

    private static String canonical(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * A crate {@code name} and {@code vers} become store-key path segments (the crate pointer {@link #crateKey} and
     * its sparse-index file {@link #indexKey}), and both come from the body-supplied publish frame (or, on import,
     * from a source filename), so a value that is empty, carries a path separator or control character, or is a
     * {@code .}/{@code ..} traversal segment could steer a write outside the crate's key space and is refused. Real
     * Cargo crate names are {@code [A-Za-z0-9_-]} and versions are semver, so no legitimate value is rejected. The
     * store's own root check confines a write to the tenant, but not to this format's {@code cargo/<repo>/} namespace
     * within it, so this guard is what keeps a crafted name from poisoning a sibling registry or format.
     */

    /**
     * The crate version a stored Cargo pointer serves - the backwards direction the inventory back-fill rebuilds a
     * lost {@code published/} row from.
     *
     * <p>Only the index key is decoded, {@code cargo/<repo>/index.d/<crate>/<version>}: every segment is in a fixed
     * position and {@code index.d} is a literal this format writes, so the pair is read off the key rather than out
     * of a filename. The {@code .crate} archive beside it spells the pair as {@code <crate>-<version>}, and a crate
     * name may itself contain a hyphen, so that split is ambiguous and is not attempted. Every published version
     * has an index entry, so nothing is lost by reading only the shape that cannot be misread.
     */
    @Override
    public Optional<ArtifactDescriptor> describePointer(String key) {
        String marker = "/index.d/";
        int at = key.indexOf(marker);
        if (!key.startsWith("cargo/") || at < 0) {
            return Optional.empty();
        }
        String[] parts = key.substring(at + marker.length()).split("/");
        if (parts.length != 2) {
            return Optional.empty();   // the crate's index folder itself, or something deeper - not a version
        }
        String crate = parts[0], version = parts[1];
        if (!BlobLayout.addressable(crate, version)) {
            return Optional.empty();
        }
        return Optional.of(new ArtifactDescriptor(ECOSYSTEM, crate, version, key,
                "application/octet-stream", version.contains("-"), null, 0L));
    }

    static String crateKey(String repo, String crate, String version) {
        return "cargo/" + repo + "/crates/" + crate + "/" + crate + "-" + version + ".crate";
    }

    static String indexPrefix(String repo, String crate) {
        return "cargo/" + repo + "/index.d/" + crate;
    }

    static String indexKey(String repo, String crate, String version) {
        return indexPrefix(repo, crate) + "/" + version;
    }

    private static void respondBody(FormatExchange exchange, byte[] body) throws IOException {
        if (exchange.method().equals("HEAD")) {
            exchange.setResponseHeader("Content-Length", Integer.toString(body.length));
            exchange.respond(200, -1L).close();
        } else {
            exchange.respond(200, body);
        }
    }

    /** The migration-import capability (WSPI.2 (c)), delegated to the layout-only {@link CargoImporter} - the format IS the
     *  discovered importer now (an {@code instanceof} capability), and the importer class stays as its delegate. */
    private final CargoImporter importer = new CargoImporter();

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
