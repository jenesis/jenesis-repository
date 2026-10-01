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
import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.format.PublishedExport;
import build.jenesis.repository.format.RepositoryExporter;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.store.Withheld;

/**
 * The Cargo registry format (the sparse-index protocol), so {@code cargo publish} and {@code cargo build} resolve Rust
 * crates over the shared store. It owns {@code /cargo/<repo>/...}: a crate is pushed with
 * {@code PUT /cargo/<repo>/api/v1/crates/new} (Cargo's frame - a little-endian {@code u32} metadata length, the JSON
 * metadata, a {@code u32} {@code .crate} length, the {@code .crate}) and downloaded from
 * {@code /cargo/<repo>/api/v1/crates/<name>/<version>/download}. The {@code config.json} is generated on read; the
 * per-crate index file at Cargo's name-sharded path ({@code /cargo/<repo>/se/rd/serde}) is a stored listing the publish
 * maintains.
 *
 * <p><b>Streaming publish.</b> Only the bounded JSON metadata is materialised; the {@code .crate} that follows streams
 * hash-on-write into the store as the accepted body, so the bytes the interceptor chain assesses are the crate's own,
 * not the frame's ({@link #screened()}). The returned SHA-256 is both the crate pointer's hash and the index's
 * {@code cksum}, and a precomputed index line is stored per version, so a read never reopens a {@code .crate}.
 *
 * <p><b>Pull-through proxy.</b> A miss on a proxy registry is served from an upstream sparse-index registry (crates.io
 * by default). A per-crate index file is mutable and streams through fresh; it carries no download URLs, since a client
 * builds them from the locally generated {@code config.json}, which points downloads back through here. A
 * {@code .crate} is immutable, so it is cached; its upstream URL comes from the upstream's {@code config.json}
 * {@code dl} template.
 *
 * <p>The ecosystem is {@code "crates.io"}, the OSV name. Crate pointers live in the shared {@code Blobs} namespace, so
 * {@link #paths} is empty and coordinate-scoped enforcement runs through {@link #blobKeys}/{@link #servedPaths}.
 */
