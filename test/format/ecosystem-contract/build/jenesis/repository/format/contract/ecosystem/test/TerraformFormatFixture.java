package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.format.testkit.FormatContract;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The Terraform / OpenTofu registry's leg of the shared contract - the only entry here that serves <em>two</em>
 * protocols, and the one whose enumeration surface a client cannot use without verifying a signature first.
 *
 * <p>A {@code PUT} takes opaque bytes: neither protocol parses the artifact, because neither archive carries a
 * manifest the registry reads. So the kit's own publish leg applies verbatim, which is rarer here than not.
 *
 * <p><b>The enumeration probed is the provider version list, and the hold has to leave two documents.</b> A
 * provider version is several per-platform zips; holding one must drop its line from that release's
 * {@code SHA256SUMS} <em>and</em>, when it was the last servable platform, drop the version from the list. Probing
 * only the list would pass over a release still declaring the digest of a zip the download refuses - which a client
 * meets as a checksum mismatch rather than an absent version, and reports as a corrupt registry.
 */
final class TerraformFormatFixture implements EcosystemFormatFixture {

    private static final String REPOSITORY = "registry";

    private static final String BASE = "/terraform/" + REPOSITORY;

    private static final String NAMESPACE = "contract";

    private static final String TYPE = "widget";

    private static final String COORDINATE = NAMESPACE + "/" + TYPE;

    private static final String VERSIONS = BASE + "/v1/providers/" + NAMESPACE + "/" + TYPE + "/versions";

    private RepositoryFormat serving;

    @Override
    public String format() {
        return "terraform";
    }

    @Override
    public Signatures signatures() {
        return Signatures.none("a provider's SHA256SUMS is signed by the publisher's GPG key registered with the registry "
                + "that serves it, and here that registry is this deployment: a hosted publish sends the provider zips "
                + "alone, the SHA256SUMS and its signature are generated and signed with the repository's own key, and "
                + "there is no proxy leg through which an upstream registry's signed sums could arrive. What a client "
                + "verifies is this deployment vouching for its own index, which the seam deliberately does not "
                + "describe; an inbound story begins the day a proxy leg stores an upstream's sums.");
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.format.terraform.TerraformFormat";
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
        return List.of("terraform", "blobs");
    }

    @Override
    public Published publish(ArtifactStore store, byte[] body) throws IOException {
        String path = zip("1.0.0", "linux", "amd64");
        put(store, path, body);
        return new Published(path, Packages.sha256(body));
    }

    @Override
    public Seeded seed(ArtifactStore store) throws IOException {
        release(store, "1.0.0");
        return new Seeded(COORDINATE, "1.0.0", zip("1.0.0", "linux", "amd64"));
    }

    @Override
    public String probe(String vector) {
        // The namespace is client-supplied and composes the pointer key, so that is where the vector goes - with a
        // well-formed release path after it, so the request really reaches the pointer composition rather than
        // being turned away by the file-name screen first.
        return BASE + "/providers/" + vector + "/" + TYPE + "/1.0.0/terraform-provider-" + TYPE
                + "_1.0.0_linux_amd64.zip";
    }

    @Override
    public Optional<Enumerated> enumerated(ArtifactStore store) throws IOException {
        release(store, "1.0.0");
        release(store, "2.0.0");
        return Optional.of(new Enumerated(zip("2.0.0", "linux", "amd64"),
                List.of(new Probe(VERSIONS, "\"version\":\"2.0.0\""),
                        new Probe(BASE + "/providers/" + NAMESPACE + "/" + TYPE + "/2.0.0/SHA256SUMS",
                                "terraform-provider-" + TYPE + "_2.0.0_linux_amd64.zip")),
                target -> hold(target, COORDINATE, "2.0.0")));
    }

    @Override
    public Optional<Index> index(ArtifactStore store) throws IOException {
        release(store, "1.0.0");
        return Optional.of(new Index(VERSIONS, target -> release(target, "1.1.0")));
    }

    @Override
    public Map<FormatContract.Property, String> unsupported() {
        return Map.of(
                FormatContract.Property.PROXY_VERIFIES_UPSTREAM_INTEGRITY, PROXY,
                FormatContract.Property.PROXY_REFUSAL_IS_NOT_AN_ABSENCE, PROXY,
                FormatContract.Property.PROXY_STREAMS_UPSTREAM_BODY, PROXY,
                FormatContract.Property.COORDINATE_TRAVERSAL_REFUSED,
                "TerraformFormat implements ArtifactLayout for ecosystem()/describe() only: paths() answers empty "
                        + "by design, because a Terraform artifact's pointer lives in the blobs namespace rather "
                        + "than under publish/, so the kit's leg would fail its own non-vacuity check rather than "
                        + "prove anything. The seam this format really has is the BlobLayout, proven "
                        + "over the same hostile coordinates by BlobLayoutCoordinateSeamTest; the request seam is "
                        + "covered by REQUEST_PATH_TRAVERSAL_REFUSED");
    }

    /** Scoped to serving the registry; mirroring {@code registry.terraform.io} is a separate change, so these three
     *  rows have no subject rather than a failing one. */
    private static final String PROXY =
            "terraform has no proxy leg: this entry is scoped to serving a hosted registry - the two protocols and "
                    + "the signed SHA256SUMS - and pulling through registry.terraform.io is a separate change that "
                    + "these rows arrive with";

    /** One provider release, published as the two platforms a version list then reports. */
    private void release(ArtifactStore store, String version) throws IOException {
        put(store, zip(version, "linux", "amd64"),
                ("a provider binary for linux " + version).getBytes(StandardCharsets.UTF_8));
        put(store, zip(version, "darwin", "arm64"),
                ("a provider binary for darwin " + version).getBytes(StandardCharsets.UTF_8));
    }

    private static String zip(String version, String os, String arch) {
        return BASE + "/providers/" + NAMESPACE + "/" + TYPE + "/" + version + "/terraform-provider-" + TYPE
                + "_" + version + "_" + os + "_" + arch + ".zip";
    }

    private void put(ArtifactStore store, String path, byte[] body) throws IOException {
        seed(store, ContractExchange.of("PUT", path, body), 201);
    }
}
