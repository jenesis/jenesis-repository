package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.format.testkit.FormatContract;
import build.jenesis.repository.format.testkit.GeneratedBody;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The Conan (C/C++) v2 registry's leg of the shared contract. Conan carries the whole contract bar the coordinate seam:
 * the client computes its own recipe and package revisions and {@code PUT}s each file to that revision's path, so an
 * upload is opaque bytes and the kit's own publish leg applies verbatim; a revision's {@code files} listing is both the
 * generated document and the enumeration surface; and its proxy leg holds a fetched revision file to the MD5 the
 * revision's own {@code conanmanifest.txt} records for it.
 *
 * <p><b>Two enumeration surfaces, one hold.</b> A Conan version discloses its content twice - the recipe revision's
 * {@code files} listing and, under it, the package revision's own {@code files} listing - and a hold is placed on the
 * {@code (name, version)} coordinate, which spans every revision. Both must clear, or a {@code conan install} still
 * sees a {@code conan_package.tgz} whose download now {@code 404}s. The recipe's {@code latest}/{@code revisions}
 * surfaces enumerate revision <em>ids</em>, which map to no single blob, so they carry no per-entry screen and are not
 * probed here.
 */
final class ConanFormatFixture implements EcosystemFormatFixture {

    private static final String REGISTRY = "contract";
    private static final String NAME = "contract-lib";
    private static final String VERSION = "1.0.0";
    /** A reference with no user/channel, which Conan spells with its {@code _} placeholder for both. */
    private static final String REFERENCE = NAME + "/" + VERSION + "/_/_";
    private static final String RREV = "e4e1703f72ed07c15d73a555ec3a2fa1";
    private static final String PID = "da39a3ee5e6b4b0d3255bfef95601890afd80709";
    private static final String PREV = "9a5b6c7d8e9f0a1b2c3d4e5f60718293";

    private static final String RECIPE = "/conan/" + REGISTRY + "/v2/conans/" + REFERENCE + "/revisions/" + RREV;
    private static final String RECIPE_FILES = RECIPE + "/files";
    private static final String PACKAGE_FILES =
            RECIPE + "/packages/" + PID + "/revisions/" + PREV + "/files";
    private static final URI ROOT = URI.create("https://center.invalid");

    /** The proxied revision, deliberately one nothing local publishes, so the pull-through leg is a real local miss. */
    private static final String PROXIED_RREV = "aabbccddeeff00112233445566778899";
    private static final String PROXIED = "/conan/" + REGISTRY + "/v2/conans/proxied/9.9.9/_/_/revisions/"
            + PROXIED_RREV + "/files/conan_export.tgz";

    private RepositoryFormat serving;

    @Override
    public String format() {
        return "conan";
    }

    @Override
    public Signatures signatures() {
        return Signatures.none("a Conan package is pinned by conanmanifest.txt, a per-file MD5 manifest the client verifies. "
                + "Signing exists only as an optional third-party hook, not as part of the protocol this serves.");
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.format.conan.ConanFormat";
    }

    @Override
    public RepositoryFormat serving() {
        if (serving == null) {
            serving = EcosystemFormatFixture.super.serving();
        }
        return serving;
    }

    @Override
    public List<String> namespaces() {
        return List.of("conan", "blobs");
    }

    @Override
    public Published publish(ArtifactStore store, byte[] body) throws IOException {
        put(store, RECIPE_FILES + "/conan_export.tgz", body);
        return new Published(RECIPE_FILES + "/conan_export.tgz", Packages.sha256(body));
    }

    @Override
    public Seeded seed(ArtifactStore store) throws IOException {
        put(store, RECIPE_FILES + "/conan_export.tgz", "seeded recipe export".getBytes(StandardCharsets.UTF_8));
        return new Seeded(NAME, VERSION, RECIPE_FILES + "/conan_export.tgz");
    }

    @Override
    public String probe(String vector) {
        // The recipe name is the first client-supplied element of every /v2/conans/ path and becomes the
        // conan/<repo>/r/<name>/... key verbatim, so that is where the vector goes, with a well-formed
        // <version>/<user>/<channel>/revisions/<rrev>/files/<file> tail after it - the shape that really reaches the
        // file PUT rather than one the route table turns away first.
        return "/conan/" + REGISTRY + "/v2/conans/" + vector + "/" + VERSION + "/_/_/revisions/" + RREV
                + "/files/conanfile.py";
    }