public final class CargoFormat implements RepositoryFormat, ArtifactLayout, ProxyLeg, BlobLayout, RepositoryImporter,
        RepositoryExporter {

    /** The OSV ecosystem name this format's artifacts report (distinct from {@link #name()}, the routing id). */
    public static final String ECOSYSTEM = "crates.io";

    static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PREFIX = "/cargo/";
    private static final String NEW = "api/v1/crates/new";
    private static final String API_CRATES = "api/v1/crates/";
    private static final String DOWNLOAD = "/download";
    private static final String YANK = "/yank";
    private static final String UNYANK = "/unyank";
    private static final String CONFIG = "config.json";

    /** Cargo's publish response - the empty warning envelope a registry returns on a successful upload. */
    private static final byte[] WARNINGS =
            "{\"warnings\":{\"invalid_categories\":[],\"invalid_badges\":[],\"other\":[]}}"
                    .getBytes(StandardCharsets.UTF_8);

    /** The largest JSON metadata a publish frame may declare, so a hostile frame cannot force a large allocation. */
    private static final int MAX_METADATA = 32 * 1024 * 1024;

    @Override
    public String name() {
        return "cargo";
    }

    /** A lifecycle mark surfaces in the metadata this format's clients read, so marks are accepted here. */
    @Override
    public boolean surfacesLifecycleMarks() {
        return true;
    }

    /** A crate's marks are its registry's: {@code <registry>/<crate>}, the registry read off the path it serves at. */
    @Override
    public String lifecycleCoordinate(String coordinate, String path) {
        if (path == null || !path.startsWith(PREFIX)) {
            return coordinate;
        }
        int slash = path.indexOf('/', PREFIX.length());
        return slash < 0 ? coordinate : path.substring(PREFIX.length(), slash) + "/" + coordinate;
    }

    /** Cargo retries its sparse index with the token only when a {@code 401} names Cargo's own scheme. */
    @Override
    public List<String> challenges() {
        return List.of("Cargo");
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
        } else if (sub.startsWith(API_CRATES) && method.equals("DELETE") && sub.endsWith(YANK)) {
            yank(repo, sub.substring(API_CRATES.length(), sub.length() - YANK.length()), true, store, exchange);
        } else if (sub.startsWith(API_CRATES) && method.equals("PUT") && sub.endsWith(UNYANK)) {
            yank(repo, sub.substring(API_CRATES.length(), sub.length() - UNYANK.length()), false, store, exchange);
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

    /** A failure in Cargo's registry error document, {@code {"errors":[{"detail":...}]}}, which cargo prints. */
    @Override
    public void failed(FormatExchange exchange, String sentence) throws IOException {
        ObjectNode error = MAPPER.createObjectNode();
        error.putArray("errors").addObject().put("detail", sentence);
        exchange.setResponseHeader("Content-Type", "application/json");
        exchange.respond(500, MAPPER.writeValueAsBytes(error));
    }

    /**
     * Not edge-screened: a publish body is a frame ({@code [u32 json-len][json][u32 crate-len][.crate]}), and gating
     * it at the shared edge would assess the envelope while the {@code .crate} inside is what serves - a hash no
     * interceptor saw, {@code RepositoryFormat} clause 14's fail-open direction. {@link #publish} is the one choke
     * point instead: it unwraps the frame and drives {@code Publication.commit} with the discovered chain over the
     * crate's own bytes, under its own download path.
     */
    @Override
    public boolean screened() {
        return false;
    }

    /** The republish policy: {@code OVERWRITE} at the operation, the refusal being taken at the link. Like crates.io
     *  this registry refuses a version already uploaded - the crate pointer is linked through {@link Blobs#linkOnce},
     *  which decides inside its compare-and-set - and answers as crates.io does ({@link #alreadyUploaded}).
     *  Re-publishing the identical crate converges. */
    private static final Publication.Republish REPUBLISH = Publication.Republish.overwrite();

    /**
     * Unwrap the publish frame and run the {@code .crate} it carries through the shared {@code Publication.commit}.
     * Only the bounded JSON metadata is materialised; the {@code .crate} streams hash-on-write as the accepted body, so
     * <b>the hash the chain assesses is the hash the download serves</b>.
     *
     * <p>The metadata precedes the crate, so the coordinate is known up front: the descriptor carries the real
     * {@code name}/{@code vers} and the crate's download path, which a deny-list keys on, a {@code /quarantine} hold is
     * linked at and an inspector parses.
     *
     * <p>Pointer-last: the sparse-index line, which needs the accepted hash as its {@code cksum}, is content-addressed
     * inside the layout before anything serves, and the visibility steps link the crate pointer before the index line
     * that advertises it. A crash between them leaves a downloadable crate no index lists, never an indexed crate with
     * no bytes.
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
        // Name and version become store-key segments, so a traversal-shaped one could steer a write into a sibling
        // registry or format; real crate names and semver versions are never refused.
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
        Publication.Commit commit = null;
        try (crate) {
            commit = new Publication(store).commit(
                    new ArtifactDescriptor(ECOSYSTEM, canonical, version, downloadPath(repo, canonical, version),
                            "application/gzip", version.contains("-"), null, -1L),
                    crate, REPUBLISH,
                    accepted -> {
                        if (crate.remaining() > 0) {
                            // The body ended before the declared crate length arrived: the store hashed truncated
                            // bytes, which are not the crate the client meant. Declare nothing (the orphan blob is
                            // collected) rather than serve it under a 200. This is the first point where the body has
                            // been read to its end and nothing servable has been written.
                            return Publication.Visibility.declined();
                        }
                        // The sparse-index line, content-addressed before any pointer exists; its cksum is the accepted
                        // hash. Written through Blobs because a blobs-namespace format stores derived documents as
                        // pointer -> blob like its artifacts, and it still runs strictly before any declared visibility
                        // step.
                        String line = blobs.store(new ByteArrayInputStream(
                                indexLine(metadata, name, version, accepted.hash())
                                        .getBytes(StandardCharsets.UTF_8)));
                        return Publication.Visibility
                                // The crate pointer, in this format's namespace rather than publish/, so declared as a
                                // Serving step. Blobs.link retries its compare-and-set and clears a collector's
                                // gc/condemned/<hash> marker, so republishing bytes identical to a condemned crate
                                // un-condemns them.
                                .through((hash, size, _) -> blobs.linkRelease(crateKey(repo, canonical, version), hash,
                                        size))
                                // The index line after the crate, so a crash never indexes a crate nothing serves. It
                                // records the release's dependencies and features, so a re-publish with other metadata
                                // is refused.
                                .andThrough((_, _, _) -> blobs.linkRelease(indexKey(repo, canonical, version), line,
                                        -1L))
                                // The served index file joins the version's line here, on the publish, not on every
                                // read.
                                .andThrough((_, _, _) -> new CargoListings(blobs).refresh(repo, canonical, version));
                    });
            if (commit.disposition() == PublishInterceptor.Disposition.QUARANTINE) {
                held(repo, canonical, version, name, metadata, crate, blobs, store, commit.hash());
            }
        } catch (Publication.RepublishConflict taken) {
            if (commit != null) {
                // A held re-publish was refused before anything was marked: its review handle goes with it.
                new Publication(store, List.of(), List.of()).unpublish("/quarantine" + commit.artifact().path());
            }
            exchange.setResponseHeader("Content-Type", "application/json");
            exchange.respond(400, alreadyUploaded(canonical, version));
            return;
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
            // The chain held the crate: its layout is written behind the withhold marker (see held), so a review
            // release is the marker clear rather than a replay of a publish whose envelope is gone.
            case QUARANTINE -> exchange.respond(202);
            // Refused: nothing is linked and no marker set, so the index never names it and the blob is collected.
            case REJECT -> exchange.respond(422);
        }
    }

    /**
     * Lay a held crate out behind its withhold marker, so its review release is the same marker clear any other hold's
     * release is. The shared commit runs its accepted layout only on {@code ACCEPT}, so without this a screen-time
     * {@code QUARANTINE} would link and index nothing and {@code HoldLifecycle.release} would materialise no version.
     *
     * <p>The order matters: the index line is content-addressed first (a blob nothing serves), then
     * {@link Withheld#mark} retracts the crate's hash, and only then are the two pointers linked - so the held crate is
     * never downloadable or listed. The sparse-index read screens every version on the same marker the download does. A
     * truncated frame lays out nothing, as the accepted layout declines it.
     */
    private static void held(String repo, String canonical, String version, String name, JsonNode metadata,
                             Bounded crate, Blobs blobs, ArtifactStore store, String hash) throws IOException {
        if (crate.remaining() > 0) {
            return;
        }
        String line = blobs.store(new ByteArrayInputStream(
                indexLine(metadata, name, version, hash).getBytes(StandardCharsets.UTF_8)));
        // A hold never replaces a released crate, nor its index line: refused before the mark, so nothing is left held.
        blobs.refuseReplacement(crateKey(repo, canonical, version), hash);
        blobs.refuseReplacement(indexKey(repo, canonical, version), line);
        Withheld.mark(store, hash, new ArtifactDescriptor(ECOSYSTEM, canonical, version,
                downloadPath(repo, canonical, version), null, false, null, -1L));
        blobs.linkRelease(crateKey(repo, canonical, version), hash, -1L);
        blobs.linkRelease(indexKey(repo, canonical, version), line, -1L);
        new CargoListings(blobs).refresh(repo, canonical, version);   // held: the stored index keeps it out
    }

    /** crates.io's refusal of a version already uploaded, in the {@code errors} document cargo prints from. */
    private static byte[] alreadyUploaded(String canonical, String version) {
        ObjectNode detail = MAPPER.createObjectNode().put("detail",
                "crate version `" + version + "` is already uploaded: " + canonical + "@" + version
                        + " cannot be replaced; publish a new version");
        ObjectNode errors = MAPPER.createObjectNode();
        errors.putArray("errors").add(detail);
        return MAPPER.writeValueAsBytes(errors);
    }

    /** The path a published crate downloads from - the path the screen assesses it under, {@link #describe} parses back
     *  and a {@code /quarantine} review handle is linked at. */
    private static String downloadPath(String repo, String canonical, String version) {
        return PREFIX + repo + "/" + API_CRATES + canonical + "/" + version + DOWNLOAD;
    }

    /**
     * Import one migrated crate: stream its {@code .crate} into the store and record the same crate pointer and index
     * line a {@link #publish} writes, so the registry indexes it as its own. The store's SHA-256 is the line's
     * {@code cksum}. Called by {@link CargoImporter}, which owns the stream.
     *
     * <p>Dependency edges are not reconstructed - that would mean parsing the embedded {@code Cargo.toml}; the
     * pull-through proxy is the dependency-faithful route. The line is otherwise complete (name, version, checksum,
     * empty deps and features), so a migrated crate resolves and downloads.
     */
    void importCrate(String repo, String name, String version, InputStream crate, ArtifactStore store)
            throws IOException {
        if (Keys.unsafe(name) || Keys.unsafe(version)) {
            // A crafted filename (`..-1.0.0.crate`) cannot key the crate outside its namespace: skip it.
            return;
        }
        String hash = store.writeBlob(crate);
        String canonical = canonical(name);
        new Blobs(store).link(crateKey(repo, canonical, version), hash);
        new Blobs(store).write(indexKey(repo, canonical, version),
                indexLine(MAPPER.createObjectNode(), name, version, hash).getBytes(StandardCharsets.UTF_8));
    }

    /** The sparse-index {@code config.json}: where a client downloads crates ({@code dl}), reaches the API
     *  ({@code api}), and whether it must present its token on reads ({@code auth-required}). Cargo sends a token on
     *  index and download requests only when told to, so an enforcing deployment that grants keyless callers nothing
     *  has to say so, or every read answers 401. */
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
        exchange.answer(json.getBytes(StandardCharsets.UTF_8));
    }

    /** Whether a read needs a credential: authorization is enforced (the default) and no anonymous rights are granted. */
    private static boolean authRequired(FormatExchange exchange) {
        String auth = exchange.setting("auth");
        String anonymous = exchange.setting("anonymous-rights");
        return (auth == null || !auth.equalsIgnoreCase("false")) && (anonymous == null || anonymous.isBlank());
    }

    /** {@code cargo yank} ({@code DELETE api/v1/crates/<name>/<version>/yank}) and {@code cargo yank --undo}
     *  ({@code PUT .../unyank}): the product's lifecycle mark, set and cleared through the path the console and the API
     *  use ({@link Lifecycle#mark(FormatExchange, ArtifactStore, String, String, Lifecycle.Flag)}), so the index line
     *  carries {@code yanked} whichever surface set it. Idempotent like crates.io's: {@code {"ok":true}} for a version
     *  already in the asked state, Cargo's error document with a {@code 404} for a version this registry does not
     *  hold. */
    private static void yank(String repo, String middle, boolean yank, ArtifactStore store, FormatExchange exchange)
            throws IOException {
        int last = middle.lastIndexOf('/');
        String crate = last < 0 ? "" : canonical(middle.substring(0, last));
        String version = last < 0 ? "" : middle.substring(last + 1);
        if (crate.isEmpty() || version.isEmpty() || Keys.unsafe(crate) || Keys.unsafe(version)
                || new Blobs(store).hash(crateKey(repo, crate, version)).isEmpty()) {
            ObjectNode error = MAPPER.createObjectNode();
            error.putArray("errors").addObject().put("detail",
                    "crate `" + crate + "` does not have a version `" + version + "`");
            exchange.setResponseHeader("Content-Type", "application/json");
            exchange.respond(404, MAPPER.writeValueAsBytes(error));
            return;
        }
        String coordinate = repo + "/" + crate;
        boolean yanked = Lifecycle.read(store, coordinate, version).isPresent();
        if (yank && !yanked) {
            Lifecycle.mark(exchange, store, coordinate, version, new Lifecycle.Flag(Lifecycle.State.YANKED, ""));
        } else if (!yank && yanked) {
            Lifecycle.clear(exchange, store, coordinate, version);
        }
        exchange.setResponseHeader("Content-Type", "application/json");
        exchange.respond(200, MAPPER.writeValueAsBytes(Map.of("ok", true)));
    }

    /** Serve a crate archive. The path is {@code api/v1/crates/<name>/<version>/download}. */
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

    /** The public sparse index this format mirrors when proxying is enabled without naming one. */
    @Override
    public Optional<URI> defaultUpstream() {
        return Optional.of(URI.create("https://index.crates.io/"));
    }

    /** Proxy a Cargo miss to an upstream sparse-index registry: a per-crate index file streams through fresh and
     *  unrewritten; a {@code .crate} streams into the store ({@link ProxyRelay#fill}) and is served from there, its URL
     *  resolved from the upstream's {@code dl} template. */
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
            // The local config.json is always generated, so it never misses and is never proxied.
            return false;
        }
        if (sub.startsWith(API_CRATES) && sub.endsWith(DOWNLOAD)) {
            return proxyCrate(repo, sub, exchange, store, root, fetcher);
        }
        return proxyIndex(root + sub, exchange, fetcher);
    }

    /** Fetch, cache and serve an immutable {@code .crate} from the URL the upstream's {@code dl} template names. */
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
            // The download URL comes from the upstream index's dl template, which the index's owner controls, and is
            // fetched as an initial request the fetcher's redirect screen never inspects. A cross-origin private,
            // loopback or metadata host, or a plaintext target, is declined so the local 404 stands; the upstream's own
            // origin is admitted. Every proxy leg makes this call.
            return false;
        }
        // The sparse index publishes the .crate's SHA-256 as its cksum, so the streamed archive is verified against it.
        // The index is a separate document from the download, so an index that could not be read is not "no cksum
        // published".
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

    /**
     * The SHA-256 the upstream index records as a version's {@code cksum}, read from the crate's index file (a small
     * document, fetched buffered on a crate miss).
     *
     * <p>{@link ProxyRelay.Declared#NONE} - cache without a point check - when the index answered and declares nothing:
     * a {@code 404}/{@code 410}, no line for this version, or no 64-hex {@code cksum}.
     * {@linkplain ProxyRelay.Declared#unreadable Unreadable} when the index could not be read, which must not downgrade
     * the fill.
     */
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

    /** Decode a hex digest of exactly {@code bytes} bytes, or {@code null} when absent or malformed - so a malformed
     *  checksum falls back to plain caching. */
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

    /**
     * Stream a mutable upstream index file through fresh, never cached, forwarding the client's validators and relaying
     * a {@code 304}.
     *
     * <p>ENUMERATION: the index file is the crate's version list, which {@code cargo update} resolves against, so its
     * absence is an answer and a transport failure must not render as one. Only an upstream that answered
     * {@code 404}/{@code 410} reaches the client as one. The generated {@code config.json} is never proxied, and a
     * {@code .crate} download is PINNED.
     */
    private boolean proxyIndex(String target, FormatExchange exchange, ProxyFormat.Fetcher fetcher) throws IOException {
        return ProxyRelay.streamFresh(fetcher, URI.create(target), "text/plain; charset=utf-8", exchange,
                ProxyRelay.Document.ENUMERATION);
    }

    /** Resolve the upstream {@code .crate} URL from the upstream {@code config.json} {@code dl} template, substituting
     *  Cargo's markers ({@code {crate}}, {@code {version}}, {@code {prefix}}, {@code {lowerprefix}}); a template with
     *  none of them takes the {@code /{crate}/{version}/download} default. Read buffered, once per crate, since the
     *  archive is then cached. */
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
            // The proxy does not retain the index checksum, so a {sha256-checksum} template cannot be resolved:
            // decline, so the local 404 stands, rather than build a URI with the marker left in.
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

    /** Cargo's index-path shard for a lower-cased crate name: {@code 1}/{@code 2}/{@code 3/x} for one to three
     *  characters, else {@code xx/yy} - also the {@code {prefix}} / {@code {lowerprefix}} download markers. */
    private static String prefix(String crate) {
        return switch (crate.length()) {
            case 0 -> "";
            case 1 -> "1";
            case 2 -> "2";
            case 3 -> "3/" + crate.substring(0, 1);
            default -> crate.substring(0, 2) + "/" + crate.substring(2, 4);
        };
    }

    /** The per-crate index file: one stored JSON line per published version, at Cargo's name-sharded path whose last
     *  segment is the lower-cased crate name. */
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
        // Crate pointers live in the shared Blobs namespace, which BlobLayout below enumerates.
        return List.of();
    }

    @Override
    public List<String> blobRoots() {
        return List.of("cargo");
    }

    @Override
    public List<String> blobKeys(String coordinate, String version, ArtifactStore store) throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            // A traversal-shaped coordinate or version maps nowhere: these keys are what an eviction deletes, and
            // ArtifactStore.delete is not screened. The per-part screen still resolves a multi-segment coordinate.
            return List.of();
        }
        // The .crate pointer and its index line, keyed by the lower-cased crate name; the <repo> segment is not
        // derivable from the coordinate, so it is found by listing.
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

    /** The download path the crate archive occupies for one coordinate version, so a retroactive hold retracts it (a
     *  {@code /quarantine} handle per path). The {@code <repo>} segment is found by listing, as in {@link #blobKeys}.
     *  Only the archive is a served artifact; the index line is metadata and gets no handle. */
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

    /** One sparse-index line for a version, from the publish metadata and the crate's checksum. The publish
     *  {@code deps} shape ({@code version_req}, {@code explicit_name_in_toml}) becomes the index shape ({@code req},
     *  {@code name}/ {@code package}). */
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

    /** The external base URL of this registry, {@code <scheme>://<host><prefix>/cargo/<repo>}. */
    private static String repoBase(String repo, FormatExchange exchange) {
        return RequestBase.of(exchange) + exchange.external(PREFIX + repo);
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

    /** A view of {@code in} that ends after {@code limit} bytes, so the crate streams into the store without reading
     *  past its frame. After draining, {@link #remaining()} reports the declared bytes that never arrived, so a frame
     *  that ended early is caught rather than stored truncated. */
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

        // The request stream belongs to the exchange, so closing this view leaves it open.
        @Override
        public void close() {
        }
    }

    private static String canonical(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /** The crate version a stored Cargo pointer serves, for the inventory back-fill. Only the index key
     *  {@code cargo/<repo>/index.d/<crate>/<version>} is decoded: its segments are fixed, while the archive's
     *  {@code <crate>-<version>} is ambiguous because a crate name may contain a hyphen. Every version has an index
     *  entry. */
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

    /** The migration-import capability, delegated to {@link CargoImporter}. */
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

    /** Each registry's crate of the version is sent as {@code cargo publish} frames it to that registry's
     *  {@code api/v1/crates/new}, with the token as the raw {@code Authorization} value Cargo sends. The metadata is
     *  rebuilt from the stored index line (name, version, dependencies, features); what the line lacks, the licence
     *  among it, a registry reads from the {@code Cargo.toml} inside the crate. Asked for back at its download path
     *  first, so a crate already there is not sent. */
    @Override
    public Exported export(ArtifactStore repository, String coordinate, String version, ExportTarget target)
            throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            return Exported.WITHHELD;
        }
        String crate = canonical(coordinate);
        Blobs blobs = new Blobs(repository);
        List<PublishedExport.File> files = new ArrayList<>();
        for (String repo : repository.list("cargo")) {
            Optional<Blobs.Located> located = blobs.locate(crateKey(repo, crate, version));
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            if (located.isEmpty() || !blobs.read(indexKey(repo, crate, version), line)) {
                continue;
            }
            byte[] metadata = MAPPER.writeValueAsBytes(publishMetadata(MAPPER.readTree(line.toByteArray())));
            String hash = located.get().hash();
            // The frame states the crate's length first; a pointer that recorded none is sized from the blob.
            long size = located.get().size() >= 0 ? located.get().size() : blobs.size(crateKey(repo, crate, version));
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/octet-stream");
            target.credential().ifPresent(credential -> headers.put("Authorization", credential.secret()));
            ExportTarget.Body frame = ExportTarget.Body.of(8L + metadata.length + size,
                    () -> new SequenceInputStream(Collections.enumeration(List.of(
                            new ByteArrayInputStream(length(metadata.length)), new ByteArrayInputStream(metadata),
                            new ByteArrayInputStream(length(size)), blobs.open(hash)))));
            files.add(new PublishedExport.File(new ExportTarget.Request("PUT", repo + "/" + NEW, headers, frame),
                    Optional.of(repo + "/" + API_CRATES + coordinate + "/" + version + DOWNLOAD), hash));
        }
        return PublishedExport.send(files, target);
    }

    /** The publish metadata an index line was made from: {@link #indexLine}'s rewrite, run backwards. */
    private static ObjectNode publishMetadata(JsonNode line) {
        ObjectNode metadata = MAPPER.createObjectNode();
        metadata.put("name", text(line, "name"));
        metadata.put("vers", text(line, "vers"));
        ArrayNode deps = metadata.putArray("deps");
        for (JsonNode dep : line.path("deps")) {
            ObjectNode entry = deps.addObject();
            String renamed = dep.path("package").asString(null);
            entry.put("name", renamed != null ? renamed : text(dep, "name"));
            entry.put("version_req", text(dep, "req"));
            entry.set("features", dep.get("features") instanceof ArrayNode features
                    ? features : MAPPER.createArrayNode());
            entry.put("optional", dep.path("optional").asBoolean(false));
            entry.put("default_features", dep.path("default_features").asBoolean(true));
            put(entry, "target", dep.path("target").asString(null));
            entry.put("kind", dep.path("kind").asString("normal"));
            put(entry, "registry", dep.path("registry").asString(null));
            put(entry, "explicit_name_in_toml", renamed != null ? text(dep, "name") : null);
        }
        metadata.set("features", line.get("features") instanceof ObjectNode features
                ? features : MAPPER.createObjectNode());
        metadata.putArray("authors");
        metadata.putArray("keywords");
        metadata.putArray("categories");
        metadata.putObject("badges");
        put(metadata, "links", line.path("links").asString(null));
        return metadata;
    }

    /** Cargo's little-endian {@code u32} length prefix. */
    private static byte[] length(long value) {
        return new byte[] {(byte) value, (byte) (value >>> 8), (byte) (value >>> 16), (byte) (value >>> 24)};
    }
}
