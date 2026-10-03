package build.jenesis.repository.format.gems;

import module java.base;
import module org.apache.commons.compress;
import module org.yaml.snakeyaml;
import module tools.jackson.databind;
import build.jenesis.repository.multipart.MultipartBody;
import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.format.PublishedExport;
import build.jenesis.repository.format.RepositoryExporter;
import build.jenesis.repository.store.PublishInterceptor;

import build.jenesis.repository.format.Listings;
import build.jenesis.repository.store.Limits;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.blobs.Keys;
import build.jenesis.repository.blobs.ProxyLeg;
import build.jenesis.repository.blobs.ProxyRelay;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.format.LifecycleMark;
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
import build.jenesis.repository.store.Withheld;

/**
 * The RubyGems format: {@code gem push}, {@code bundle install} and {@code gem install} over the same store, under
 * {@code /rubygems/...}. A push ({@code POST /rubygems/api/v1/gems}) reads the name, version, runtime dependencies and
 * Ruby constraint from the gem's {@code metadata.gz} gemspec (gzipped YAML in a tar, parsed with SnakeYAML) and stores
 * the gem under {@code rubygemfiles/<name>-<version>.gem} with a compact-index line under
 * {@code rubygems/<name>/versions/<version>}.
 *
 * <p>The compact index - {@code GET /rubygems/info/<name>} and {@code /rubygems/versions}, all {@code bundle install}
 * needs - is served from stored listings the push maintains. {@code gem install} also fetches the legacy
 * {@code /rubygems/quick/Marshal.4.8/<name>-<version>.gemspec.rz}, produced by {@link QuickSpec} at push. The gem is
 * served at {@code /rubygems/gems/<file>.gem}.
 */
