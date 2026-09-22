package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.format.testkit.FormatContract;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.format.ArtifactSignatures;

/**
 * The Swift Package Registry's leg of the shared contract.
 *
 * <p>A release is created by a multipart {@code PUT} carrying the source archive, so an arbitrary byte body is not
 * a publishable artifact here and the two publish properties run over a real form through
 * {@link PackagedArtifactContract}.
 *
 * <p>The enumeration probed is the release list, which is the only document this registry maintains - and the one
 * a client resolves a version range against, so a version left in it after its bytes are withheld is a version
 * {@code swift package resolve} would select and then fail to download.
 */
final class SwiftFormatFixture implements EcosystemFormatFixture {

    private static final String REPOSITORY = "registry";

    private static final String BASE = "/swift/" + REPOSITORY;

    private static final String SCOPE = "contract";

    private static final String NAME = "widget";

    /** The registry's own namespaced identifier: scope and name joined by a dot. */
    private static final String COORDINATE = SCOPE + "." + NAME;

    private static final String RELEASES = BASE + "/" + SCOPE + "/" + NAME;

    private static final String BOUNDARY = "jenesiscontractboundary";

    private RepositoryFormat serving;

    @Override
    public String format() {
        return "swift";
    }

    @Override
    public Signatures signatures() {
        return Signatures.of(ArtifactSignatures.Scheme.PKCS7);
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.format.swift.SwiftFormat";
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
        return List.of("swift", "blobs");
    }

    @Override
    public Published publish(ArtifactStore store, byte[] body) {
        throw new AssertionError("swift: a release is created by a multipart PUT carrying the source archive as a "
                + "form part, so an arbitrary byte body is not a publishable artifact here. This fixture publishes "
                + "a real form through publishPackage() and runs the same two properties through "
                + "PackagedArtifactContract.");
    }

    @Override
    public Optional<Packaged> publishPackage(ArtifactStore store) throws IOException {
        byte[] archive = archive("1.0.0");
        release(store, "1.0.0", archive);
        return Optional.of(new Packaged(archive, download("1.0.0"), Packages.sha256(archive)));
    }

    @Override
    public Seeded seed(ArtifactStore store) throws IOException {
        release(store, "1.0.0", archive("1.0.0"));
        return new Seeded(COORDINATE, "1.0.0", download("1.0.0"));
    }

    @Override
    public String probe(String vector) {
        // The scope is client-supplied and composes the pointer key, so that is where the vector goes - with a
        // well-formed name and version after it, so the request reaches the pointer composition.
        return BASE + "/" + vector + "/" + NAME + "/1.0.0.zip";
    }

    @Override
    public Optional<Enumerated> enumerated(ArtifactStore store) throws IOException {
        release(store, "1.0.0", archive("1.0.0"));
        release(store, "2.0.0", archive("2.0.0"));
        return Optional.of(new Enumerated(download("2.0.0"),
                List.of(new Probe(RELEASES, "\"2.0.0\"")),
                target -> hold(target, COORDINATE, "2.0.0")));
    }

    @Override
    public Optional<Index> index(ArtifactStore store) throws IOException {
        release(store, "1.0.0", archive("1.0.0"));
        return Optional.of(new Index(RELEASES, target -> release(target, "1.1.0", archive("1.1.0"))));
    }

    @Override
    public Map<FormatContract.Property, String> unsupported() {
        return Map.of(

                FormatContract.Property.PROXY_VERIFIES_UPSTREAM_INTEGRITY, PROXY,
                FormatContract.Property.PROXY_REFUSAL_IS_NOT_AN_ABSENCE, PROXY,
                FormatContract.Property.PROXY_STREAMS_UPSTREAM_BODY, PROXY,
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
                "a release is created by a multipart PUT whose source-archive part is what gets stored, so the "
                        + "protocol PARSES the request body and an arbitrary byte body publishes nowhere. "
                        + "Restated, not dropped: PackagedArtifactContract runs the identical property over a real "
                        + "form",
                FormatContract.Property.HEAD_ANSWERS_FROM_METADATA,
                "same reason: the artifact this property is asked of has to arrive as a real multipart release, "
                        + "and PackagedArtifactContract asks it of one",
                FormatContract.Property.COORDINATE_TRAVERSAL_REFUSED,
                "SwiftFormat implements ArtifactLayout for ecosystem()/describe() only: paths() answers empty by "
                        + "design, because a release's pointer lives in the blobs namespace rather than under "
                        + "publish/, so the kit's leg would fail its own non-vacuity check rather than prove "
                        + "anything. The seam this format really has is the BlobLayout, proven over the "
                        + "same hostile coordinates by BlobLayoutCoordinateSeamTest; the request seam is covered "
                        + "by REQUEST_PATH_TRAVERSAL_REFUSED");
    }

    /** There is no canonical public Swift registry to mirror, which is unusual among these formats and is the
     *  whole reason the proxy rows here are declarations rather than tests. */
    private static final String PROXY =
            "swift has no proxy leg, and unlike the other formats that say so it has nowhere to point one: there "
                    + "is no canonical public Swift package registry to pull through. A deployment mirroring "
                    + "another organisation's registry is a real case and a separate change; these rows arrive "
                    + "with it";

    private static String download(String version) {
        return RELEASES + "/" + version + ".zip";
    }

    /** One release, published the way the specification says: a multipart PUT carrying the archive. */
    private void release(ArtifactStore store, String version, byte[] archive) throws IOException {
        byte[] form = Packages.swiftForm(BOUNDARY, archive,
                "{\"author\":{\"name\":\"Contract\"}}",
                "// swift-tools-version:5.9\n");
        seed(store, ContractExchange.of("PUT", RELEASES + "/" + version, form)
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY), 201);
    }

    private static byte[] archive(String version) throws IOException {
        SequencedMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(NAME + "/Package.swift",
                ("// swift-tools-version:5.9\n// " + version + "\n").getBytes(StandardCharsets.UTF_8));
        return Packages.zip(entries);
    }
}
