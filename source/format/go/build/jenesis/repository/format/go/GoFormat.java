package build.jenesis.repository.format.go;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.format.Listings;
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
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.format.Semver;

/**
 * The Go module proxy format (the GOPROXY protocol), so {@code go mod download} and {@code go get} resolve modules
 * from this registry. It owns {@code /go/...}: a module version is the trio {@code <module>/@v/<version>.info},
 * {@code .mod} and {@code .zip}, stored under {@code go/<module>/@v/...}; {@code <module>/@v/list} enumerates the
 * stored versions and {@code <module>/@latest} answers with the newest, both {@code 404}ing when nothing is stored so
 * a proxy repository fills them from the upstream. The protocol is read-only for the
 * {@code go} client; a {@code PUT} to the same paths lets a build push a module into the registry, which is how
 * versions get in. The module path is used verbatim (the {@code !lower} upper-case escaping the client applies is
 * preserved through to the store key).
 *
 * <p>As a proxy it also relays the checksum database under {@code /go/sumdb/<name>/...}, so a client whose only egress
 * is this repository can still run the {@code GOSUMDB} verification the ecosystem defines - see the contract below.
 *
 * <h2>Contract</h2>
 * The clauses of {@code RepositoryFormat} and {@code ProxyFormat} bind unchanged; what follows is what
 * {@code ProxyFormat} clause 5 (upstream integrity) resolves to for the GOPROXY protocol, because the protocol itself
 * advertises no digest and the answer is therefore neither obvious nor uniform across request shapes.
 * <ol>
 * <li><b>Where the digest comes from.</b> The GOPROXY protocol publishes no checksum: a {@code .info}, {@code .mod} or
 *     {@code .zip} arrives with no checksum sibling, no digest header and no content-addressed reference. Go's digest
 *     lives in a <em>separate</em> service, the checksum database ({@code GOSUMDB}, {@code sum.golang.org} by default),
 *     which publishes an {@code h1:} dirhash per module version. This format consults it
 *     ({@link GoChecksumDatabase}), directly where it can and through the configured upstream's own
 *     {@code /sumdb/} mirror otherwise.</li>
 * <li><b>What is verified.</b> A proxied <b>{@code .zip}</b> and <b>{@code .mod}</b> are streamed into the
 *     content-addressed store and held to that dirhash ({@link GoDirhash}) <em>before</em> any pointer is linked. A
 *     mismatch is a refusal: the pointer is not written, nothing serves the body, the local {@code 404} stands so a
 *     later pull re-hits the upstream, and the refusal is logged with the module version and both digests. The
 *     unreferenced blob is left for the collector, exactly as a refused {@code Blobs.writeVerified} leaves one.</li>
 * <li><b>What is not, and why - by request shape, not "sometimes".</b> Four shapes cache unverified, and each says so
 *     in the log line it emits:
 *     <ul>
 *     <li>a <b>{@code .info}</b>: the checksum database publishes a dirhash for the module zip and for
 *         {@code go.mod}, and none for the version-timestamp document. There is nothing to check it against, and the
 *         document names no bytes a build compiles;</li>
 *     <li>a module version the <b>database does not carry</b> - a private or internal module, the {@code GOPRIVATE}
 *         territory a public database is not asked about. Cached unverified rather than refused, exactly as the Maven
 *         leg caches an artifact whose {@code .sha1} sibling the upstream does not publish;</li>
 *     <li>a deployment that set <b>{@code jenreg.go.sumdb=off}</b>, or one whose database is unreachable while the
 *         GOPROXY is not: no digest is advertised to this repository at all;</li>
 *     <li>an entry the dirhash cannot be <b>computed</b> for - a {@code .zip} whose entries exceed the shared
 *         archive-walk ceiling or its entry cap. This one is <em>not</em> cached: an uncomputable digest is "we
 *         stopped looking", never "this matches", so it refuses like a mismatch.</li>
 *     </ul>
 *     {@code @v/list} and {@code @latest} are mutable version queries, streamed through fresh and never cached, so no
 *     unverified body is retained for them.</li>
 * <li><b>The strength of the check, stated.</b> The record is read out of the lookup response and compared; the note's
 *     signature and the tile-based inclusion proof that would bind that record to the signed tree head are not
 *     verified. So this is a digest check against what the checksum database says - which already defeats a corrupted
 *     or hostile GOPROXY, since the database is a different origin - and not a transparency-log attestation. The
 *     end-to-end check remains the client's, which is why clause 5 below exists.</li>
 * <li><b>{@code /go/sumdb/<name>/...} is relayed, not declined.</b> A client pointed only at this repository gets the
 *     protocol's four checksum-database operations ({@code supported}, {@code latest}, {@code lookup/...},
 *     {@code tile/...}) relayed fresh and uncached - through the configured upstream's mirror where it has one, and
 *     otherwise to the configured database itself. Declining them (which this format did) left such a client with no
 *     route to the verification the ecosystem builds on, so a declared limitation had no client-side complement.
 *     Nothing else under {@code /go/sumdb/} is relayed, so the name in the request path can never become a request the
 *     protocol does not define.</li>
 * <li><b>A version query that could not be asked is not an empty version list.</b> {@code ProxyFormat} clause 2 makes
 *     {@code false} - "let the local {@code 404} stand" - the answer for an upstream miss <em>and</em> for a transport
 *     failure alike, and on the immutable {@code .info}/{@code .mod}/{@code .zip} shapes that is right: the {@code 404}
 *     says "not cached here", the client re-pulls, and nothing is decided by the absence. On {@code @v/list} and
 *     {@code @latest} it is not, because there the {@code 404} <em>is</em> the answer - an empty enumeration a build
 *     resolves against. So the two are split by who said what: an upstream {@code 404}/{@code 410} is a
 *     real miss and the local {@code 404} stands, while a transport failure or any other non-{@code 200}
 *     (a {@code 429} under a shared egress IP, a {@code 5xx}, an auth challenge) answers {@code 502} and is logged.
 *     The split was written here first and now lives in the shared {@code ProxyRelay} seam that all thirteen
 *     proxying formats relay through, so what this leg still owns is only the classification the GOPROXY
 *     protocol decides: {@code @v/list} and {@code @latest} are {@code ENUMERATION}, the trio is {@code PINNED}.
 *     This is the same argument the local version list already makes, served whole from its stored document and
 *     never as a prefix of the versions - "a plausible-but-incomplete answer, and {@code @v/list} is what a
 *     build resolves against". A version list that is incomplete because the store could not be walked and one that is
 *     empty because the upstream could not be reached are the same failure wearing different clothes; both now refuse
 *     instead of answering (&sect;5, &sect;9).</li>
 * </ol>
 */
