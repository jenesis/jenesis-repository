package build.jenesis.repository.format.gems;

import module java.base;
import module org.apache.commons.compress;
import module org.yaml.snakeyaml;
import module tools.jackson.databind;
import build.jenesis.repository.multipart.MultipartBody;
import build.jenesis.repository.store.PublishInterceptor;

import build.jenesis.repository.format.Listings;
import build.jenesis.repository.store.Limits;
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
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.store.Publication;

/**
 * The RubyGems format, so {@code gem push}, {@code bundle install} and {@code gem install} work over the same store.
 * It owns {@code /rubygems/...}: a push ({@code POST /rubygems/api/v1/gems}, the raw {@code .gem} as the body) reads
 * the gem's name, version, runtime dependencies and Ruby constraint from the {@code metadata.gz} gemspec inside the
 * gem (a tar of gzipped YAML, parsed with SnakeYAML) and stores the file under
 * {@code rubygemfiles/<name>-<version>.gem} with a precomputed compact-index line under
 * {@code rubygems/<name>/versions/<version>}.
 *
 * The modern compact index is served from stored listings the push maintains: the per-gem info
 * ({@code GET /rubygems/info/<name>}) and the {@code /rubygems/versions} list, which is all {@code bundle install}
 * needs. Plain {@code gem install} additionally
 * fetches each gem's full spec from the legacy {@code /rubygems/quick/Marshal.4.8/<name>-<version>.gemspec.rz}
 * endpoint; that one document - a Ruby Marshal of the {@code Gem::Specification} - is produced by {@link QuickSpec}
 * (kept apart so the legacy surface stays visible), precomputed at push and served as a plain streamed read. The gem
 * itself is served at {@code /rubygems/gems/<file>.gem}.
 */
public final class RubyGemsFormat implements RepositoryFormat, ProxyLeg, BlobLayout, RepositoryImporter, ArtifactSignatures {

    private static final String QUICK = "quick/Marshal.4.8/";




    // The gemspec YAML carries Ruby object tags (!ruby/object:Gem::Specification and friends) that no Java class
    // matches; stripping them lets the SafeConstructor load the document as plain maps and lists. Compiled once.
    private static final Pattern RUBY_TAG = Pattern.compile("!ruby/\\S+");

    @Override
    public String name() {
        return "rubygems";
    }

    @Override
    public String ecosystem() {
        return "RubyGems";
    }

    /**
     * The coordinate version a stored RubyGems pointer serves - the backwards direction the inventory back-fill
     * rebuilds a lost {@code published/} row from.
     *
     * <p>Only {@code rubygems/<name>/versions/<version>} is decoded. The two {@code rubygemfiles/} keys spell the
     * pair as {@code <name>-<version>}, and a gem name may itself contain a hyphen, so that split is ambiguous and
     * is deliberately not attempted: every published version has a versions pointer, so nothing is lost by reading
     * only the shape that cannot be misread. A wrong answer would not fail a read - it would write a row against a
     * release that was never published, and retention ages artifacts by that row.
     *
     * <p>Simpler than npm's parse in one respect: a gem name is a single path segment, so the marker cannot be
     * preceded by a slash-bearing coordinate. It is still screened through {@link BlobLayout#addressable}, which is
     * what refuses a traversal-shaped key rather than decoding it into a coordinate this format never wrote.
     */
    @Override
    public Optional<ArtifactDescriptor> describePointer(String key) {
        String marker = "rubygems/";
        if (!key.startsWith(marker)) {
            return Optional.empty();
        }
        String[] parts = key.substring(marker.length()).split("/");
        if (parts.length != 3 || !parts[1].equals("versions")) {
            return Optional.empty();
        }
        String coordinate = parts[0], version = parts[2];
        if (!BlobLayout.addressable(coordinate, version)) {
            return Optional.empty();
        }
        return Optional.of(new ArtifactDescriptor(ecosystem(), coordinate, version, key,
                "application/octet-stream", version.contains("-"), null, 0L));
    }

    @Override
    public List<String> blobRoots() {
        // rubygemsindex held the one precomputed compact-index /versions document (a pointer to its content-addressed
        // body) before the stored listing under listing/ replaced it; naming it here keeps garbage collection from
        // reclaiming such a cached blob out from under a store that still carries the pointer.
        return List.of("rubygemfiles", "rubygems", "rubygemsindex");
    }

    @Override
    public List<String> blobKeys(String coordinate, String version, ArtifactStore store) throws IOException {
        // The .gem file, its precomputed legacy quick-spec companion, and the compact-index line - all keyed
        // deterministically by <name>-<version>.
        if (!BlobLayout.addressable(coordinate, version)) {
            return List.of();   // a traversal-shaped coordinate maps nowhere - these keys are what an eviction DELETES
        }
        List<String> keys = new ArrayList<>();
        for (String key : List.of(
                "rubygemfiles/" + coordinate + "-" + version + ".gem",
                "rubygemfiles/" + coordinate + "-" + version + ".gemspec.rz",
                "rubygems/" + coordinate + "/versions/" + version)) {
            if (store.readVersioned(key).isPresent()) {
                keys.add(key);
            }
        }
        return keys;
    }

