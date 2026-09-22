package build.jenesis.repository.format.homebrew;

import module java.base;
import module tools.jackson.databind;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.store.PublishInterceptor;

import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.blobs.Keys;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Homebrew bottles: a bottle domain a {@code brew install} pours from.
 *
 * <p>A bottle is the built binary package - a gzipped tar of the installed files, one per platform tag - and
 * pointing a client here is one variable: {@code HOMEBREW_BOTTLE_DOMAIN=<base>/homebrew/<repo>}.
 *
 * <h2>What a bottle domain actually serves, measured</h2>
 *
 * <p>Homebrew's own bottles live on {@code ghcr.io} as OCI artifacts, and a formula's stable bottle block records
 * {@code root_url https://ghcr.io/v2/homebrew/core} with per-tag URLs of the form
 * {@code .../hello/blobs/sha256:<digest>}. It is natural to read that as "a bottle domain is an OCI registry", and
 * it is wrong: <b>that layout is used only when the domain is GitHub Packages itself.</b> Driven against a real
 * client with the domain pointed elsewhere, {@code brew} asks for
 *
 * <pre>{@code <domain>/hello-2.12.3.x86_64_linux.bottle.tar.gz}</pre>
 *
 * <p>a flat file named for the coordinate - and, when that answers {@code 404}, prints
 * <em>"Bottle missing, falling back to the default domain"</em> and fetches from {@code ghcr.io} instead. So this
 * format serves files, not an OCI layout, and needs none of the registry machinery the entry that scoped it
 * assumed. The measurement is recorded here because the assumption is the natural one and would cost a day.
 *
 * <h2>There is no enumeration surface, and that is the protocol</h2>
 *
 * <p>A client never asks a bottle domain what it holds: the <em>formula</em> - which lives in a tap, a git
 * repository this product deliberately does not serve - names the file, its checksum and its platform. So there is
 * no index to maintain, no listing to keep in step with a hold, and no document a publish has to re-decide. That
 * makes this the simplest format here, and the simplicity is the ecosystem's rather than a reduction of it.
 *
 * <p><b>What a hold means, and the one thing it cannot do.</b> Withholding a bottle makes this domain answer
 * {@code 404}, and for a privately built formula that is the end of it. For a bottle <em>mirrored</em> from
 * homebrew-core it is not: the client falls back to the default domain and installs from upstream. A hold here is
 * therefore a statement about what this repository serves, not a guarantee about what a client ends up with -
 * which is a property of the ecosystem's fallback, and is worth knowing before relying on it.
 */
public final class HomebrewFormat implements RepositoryFormat, ArtifactLayout, BlobLayout, ArtifactSignatures {

    /** The package-ecosystem name Homebrew coordinates report. */
    public static final String ECOSYSTEM = "Homebrew";

    /**
     * The attestations kept beside a bottle, at {@code <bottle>.attestations.json}: what GitHub's attestation store
     * answers for the bottle's digest ({@code {"attestations":[{"bundle":{...}},...]}}), or a bare array of bundles,
     * or one bundle. Homebrew-core attests every bottle its CI builds through GitHub Artifact Attestations, keyed by
     * the bottle's SHA-256, and a client never asks a bottle domain for it - so the document arrives here either
     * pushed beside the bottle by whoever mirrors it, or looked up after a publish by the signatures dimension's
     * attestation lookup where an operator switched it on. Read as the bottle's evidence, one bundle at a time.
     */
    public static final String ATTESTATIONS = ".attestations.json";

    private static final String PREFIX = "/homebrew/";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Override
    public String name() {
        return "homebrew";
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
        Blobs blobs = new Blobs(store);
        String[] segments = exchange.path().substring(PREFIX.length()).split("/");
        if (segments.length != 2 || Keys.unsafe(segments[0]) || Keys.unsafe(segments[1])) {
            exchange.respond(404);
            return;
        }
        String repo = segments[0], file = segments[1];
        // A bottle, or the attestations document kept beside one - published and served the same way, the
        // sidecar under the bottle's own name plus its suffix.
        boolean sidecar = file.endsWith(ATTESTATIONS);
        Optional<Bottle> bottle = Bottle.of(sidecar ? file.substring(0, file.length() - ATTESTATIONS.length()) : file);
        if (bottle.isEmpty()) {
            exchange.respond(404);
            return;
        }
        switch (exchange.method()) {
            case "PUT" -> push(exchange, blobs, repo, file);
            case "GET", "HEAD" -> serveBottle(exchange, blobs, repo, file,
                    sidecar ? "application/json" : "application/gzip");
            default -> exchange.respond(405);
        }
    }

    // ---- signatures ----

    /** A bottle may carry the attestations GitHub's store holds for it beside it; optional, since a privately built
     *  bottle has none and the store is asked only where an operator switched the lookup on. */
    @Override
    public List<ArtifactSignatures.Expectation> expects(String path) {
        return describe(path).map(described -> described.coordinate() != null).orElse(false)
                ? List.of(ArtifactSignatures.Expectation.optional(ArtifactSignatures.Scheme.SIGSTORE_BUNDLE))
                : List.of();
    }

    @Override
    public Optional<String> covers(String path) {
        if (!path.startsWith(PREFIX) || !path.endsWith(ATTESTATIONS)) {
            return Optional.empty();
        }
        String bottle = path.substring(0, path.length() - ATTESTATIONS.length());
        return describe(bottle).filter(described -> described.coordinate() != null).map(_ -> bottle);
    }

    /** The key a bottle or its attestations document serves from: this layout's pointer key is its request path
     *  without the leading slash, for the sidecar exactly as for the bottle. */
    @Override
    public Optional<String> servingKey(String requestPath, ArtifactStore store) {
        if (!requestPath.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String[] segments = requestPath.substring(PREFIX.length()).split("/");
        if (segments.length != 2 || Keys.unsafe(segments[0]) || Keys.unsafe(segments[1])) {
            return Optional.empty();
        }
        String file = segments[1];
        String subject = file.endsWith(ATTESTATIONS) ? file.substring(0, file.length() - ATTESTATIONS.length()) : file;
        return Bottle.of(subject).map(_ -> key(segments[0], file));
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
        String sidecar = path + ATTESTATIONS;
        Optional<byte[]> document = material.sibling(sidecar, ArtifactSignatures.Material.LARGEST_SIGNATURE)
                .filter(bounded -> !bounded.truncated())
                .map(PublishInterceptor.Content.Bounded::content);
        if (document.isEmpty()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(document.get());
        } catch (RuntimeException notJson) {
            return List.of();
        }
        List<JsonNode> bundles = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(element -> bundles.add(element.has("bundle") ? element.get("bundle") : element));
        } else if (root.has("attestations")) {
            root.get("attestations").forEach(element -> bundles.add(element.has("bundle") ? element.get("bundle") : element));
        } else if (root.has("mediaType")) {
            bundles.add(root);
        }
        List<ArtifactSignatures.Evidence> evidence = new ArrayList<>();
        int index = 0;
        for (JsonNode bundle : bundles) {
            if (bundle.isObject()) {
                evidence.add(new ArtifactSignatures.Evidence(ArtifactSignatures.Scheme.SIGSTORE_BUNDLE,
                        MAPPER.writeValueAsBytes(bundle), body.get(), sidecar + "#" + index));
            }
            index++;
        }
        return evidence;
    }

    /**
     * Publish a bottle.
     *
     * <p>The bytes stream into the content-addressed store and the file name is the coordinate, which is the whole
     * of the metadata this ecosystem puts anywhere this repository can see: a bottle is a tar of installed files
     * with no manifest, and what a client verifies it against - the checksum - lives in the formula, in a tap.
     */
    private void push(FormatExchange exchange, Blobs blobs, String repo, String file) throws IOException {
        String hash = blobs.store(exchange.requestStream());
        blobs.link(key(repo, file), hash);
        exchange.respond(201);
    }

    private void serveBottle(FormatExchange exchange, Blobs blobs, String repo, String file, String contentType)
            throws IOException {
        Optional<Blobs.Located> located = blobs.locate(key(repo, file));
        if (located.isEmpty()) {
            exchange.respond(404);
            return;
        }
        exchange.setResponseHeader("Content-Type", contentType);
        if (exchange.method().equals("HEAD")) {
            if (located.get().size() >= 0) {
                exchange.setResponseHeader("Content-Length", Long.toString(located.get().size()));
            }
            exchange.respond(200, -1L).close();
            return;
        }
        blobs.serve(located.get(), exchange);
    }

    private static String key(String repo, String file) {
        return "homebrew/" + repo + "/" + file;
    }

    // ---- layout ----

    @Override
    public Optional<ArtifactDescriptor> describe(String path) {
        if (!path.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String[] segments = path.substring(PREFIX.length()).split("/");
        if (segments.length != 2) {
            return Optional.empty();
        }
        return Bottle.of(segments[1])
                .<ArtifactDescriptor>map(bottle -> new ArtifactDescriptor(ECOSYSTEM, bottle.name(), bottle.version(),
                        path, "application/gzip", false, null, -1L))
                .or(() -> Optional.of(ArtifactDescriptor.at(ECOSYSTEM, path)));
    }

    @Override
    public List<String> paths(String coordinate, String version, ArtifactStore store) {
        // A bottle's pointer lives in the blobs namespace rather than under publish/, so the coordinate seam this
        // format really has is BlobLayout's - see blobKeys below.
        return List.of();
    }

    @Override
    public List<String> blobRoots() {
        return List.of("homebrew");
    }

    @Override
    public List<String> blobKeys(String coordinate, String version, ArtifactStore store) throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            return List.of();   // a traversal-shaped coordinate maps nowhere - these keys are what an eviction DELETES
        }
        // One coordinate is many bottles: a version is built per platform tag, and a rebuild adds another. The
        // file name carries all three, so the version's keys are every stored file that parses back to it.
        List<String> keys = new ArrayList<>();
        for (String repo : store.list("homebrew")) {
            for (String file : store.list("homebrew/" + repo)) {
                // The attestations document kept beside a bottle goes with the bottle: it is the version's too.
                String subject = file.endsWith(ATTESTATIONS)
                        ? file.substring(0, file.length() - ATTESTATIONS.length())
                        : file;
                Optional<Bottle> bottle = Bottle.of(subject);
                if (bottle.isPresent()
                        && bottle.get().name().equals(coordinate)
                        && bottle.get().version().equals(version)
                        && store.readVersioned(key(repo, file)).isPresent()) {
                    keys.add(key(repo, file));
                }
            }
        }
        return keys;
    }

    /**
     * {@inheritDoc}
     *
     * <p>This layout's pointer key <em>is</em> its served path without the leading slash - {@link #servedPaths}
     * composes one from the other - so the request-path describer is already the parse, and writing a second one
     * here would be two spellings of one grammar with nothing holding them together. The description is re-keyed to
     * the pointer, because what a repair rebuilding the inventory row holds is the key, not the request path.
     *
     * <p><b>Only when the description actually names a version.</b> The two describers have different contracts:
     * {@code describe} answers about any path this format serves and falls back to a coordinate-less descriptor for
     * the indexes and checksums beside the artifacts, while this one must answer <em>empty</em> for those - a
     * repair walking the blob root asks about every key it meets, and a present descriptor with no coordinate is
     * an absence dressed as a claim. The filter is what keeps the delegation honest.
     *
     * <p>{@code BlobLayoutCoordinateSeamTest} drives this over keys this layout really wrote and over the folders
     * above them, so both halves are checked rather than asserted: if the two shapes ever stop coinciding the round
     * trip names the wrong coordinate, and if the filter goes the parent of a pointer is claimed as one.
     */
    @Override
    public Optional<ArtifactDescriptor> describePointer(String key) {
        return describe("/" + key)
                .filter(described -> described.coordinate() != null && described.version() != null)
                .map(described -> described.withPath(key));
    }

    @Override
    public List<String> servedPaths(String coordinate, String version, ArtifactStore store) throws IOException {
        List<String> paths = new ArrayList<>();
        for (String key : blobKeys(coordinate, version, store)) {
            paths.add("/" + key);
        }
        return paths;
    }
}
