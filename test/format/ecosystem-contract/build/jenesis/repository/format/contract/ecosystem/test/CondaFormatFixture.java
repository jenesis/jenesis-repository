package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.format.testkit.FormatContract;
import build.jenesis.repository.format.testkit.GeneratedBody;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The Conda channel's leg of the shared contract. A push reads the {@code info/index.json} out of the uploaded archive
 * (and refuses a package whose embedded name disagrees with its filename), so the artifact is not opaque to the publish
 * protocol and the two publish properties run over a real package through {@link PackagedArtifactContract}.
 *
 * <p><b>Two container shapes, one hold.</b> Conda ships the legacy bzip2 {@code .tar.bz2} and the modern zip
 * {@code .conda}, and the generated {@code repodata.json} buckets them into two <em>different</em> maps
 * ({@code packages} and {@code packages.conda}). A hold is placed on a {@code (name, version)} coordinate, not on a
 * file, so the withhold leg publishes one build in each container and requires the version to leave <em>both</em>
 * buckets - a screen applied to only one map would still look green over a single-container fixture.
 *
 * <p>The {@code .bz2} variant of the index is deliberately not a probe: it renders the same screened record list through
 * the same method, compressed, so its bytes carry no searchable token and probing it would assert nothing the
 * {@code repodata.json} probe does not already prove.
 */
final class CondaFormatFixture implements EcosystemFormatFixture {

    private static final String CHANNEL = "contract";
    private static final String SUBDIR = "linux-64";
    private static final String PACKAGE = "contract-lib";
    private static final String BASE = "/conda/" + CHANNEL + "/" + SUBDIR + "/";
    private static final String REPODATA = BASE + "repodata.json";
    private static final URI ROOT = URI.create("https://conda.invalid/");

    /** The proxied package, deliberately one nothing local publishes, so the pull-through leg is a real local miss. */
    private static final String PROXIED_FILE = "proxied-lib-9.9.9-py311_0.tar.bz2";
    private static final String PROXIED = BASE + PROXIED_FILE;

    private RepositoryFormat serving;

    @Override
    public String format() {
        return "conda";
    }

    @Override
    public Signatures signatures() {
        return Signatures.none("repodata.json carries a per-package sha256 and nothing else. conda-content-trust defines signed "
                + "metadata but is not used by the channels this proxies, so there is no signature to read.");
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.format.conda.CondaFormat";
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
        return List.of("conda", "blobs");
    }

    @Override
    public Published publish(ArtifactStore store, byte[] body) {
        throw new AssertionError("conda: a package push reopens the just-stored archive and reads the "
                + "info/index.json inside it (refusing one whose name disagrees with the filename), so an arbitrary "
                + "byte body is not a publishable artifact here. This fixture publishes a real .tar.bz2 through "
                + "publishPackage() and runs the same two properties through PackagedArtifactContract.");
    }

    @Override
    public Optional<Packaged> publishPackage(ArtifactStore store) throws IOException {
        byte[] artifact = Packages.condaTarBz2(PACKAGE, "1.0.0", "py311_0");
        put(store, tarBz2("1.0.0", "py311_0"), artifact);
        return Optional.of(new Packaged(artifact, BASE + tarBz2("1.0.0", "py311_0"), Packages.sha256(artifact)));
    }

    @Override
    public Seeded seed(ArtifactStore store) throws IOException {
        put(store, tarBz2("1.0.0", "py311_0"), Packages.condaTarBz2(PACKAGE, "1.0.0", "py311_0"));
        return new Seeded(PACKAGE, "1.0.0", BASE + tarBz2("1.0.0", "py311_0"));
    }

    @Override
    public String probe(String vector) {
        // The channel segment is client-supplied and becomes the conda/<channel>/... pointer key verbatim, so the
        // vector goes there with a well-formed <subdir>/<name>-<version>-<build>.<ext> tail after it - the shape that
        // really reaches the publish, rather than one the package-suffix gate turns away first.
        return "/conda/" + vector + "/" + SUBDIR + "/t202c-probe-1.0.0-0.tar.bz2";
    }