public final class RubyGemsFormat implements RepositoryFormat, ProxyLeg, BlobLayout, RepositoryImporter.Delegating,
        ArtifactSignatures, RepositoryExporter {

    private static final String QUICK = "quick/Marshal.4.8/";




    // Ruby object tags (!ruby/object:Gem::Specification) are stripped so the SafeConstructor loads plain maps and
    // lists.
    private static final Pattern RUBY_TAG = Pattern.compile("!ruby/\\S+");

    @Override
    public String name() {
        return "rubygems";
    }

    /** The marks this format's clients see, each by its own word. */
    @Override
    public Map<LifecycleMark, String> lifecycleMarks() {
        return LifecycleMark.shown(LifecycleMark.YANKED);
    }

    @Override
    public String ecosystem() {
        return "RubyGems";
    }

    /** The coordinate version a stored RubyGems pointer serves, from which the inventory back-fill rebuilds a lost
     *  {@code published} record. Only {@code rubygems/<name>/versions/<version>} is decoded: the {@code rubygemfiles/}
     *  keys spell {@code <name>-<version>} and a name may contain a hyphen, so that split is ambiguous, and a wrong row
     *  would be aged by retention against a release never published. Every version has a versions pointer. Screened
     *  through {@link BlobLayout#addressable}, so a traversal-shaped key decodes to nothing. */
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
                "application/octet-stream", prerelease(version), null, 0L));
    }

    /**
     * Whether a gem version is a prerelease, as RubyGems decides it: its number holds a letter ({@code 7.1.0.rc1},
     * {@code 2.0.0.pre}). A platform gem's version carries its platform after a {@code -}
     * ({@code 1.16.0-x86_64-linux}), which is no part of the number and makes no prerelease: a platform gem is a
     * version of its own, released or not as its number says. The pointer and the download path decide it alike.
     */
    static boolean prerelease(String version) {
        int platform = version.indexOf('-');
        String number = platform < 0 ? version : version.substring(0, platform);
        return number.chars().anyMatch(Character::isLetter);
    }

    @Override
    public List<String> blobRoots() {
        // rubygemsindex is a blob root so a store still carrying a pointer to a precomputed compact-index body there
        // does not have the body collected under it.
        return List.of("rubygemfiles", "rubygems", "rubygemsindex");
    }

    @Override
    public List<String> blobKeys(String coordinate, String version, ArtifactStore store) throws IOException {
        // The .gem, its quick spec and its compact-index line, keyed by <name>-<version>.
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

    /** The request path this gem version serves at ({@code /rubygems/gems/<name>-<version>.gem}), where a retroactive
     *  hold links its {@code /quarantine} handle; the quick spec and the index line are not downloads. */
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

    /** The coordinate a gem path carries ({@code /rubygems/gems/<name>-<version>.gem}), split at the rightmost
     *  {@code -} followed by a digit, since a gem version starts with one and a name's segments conventionally do not.
     *  The compact index, the quick spec, the push endpoint and a filename with no version-looking suffix describe
     *  nothing, rather than a wrong coordinate. */
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
        String version = stem.substring(split + 1);
        return Optional.of(new ArtifactDescriptor("RubyGems", stem.substring(0, split), version,
                path, "application/octet-stream", prerelease(version), null, -1L));
    }

    // An original CC0 line glyph (a faceted gem).
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

    /** Where a proxied gem's Sigstore attestations are fetched: the base of an API answering
     *  {@code <base>/<name>-<version>.json} with an array of bundles, as rubygems.org does at
     *  {@code /api/v1/attestations/}. Empty by default, meaning that path under the upstream; a mirror without it
     *  answers 404, which is absence. */
    public static final String ATTESTATIONS_URL = "rubygems-attestations-url";

    /** The stored attestations of a gem version, beside the gem: the array rubygems.org answers, kept only when it
     *  names a bundle. */
    static String attestationsKey(String stem) {
        return "rubygemfiles/" + stem + ".attestations.json";
    }

    /** Where a version's attestations serve, the path rubygems.org serves them at. */
    private static final String ATTESTATIONS_PATH = "/rubygems/api/v1/attestations/";

    /** The reader of an attestations array. */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Whether a body is a JSON array naming an element; rubygems.org answers {@code []} for a version pushed without
     *  any. */
    private static boolean namesABundle(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8).strip();
        return text.startsWith("[") && text.endsWith("]") && !text.substring(1, text.length() - 1).isBlank();
    }

    /** The attestations rubygems.org publishes for a version and no client fetches, fetched beside the gem so the
     *  screen judges it by them, and kept through {@link #keep} under this format's own key. */
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

    /** A fetched attestations array is kept beside the gem when it names a bundle; an empty one is not provenance.
     *  Always {@code true}: what is not kept is dropped. */
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
        } else if (method.equals("DELETE") && rest.equals("api/v1/gems/yank")) {
            yank(exchange, blobs, store);
        } else if (!method.equals("GET") && !method.equals("HEAD")) {
            // Every route below reads; a write verb is a 405. The writes are the push and the yank, handled above.
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
            // The version's attestations as rubygems.org serves them, or a 404 where none were kept - never an empty
            // array, which would claim the upstream was asked.
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

    /** The republish policy for the hosted publish: {@code OVERWRITE}, since the coordinate lives in the gemspec the
     *  layout parses. A version already pushed is refused at the link ({@link Blobs#linkOnce}) inside the pointer's
     *  compare-and-set, with rubygems.org's {@code 409}; an identical re-push converges. */
    private static final Publication.Republish REPUBLISH = Publication.Republish.overwrite();

    /** Not edge-screened: a push is the bare {@code .gem} or a multipart form around it
     *  ({@code gem push --attestations}), and screening the form would not screen the gem clients download
     *  ({@code RepositoryFormat}'s envelope clause). The format screens at {@link #push}, over the gem's own bytes. */
    @Override
    public boolean screened() {
        return false;
    }

    /** The attestations a push carried, read only once the gem part is stored, since a client orders its parts as it
     *  likes. */
    @FunctionalInterface
    private interface Attestations {
        byte[] read() throws IOException;
    }

    /** The most a yank's form may carry: a gem name, a version and a platform. */
    private static final int YANK_FORM = 4096;

    /** {@code gem yank <name> -v <version> [--platform <platform>]}: {@code DELETE api/v1/gems/yank} with form fields.
     *  The yank is the product's lifecycle mark, through the path the console and API use
     *  ({@link Lifecycle#mark(FormatExchange, ArtifactStore, String, String, Lifecycle.Flag)}), so the version leaves
     *  the resolver's index whichever surface yanked it. Answered as rubygems.org does: {@code 200} with its sentence,
     *  {@code 404} for a version not held, {@code 422} for one already yanked. */
    private static void yank(FormatExchange exchange, Blobs blobs, ArtifactStore store) throws IOException {
        Map<String, String> form = form(exchange);
        String name = form.get("gem_name"), version = form.get("version"), platform = form.get("platform");
        if (name == null || version == null || name.isEmpty() || version.isEmpty() || Keys.unsafe(name)
                || Keys.unsafe(version) || (platform != null && Keys.unsafe(platform))) {
            exchange.respond(400, "Specify a gem name and a version to yank.".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String held = platform == null || platform.isEmpty() || platform.equals("ruby") ? version
                : version + "-" + platform;
        if (blobs.hash(gemKey(name, held)).isEmpty()) {
            exchange.respond(404, ("The version " + version + " does not exist.").getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (Lifecycle.read(store, name, held).filter(flag -> flag.state() == LifecycleMark.YANKED).isPresent()) {
            exchange.respond(422, ("The version " + version + " has already been yanked.")
                    .getBytes(StandardCharsets.UTF_8));
            return;
        }
        Lifecycle.mark(exchange, store, name, held, new Lifecycle.Flag(LifecycleMark.YANKED, ""));
        exchange.respond(200, ("Successfully deleted gem: " + name + " (" + held + ")")
                .getBytes(StandardCharsets.UTF_8));
    }

    /** The fields a yank sends, from its form body and, where a client puts them, its query string. */
    private static Map<String, String> form(FormatExchange exchange) throws IOException {
        Map<String, String> fields = new HashMap<>();
        for (String field : List.of("gem_name", "version", "platform")) {
            String value = exchange.queryParameter(field);
            if (value != null) {
                fields.put(field, value);
            }
        }
        byte[] body;
        try (InputStream in = exchange.requestStream()) {
            body = in.readNBytes(YANK_FORM);
        }
        for (String pair : new String(body, StandardCharsets.UTF_8).split("&")) {
            int split = pair.indexOf('=');
            if (split > 0) {
                fields.putIfAbsent(URLDecoder.decode(pair.substring(0, split), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8).strip());
            }
        }
        return fields;
    }

    /** A push is the raw {@code .gem}, or ({@code gem push --attestations}) a multipart form with the gem as its file
     *  part and an {@code attestations} field of Sigstore bundles, in either order. The bundles are kept beside the gem
     *  before the version is discoverable. */
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

    /** One small field of a push form: only {@code attestations} is read, bounded to the signature limit; an oversized
     *  one is not kept. */
    private static void field(MultipartBody.Part part, byte[][] attestations) throws IOException {
        if ("attestations".equals(part.name())) {
            attestations[0] = part.bytes(ArtifactSignatures.Material.LARGEST_SIGNATURE).orElse(null);
        }
    }

    /**
     * The {@code gem push} endpoint, through {@code Publication.commit}: the {@code .gem} streams into the store as the
     * accepted body, its SHA-256 the compact-index checksum, and the layout reopens only the stored gem's front to read
     * the gemspec ({@code metadata.gz} is the first tar entry).
     *
     * <p><b>The commit point is the {@code rubygemfiles/<name>-<version>.gem} pointer link.</b> Every write is a
     * declared step, so a failure fails the push rather than answering {@code 200} over a half-built index:
     * <ol>
     *   <li>the {@code .gem} pointer, first because it is where a version already pushed refuses this one;</li>
     *   <li>the quick spec and the attestations, reachable only once a client resolved the version;</li>
     *   <li>the compact-index line under {@code rubygems/<name>/versions/<version>}, which makes the version
     *       enumerable;</li>
     *   <li>the stored {@code /info/<name>} and, derived from it, the gem's line in {@code /versions}
     *       ({@link RubyGemsListings}), one rewrite of each.</li>
     * </ol>
     * So when a version becomes listable its quick spec already exists.
     *
     * <p><b>This is the format's screening choke point</b> ({@link #screened()}): the operation carries the discovered
     * chain and observers, which run over the gem's own bytes and are told the gem's coordinate, since the endpoint
     * names none.
     */
    private void push(InputStream body, Attestations attestations, Blobs blobs, FormatExchange exchange,
                      ArtifactStore store) throws IOException {
        try {
            commitPush(body, attestations, blobs, exchange, store);
        } catch (Publication.RepublishConflict taken) {
            exchange.respond(409, ("Repushing of gem versions is not allowed.\nPlease bump the version number and "
                    + "push a new gem.").getBytes(StandardCharsets.UTF_8));
        }
    }

    /** The push {@link #push} answers, a version already pushed aside. */
    private void commitPush(InputStream body, Attestations attestations, Blobs blobs, FormatExchange exchange,
                            ArtifactStore store) throws IOException {
        Publication.Commit commit = new Publication(store).commit(
                ArtifactDescriptor.at("RubyGems", exchange.path()), body, REPUBLISH,
                accepted -> {
                    Spec spec = pushed(store, accepted.hash());
                    if (spec == null) {
                        // No parseable gemspec, or a coordinate or dependency that would forge a key or index line:
                        // nothing is declared.
                        return Publication.Visibility.declined();
                    }
                    String versionKey = "rubygems/" + spec.name() + "/versions/" + spec.version();
                    byte[] bundles = attestations.read();
                    return Publication.Visibility
                            // The pointers live in this format's namespaces, so they are Serving steps. The .gem
                            // pointer comes first, where a version already pushed refuses this one, so nothing keyed by
                            // the version is written for a refused push.
                            .through((hash, size, _) ->
                                    blobs.linkRelease(gemKey(spec.name(), spec.version()), hash, size))
                            // The quick spec, precomputed so serving it is a streamed read.
                            .andThrough((_, _, _) -> blobs.write("rubygemfiles/" + spec.name() + "-" + spec.version()
                                    + ".gemspec.rz", QuickSpec.deflated(spec)))
                            // The attestations, kept before the listings so the version is never discoverable without
                            // them.
                            .andThrough((_, _, _) -> keepAttestations(blobs, spec, bundles))
                            // The index line's checksum is the content address the body was stored under.
                            .andThrough((hash, _, _) -> blobs.write(versionKey,
                                    line(spec, hash).getBytes(StandardCharsets.UTF_8)))
                            // The listings are maintained on the push: the line joins the gem's /info, which re-derives
                            // its /versions line.
                            .andThrough((hash, _, _) -> new RubyGemsListings(blobs).published(spec.name(),
                                    spec.version(), line(spec, hash).getBytes(StandardCharsets.UTF_8)))
                            // The push endpoint names no gem, so the observers are told which one this laid out.
                            .describing(described(spec));
                });
        switch (commit.disposition()) {
            case ACCEPT -> exchange.respond(commit.visible() ? 200 : 400);
            // Held: the layout is written behind the withhold marker (see held), so a review release is the marker
            // clear rather than a replay of a push whose envelope is gone.
            case QUARANTINE -> {
                held(attestations, blobs, store, exchange.path(), commit.hash());
                explain(exchange, 202, commit.explanation());
            }
            // Refused: nothing linked or marked; the stored blob is an unreferenced object the collector reclaims.
            case REJECT -> explain(exchange, 422, commit.explanation());
        }
    }

    /** The gem a stored push carries, parsed from the stored blob, or {@code null} when nothing servable can be
     *  derived: no parseable gemspec, a name or version that would forge a key, or a dependency carrying a control
     *  character. Shared by the accepted and held legs. Dependencies flow unescaped into the index line, one version
     *  per newline in {@code /info}, so a newline in one would inject a version line. */
    private Spec pushed(ArtifactStore store, String hash) throws IOException {
        Spec spec;
        try (InputStream stored = store.open("blobs/" + hash)) {
            spec = parse(gemspec(stored));
        }
        if (spec == null || Keys.unsafe(spec.name()) || Keys.unsafe(spec.version())) {
            return null;
        }
        for (Dependency dependency : spec.deps()) {
            String requirement = constraint(dependency.requirement());
            if (hasControlChar(dependency.name()) || (requirement != null && hasControlChar(requirement))) {
                return null;
            }
        }
        return spec;
    }

    /** Keep the attestations a push carried, unless they name no bundle. They are the version's own, so a re-push with
     *  others is refused. */
    private static void keepAttestations(Blobs blobs, Spec spec, byte[] bundles) throws IOException {
        if (bundles != null && namesABundle(bundles)) {
            blobs.writeRelease(attestationsKey(spec.name() + "-" + spec.version()), bundles);
        }
    }

    /** The descriptor a pushed gem is observed and held under: its own download path and coordinate. */
    private static ArtifactDescriptor described(Spec spec) {
        return new ArtifactDescriptor("RubyGems", spec.name(), spec.version(),
                "/rubygems/gems/" + spec.name() + "-" + spec.version() + ".gem", "application/octet-stream", false,
                null, -1L);
    }

    /**
     * Lay a held gem out behind its withhold marker, so its review release is a retroactive hold's marker clear; the
     * shared commit lays out only on {@code ACCEPT}.
     *
     * <p>The review pointer is re-keyed from the shared push endpoint onto the gem's download path: a second held push
     * would otherwise overwrite the first one's handle, and the reconcile backstop would lift the first gem's marker as
     * holderless, un-withholding it unreviewed. The marker retracts the hash before the pointer is linked, and the
     * stored {@code /info} and {@code /versions} leave a marked version out.
     */
    private void held(Attestations attestations, Blobs blobs, ArtifactStore store, String endpoint, String hash)
            throws IOException {
        Publication publication = new Publication(store, List.of(), List.of());
        Spec spec = pushed(store, hash);
        if (spec == null) {
            return;   // nothing servable to hold open; the hold stays reviewable by its stored blob alone
        }
        byte[] bundles = attestations.read();
        try {
            // A hold never replaces a released gem or its attestations: refused before the mark.
            blobs.refuseReplacement(gemKey(spec.name(), spec.version()), hash);
            if (bundles != null && namesABundle(bundles)) {
                blobs.refuseReplacement(attestationsKey(spec.name() + "-" + spec.version()), bundles);
            }
        } catch (Publication.RepublishConflict taken) {
            publication.unpublish("/quarantine" + endpoint);
            throw taken;
        }
        ArtifactDescriptor described = described(spec);
        Withheld.mark(store, hash, described);
        blobs.linkRelease(gemKey(spec.name(), spec.version()), hash, -1L);
        blobs.write("rubygemfiles/" + spec.name() + "-" + spec.version() + ".gemspec.rz", QuickSpec.deflated(spec));
        keepAttestations(blobs, spec, bundles);
        blobs.write("rubygems/" + spec.name() + "/versions/" + spec.version(),
                line(spec, hash).getBytes(StandardCharsets.UTF_8));
        new RubyGemsListings(blobs).published(spec.name(), spec.version(),
                line(spec, hash).getBytes(StandardCharsets.UTF_8));   // held: the stored documents keep it out
        publication.link("/quarantine" + described.path(), hash);
        publication.unpublish("/quarantine" + endpoint);
    }

    private void serveFile(String key, Blobs blobs, FormatExchange exchange) throws IOException {
        serveFile(key, blobs, exchange, "application/octet-stream");
    }

    private void serveFile(String key, Blobs blobs, FormatExchange exchange, String contentType) throws IOException {
        blobs.answer(key, exchange, contentType);
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

    /** Stream a stored listing with the revalidation bundler relies on: the ETag is the document's digest, so a
     *  matching {@code If-None-Match} answers {@code 304} from the header. */
    private static void respondListing(StoredListing.Served document, FormatExchange exchange) throws IOException {
        Listings.serve(exchange, document, "text/plain; charset=utf-8");
    }

    /** The {@code .gem} pointer key a version serves from, by which every enumerating surface judges a version. */
    static String gemKey(String name, String version) {
        return "rubygemfiles/" + name + "-" + version + ".gem";
    }

    /** Serve the compact-index {@code /versions}: the stored listing, streamed with its digest as {@code ETag}. An
     *  empty local index is a {@code 404}, not a header alone, so a proxy repository falls through to the upstream's:
     *  bundler uses the compact index only when {@code /versions} parses non-empty. */
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
     * Proxy a RubyGems miss to the upstream compact index. A {@code .gem} is immutable, so it is fetched, cached and
     * served; an info, versions or quick-spec document is streamed through, needing no rewrite.
     *
     * <p><b>Streamed, from the first byte.</b> {@code /versions} is tens of megabytes, and bundler waits for its first
     * byte only {@code BUNDLE_TIMEOUT}, ten seconds by default; buffered whole, the first byte could arrive later than
     * that, and bundler would drop the connection and fall back to the legacy full index.
     * {@link ProxyRelay#streamFresh} sends the first byte as the upstream does.
     *
     * <p>The legacy index ({@code specs.4.8.gz} and its siblings) is not proxied: a client asks for it only once the
     * compact index has failed, and serving it would hide that failure.
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
            // The compact index publishes each version's SHA-256 as the checksum:<hex> requirement of its /info/<gem>
            // line, so a proxied .gem is held to it. That is a separate fetch, and one that could not be read must not
            // become an unverified fill.
            URI target = URI.create(root + rest);
            ProxyRelay.Declared expected = gemChecksum(root, file, fetcher);
            if (!expected.readable()) {
                return ProxyRelay.unverifiable(target, expected);
            }
            // Streamed from the network into the content-addressed store, since a .gem is unbounded.
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
            // /versions and /info/<gem> are what bundler resolves against, an ENUMERATION; a quick spec is PINNED, the
            // gem and version already fixed, so its 404 means "re-pull".
            ProxyRelay.Document document = rest.startsWith(QUICK)
                    ? ProxyRelay.Document.PINNED
                    : ProxyRelay.Document.ENUMERATION;
            // The shared streaming relay, with validators forwarded and a 304 relayed bare.
            return ProxyRelay.streamFresh(fetcher, URI.create(root + rest), "application/octet-stream", exchange,
                    document);
        }
        return false;
    }

    /** The largest {@code /info/<gem>} read to resolve a proxied gem's checksum, far past a gem of thousands of
     *  versions. A body past it could not be read, so the fill is refused rather than downgraded: a bound never answers
     *  "declares no checksum". */
    private static final int MAX_INFO = 8 * 1024 * 1024;

    /**
     * The SHA-256 the upstream compact index declares for one {@code .gem}. The line is
     * {@code <version> <deps>|checksum:<sha256>[,ruby:<constraint>]}: the version matched exactly, never as a prefix,
     * and the checksum read from the requirement list the publish side writes.
     *
     * <p>{@link ProxyRelay.Declared#NONE}, cached unverified, when the index answered and declares nothing: a filename
     * off the {@code <name>-<version>.gem} convention, a {@code 404}/{@code 410}, no line for the version, or no
     * parseable {@code checksum:}. {@linkplain ProxyRelay.Declared#unreadable Unreadable} on a transport failure, a
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

    /** The {@code <name>-<version>} a {@code .gem} filename carries, by {@link #describe}'s rule; {@code null} when it
     *  does not follow the convention. */
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

    /** The compact-index rendering of a requirement, {@code op version} pairs joined with {@code &}, or null when empty
     *  (the caller writes the {@code >= 0} default). */
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

    /** Whether a string carries a control character, which a dependency field must never smuggle into a
     *  newline-separated index line. */
    private static boolean hasControlChar(String value) {
        return value.chars().anyMatch(c -> c < 0x20);
    }

    /** The gem members RubyGems signs, each beside a {@code <member>.sig} made by the leaf key of the gemspec's
     *  {@code cert_chain}. */
    private static final List<String> SIGNED_MEMBERS = List.of("metadata.gz", "data.tar.gz", "checksums.yaml.gz");

    /** A gem may carry its signer's X.509 chain in its gemspec and a signature per member, optional and read as
     *  {@code gem install --trust-policy} reads it: each signature by the chain's leaf, the chain to a trusted
     *  certificate. Beside it, also optional, the Sigstore bundles rubygems.org publishes, kept under
     *  {@link #attestationsKey} by a push or the pull-through and served at
     *  {@code /rubygems/api/v1/attestations/<name>-<version>.json}. */
    @Override
    public List<ArtifactSignatures.Expectation> expects(String path) {
        return describe(path).map(described -> described.coordinate() != null).orElse(false)
                ? List.of(ArtifactSignatures.Expectation.optional(ArtifactSignatures.Scheme.X509_DETACHED),
                        ArtifactSignatures.Expectation.optional(ArtifactSignatures.Scheme.SIGSTORE_BUNDLE))
                : List.of();
    }

    /** The signature rides beside the artifact, where it is already visible, not inside its bytes. */
    @Override
    public boolean embedsEvidence(String path) {
        return false;
    }

    /** The gem an attestations document covers: {@code /rubygems/api/v1/attestations/<stem>.json} covers
     *  {@code /rubygems/gems/<stem>.gem}, so a document landing after its gem re-derives the gem's verdict. */
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

    /** Read the gzipped YAML gemspec from {@code metadata.gz} at the front of the gem's tar; only that member is
     *  pulled, so the caller's stream is never drained whole. */
    static String gemspec(InputStream gem) throws IOException {
        TarArchiveInputStream tar = new TarArchiveInputStream(gem, "UTF-8");
        for (TarArchiveEntry entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry()) {
            if (entry.getName().equals("metadata.gz")) {
                // Both reads use the shared inflation read with RubyGems' larger ceilings; neither yields a prefix, so
                // an over-ceiling member declines the publish, never "this gem declares nothing".
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

    /** The ceilings on the compressed {@code metadata.gz} and its gunzipped YAML, both attacker-supplied: a member past
     *  one is unparsable and the publish is refused. They are RubyGems' own rather than the shared default, passed to
     *  {@link build.jenesis.repository.store.ArchiveInflation#entry(InputStream, int)}, since a gemspec carries the
     *  gem's whole description and file list; the shared read still never answers a prefix. */
    private static final int MAX_COMPRESSED_METADATA = 16 * 1024 * 1024;
    private static final int MAX_GEMSPEC_YAML = 8 * 1024 * 1024;

    /** The keys an operator moves the two ceilings with, for a registry whose gems are larger than RubyGems'
     *  conventions. */
    private static final String MAX_COMPRESSED_METADATA_KEY = "jenrepo.rubygems.compressed-metadata-bytes";
    private static final String MAX_GEMSPEC_YAML_KEY = "jenrepo.rubygems.gemspec-yaml-bytes";

    private static int maxCompressedMetadata() {
        return Limits.positive(MAX_COMPRESSED_METADATA_KEY, MAX_COMPRESSED_METADATA);
    }

    private static int maxGemspecYaml() {
        return Limits.positive(MAX_GEMSPEC_YAML_KEY, MAX_GEMSPEC_YAML);
    }

    // SnakeYAML with the Ruby tags stripped (RUBY_TAG), so the SafeConstructor instantiates nothing.
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



    /** The migration-import capability, delegated to {@link RubyGemsImporter}. */
    private final RubyGemsImporter importer = new RubyGemsImporter();

    @Override
    public RepositoryImporter importer() {
        return importer;
    }

    /** The version's {@code .gem} is pushed as {@code gem push} does - posted to {@code api/v1/gems} with the key as
     *  the bare {@code Authorization} value - unless {@code gems/<name>-<version>.gem} already answers. Signatures
     *  travel inside the gem. */
    @Override
    public Exported export(ArtifactStore repository, String coordinate, String version, ExportTarget target)
            throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            return Exported.WITHHELD;
        }
        Blobs blobs = new Blobs(repository);
        String file = coordinate + "-" + version + ".gem";
        Optional<Blobs.Located> located = blobs.locate("rubygemfiles/" + file);
        if (located.isEmpty()) {
            return Exported.WITHHELD;
        }
        String hash = located.get().hash();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/octet-stream");
        target.credential().ifPresent(credential -> headers.put("Authorization", credential.secret()));
        return PublishedExport.send(List.of(new PublishedExport.File(new ExportTarget.Request("POST", "api/v1/gems",
                headers, ExportTarget.Body.of(located.get().size(), () -> blobs.open(hash))),
                Optional.of("gems/" + file), hash)), target);
    }
}