    /** The request path this gem version serves at ({@code /rubygems/gems/<name>-<version>.gem}), the inverse of
     *  {@link #describe} - a retroactive hold links a {@code /quarantine} review handle there. The quick-spec companion
     *  and the compact-index line are not served downloads and stay out. */
    @Override
    public List<String> servedPaths(String coordinate, String version, ArtifactStore store) throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            return List.of();
        }
        String key = "rubygemfiles/" + coordinate + "-" + version + ".gem";
        return store.readVersioned(key).isPresent()
                ? List.of("/rubygems/gems/" + coordinate + "-" + version + ".gem")
                : List.of();
    }

    /** The coordinate a gem request path carries ({@code /rubygems/gems/<name>-<version>.gem}), split at the
     *  rightmost {@code -} followed by a digit - a gem version always starts with one, a name's own dashed segments
     *  conventionally do not - reproducing the {@code <name>-<version>} the push stored and {@link #blobKeys}
     *  rebuilds, so the inventory writes the {@code published/} sidecar the retroactive enforcement sweeps enumerate
     *  the version by. The compact-index {@code versions}/{@code info} documents and the derived quick-spec
     *  {@code .gemspec.rz} name no gem artifact and stay empty, as does the push endpoint (whose coordinate lives in
     *  the gemspec, not the path) and a filename with no version-looking suffix - empty over a wrong coordinate. */
    @Override
    public Optional<ArtifactDescriptor> describe(String path) {
        if (!path.startsWith("/rubygems/gems/") || !path.endsWith(".gem")) {
            return Optional.empty();
        }
        String stem = path.substring("/rubygems/gems/".length(), path.length() - ".gem".length());
        int split = -1;
        for (int dash = stem.lastIndexOf('-'); dash > 0; dash = stem.lastIndexOf('-', dash - 1)) {
            if (dash + 1 < stem.length() && Character.isDigit(stem.charAt(dash + 1))) {
                split = dash;
                break;
            }
        }
        if (split < 0 || stem.indexOf('/') >= 0) {
            return Optional.of(ArtifactDescriptor.at("RubyGems", path));
        }
        return Optional.of(new ArtifactDescriptor("RubyGems", stem.substring(0, split), stem.substring(split + 1),
                path, "application/octet-stream", false, null, -1L));
    }

    // An original CC0 line glyph (a faceted gem) drawn for this project.
    private static final IconResource ICON = IconResource.svg("""
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round">
              <path d="M6 3h12l3 6-9 12L3 9z"/><path d="M3 9h18"/><path d="M9 3 6 9l6 12 6-12-3-6"/>
            </svg>""");

    @Override
    public Optional<IconResource> icon() {
        return Optional.of(ICON);
    }

    @Override
    public Optional<URI> defaultUpstream() {
        return Optional.of(URI.create("https://rubygems.org/"));
    }

    /**
     * Where a proxied gem's Sigstore attestations are fetched from: the base of an API answering
     * {@code <base>/<name>-<version>.json} with an array of bundles, as rubygems.org does at
     * {@code /api/v1/attestations/}. Empty by default, which reads that path under the proxied upstream; a mirror
     * without the API answers 404, which is absence and never a failure.
     */
    public static final String ATTESTATIONS_URL = "rubygems-attestations-url";

    /** The stored attestations of a gem version, beside the gem's own key: the array rubygems.org answers, kept only
     *  when it names at least one bundle. */
    static String attestationsKey(String stem) {
        return "rubygemfiles/" + stem + ".attestations.json";
    }

    /** Where a version's attestations serve, the path rubygems.org serves them at. */
    private static final String ATTESTATIONS_PATH = "/rubygems/api/v1/attestations/";

    /** The reader of an attestations array, one bundle per element. */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Whether a body is a JSON array naming at least one element - the shape an attestations answer has when it
     *  carries provenance; rubygems.org answers {@code []} for a version pushed without any. */
    private static boolean namesABundle(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8).strip();
        return text.startsWith("[") && text.endsWith("]") && !text.substring(1, text.length() - 1).isBlank();
    }

    /**
     * The attestations rubygems.org publishes for a version and no gem client fetches, named so the pull-through
     * fetches them beside the gem and the screen judges the gem by them. Kept through {@link #keep} under a key of
     * this format's own rather than linked at a served path.
     */
    @Override
    public List<ProxyFormat.Companion> companions(FormatExchange exchange, URI upstream) {
        String path = exchange.path();
        Optional<ArtifactDescriptor> described = describe(path)
                .filter(d -> d.coordinate() != null && d.version() != null);
        if (described.isEmpty() || !ArtifactStore.traversalFree(path)) {
            return List.of();
        }
        String base = exchange.setting(ATTESTATIONS_URL);
        if (base == null || base.isBlank()) {
            String root = upstream.toString();
            base = (root.endsWith("/") ? root : root + "/") + "api/v1/attestations/";
        } else if (!base.endsWith("/")) {
            base += "/";
        }
        String stem = described.get().coordinate() + "-" + described.get().version();
        return List.of(new ProxyFormat.Companion("/rubygems/api/v1/attestations/" + stem + ".json",
                URI.create(base + stem + ".json")));
    }

    /**
     * A fetched attestations array is kept beside the gem when it names at least one bundle - rubygems.org answers
     * {@code []} for a version pushed without any, and an empty answer is not provenance. Always {@code true}: what
     * is not kept is dropped rather than linked at a path nothing serves.
     */
    @Override
    public boolean keep(ArtifactStore store, ProxyFormat.Companion companion, byte[] body) throws IOException {
        String prefix = "/rubygems/api/v1/attestations/";
        if (!companion.path().startsWith(prefix) || !companion.path().endsWith(".json")) {
            return true;
        }
        String stem = companion.path().substring(prefix.length(), companion.path().length() - ".json".length());
        if (stem.isEmpty() || stem.indexOf('/') >= 0 || Keys.unsafe(stem)) {
            return true;
        }
        if (!namesABundle(body)) {
            return true;
        }
        new Blobs(store).write(attestationsKey(stem), body);
        return true;
    }

    @Override
    public boolean handles(String path) {
        return path.startsWith("/rubygems/");
    }

    @Override
    public void serve(FormatExchange exchange, ArtifactStore store) throws IOException {
        Blobs blobs = new Blobs(store);
        String rest = exchange.path().substring("/rubygems/".length());
        String method = exchange.method();
        if (method.equals("POST") && rest.equals("api/v1/gems")) {
            push(exchange, blobs, store);
        } else if (!method.equals("GET") && !method.equals("HEAD")) {
            // Every read route below serves a body; gate the write verbs so a PUT/DELETE (or a POST to a read path) is a
            // 405 rather than being answered as a download, like the other formats do. The only write is the gem push
            // (POST api/v1/gems) handled above.
            exchange.respond(405);
        } else if (rest.equals("versions")) {
            versions(blobs, exchange);
        } else if (rest.startsWith("info/")) {
            info(rest.substring("info/".length()), blobs, exchange);
        } else if (rest.startsWith(QUICK) && rest.endsWith(".gemspec.rz")) {
            serveFile("rubygemfiles/" + rest.substring(QUICK.length()), blobs, exchange);
        } else if (rest.startsWith("gems/") && rest.endsWith(".gem")) {
            serveFile("rubygemfiles/" + rest.substring("gems/".length()), blobs, exchange);
        } else if (rest.startsWith("api/v1/attestations/") && rest.endsWith(".json")) {
            // The version's attestations as rubygems.org serves them: the stored array, or a 404 where none was
            // pushed or fetched - never an empty array, which would claim the question was asked upstream.
            String stem = rest.substring("api/v1/attestations/".length(), rest.length() - ".json".length());
            if (stem.isEmpty() || Keys.unsafe(stem)) {
                exchange.respond(404);
                return;
            }
            serveFile(attestationsKey(stem), blobs, exchange, "application/json");
        } else {
            exchange.respond(404);
        }
    }

    /**
     * The republish conflict policy this format hands the hosted-publish operation as <em>data</em>, rather
     * than re-implementing "is this version already taken" beside the layout: {@code OVERWRITE}, last-writer-wins.
     * That is exactly what a {@code gem push} does today - a gem's pointers live in the {@code rubygemfiles/} and
     * {@code rubygems/} blobs namespaces, not in {@code publish/}, so the release-immutability edge hook
     * (which reads a {@code publish/<path>} pointer) has never seen them, and a re-push has always silently
     * re-pointed. A <em>probing</em> mode could not be expressed here in any case: the operation evaluates the policy
     * before the accepted layout runs, and a gem push is coordinate-<em>less</em> at the request path - the name and
     * version live in the {@code metadata.gz} gemspec inside the uploaded {@code .gem}, which only that layout parses.
     */
    private static final Publication.Republish REPUBLISH = Publication.Republish.overwrite();

    /**
     * The {@code gem push} endpoint, run through the one shared hosted-publish choreography
     * ({@code Publication.commit}) rather than hand-assembled here. A {@code.gem} is an immutable artifact of
     * unbounded size, so it is handed to the operation as the accepted body and streams straight into the
     * content-addressed store (hash-on-write, never buffered), taking the SHA-256 the store computes on the way in as
     * the compact-index checksum; the layout then reopens only the front of the <em>stored</em> gem to read the
     * gemspec that names it (the {@code metadata.gz} that identifies the gem is the first tar entry, so the parse
     * never pulls the artifact back whole).
     *
     * <p><b>The commit point is the {@code rubygemfiles/<name>-<version>.gem} pointer link</b> - before it nothing
     * serves, {@code /info/<name>} is a structural miss and the compact index does not name the gem; after the last
     * declared step the gem downloads, {@code /info} lists it and {@code /versions} carries it.
     *
     * <p>This is the ordering fix owns for RubyGems: the former code linked the {@code.gem} pointer
     * <em>first</em> and only then wrote the compact-index line, the quick spec and the rolled-forward
     * {@code /versions} document - so a crash in between left a downloadable gem whose {@code gem install} could not
     * find its spec. Now the one parse result that is not itself a serving surface, the quick spec, lands before
     * anything serves, and the three writes that <em>are</em> visibility are declared to the operation in order:
     * <ol>
     *   <li>the {@code .gem} pointer - the download, and the commit point;</li>
     *   <li>the compact-index line under {@code rubygems/<name>/versions/<version>} - what makes the version
     *       <em>enumerable</em> ({@code /info}, {@code blobKeys}, the screened version scan), declared after the bytes
     *       it names rather than before them;</li>
     *   <li>the stored {@code /info/<name>} document and, derived from it, the gem's line in the stored
     *       repository-wide {@code /versions} document ({@link RubyGemsListings}) - the served listings, written
     *       incrementally from the line above once it has landed, so a push costs one rewrite of each of the two
     *       documents and never a scan of the other gems.</li>
     * </ol>
     * Declaring all three to the operation - rather than running them after a pointer the format wrote itself - is
     * what makes a failure in any of them fail the push loudly instead of answering {@code 200} over a half-built
     * index.
     *
     * <p>The quick spec is written <em>inside</em> the layout, before any of that: it is a precomputed rendering of
     * the gemspec (the {@code Gem::Specification} Marshal that plain {@code gem install} fetches), keyed by the exact
     * coordinate and reachable only by a client that has already resolved that coordinate through {@code /info} or
     * {@code /versions} - neither of which names the version until step 2. Writing it first is therefore the strong
     * ordering: at the instant a version becomes listable, its quick spec is already there, so {@code gem install}
     * can never see a listed version whose spec fetch 404s. It goes through {@link Blobs#write} rather than the
     * operation's sidecar seam because a blobs-namespace format stores its derived documents in the same
     * pointer -&gt; blob representation as its artifacts (the serve path reads it back with the ordinary blob read),
     * and that seam writes a raw store object; the ordering guarantee is the same, since this runs inside the layout,
     * strictly before any declared visibility step.
     *
     * <p>The chain and the observer list are passed in <b>explicitly empty</b>: this format never screens (screening
     * is the ingress edges' monopoly - the edge already ran the discovered {@code PublishInterceptor} chain over this
     * body) and never notifies (the edge fires the one after-commit notification once its own commit returns). So the
     * operation is used here for what it is - the pointer-last layout choreography - and adds neither a second gate
     * nor a second publish event.
     */
    /** The attestations a push carried beside its gem, read only once the gem part has been consumed and stored -
     *  a multipart client sends its parts in an order of its own, and the gem streams into the store unbuffered. */
    @FunctionalInterface
    private interface Attestations {
        byte[] read() throws IOException;
    }

    /**
     * A push is the raw {@code .gem} as the request body, or - {@code gem push --attestations}, rubygems 3.6 and
     * later - a multipart form with the gem as its file part and an {@code attestations} field holding a JSON array
     * of Sigstore bundles, in whichever order the client sends them. The bundles are kept beside the gem before the
     * version is discoverable, exactly as the npm leg keeps a publish's attestations.
     */
    private void push(FormatExchange exchange, Blobs blobs, ArtifactStore store) throws IOException {
        Optional<String> boundary = MultipartBody.boundary(exchange.requestHeader("Content-Type"));
        if (boundary.isEmpty()) {
            push(exchange.requestStream(), () -> null, blobs, exchange, store);
            return;
        }
        MultipartBody form = MultipartBody.over(exchange.requestStream(), boundary.get());
        byte[][] attestations = new byte[1][];
        for (Optional<MultipartBody.Part> next = form.next(); next.isPresent(); next = form.next()) {
            MultipartBody.Part part = next.get();
            if (part.file()) {
                push(part.stream(), () -> {
                    for (Optional<MultipartBody.Part> rest = form.next(); rest.isPresent(); rest = form.next()) {
                        field(rest.get(), attestations);
                    }
                    return attestations[0];
                }, blobs, exchange, store);
                return;
            }
            field(part, attestations);
        }
        exchange.respond(400);   // a form that carries no gem
    }

    /** One small field of a push form: only {@code attestations} is read, bounded to the signature limit; an
     *  oversized one is not kept, since an array longer than that is not one this repository can verify. */
    private static void field(MultipartBody.Part part, byte[][] attestations) throws IOException {
        if ("attestations".equals(part.name())) {
            attestations[0] = part.bytes(ArtifactSignatures.Material.LARGEST_SIGNATURE).orElse(null);
        }
    }

    private void push(InputStream body, Attestations attestations, Blobs blobs, FormatExchange exchange,
                      ArtifactStore store) throws IOException {
        Publication.Commit commit = new Publication(store, List.of(), List.of()).commit(
                ArtifactDescriptor.at("RubyGems", exchange.path()), body, REPUBLISH,
                accepted -> {
                    Spec spec;
                    try (InputStream stored = accepted.open()) {
                        spec = parse(gemspec(stored));
                    }
                    if (spec == null || Keys.unsafe(spec.name()) || Keys.unsafe(spec.version())) {
                        // No parseable gemspec, or a gemspec-supplied name/version that would forge a pointer key with
                        // '/' or '..': nothing servable, so nothing is declared and nothing is linked.
                        return Publication.Visibility.declined();
                    }
                    for (Dependency dependency : spec.deps()) {
                        String requirement = constraint(dependency.requirement());
                        if (hasControlChar(dependency.name())
                                || (requirement != null && hasControlChar(requirement))) {
                            // A runtime-dependency name/requirement flows unescaped into the compact-index line
                            // (<version> <deps>|<requirements>), one version per newline in /info. A gemspec (attacker
                            // YAML) whose dependency name carries a newline would inject a spurious version line into
                            // that gem's /info and skew the /versions md5 computed over it. Refuse any control
                            // character (never legitimate in a dependency name or a version constraint) - the guard
                            // Keys.unsafe already applies to the gem's own name/version.
                            return Publication.Visibility.declined();
                        }
                    }
                    // Precompute the legacy quick spec gem install fetches, exactly as the compact-index line is
                    // precomputed, so serving it is a plain streamed read; the Marshal encoding lives in QuickSpec.
                    blobs.write("rubygemfiles/" + spec.name() + "-" + spec.version() + ".gemspec.rz",
                            QuickSpec.deflated(spec));
                    String versionKey = "rubygems/" + spec.name() + "/versions/" + spec.version();
                    // The attestations the form carried after the gem, kept before anything serves so the version
                    // is never discoverable without the provenance it was pushed with; an empty array is not kept.
                    byte[] bundles = attestations.read();
                    return Publication.Visibility
                            .through((hash, _, _) -> {
                                if (bundles != null && namesABundle(bundles)) {
                                    blobs.write(attestationsKey(spec.name() + "-" + spec.version()), bundles);
                                }
                            })
                            // The serving pointers live in this format's own namespaces rather than publish/, so they
                            // are declared through Serving steps, not named with at().
                            .andThrough((hash, _, _) -> blobs.link(gemKey(spec.name(), spec.version()), hash))
                            // The compact-index line carries the artifact's content address as its checksum: the hash
                            // the operation stored the body under, reused rather than hashing the blob a second time.
                            .andThrough((hash, _, _) -> blobs.write(versionKey,
                                    line(spec, hash).getBytes(StandardCharsets.UTF_8)))
                            // The served documents are written here, on the push: the version's line joins the
                            // gem's stored /info document (if the version is servable), which re-derives the gem's
                            // line in the stored compact index - no scan of the other gems.
                            .andThrough((hash, _, _) -> new RubyGemsListings(blobs).published(spec.name(),
                                    spec.version(), line(spec, hash).getBytes(StandardCharsets.UTF_8)));
                });
        exchange.respond(commit.visible() ? 200 : 400);
    }

    private void serveFile(String key, Blobs blobs, FormatExchange exchange) throws IOException {
        serveFile(key, blobs, exchange, "application/octet-stream");
    }

    private void serveFile(String key, Blobs blobs, FormatExchange exchange, String contentType) throws IOException {
        Optional<Blobs.Located> located = blobs.locate(key);
        if (located.isEmpty()) {
            exchange.respond(404);
            return;
        }
        long size = located.get().size();
        exchange.setResponseHeader("Content-Type", contentType);
        if (exchange.method().equals("HEAD")) {
            // Answer HEAD from the stored blob size (Content-Length, 200, no body) rather than streaming the whole
            // .gem just to discard it - a gem/bundler client issues HEADs to probe a file's size and existence.
            if (size >= 0) {
                exchange.setResponseHeader("Content-Length", Long.toString(size));
            }
            exchange.respond(200, -1L).close();
            return;
        }
        blobs.serve(located.get(), exchange);
    }

    private void info(String name, Blobs blobs, FormatExchange exchange) throws IOException {
        if (Keys.unsafe(name) || (!StoredListing.present(blobs.store(), RubyGemsListings.info(name))
                && blobs.isEmpty("rubygems/" + name + "/versions"))) {
            exchange.respond(404);      // a structural emptiness probe: nothing published under this gem
            return;
        }
        Optional<StoredListing.Served> served = StoredListing.open(blobs.store(),
                new RubyGemsListings(blobs).infoSpec(name));
        if (served.isEmpty()) {
            exchange.respond(404);
            return;
        }
        try (StoredListing.Served document = served.get()) {
            respondListing(document, exchange);
        }
    }

    /** Stream a stored listing with the cheap revalidation bundler relies on: the ETag is the stored document's
     *  digest, so a matching {@code If-None-Match} answers {@code 304} from the header alone. */
    private static void respondListing(StoredListing.Served document, FormatExchange exchange) throws IOException {
        Listings.serve(exchange, document, "text/plain; charset=utf-8");
    }

    /** The {@code .gem} pointer key a version's bytes serve from - the identity every version-enumerating gem surface
     *  judges an enumerated version by, so /info, the compact index and the download cannot drift apart. */
    static String gemKey(String name, String version) {
        return "rubygemfiles/" + name + "-" + version + ".gem";
    }

    /**
     * Serve the compact-index {@code /versions} document: the stored listing every push maintains, streamed as is
     * with the document's digest as its {@code ETag}, so a bundler that revalidates with a matching
     * {@code If-None-Match} gets a {@code 304} that reads neither the body nor any per-gem document. An empty local
     * compact index stays a {@code 404} (not a header-only {@code 200}), so a proxy repository falls through to the
     * upstream's full {@code /versions} rather than shadowing it - the contract {@code info()} and the other formats'
     * empty indexes follow, and the one a bundler needs (it selects the compact-index fetcher only when
     * {@code /versions} parses to a non-empty set), while {@code gem install} reaches straight for the per-gem
     * {@code /info}.
     */
    private void versions(Blobs blobs, FormatExchange exchange) throws IOException {
        Optional<StoredListing.Served> served = StoredListing.open(blobs.store(),
                new RubyGemsListings(blobs).versionsSpec());
        if (served.isEmpty()) {
            exchange.respond(404);
            return;
        }
        try (StoredListing.Served document = served.get()) {
            if (RubyGemsListings.empty(document.header())) {
                exchange.respond(404);   // no gem is published, or every one is held or marked: nothing to list
                return;
            }
            respondListing(document, exchange);
        }
    }

    /**
     * Proxy a RubyGems miss to the upstream compact index (rubygems.org). A {@code .gem} is immutable, so it is
     * fetched, cached and served; an info, versions or quick-spec document is streamed through - the gems are
     * addressed relative to the source, so the list needs no rewrite.
     *
     * <p><b>Streamed, and the word is load-bearing.</b> The compact-index {@code /versions} is the one enumeration
     * document in this product that is tens of megabytes - every gem the source has ever carried, one line per
     * version - and bundler waits for its first byte under {@code BUNDLE_TIMEOUT}, ten seconds by default. This
     * leg used to buffer it whole through {@code ProxyRelay.fetchFresh} before answering, so the first byte reached
     * the client only after the whole upstream body had, and on a loaded machine that was longer than bundler waits.
     * Measured 2026-09-12 in two full lanes of six: bundler dropped the connection about thirteen seconds in (the
     * server logged a {@code Broken pipe} on this leg), fell back to the legacy full index it keeps for sources
     * without a compact index, asked for {@code specs.4.8.gz} - which this leg does not serve, because no client
     * reaches for it while the compact index answers - and exited 17 on the 404. The proxy fetch metric said
     * {@code negative} four times and nothing about which of those two facts it was. Relayed through
     * {@link ProxyRelay#streamFresh} the first byte leaves as soon as the upstream's does, which is what the
     * streaming clause of {@link ProxyFormat.Fetcher} is for; the {@link ProxyRelay.Document} classification, the
     * conditional-request forwarding and the {@code 304} relay are the same as before, in the shared control flow.
     *
     * <p>The legacy index ({@code specs.4.8.gz}, {@code latest_specs.4.8.gz}, {@code prerelease_specs.4.8.gz}) is
     * deliberately still not proxied: a client only asks for it once the compact index has failed it, so serving it
     * would paper over the failure that matters rather than answer a need.
     */
    @Override
    public boolean pullThrough(FormatExchange exchange, ArtifactStore store, URI upstream,
                               ProxyFormat.Fetcher fetcher) throws IOException {
        String path = exchange.path();
        String rest = path.substring("/rubygems/".length());
        String root = upstream.toString();
        if (!root.endsWith("/")) {
            root += "/";
        }
        if (rest.startsWith("gems/") && rest.endsWith(".gem")) {
            String file = rest.substring("gems/".length());
            // Point-integrity: the RubyGems compact index publishes every version's SHA-256 as the `checksum:<hex>`
            // requirement of its /info/<gem> line, so a proxied .gem IS held to a digest its own ecosystem advertises -
            // the parity npm (dist.integrity), PyPI (#sha256), NuGet (packageHash), Cargo, conda, CocoaPods, Conan and
            // Hugging Face already have and this leg did not. Resolved on a miss only (once per gem, since the .gem is
            // then cached).
            // The /info/<gem> document is a SEPARATE fetch from the .gem below, so an index this repository could not
            // read is not "this mirror publishes no checksum for the version" and must not become an unverified fill
            //.
            URI target = URI.create(root + rest);
            ProxyRelay.Declared expected = gemChecksum(root, file, fetcher);
            if (!expected.readable()) {
                return ProxyRelay.unverifiable(target, expected);
            }
            // A .gem is an immutable artifact of unbounded size: stream it from the network straight into the
            // content-addressed store rather than buffering the whole body, then re-serve it locally.
            try (ProxyFormat.Download download = fetcher.download(target, Map.of()).orElse(null)) {
                if (download == null || download.status() != 200) {
                    return false;
                }
                if (!ProxyRelay.fill(new Blobs(store), "rubygemfiles/" + file, target, download.body(), expected)) {
                    return false;
                }
            }
            handle(exchange, store);
            return true;
        }
        if (rest.startsWith("info/") || rest.equals("versions")
                || (rest.startsWith(QUICK) && rest.endsWith(".gemspec.rz"))) {
            // The compact index (/versions, /info/<gem>) is an ENUMERATION - it is precisely what bundler resolves
            // against, /versions listing every gem the source carries and /info/<gem> every version of one, so an
            // absent one is the answer "this source has no such gem" and a fetch that never landed must not be dressed
            // as it. A /quick/Marshal.4.8/<gem>-<version>.gemspec.rz is PINNED: the client already fixed gem AND
            // version, nothing about resolution turns on its absence, and the contract's "not cached here, re-pull"
            // 404 stays right.
            ProxyRelay.Document document = rest.startsWith(QUICK)
                    ? ProxyRelay.Document.PINNED
                    : ProxyRelay.Document.ENUMERATION;
            // The shared streaming relay: the client's conditional-request validators go upstream so a 304-capable
            // bundler's revalidation reaches the origin, a 304 comes back bare, and a 200 streams from the first
            // byte - see the method javadoc for why the buffered twin was the wrong relay here.
            return ProxyRelay.streamFresh(fetcher, URI.create(root + rest), "application/octet-stream", exchange,
                    document);
        }
        return false;
    }

    /** The largest {@code /info/<gem>} document read to resolve a proxied gem's checksum. A compact-index line is one
     *  short line per version, so even a gem with thousands of versions stays far below this; a hostile upstream past
     *  it is a document this leg could not read through, so the fill is refused rather than downgraded to unverified -
     *  a bound must never be able to answer "this index declares no checksum" (the rule the rpm leg's index bound and
     *  go's archive-walk ceiling already follow). */
    private static final int MAX_INFO = 8 * 1024 * 1024;

    /**
     * The SHA-256 the upstream compact index declares for one {@code .gem}.
     * The index line is {@code <version> <deps>|checksum:<sha256>[,ruby:<constraint>]}, so the version is matched
     * exactly (never a prefix - {@code 1.0} must not take {@code 1.0.1}'s checksum) and the checksum is read out of
     * the requirement list the format itself writes on the publish side, so the two spellings cannot drift.
     *
     * <p>{@link ProxyRelay.Declared#NONE} - cache unverified, exactly as Maven serves a jar whose {@code .sha1} sibling
     * is missing - when the index <em>answered</em> and declares nothing: a filename off the
     * {@code <name>-<version>.gem} convention (which names no line at all), a {@code 404}/{@code 410} (an index this
     * mirror does not serve), no line for this version, or a line with no parseable {@code checksum:}.
     * {@linkplain ProxyRelay.Declared#unreadable Unreadable} when the index could not be read - a transport failure, a
     * refusing status, or a body past {@link #MAX_INFO}.
     */
    private static ProxyRelay.Declared gemChecksum(String root, String file, ProxyFormat.Fetcher fetcher)
            throws IOException {
        String[] coordinate = coordinate(file);
        if (coordinate == null) {
            return ProxyRelay.Declared.NONE;   // a filename off the <name>-<version>.gem convention names no line
        }
        URI index = URI.create(root + "info/" + coordinate[0]);
        ProxyRelay.Sidecar sidecar = ProxyRelay.declaring(fetcher, index, Map.of());
        if (!sidecar.answered()) {
            return sidecar.verdict();
        }
        if (sidecar.document().body().length > MAX_INFO) {
            return ProxyRelay.Declared.unreadable("the compact index at " + index + " exceeds the " + MAX_INFO
                    + "-byte read bound, so the version's checksum line could not be read");
        }
        for (String line : new String(sidecar.document().body(), StandardCharsets.UTF_8).split("\n")) {
            int space = line.indexOf(' ');
            if (space <= 0 || !line.substring(0, space).equals(coordinate[1])) {
                continue;
            }
            int bar = line.indexOf('|', space);
            if (bar < 0) {
                return ProxyRelay.Declared.NONE;
            }
            for (String requirement : line.substring(bar + 1).split(",")) {
                String trimmed = requirement.trim();
                if (!trimmed.startsWith("checksum:")) {
                    continue;
                }
                String hex = trimmed.substring("checksum:".length());
                if (hex.length() != 64) {
                    return ProxyRelay.Declared.NONE;
                }
                try {
                    return ProxyRelay.Declared.of("SHA-256", HexFormat.of().parseHex(hex));
                } catch (IllegalArgumentException malformed) {
                    return ProxyRelay.Declared.NONE;   // a malformed digest is an absent one, never a refused fetch
                }
            }
            return ProxyRelay.Declared.NONE;
        }
        return ProxyRelay.Declared.NONE;
    }

    /** The {@code <name>-<version>} a {@code .gem} filename carries, split at the rightmost {@code -} followed by a
     *  digit - the same rule {@link #describe} applies, so the proxy and the layout read one coordinate out of one
     *  filename. {@code null} when the filename does not follow the convention. */
    private static String[] coordinate(String file) {
        if (!file.endsWith(".gem")) {
            return null;
        }
        String stem = file.substring(0, file.length() - ".gem".length());
        for (int dash = stem.lastIndexOf('-'); dash > 0; dash = stem.lastIndexOf('-', dash - 1)) {
            if (dash + 1 < stem.length() && Character.isDigit(stem.charAt(dash + 1))) {
                return new String[]{stem.substring(0, dash), stem.substring(dash + 1)};
            }
        }
        return null;
    }

    private static String line(Spec spec, String checksum) {
        StringBuilder requirements = new StringBuilder("checksum:").append(checksum);
        String ruby = constraint(spec.ruby());
        if (ruby != null) {
            requirements.append(",ruby:").append(ruby);
        }
        List<String> deps = new ArrayList<>();
        for (Dependency dependency : spec.deps()) {
            String requirement = constraint(dependency.requirement());
            deps.add(dependency.name() + ":" + (requirement == null ? ">= 0" : requirement));
        }
        return spec.version() + " " + String.join(",", deps) + "|" + requirements;
    }

    /** The compact-index rendering of a requirement: {@code op version} pairs joined with {@code &}, or null when
     *  empty (the caller substitutes the {@code >= 0} default a bare dependency carries). */
    private static String constraint(List<Constraint> constraints) {
        if (constraints.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (Constraint c : constraints) {
            parts.add(c.op() + " " + c.version());
        }
        return String.join("&", parts);
    }

    /** A single {@code operator version} requirement pair; shared by the compact-index line and the quick spec. */
    record Constraint(String op, String version) {
    }

    /** A runtime dependency: a gem name and its version requirement. */
    record Dependency(String name, List<Constraint> requirement) {
    }

    /** The parsed gemspec fields both renderings need. */
    record Spec(String name, String version, List<Dependency> deps, List<Constraint> ruby) {
    }

    /** Whether a string carries any control character (below {@code 0x20}, so including {@code \n}, {@code \r},
     *  {@code \t}) - which a compact-index line, one entry per newline, must never let a dependency field smuggle. */
    private static boolean hasControlChar(String value) {
        return value.chars().anyMatch(c -> c < 0x20);
    }

    /** The gem members RubyGems signs, each beside a {@code <member>.sig}: the signature is the leaf key's over the
     *  member's bytes, the leaf and its chain the gemspec's {@code cert_chain}. */
    private static final List<String> SIGNED_MEMBERS = List.of("metadata.gz", "data.tar.gz", "checksums.yaml.gz");

    /**
     * A gem may carry its signer's X.509 chain in its gemspec and a signature per member beside it - optional here,
     * since most gems carry none, and read the way {@code gem install --trust-policy} reads it: each member's
     * signature by the chain's leaf, the chain to a certificate the deployment trusts. Beside it, optional for the
     * same reason, the Sigstore bundles rubygems.org publishes for a version pushed with attestations, kept under
     * {@link #attestationsKey} by a push that carried them or by the pull-through that fetched them, and served back
     * at {@code /rubygems/api/v1/attestations/<name>-<version>.json} as the upstream serves them.
     */
    @Override
    public List<ArtifactSignatures.Expectation> expects(String path) {
        return describe(path).map(described -> described.coordinate() != null).orElse(false)
                ? List.of(ArtifactSignatures.Expectation.optional(ArtifactSignatures.Scheme.X509_DETACHED),
                        ArtifactSignatures.Expectation.optional(ArtifactSignatures.Scheme.SIGSTORE_BUNDLE))
                : List.of();
    }

    /** The gem an attestations document is about: {@code /rubygems/api/v1/attestations/<stem>.json} covers
     *  {@code /rubygems/gems/<stem>.gem}, so a document that lands after its gem re-derives the gem's verdict. */
    @Override
    public Optional<String> covers(String path) {
        if (!path.startsWith(ATTESTATIONS_PATH) || !path.endsWith(".json")) {
            return Optional.empty();
        }
        String stem = path.substring(ATTESTATIONS_PATH.length(), path.length() - ".json".length());
        return stem.isEmpty() || stem.indexOf('/') >= 0 ? Optional.empty() : Optional.of("/rubygems/gems/" + stem + ".gem");
    }

    @Override
    public Optional<String> servingKey(String requestPath, ArtifactStore store) {
        if (requestPath.startsWith("/rubygems/gems/") && requestPath.endsWith(".gem")) {
            String stem = requestPath.substring("/rubygems/gems/".length(), requestPath.length() - ".gem".length());
            return stem.isEmpty() || stem.indexOf('/') >= 0 ? Optional.empty() : Optional.of("rubygemfiles/" + stem + ".gem");
        }
        return covers(requestPath).map(gem ->
                attestationsKey(gem.substring("/rubygems/gems/".length(), gem.length() - ".gem".length())));
    }

    @Override
    public List<ArtifactSignatures.Evidence> evidence(String path, ArtifactSignatures.Material material)
            throws IOException {
        if (expects(path).isEmpty()) {
            return List.of();
        }
        Optional<ArtifactSignatures.Signed> body = material.body();
        if (body.isEmpty()) {
            return List.of();
        }
        List<ArtifactSignatures.Evidence> evidence = new ArrayList<>();
        String stem = path.substring("/rubygems/gems/".length(), path.length() - ".gem".length());
        String attestations = ATTESTATIONS_PATH + stem + ".json";
        Optional<byte[]> bundles = material.sibling(attestations, ArtifactSignatures.Material.LARGEST_SIGNATURE)
                .filter(bounded -> !bounded.truncated())
                .map(PublishInterceptor.Content.Bounded::content);
        if (bundles.isPresent()) {
            JsonNode array;
            try {
                array = MAPPER.readTree(bundles.get());
            } catch (RuntimeException notJson) {
                array = null;
            }
            int index = 0;
            for (JsonNode bundle : array == null || !array.isArray() ? List.<JsonNode>of() : array) {
                if (bundle.isObject()) {
                    evidence.add(new ArtifactSignatures.Evidence(ArtifactSignatures.Scheme.SIGSTORE_BUNDLE,
                            MAPPER.writeValueAsBytes(bundle), body.get(), attestations + "#" + index));
                }
                index++;
            }
        }
        Map<String, byte[]> signatures = new LinkedHashMap<>();
        byte[] chain = null;
        try (InputStream gem = body.get().open()) {
            TarArchiveInputStream tar = new TarArchiveInputStream(gem, "UTF-8");
            for (TarArchiveEntry entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry()) {
                String name = entry.getName();
                if (name.endsWith(".sig") && SIGNED_MEMBERS.contains(name.substring(0, name.length() - 4))) {
                    signatures.put(name, ArchiveInflation.entry(tar, ArtifactSignatures.Material.LARGEST_SIGNATURE)
                            .required("gem", name));
                } else if (name.equals("metadata.gz")) {
                    byte[] metadata = ArchiveInflation.entry(tar, maxCompressedMetadata()).orNull();
                    if (metadata != null) {
                        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(metadata))) {
                            byte[] yaml = ArchiveInflation.entry(in, maxGemspecYaml()).orNull();
                            chain = yaml == null ? null : certChain(new String(yaml, StandardCharsets.UTF_8));
                        }
                    }
                }
            }
        }
        for (Map.Entry<String, byte[]> signature : signatures.entrySet()) {
            String member = signature.getKey().substring(0, signature.getKey().length() - 4);
            ArtifactSignatures.Signed source = body.get();
            evidence.add(new ArtifactSignatures.Evidence(ArtifactSignatures.Scheme.X509_DETACHED, signature.getValue(),
                    () -> member(source, member), path + "!" + signature.getKey(), chain));
        }
        return evidence;
    }

    /** One member's bytes streamed out of a fresh open of the gem, the tar closed with the stream. */
    private static InputStream member(ArtifactSignatures.Signed body, String name) throws IOException {
        InputStream gem = body.open();
        TarArchiveInputStream tar = new TarArchiveInputStream(gem, "UTF-8");
        for (TarArchiveEntry entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry()) {
            if (entry.getName().equals(name)) {
                return new FilterInputStream(tar) {
                    @Override
                    public void close() throws IOException {
                        tar.close();
                    }
                };
            }
        }
        tar.close();
        throw new FileNotFoundException("The gem carries a signature for " + name + " but no such member");
    }

    /** The gemspec's {@code cert_chain} as one PEM bundle, leaf first as RubyGems lists it, or {@code null}. */
    public static byte[] certChain(String yaml) {
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(RUBY_TAG.matcher(yaml).replaceAll(""));
        } catch (RuntimeException unreadable) {
            return null;
        }
        if (!(loaded instanceof Map<?, ?> root) || !(root.get("cert_chain") instanceof List<?> chain) || chain.isEmpty()) {
            return null;
        }
        StringBuilder pem = new StringBuilder();
        for (Object certificate : chain) {
            if (certificate instanceof String text && text.contains("-----BEGIN CERTIFICATE-----")) {
                pem.append(text.strip()).append('\n');
            }
        }
        return pem.isEmpty() ? null : pem.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /** Read the gzipped YAML gemspec from {@code metadata.gz} at the front of the gem's tar, using Commons Compress.
     *  Only the {@code metadata.gz} entry's bytes are pulled (bounded to that small member by the tar stream, then
     *  gunzipped) and the walk stops there, so the caller's reopened artifact stream is never drained whole. */
    static String gemspec(InputStream gem) throws IOException {
        TarArchiveInputStream tar = new TarArchiveInputStream(gem, "UTF-8");
        for (TarArchiveEntry entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry()) {
            if (entry.getName().equals("metadata.gz")) {
                // Both reads go through the product's one archive-inflation read, given RubyGems' own larger ceilings
                // explicitly because a gemspec legitimately carries the gem's whole description and file list. Neither
                // ever yields a prefix, so an over-ceiling member is "unindexable" and the publish is DECLINED - the
                // fail-closed disposition this format expresses as a decline rather than as an exception (nothing is
                // linked and nothing is served), never as "this gem declares nothing".
                byte[] metadata = ArchiveInflation.entry(tar, maxCompressedMetadata()).orNull();
                if (metadata == null) {
                    return null;   // an over-large metadata.gz member is not a spec this parses - treat as unindexable
                }
                try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(metadata))) {
                    byte[] yaml = ArchiveInflation.entry(in, maxGemspecYaml()).orNull();
                    return yaml == null ? null : new String(yaml, StandardCharsets.UTF_8);   // gunzip-bomb cap
                }
            }
        }
        return null;
    }

    /** The compressed {@code metadata.gz} member and its gunzipped YAML are both attacker-supplied, so neither is read
     *  whole: a member that would exceed its ceiling (a decompression bomb, or a tar entry declaring a vast size) is
     *  reported as unparsable rather than buffered, and the caller rejects the publish.
     *
     *  <p>These are RubyGems' own ceilings rather than the shared archive-inflation default, and so are passed
     *  explicitly to {@link build.jenesis.repository.store.ArchiveInflation#entry(InputStream, int)} at the call site
     *  that chose them (the clause's own escape hatch): a gemspec legitimately carries the gem's whole
     *  description and file list, which is why the {@code RubyGemsQualityInspector} states the identical reason for
     *  its {@code MAX_GEMSPEC}. What is not negotiable, and is what changed here, is that the ceiling is applied by
     *  the shared read - so it can never come back as a prefix, and reaching it is an outcome. */
    private static final int MAX_COMPRESSED_METADATA = 16 * 1024 * 1024;
    private static final int MAX_GEMSPEC_YAML = 8 * 1024 * 1024;

    /** The keys an operator moves the two ceilings above with - format-local, for the registry whose gems are
     *  legitimately larger than RubyGems' own conventions, and defaulting to the constants for everyone else. */
    private static final String MAX_COMPRESSED_METADATA_KEY = "jenreg.rubygems.compressed-metadata-bytes";
    private static final String MAX_GEMSPEC_YAML_KEY = "jenreg.rubygems.gemspec-yaml-bytes";

    private static int maxCompressedMetadata() {
        return Limits.positive(MAX_COMPRESSED_METADATA_KEY, MAX_COMPRESSED_METADATA);
    }

    private static int maxGemspecYaml() {
        return Limits.positive(MAX_GEMSPEC_YAML_KEY, MAX_GEMSPEC_YAML);
    }

    // Parse the gemspec YAML with SnakeYAML, stripping the Ruby object tags (see RUBY_TAG) so the SafeConstructor
    // loads it as plain maps and lists without instantiating anything.
    static Spec parse(String yaml) {
        if (yaml == null) {
            return null;
        }
        Object loaded = new Yaml(new SafeConstructor(new LoaderOptions()))
                .load(RUBY_TAG.matcher(yaml).replaceAll(""));
        if (!(loaded instanceof Map<?, ?> root)) {
            return null;
        }
        String name = string(root.get("name"));
        String version = root.get("version") instanceof Map<?, ?> holder ? string(holder.get("version")) : null;
        if (name == null || version == null) {
            return null;
        }
        List<Dependency> deps = new ArrayList<>();
        if (root.get("dependencies") instanceof List<?> dependencies) {
            for (Object dependency : dependencies) {
                if (dependency instanceof Map<?, ?> dep && ":runtime".equals(string(dep.get("type")))
                        && string(dep.get("name")) != null) {
                    deps.add(new Dependency(string(dep.get("name")), constraints(dep.get("requirement"))));
                }
            }
        }
        return new Spec(name, version, deps, constraints(root.get("required_ruby_version")));
    }

    /** The {@code [operator, version]} constraints of a {@code Gem::Requirement}'s {@code requirements} list. */
    private static List<Constraint> constraints(Object requirement) {
        List<Constraint> constraints = new ArrayList<>();
        if (requirement instanceof Map<?, ?> block && block.get("requirements") instanceof List<?> list) {
            for (Object pair : list) {
                if (pair instanceof List<?> tuple && tuple.size() == 2 && tuple.get(1) instanceof Map<?, ?> holder) {
                    constraints.add(new Constraint(string(tuple.get(0)), string(holder.get("version"))));
                }
            }
        }
        return constraints;
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }



    /** The migration-import capability (WSPI.2 (c)), delegated to the layout-only {@link RubyGemsImporter} - the format IS the
     *  discovered importer now (an {@code instanceof} capability), and the importer class stays as its delegate. */
    private final RubyGemsImporter importer = new RubyGemsImporter();

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