public final class GoFormat implements RepositoryFormat, ProxyLeg, BlobLayout, RepositoryImporter, RepositoryExporter {

    @Override
    public String name() {
        return "go";
    }

    /** A lifecycle mark surfaces in the metadata this format's clients read, so marks are accepted here. */
    @Override
    public boolean surfacesLifecycleMarks() {
        return true;
    }

    @Override
    public String ecosystem() {
        return "Go";
    }

    @Override
    public List<String> blobRoots() {
        return List.of("go");
    }

    /**
     * The module version a stored Go pointer serves - the backwards direction the inventory back-fill rebuilds a
     * lost {@code published/} row from.
     *
     * <p>A Go module path is legitimately multi-segment, so the coordinate and the version cannot be separated by
     * counting segments the way NuGet's can. They are separated by {@code /@v/}, and that is safe rather than
     * merely convenient: {@code @v} is the module proxy protocol's own reserved separator, so a module path cannot
     * contain one. The three suffixes the trio is stored under are stripped from the end, which is deterministic -
     * a Go version may carry dots and a {@code +incompatible} build tag, but it does not end in {@code .info},
     * {@code .mod} or {@code .zip}.
     *
     * <p>The coordinate is the module path verbatim, including the client's {@code !upper} escaping, exactly as
     * {@link #blobKeys} composes it - so the row this rebuilds is keyed the way the accept path keyed it.
     */
    @Override
    public Optional<ArtifactDescriptor> describePointer(String key) {
        if (!key.startsWith("go/")) {
            return Optional.empty();
        }
        String rest = key.substring("go/".length());
        int marker = rest.lastIndexOf("/@v/");
        if (marker < 0) {
            return Optional.empty();
        }
        String coordinate = rest.substring(0, marker);
        String file = rest.substring(marker + "/@v/".length());
        String version = null;
        for (String suffix : List.of(".info", ".mod", ".zip")) {
            if (file.endsWith(suffix)) {
                version = file.substring(0, file.length() - suffix.length());
                break;
            }
        }
        if (version == null || version.indexOf('/') >= 0 || !BlobLayout.addressable(coordinate, version)) {
            return Optional.empty();
        }
        return Optional.of(new ArtifactDescriptor(ecosystem(), coordinate, version, key,
                "application/octet-stream", version.contains("-"), null, 0L));
    }

