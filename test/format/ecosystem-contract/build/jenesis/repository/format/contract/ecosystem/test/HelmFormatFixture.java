package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.format.testkit.FormatContract;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.format.ArtifactSignatures;

/**
 * The classic Helm chart repository's leg of the shared contract.
 *
 * <p>Like the CocoaPods and Conda legs, an arbitrary byte body is not a publishable artifact here: a chart publish
 * reopens the stored archive, reads {@code Chart.yaml} out of it and refuses one whose metadata disagrees with the
 * file name it was deployed to. So this fixture publishes a real chart through {@link #publishPackage} and the same
 * two properties run through {@code PackagedArtifactContract}.
 *
 * <p><b>The enumeration is a single document keyed by chart.</b> {@code index.yaml} carries one block per chart and
 * one entry per servable version, and a hold removes the version from that block - or the chart from the document
 * when it was the only one. That is the whole of a Helm client's discovery surface, so it is the only probe this row
 * needs.
 */
final class HelmFormatFixture implements EcosystemFormatFixture {

    private static final String REGISTRY = "contract";
    private static final String BASE = "/helm/" + REGISTRY;
    private static final String CHART = "my-chart";
    private static final String VERSION = "1.0.0";

    private static final String INDEX = BASE + "/index.yaml";

    private RepositoryFormat serving;

    @Override
    public String format() {
        return "helm";
    }

    @Override
    public Signatures signatures() {
        return Signatures.of(ArtifactSignatures.Scheme.OPENPGP_CLEARSIGNED);
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.format.helm.HelmFormat";
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
        return List.of("helm", "blobs");
    }

    @Override
    public Published publish(ArtifactStore store, byte[] body) {
        throw new AssertionError("helm: a chart publish reopens the stored archive and reads Chart.yaml out of it - "
                + "refusing one whose metadata disagrees with the file name - so an arbitrary byte body is not a "
                + "publishable artifact here. This fixture publishes a real chart through publishPackage() and runs "
                + "the same two properties through PackagedArtifactContract.");
    }

    @Override
    public Optional<Packaged> publishPackage(ArtifactStore store) throws IOException {
        byte[] chart = Packages.helmChart(CHART, VERSION);
        put(store, download(VERSION), chart);
        return Optional.of(new Packaged(chart, download(VERSION), Packages.sha256(chart)));
    }

    @Override
    public Seeded seed(ArtifactStore store) throws IOException {
        put(store, download(VERSION), Packages.helmChart(CHART, VERSION));
        return new Seeded(CHART, VERSION, download(VERSION));
    }

    @Override
    public String probe(String vector) {
        // The chart file name is client-supplied and becomes the download pointer's key verbatim - the pointer is
        // keyed on the whole file name, precisely because <name>-<version>.tgz cannot be split reliably.
        return BASE + "/charts/" + vector + ".tgz";
    }

    @Override
    public Optional<Enumerated> enumerated(ArtifactStore store) throws IOException {
        put(store, download(VERSION), Packages.helmChart(CHART, VERSION));
        return Optional.of(new Enumerated(download(VERSION),
                List.of(new Probe(INDEX, VERSION)),
                target -> hold(target, CHART, VERSION)));
    }

    @Override
    public Optional<Index> index(ArtifactStore store) throws IOException {
        put(store, download(VERSION), Packages.helmChart(CHART, VERSION));
        return Optional.of(new Index(INDEX,
                target -> put(target, download("2.0.0"), Packages.helmChart(CHART, "2.0.0"))));
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
                "a chart publish reopens the stored archive and reads Chart.yaml out of it, refusing one that claims "
                        + "a different name or version than the file it was deployed to, so the protocol PARSES the "
                        + "artifact and an arbitrary byte body publishes nowhere. Restated, not dropped: "
                        + "PackagedArtifactContract runs the identical property over a real chart",
                FormatContract.Property.HEAD_ANSWERS_FROM_METADATA,
                "same reason: the artifact this property is asked of has to be a real chart, and "
                        + "PackagedArtifactContract asks it of one",
                FormatContract.Property.COORDINATE_TRAVERSAL_REFUSED,
                "HelmFormat implements ArtifactLayout for ecosystem()/describe() only: paths() answers empty by "
                        + "design, because a chart's pointer lives in the blobs namespace rather than under publish/, "
                        + "so the kit's leg would fail its own non-vacuity check rather than prove anything. The seam "
                        + "this format really has is the BlobLayout, proven over the same hostile "
                        + "coordinates by BlobLayoutCoordinateSeamTest; the request seam is covered by "
                        + "REQUEST_PATH_TRAVERSAL_REFUSED");
    }

    /** This entry is scoped to the classic HTTP repository, and a pull-through against an upstream chart repository
     *  is a separate change - so these three rows have no subject rather than a failing one. */
    private static final String PROXY =
            "helm has no proxy leg: this format is scoped to serving the classic index.yaml + .tgz repository, and "
                    + "pulling through an upstream chart repository is a separate change that these rows arrive with";

    private static String download(String version) {
        return BASE + "/charts/" + CHART + "-" + version + ".tgz";
    }

    private void put(ArtifactStore store, String path, byte[] body) throws IOException {
        seed(store, ContractExchange.of("PUT", path, body), 201);
    }
}
