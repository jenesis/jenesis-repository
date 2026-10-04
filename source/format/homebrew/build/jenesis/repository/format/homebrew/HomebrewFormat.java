package build.jenesis.repository.format.homebrew;

import module java.base;
import module tools.jackson.databind;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.store.PublishInterceptor;

import build.jenesis.repository.blobs.BlobExport;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.blobs.PathKeyedBlobLayout;
import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.format.RepositoryExporter;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.blobs.Keys;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;

/**
 * Homebrew bottles: a bottle domain a {@code brew install} pours from, reached with
 * {@code HOMEBREW_BOTTLE_DOMAIN=<base>/homebrew/<repo>}. A bottle is a gzipped tar of a formula's installed files, one
 * per platform tag.
 *
 * <h2>A bottle domain serves flat files</h2>
 *
 * <p>Homebrew's own bottles are OCI artifacts on {@code ghcr.io}, but that layout is used only when the domain is
 * GitHub Packages itself. With the domain pointed elsewhere, {@code brew} asks for
 *
 * <pre>{@code <domain>/hello-2.12.3.x86_64_linux.bottle.tar.gz}</pre>
 *
 * <p>a flat file named for the coordinate, and on a {@code 404} prints <em>"Bottle missing, falling back to the default
 * domain"</em> and fetches from {@code ghcr.io}. So this format serves files, not an OCI layout.
 *
 * <h2>No enumeration surface</h2>
 *
 * <p>A client never asks a bottle domain what it holds: the formula, in a tap this product does not serve, names the
 * file, its checksum and its platform. So there is no index and no listing to keep in step with a hold.
 *
 * <p><b>What a hold cannot do.</b> A withheld bottle answers {@code 404}, which ends it for a privately built formula;
 * for a bottle mirrored from homebrew-core the client falls back to the default domain and installs from upstream. A
 * hold is a statement about what this repository serves, not what a client ends up with.
 */
public final class HomebrewFormat implements RepositoryFormat, ArtifactLayout, PathKeyedBlobLayout, ArtifactSignatures,
        RepositoryExporter, RepositoryImporter.Delegating {

    /** The migration-import capability, delegated to {@link HomebrewImporter}. */
    private final HomebrewImporter importer = new HomebrewImporter();

    @Override
    public RepositoryImporter importer() {
        return importer;
    }

    /** The package-ecosystem name Homebrew coordinates report. */
    public static final String ECOSYSTEM = "Homebrew";

    /** The attestations kept beside a bottle, at {@code <bottle>.attestations.json}: what GitHub's attestation store
     *  answers for the bottle's digest ({@code {"attestations":[{"bundle":{...}},...]}}), a bare array of bundles, or
     *  one bundle. Homebrew-core attests every bottle its CI builds, keyed by SHA-256, and no client asks a bottle
     *  domain for it, so the document arrives pushed beside the bottle by a mirror, or looked up after a publish by the
     *  signatures dimension where an operator switched that on. Read as the bottle's evidence, one bundle at a time. */
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
        // A bottle, or the attestations document beside one, under the bottle's name plus its suffix.
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

    // ---- signatures

    /** A bottle may carry its attestations beside it; optional, since a privately built bottle has none. */
    @Override
    public List<ArtifactSignatures.Expectation> expects(String path) {
        return describe(path).map(described -> described.coordinate() != null).orElse(false)
                ? List.of(ArtifactSignatures.Expectation.optional(ArtifactSignatures.Scheme.SIGSTORE_BUNDLE))
                : List.of();
    }

    /** The signature rides beside the artifact, where it is already visible, not inside its bytes. */
    @Override
    public boolean embedsEvidence(String path) {
        return false;
    }

    @Override
    public Optional<String> covers(String path) {
        if (!path.startsWith(PREFIX) || !path.endsWith(ATTESTATIONS)) {
            return Optional.empty();
        }
        String bottle = path.substring(0, path.length() - ATTESTATIONS.length());
        return describe(bottle).filter(described -> described.coordinate() != null).map(_ -> bottle);
    }

    /** The key a bottle or its attestations document serves from: the request path without its leading slash. */
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

    /** Publish a bottle: the bytes stream into the store, and the file name is the coordinate - all the metadata this
     *  ecosystem puts where this repository can see, since a bottle has no manifest and its checksum lives in the
     *  formula. */
    private void push(FormatExchange exchange, Blobs blobs, String repo, String file) throws IOException {
        String hash = blobs.store(exchange.requestStream());
        try {
            blobs.linkRelease(key(repo, file), hash, -1L);
        } catch (Publication.RepublishConflict taken) {
            exchange.respond(409, Blobs.alreadyPublished(file));
            return;
        }
        exchange.respond(201);
    }

    private void serveBottle(FormatExchange exchange, Blobs blobs, String repo, String file, String contentType)
            throws IOException {
        blobs.answer(key(repo, file), exchange, contentType);
    }

    private static String key(String repo, String file) {
        return "homebrew/" + repo + "/" + file;
    }

    // ---- layout

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
        // A bottle's pointer lives in the blobs namespace, so its coordinate seam is BlobLayout's (blobKeys below).
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
        // One coordinate is many bottles - one per platform tag, plus rebuilds - so its keys are every stored file that
        // parses back to it.
        List<String> keys = new ArrayList<>();
        for (String repo : store.list("homebrew")) {
            for (String file : store.list("homebrew/" + repo)) {
                // The attestations beside a bottle belong to the version too.
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

    /** Each bottle of the version is put at its path, its attestations document after it. */
    @Override
    public Exported export(ArtifactStore repository, String coordinate, String version, ExportTarget target)
            throws IOException {
        return BlobExport.put(repository, mount(), blobKeys(coordinate, version, repository), target);
    }
}