    @Override
    public List<String> blobKeys(String coordinate, String version, ArtifactStore store) throws IOException {
        // A module version is the .info/.mod/.zip trio under go/<module>/@v/<version>; the module path is the
        // coordinate verbatim (the client's !lower escaping preserved), so the keys are deterministic.
        if (!BlobLayout.addressable(coordinate, version)) {
            // A Go module path is legitimately multi-segment, so the shared screen judges it part by part - but a part
            // that is . or .. maps nowhere, because these keys are what an eviction DELETES.
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        String base = "go/" + coordinate + "/@v/" + version;
        for (String suffix : List.of(".info", ".mod", ".zip")) {
            if (store.readVersioned(base + suffix).isPresent()) {
                keys.add(base + suffix);
            }
        }
        return keys;
    }

    /** The request path this module version's archive serves at ({@code /go/<module>/@v/<version>.zip}), the inverse of
     *  {@link #describe} - a retroactive hold links a {@code /quarantine} review handle there. The {@code .info}/{@code
     *  .mod} metadata name no downloadable artifact and stay out; the {@code .zip} carries the version. */
    @Override
    public List<String> servedPaths(String coordinate, String version, ArtifactStore store) throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            return List.of();
        }
        String zip = "go/" + coordinate + "/@v/" + version + ".zip";
        return store.readVersioned(zip).isPresent() ? List.of("/" + zip) : List.of();
    }

    /** The coordinate a module-archive request path carries ({@code /go/<module>/@v/<version>.zip}, the module path
     *  verbatim with the client's {@code !lower} escaping preserved, exactly as the store and {@link #blobKeys} key
     *  it), so the inventory writes the {@code published/} sidecar the retroactive enforcement sweeps enumerate the
     *  version by. The {@code .info}/{@code .mod} metadata and the {@code @v/list} / {@code @latest} version queries
     *  name no module archive and stay empty - a version is enumerated by the {@code .zip} that carries it. A
     *  {@code -} suffix in the version marks a prerelease (a pseudo-version included), the same convention
     *  {@link Semver#compare} ranks by. */
    @Override
    public Optional<ArtifactDescriptor> describe(String path) {
        if (!path.startsWith("/go/") || !path.endsWith(".zip")) {
            return Optional.empty();
        }
        String rest = path.substring("/go/".length());
        int at = rest.indexOf("/@v/");
        if (at <= 0) {
            return Optional.empty();
        }
        String modulePath = rest.substring(0, at);
        String version = rest.substring(at + "/@v/".length(), rest.length() - ".zip".length());
        if (version.isEmpty() || version.indexOf('/') >= 0) {
            return Optional.empty();
        }
        return Optional.of(new ArtifactDescriptor("Go", modulePath, version, path,
                "application/zip", version.contains("-"), null, -1L));
    }

    // An original CC0 line glyph (a rounded head with speed lines) drawn for this project.
    private static final IconResource ICON = IconResource.svg("""
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round">
              <path d="M2 10h6M3 13h5"/><circle cx="15" cy="12" r="6"/><circle cx="16.5" cy="10.7" r="1"/>
            </svg>""");

    @Override
    public Optional<IconResource> icon() {
        return Optional.of(ICON);
    }

    @Override
    public Optional<URI> defaultUpstream() {
        return Optional.of(URI.create("https://proxy.golang.org/"));
    }

    @Override
    public boolean handles(String path) {
        return path.startsWith("/go/");
    }

    @Override
    public void serve(FormatExchange exchange, ArtifactStore store) throws IOException {
        Blobs blobs = new Blobs(store);
        String rest = exchange.path().substring("/go/".length());
        int at = rest.indexOf("/@");
        if (at < 0) {
            exchange.respond(404);
            return;
        }
        String modulePath = rest.substring(0, at);
        String suffix = rest.substring(at + 1);
        // Guard the path-derived key segments at the front door, the way the siblings (Composer/CocoaPods/conda) do
        // with Keys.unsafe before weaving a coordinate into a blob key. A hostile-but-normalizer-passing input - a
        // backslash in a module segment, or a trailing-slash-empty file (PUT .../@v/) - would otherwise reach
        // Blobs.requireSafeKey and throw an unchecked IllegalArgumentException that escapes handle() as a 500; this
        // turns it into the siblings' clean 400 (and stores nothing). modulePath is validated per slash-segment since,
        // unlike a sibling's single-segment coordinate, a Go module path is legitimately multi-segment.
        if (unsafeModule(modulePath)) {
            exchange.respond(400);
            return;
        }
        if (suffix.equals("@latest")) {
            latest(modulePath, blobs, exchange);
            return;
        }
        if (!suffix.startsWith("@v/")) {
            exchange.respond(404);
            return;
        }
        String file = suffix.substring("@v/".length());
        if (Keys.unsafe(file)) {
            exchange.respond(400);
            return;
        }
        if (exchange.method().equals("PUT")) {
            blobs.write("go/" + modulePath + "/@v/" + file, exchange.requestStream());
            // Stamp the per-module hosted-publish marker, so a later @v/list or @latest read serves the local
            // versions. A pull-through proxy repository (whose .info/.mod/.zip are cached by proxy(), never PUT) never
            // writes it, so its version discovery misses locally and the pull-through streams the authoritative
            // upstream version list for an uncached version rather than shadowing it. Mirrors the RPM hosted gate.
            markHosted(store, hostedKey(modulePath));
            // The served @v/list (and @latest) are written here, on the publish: the file's version is re-decided in
            // the module's stored list rather than enumerated on every read.
            int dot = file.lastIndexOf('.');
            if (dot > 0) {
                new GoListings(blobs).refresh(modulePath, file.substring(0, dot));
            }
            exchange.respond(201);
        } else if (file.equals("list")) {
            list(modulePath, blobs, exchange);
        } else {
            serve(modulePath, file, blobs, exchange);
        }
    }

    /**
     * Proxy a {@code /go/} miss to the upstream GOPROXY (proxy.golang.org). A version's {@code .info}, {@code .mod}
     * and {@code .zip} are immutable and cached; {@code @v/list} and {@code @latest} are mutable version queries, so
     * they are streamed through fresh (never cached) - which is what {@code go get module@latest} and version
     * discovery need against a proxy-only repository. {@code /go/sumdb/...} relays the checksum database, and a
     * cached {@code .zip} / {@code .mod} is held to the dirhash that database publishes for it - the contract section
     * on this class states both, and states exactly which shapes stay unverified.
     */
    @Override
    public boolean pullThrough(FormatExchange exchange, ArtifactStore store, URI upstream,
                               ProxyFormat.Fetcher fetcher) throws IOException {
        String path = exchange.path();
        String rest = path.substring("/go/".length());
        if (rest.startsWith(SUMDB)) {
            // The checksum database, relayed rather than declined: a client whose only egress is this repository must
            // still be able to run the GOSUMDB verification the ecosystem is built on (contract clause 5).
            return sumdb(rest.substring(SUMDB.length()), exchange, upstream, fetcher);
        }
        boolean immutable = rest.endsWith(".info") || rest.endsWith(".mod") || rest.endsWith(".zip");
        boolean query = rest.endsWith("/@latest") || rest.endsWith("/@v/list");
        if (!immutable && !query) {
            return false;
        }
        String root = upstream.toString();
        URI target = URI.create(root.endsWith("/") ? root + rest : root + "/" + rest);
        if (query) {
            // @v/list and @latest are small mutable version queries, streamed through fresh (never cached). Forward the
            // client's conditional-request validators so a 304-capable client's revalidation reaches the upstream, and
            // relay the upstream's validators back so its next read can revalidate rather than re-pulling the list.
            // ENUMERATION, not PINNED: a 404 here is not "the leg served nothing", it is an ANSWER - an empty
            // enumeration the go client reads as "this module has no such versions here" and resolves a build
            // against. So only an upstream that ANSWERED 404/410 reaches the client as one; a transport failure or
            // any other status is a question this repository could not put to its upstream and refuses visibly. That
            // is the very thing list() refuses to do below, where the local list is served whole from its stored
            // document and never as a prefix of the versions (§5, §9). The rule is ProxyRelay's, shared with the twelve
            // peer legs by; only this classification is the go protocol's own.
            ProxyRelay.Answer answer = ProxyRelay.fetchFresh(fetcher, target, ProxyRelay.conditionalHeaders(exchange),
                    exchange, ProxyRelay.Document.ENUMERATION);
            if (!answer.answered()) {
                return answer.served();
            }
            ProxyRelay.relayValidators(answer.document(), exchange);
            exchange.setResponseHeader("Content-Type", rest.endsWith("/@latest") ? "application/json" : "text/plain");
            exchange.respond(200, answer.document().body());
            return true;
        }
        // The digest the checksum database advertises for this module version, read BEFORE the body is opened so the
        // fill is one streamed pass rather than a body held while a sidecar is fetched. It declares NOTHING for a
        // .info (the database publishes none), for a module the database ANSWERED that it does not carry, and for a
        // deployment that turned it off - the three declared-unverified shapes of contract clause 3. A database
        // neither route could read is a fourth shape and not one of them: it is refused, because "we could not ask
        // what this module should hash to" is not "nothing vouches for it".
        ProxyRelay.Declared advertised = rest.endsWith(".info")
                ? ProxyRelay.Declared.NONE
                : advertisedDirhash(rest, upstream, fetcher, ProxyLeg.allowInternalTargets(exchange));
        if (!advertised.readable()) {
            return ProxyRelay.unverifiable(target, advertised);
        }
        String expected = advertised.text();
        // The .info/.mod/.zip trio is immutable; the .zip is an unbounded module archive, so stream it from the
        // network straight into the content-addressed store rather than buffering the whole body, then re-serve locally.
        try (ProxyFormat.Download download = fetcher.download(target, Map.of()).orElse(null)) {
            if (download == null || download.status() != 200) {
                return false;
            }
            Blobs blobs = new Blobs(store);
            String key = "go/" + rest;
            if (expected == null) {
                blobs.write(key, download.body());
                return serveProxied(exchange, store);
            }
            // Content-address the body first and link the serving pointer only once it has been held to the dirhash:
            // pointer-last (clause 8), so a body that fails the check is an unreferenced blob rather than something
            // that briefly served. The store returns the body's SHA-256, which IS the per-file digest a .mod's dirhash
            // is composed from, so a verified .mod costs no second read at all.
            String hash = blobs.store(download.body());
            String actual = rest.endsWith(".mod")
                    ? GoDirhash.ofGoMod(hash)
                    : zipDirhash(blobs, store, hash);
            if (!expected.equals(actual)) {
                // A refusal, and a visible one: nothing is linked, nothing serves, the local 404 stands so a later
                // pull re-hits the upstream (clause 2), and the operator is told which module version failed and how -
                // a mismatch and an uncomputable dirhash are different facts and are never reported as one.
                LOGGER.warn("Refusing the proxied Go module {}: the checksum database advertises {} but the upstream "
                                + "body {}. Nothing was cached or served.", rest, expected,
                        actual == null
                                ? "could not be hashed within the archive-walk bound (jenreg.archive.largest-walk)"
                                : "hashes to " + actual);
                return false;
            }
            blobs.link(key, hash);
        }
        return serveProxied(exchange, store);
    }

    /** The just-cached body, served back through this format's own read path - so a proxied artifact and a published
     *  one leave through exactly one serve. */
    private boolean serveProxied(FormatExchange exchange, ArtifactStore store) throws IOException {
        handle(exchange, store);
        return true;
    }

    /** The path prefix the GOPROXY protocol reserves for the checksum database. */
    private static final String SUMDB = "sumdb/";

    private static final Logger LOGGER = LoggerFactory.getLogger(GoFormat.class);

    /**
     * Relay one checksum-database request - {@code <name>/<operation>} as the client spelled it - fresh and uncached,
     * through the configured upstream's mirror or the configured database itself. {@code false} (the local {@code 404})
     * when the name carries no protocol operation or neither route answers, which is what a {@code go} client reads as
     * "this proxy does not mirror the database" and is exactly the signal that lets it fall back on its own.
     */
    private boolean sumdb(String rest, FormatExchange exchange, URI upstream, ProxyFormat.Fetcher fetcher)
            throws IOException {
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            return false;
        }
        String database = rest.substring(0, slash);
        String operation = rest.substring(slash + 1);
        if (Keys.unsafe(database) || !GoChecksumDatabase.relayable(operation)) {
            // The name is spliced into the upstream's own path, so it is screened like every other name this format
            // composes a request from; an operation the protocol does not define is not relayed at all.
            return false;
        }
        Optional<ProxyFormat.Fetched> response = GoChecksumDatabase.read(fetcher, upstream, database, operation,
                ProxyLeg.allowInternalTargets(exchange));
        if (response.isEmpty()) {
            return false;
        }
        // A note, a signed tree head or a tile: all small, all mutable (the tree head moves), so none is cached and
        // the body is relayed verbatim - a rewritten one would no longer verify against the database's signature.
        String contentType = response.get().header("Content-Type");
        exchange.setResponseHeader("Content-Type", contentType == null ? "text/plain; charset=utf-8" : contentType);
        exchange.respond(200, response.get().body());
        return true;
    }

    /** The {@code h1:} dirhash the checksum database advertises for the module version a {@code <module>/@v/<file>}
     *  request names. {@link ProxyRelay.Declared#NONE} when it advertises none for that shape (contract clause 3);
     *  {@linkplain ProxyRelay.Declared#unreadable unreadable} when neither the database nor the upstream's mirror of it
     *  could be read, which is the case that used to log at DEBUG and cache the module unverified anyway.
     *  The dirhash is a composed string rather than a raw digest, so it rides as a {@link ProxyRelay.Declared#text}
     *  declaration and is compared as text against the walk of the stored archive. */
    private static ProxyRelay.Declared advertisedDirhash(String rest, URI upstream, ProxyFormat.Fetcher fetcher,
                                                         boolean allowInternal) throws IOException {
        int at = rest.indexOf("/@v/");
        if (at <= 0) {
            return ProxyRelay.Declared.NONE;
        }
        String module = rest.substring(0, at);
        String file = rest.substring(at + "/@v/".length());
        int dot = file.lastIndexOf('.');
        if (dot <= 0) {
            return ProxyRelay.Declared.NONE;
        }
        String version = file.substring(0, dot);
        GoChecksumDatabase.Advertised advertised =
                GoChecksumDatabase.lookup(fetcher, upstream, module, version, allowInternal);
        if (advertised.unreadable() != null) {
            return ProxyRelay.Declared.unreadable(advertised.unreadable());
        }
        String dirhash = advertised.dirhashes() == null
                ? null
                : file.endsWith(".mod") ? advertised.dirhashes().mod() : advertised.dirhashes().zip();
        if (dirhash == null) {
            LOGGER.debug("The checksum database advertises no dirhash for {}: caching it unverified.", rest);
            return ProxyRelay.Declared.NONE;
        }
        return ProxyRelay.Declared.text(GoDirhash.PREFIX, dirhash);
    }

    /** The dirhash of a module {@code .zip} already stored under {@code hash} - reopened from the content-addressed
     *  store and walked entry by entry, so the digest costs one bounded local pass and the body was never in heap.
     *  {@code null} when a bound stopped the walk, which the caller treats as a refusal, not as an absent digest. */
    private static String zipDirhash(Blobs blobs, ArtifactStore store, String hash) throws IOException {
        try (InputStream archive = blobs.open(hash)) {
            return GoDirhash.ofZip(archive, store.size("blobs/" + hash));
        }
    }

    /**
     * The GOPROXY version query {@code GET /go/<module>/@v/list}: the module's disclosable versions, one per line.
     *
     * <p><b>Two absences, and only one of them is a 404 (beside /).</b> This route's {@code 404}
     * already carries a second meaning - "not hosted here, ask the upstream" - which is what makes a pull-through
     * proxy's version discovery reach the authoritative list instead of the locally cached trio. That meaning is
     * carried entirely by the {@link #hosted} marker, which a {@code PUT} (or an import) stamps and a proxy fill never
     * does, so it is checked FIRST and on its own. What was conflated with it is the third case: a module that IS
     * hosted here, whose versions are all withheld by a compliance hold. Keying the {@code 404} on the screened set
     * read that as "no such module" - and, on a repository with an upstream configured, sent the client on to the
     * upstream's list, disclosing the very versions the hold withholds. So the emptiness probe below is over the RAW
     * {@code @v} container: a hosted module with nothing servable answers {@code 200} with an empty list, the same
     * "addressed by the container's own name" rule PyPI states in its project index and npm, NuGet's flat container,
     * Cargo, Conda and RubyGems all follow. {@code @latest} keeps its {@code 404} because it names ONE version and has
     * no empty form (see {@link #latest}).
     */
    private void list(String modulePath, Blobs blobs, FormatExchange exchange) throws IOException {
        if (!hosted(modulePath, blobs)) {
            exchange.respond(404);
            return;
        }
        Optional<StoredListing.Served> served = StoredListing.open(blobs.store(), new GoListings(blobs).spec(modulePath));
        if (served.isEmpty()) {
            exchange.respond(404);
            return;
        }
        try (StoredListing.Served document = served.get()) {
            if (document.header().size() == 0 && !stored(modulePath, blobs)) {
                exchange.respond(404);
                return;
            }
            Listings.serve(exchange, document, "text/plain");
        }
    }

    private void latest(String modulePath, Blobs blobs, FormatExchange exchange) throws IOException {
        if (!hosted(modulePath, blobs)) {
            exchange.respond(404);
            return;
        }
        // @latest is derived from the stored list on every write; a module read before its list was materialised
        // derives it now, once.
        Optional<StoredListing.Served> served = StoredListing.openDerived(blobs.store(), GoListings.latest(modulePath));
        if (served.isEmpty()) {
            StoredListing.open(blobs.store(), new GoListings(blobs).spec(modulePath)).ifPresent(GoFormat::closeQuietly);
            served = StoredListing.openDerived(blobs.store(), GoListings.latest(modulePath));
        }
        if (served.isEmpty()) {
            exchange.respond(404);
            return;
        }
        String latest;
        try (StoredListing.Served document = served.get()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            document.copyTo(buffer);
            latest = buffer.toString(StandardCharsets.UTF_8).trim();
        }
        if (latest.isEmpty()) {
            exchange.respond(404);
            return;
        }
        serve(modulePath, latest + ".info", blobs, exchange);
    }

    private static void closeQuietly(StoredListing.Served served) {
        try {
            served.close();
        } catch (IOException ignored) {
            // nothing was read from it
        }
    }

    private void serve(String modulePath, String file, Blobs blobs, FormatExchange exchange) throws IOException {
        String key = "go/" + modulePath + "/@v/" + file;
        Optional<Blobs.Located> located = blobs.locate(key);
        if (located.isEmpty()) {
            exchange.respond(404);
            return;
        }
        long size = located.get().size();
        exchange.setResponseHeader("Content-Type", contentType(file));
        if (exchange.method().equals("HEAD")) {
            // Answer HEAD from the stored blob size (Content-Length, 200, no body) rather than streaming or buffering the
            // whole module archive just to discard it - the go client issues HEADs to probe existence and size.
            if (size >= 0) {
                exchange.setResponseHeader("Content-Length", Long.toString(size));
            }
            exchange.respond(200, -1L).close();
            return;
        }
        if (file.endsWith(".zip")) {
            blobs.serve(located.get(), exchange);
            return;
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        blobs.stream(located.get(), buffer);
        exchange.respond(200, buffer.toByteArray());
    }

    private static final byte[] HOSTED = "1".getBytes(StandardCharsets.UTF_8);

    /** Whether a Go module path carries a segment unsafe to weave into a blob key - each {@code /}-delimited segment
     *  is held to the same front-door rule {@link Keys#unsafe} applies to a sibling format's single-segment coordinate
     *  (empty, {@code .}/{@code ..}, or a backslash/control character), so a hostile module path becomes a clean 400
     *  rather than an {@code IllegalArgumentException} escaping from the store boundary as a 500.
     *
     *  <p>One qualification, because the reach changed under this text: a backslash in a <em>whole request path</em>
     *  no longer arrives here at all. The core folded {@code \} into {@code ArtifactStore.traversalFree}, which the
     *  shared request screen runs first, so that shape is already a 404 before this method is asked. What this rule
     *  still owns is the per-<em>segment</em> judgement - {@code Keys.unsafe} never consults {@code traversalFree} -
     *  and the 400 it promises is for the segment-level shapes, not for the path-level backslash. */
    private static boolean unsafeModule(String modulePath) {
        for (String segment : modulePath.split("/", -1)) {
            if (Keys.unsafe(segment)) {
                return true;
            }
        }
        return false;
    }

    /** The per-module hosted-publish marker key - a sibling of the version files under {@code @v}, and not a
     *  {@code .info}, so the version list never mistakes it for a version. Package-private so {@link GoImporter}
     *  stamps it too: an import is a hosted publish, exactly as the {@code PUT} is. */
    static String hostedKey(String modulePath) {
        return "go/" + modulePath + "/@v/.hosted";
    }

    /** Whether this module has ever taken a hosted publish - it then carries the marker a {@code PUT} (or an import)
     *  stamps, which a pull-through proxy never writes. The {@code @v/list} / {@code @latest} discovery gate keys on it
     *  so a proxy repository's version discovery always misses locally and reproxies the upstream version list (every
     *  version) for an uncached version rather than shadowing it with only the cached trio. */
    private static boolean hosted(String modulePath, Blobs blobs) throws IOException {
        return blobs.exists(hostedKey(modulePath));
    }

    /** Stamp the hosted-publish marker once, idempotently - a compare-and-set against an absent pointer, so a
     *  concurrent publish's lost race simply means a peer already set it. */
    static void markHosted(ArtifactStore store, String key) throws IOException {
        if (store.readVersioned(key).isEmpty()) {
            store.writeVersioned(key, HOSTED, null);
        }
    }

    /** Whether the module's {@code @v} container holds any version at all - a structural emptiness probe over the RAW
     *  container, judged by the {@code .info} sibling the version list keys a version on. Deliberately NOT
     *  the stored list's emptiness: that set is screened, and a hosted module whose every version a hold has
     *  withheld must answer an empty list rather than "no such module". Walked through the shared bounded
     *  children primitive rather than a whole-namespace {@code list(...)}: the {@code @v} container is client-grown,
     *  so materialising it to test emptiness is the unpaged-DoS shape the bounded-listing clause refuses.
     *
     *  <p><b>A stated bound.</b> The scan examines at most the primitive's default entry cap - a thousand names,
     *  one directory page on the filesystem store - so a module whose first thousand children carry no
     *  {@code .info} reads as not stored, and a request pays at most that page whatever the module holds; it is a
     *  request-path probe, so it keeps the default width rather than the drain page. */
    private static boolean stored(String modulePath, Blobs blobs) throws IOException {
        boolean[] any = {false};
        BoundedChildren.bounded().scan(blobs.store(), "go/" + modulePath + "/@v", name -> {
            if (name.endsWith(".info")) {
                any[0] = true;
            }
        });
        return any[0];
    }


    private static String contentType(String file) {
        if (file.endsWith(".info")) {
            return "application/json";
        }
        if (file.endsWith(".zip")) {
            return "application/zip";
        }
        return "text/plain";
    }






    /** The migration-import capability (WSPI.2 (c)), delegated to the layout-only {@link GoImporter} - the format IS the
     *  discovered importer now (an {@code instanceof} capability), and the importer class stays as its delegate. */
    private final GoImporter importer = new GoImporter();

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

    /** Each of the version's {@code .info}, {@code .mod} and {@code .zip} is put at its {@code @v/} path, which is how
     *  a module version is published to a GOPROXY that accepts uploads. */
    @Override
    public Exported export(ArtifactStore repository, String coordinate, String version, ExportTarget target)
            throws IOException {
        return BlobExport.put(repository, mount(), blobKeys(coordinate, version, repository), target);
    }
}