    /** A real {@code .tar.bz2}, so a probing {@code PUT} reaches the pointer composition rather than being rejected as
     *  an unindexable archive - which would leave the one verb that writes untested. */
    @Override
    public byte[] probeBody() {
        try {
            return Packages.condaTarBz2("t202c-probe", "1.0.0", "0");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<Enumerated> enumerated(ArtifactStore store) throws IOException {
        put(store, tarBz2("1.0.0", "py311_0"), Packages.condaTarBz2(PACKAGE, "1.0.0", "py311_0"));
        // One version, two builds, two container shapes: the legacy .tar.bz2 lands in repodata's `packages` map and the
        // modern .conda in `packages.conda`, and a hold on the (name, version) coordinate has to clear both.
        put(store, tarBz2("2.0.0", "py311_0"), Packages.condaTarBz2(PACKAGE, "2.0.0", "py311_0"));
        put(store, conda("2.0.0", "py311_1"), Packages.conda(PACKAGE, "2.0.0", "py311_1"));
        return Optional.of(new Enumerated(BASE + tarBz2("2.0.0", "py311_0"),
                List.of(new Probe(REPODATA, tarBz2("2.0.0", "py311_0")),
                        new Probe(REPODATA, conda("2.0.0", "py311_1"))),
                target -> hold(target, PACKAGE, "2.0.0")));
    }

    @Override
    public Optional<Index> index(ArtifactStore store) throws IOException {
        put(store, tarBz2("1.0.0", "py311_0"), Packages.condaTarBz2(PACKAGE, "1.0.0", "py311_0"));
        return Optional.of(new Index(REPODATA, target ->
                put(target, tarBz2("1.1.0", "py311_0"), Packages.condaTarBz2(PACKAGE, "1.1.0", "py311_0"))));
    }

    @Override
    public Optional<Upstream> upstream(GeneratedBody body) {
        // The honest upstream: the subdir's repodata.json declaring this package's sha256, and the archive itself
        // streamed from the same subdir - exactly the two reads a conda channel answers.
        return Optional.of(new Upstream(PROXIED, ROOT, fetcher(body, body.sha256())));
    }

    @Override
    public Optional<Upstream> tampered(GeneratedBody body) {
        // The same bytes advertised under a sha256 they do not hash to. Nothing may be linked, and the local 404 must
        // stand so a later pull re-hits the upstream.
        return Optional.of(new Upstream(PROXIED, ROOT, fetcher(body, "0".repeat(64))));
    }

    @Override
    public Map<FormatContract.Property, String> unsupported() {
        return Map.of(
                FormatContract.Property.PROXY_REFUSAL_IS_NOT_AN_ABSENCE, "audited 2026-08-24: repodata.json is an ENUMERATION already refused as a 502, and the package archive is "
                        + "the only other path the proxy fills - a miss on it fails the solve. The .bz2/.zst repodata variants a "
                        + "client falls back between are served from stored listings, not proxied, so no refusal can be spelled as "
                        + "one of those misses. ",
                FormatContract.Property.PUBLISH_PATHS_ARE_DESCRIBED,
                "same protocol reason as PUBLISH_SERVES_EXACT_BYTES: the kit's arbitrary body publishes nowhere here. "
                        + "Restated, not dropped: PackagedArtifactContract records where a real package publish "
                        + "writes and requires the format to place every one of those paths",
                FormatContract.Property.HELD_THEN_RELEASED_SERVES_AGAIN,
                "same protocol reason as PUBLISH_SERVES_EXACT_BYTES: the kit's arbitrary body publishes nowhere here. "
                        + "Restated, not dropped: PackagedArtifactContract holds and releases a real package and "
                        + "requires it to serve again",
                FormatContract.Property.GONE_BLOB_IS_A_CLEAN_404,
                "same protocol reason as PUBLISH_SERVES_EXACT_BYTES: the kit's arbitrary body publishes nowhere here. "
                        + "Restated, not dropped: PackagedArtifactContract deletes the blob behind a real package and "
                        + "requires the clean 404",
                FormatContract.Property.PUBLISH_SERVES_EXACT_BYTES,
                "a conda push reopens the just-stored blob and reads the info/index.json inside the archive (a bzip2 "
                        + "tar for a .tar.bz2, a zip whose info-*.tar.zst member is a Zstandard tar for a .conda), "
                        + "refusing a package whose embedded name disagrees with the filename - so the protocol PARSES "
                        + "the artifact and an arbitrary byte body publishes nowhere. Restated, not dropped: "
                        + "PackagedArtifactContract runs the identical property over a real .tar.bz2 published through "
                        + "this format's own subdir PUT",
                FormatContract.Property.HEAD_ANSWERS_FROM_METADATA,
                "same protocol reason as PUBLISH_SERVES_EXACT_BYTES. PackagedArtifactContract runs it over a real "
                        + "conda package with the same sealed-blob proof and the same non-vacuity check",
                FormatContract.Property.COORDINATE_TRAVERSAL_REFUSED,
                "CondaFormat DOES implement ArtifactLayout, but only for ecosystem()/describe(): paths() answers empty "
                        + "by design, because a package's pointer lives in the blobs namespace "
                        + "(conda/<channel>/<subdir>/pkgs/<file>) rather than under publish/, and the build and subdir "
                        + "a version was published under are not derivable from the <name, version> coordinate at all. "
                        + "The kit's leg would therefore fail its own non-vacuity check rather than prove anything. The "
                        + "seam this format really has is the BlobLayout - blobKeys pages every channel's "
                        + "subdir pkgs container for every build of the version, which is what an eviction deletes - "
                        + "and it is proven over the same hostile coordinates by BlobLayoutCoordinateSeamTest in this "
                        + "module; the request seam is covered by REQUEST_PATH_TRAVERSAL_REFUSED");
    }

    /**
     * An upstream conda channel answering the subdir's {@code repodata.json} (which declares each package's
     * {@code sha256}) and the package archive itself. Both go through {@code download}, because the format
     * stream-parses the index rather than materialising it - a real subdir's repodata runs to hundreds of megabytes.
     */
    private static ProxyFormat.Fetcher fetcher(GeneratedBody body, String sha256) {
        String repodata = ROOT + SUBDIR + "/repodata.json";
        String artifact = ROOT + SUBDIR + "/" + PROXIED_FILE;
        byte[] index = ("{\"info\":{\"subdir\":\"" + SUBDIR + "\"},\"packages\":{\"" + PROXIED_FILE + "\":{"
                + "\"name\":\"proxied-lib\",\"version\":\"9.9.9\",\"build\":\"py311_0\",\"depends\":[],"
                + "\"sha256\":\"" + sha256 + "\",\"size\":" + body.length() + "}},"
                + "\"packages.conda\":{},\"repodata_version\":1}").getBytes(StandardCharsets.UTF_8);
        return new ProxyFormat.Fetcher.Buffered() {

            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) {
                return url.toString().equals(repodata)
                        ? Optional.of(new ProxyFormat.Fetched(200, index, Map.of()))
                        : Optional.of(new ProxyFormat.Fetched(404, new byte[0], Map.of()));
            }

            @Override
            public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders) {
                if (url.toString().equals(repodata)) {
                    return Optional.of(new ProxyFormat.Download(200, new ByteArrayInputStream(index), Map.of()));
                }
                return url.toString().equals(artifact)
                        ? Optional.of(new ProxyFormat.Download(200, body.open(), Map.of()))
                        : Optional.of(new ProxyFormat.Download(404, InputStream.nullInputStream(), Map.of()));
            }
        };
    }

    private static String tarBz2(String version, String build) {
        return PACKAGE + "-" + version + "-" + build + ".tar.bz2";
    }

    private static String conda(String version, String build) {
        return PACKAGE + "-" + version + "-" + build + ".conda";
    }

    private void put(ArtifactStore store, String file, byte[] archive) throws IOException {
        seed(store, ContractExchange.of("PUT", BASE + file, archive), 201);
    }
}