    @Override
    public Optional<Enumerated> enumerated(ArtifactStore store) throws IOException {
        // A whole recipe revision plus one of its package revisions, as `conan upload` lays them down: each file
        // carries distinct bytes, so the hold's per-content-hash markers name six distinct blobs rather than colliding.
        put(store, RECIPE_FILES + "/conanfile.py", "from conan import ConanFile\n".getBytes(StandardCharsets.UTF_8));
        put(store, RECIPE_FILES + "/conan_export.tgz", "the exported recipe sources".getBytes(StandardCharsets.UTF_8));
        put(store, RECIPE_FILES + "/conanmanifest.txt", "1700000000\nconanfile.py: 0\n"
                .getBytes(StandardCharsets.UTF_8));
        put(store, PACKAGE_FILES + "/conaninfo.txt", "[settings]\n    os=Linux\n".getBytes(StandardCharsets.UTF_8));
        put(store, PACKAGE_FILES + "/conan_package.tgz", "the built binary".getBytes(StandardCharsets.UTF_8));
        put(store, PACKAGE_FILES + "/conanmanifest.txt", "1700000000\nconan_package.tgz: 0\n"
                .getBytes(StandardCharsets.UTF_8));
        return Optional.of(new Enumerated(PACKAGE_FILES + "/conan_package.tgz",
                List.of(new Probe(RECIPE_FILES, "conan_export.tgz"),
                        new Probe(PACKAGE_FILES, "conan_package.tgz")),
                target -> hold(target, NAME, VERSION)));
    }

    @Override
    public Optional<Index> index(ArtifactStore store) throws IOException {
        put(store, RECIPE_FILES + "/conanfile.py", "from conan import ConanFile\n".getBytes(StandardCharsets.UTF_8));
        return Optional.of(new Index(RECIPE_FILES,
                target -> put(target, RECIPE_FILES + "/conanmanifest.txt",
                        "1700000000\nconanfile.py: 0\n".getBytes(StandardCharsets.UTF_8))));
    }

    @Override
    public Optional<Upstream> upstream(GeneratedBody body) {
        return Optional.of(new Upstream(PROXIED, ROOT, fetcher(body, body.digest("MD5"))));
    }

    @Override
    public Optional<Upstream> tampered(GeneratedBody body) {
        // The revision's own manifest declares an MD5 the file it sits beside does not hash to - a file substituted
        // between the manifest and the download. Nothing may be linked, and the local 404 must stand.
        return Optional.of(new Upstream(PROXIED, ROOT, fetcher(body, "0".repeat(32))));
    }

    @Override
    public Map<FormatContract.Property, String> unsupported() {
        return Map.of(
                FormatContract.Property.PROXY_REFUSAL_IS_NOT_AN_ABSENCE, "audited 2026-08-24: the revisions listings are ENUMERATIONs already refused as 502s. Each file of a "
                        + "revision is named by the files document the client just read, so a miss is a broken revision it reports "
                        + "rather than a fact it resolves around - conanmanifest.txt included, which is read for verification and "
                        + "whose absence is a failure, not a default. ",
               FormatContract.Property.COORDINATE_TRAVERSAL_REFUSED,
                "ConanFormat DOES implement ArtifactLayout, but only for ecosystem()/describe(): paths() answers empty "
                        + "by design, because a revision file's pointer lives in the blobs namespace "
                        + "(conan/<repo>/r/<name>/<version>/<user>/<channel>/<rrev>/files/<file>) rather than under "
                        + "publish/, and the client-computed revisions that key it are not derivable from the "
                        + "<name, version> coordinate at all. The kit's leg would therefore fail its own non-vacuity "
                        + "check rather than prove anything. The seam this format really has is "
                        + "BlobLayout - blobKeys pages the whole fixed-depth user/channel/rrev[/pkg/pid/prev]/files "
                        + "subtree of the version, which is what an eviction deletes - and it is proven over the same "
                        + "hostile coordinates by BlobLayoutCoordinateSeamTest in this module; the request seam is "
                        + "covered by REQUEST_PATH_TRAVERSAL_REFUSED");
    }

    /** An upstream Conan server answering the revision's {@code conanmanifest.txt} (the {@code <path>: <md5>} sidecar
     *  every revision carries) and the file itself, streamed. */
    private static ProxyFormat.Fetcher fetcher(GeneratedBody body, String md5) {
        String directory = ROOT + "/v2/conans/proxied/9.9.9/_/_/revisions/" + PROXIED_RREV + "/files/";
        String manifest = directory + "conanmanifest.txt";
        String artifact = directory + "conan_export.tgz";
        byte[] document = ("1700000000\nconan_export.tgz: " + md5 + "\n").getBytes(StandardCharsets.UTF_8);
        return new ProxyFormat.Fetcher.Buffered() {

            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) {
                return url.toString().equals(manifest)
                        ? Optional.of(new ProxyFormat.Fetched(200, document, Map.of()))
                        : Optional.of(new ProxyFormat.Fetched(404, new byte[0], Map.of()));
            }

            @Override
            public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders) {
                return url.toString().equals(artifact)
                        ? Optional.of(new ProxyFormat.Download(200, body.open(), Map.of()))
                        : Optional.of(new ProxyFormat.Download(404, InputStream.nullInputStream(), Map.of()));
            }
        };
    }

    private void put(ArtifactStore store, String path, byte[] body) throws IOException {
        seed(store, ContractExchange.of("PUT", path, body), 201);
    }
}
