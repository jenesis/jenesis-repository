package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.format.testkit.FormatContract;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Homebrew's leg of the shared contract, and the only entry here with <b>no enumeration surface at all</b> - not
 * a reduced one, and not an omission.
 *
 * <p>A client never asks a bottle domain what it holds. The formula names the file, its checksum and its
 * platform, and the formula lives in a tap: a git repository this product deliberately does not serve. So a bottle
 * domain answers exactly one question - "give me this file" - and the two rows about listings have no subject
 * here rather than a failing one.
 *
 * <p>A {@code PUT} takes opaque bytes, since a bottle carries no manifest to parse, so the kit's own publish leg
 * applies verbatim.
 */
final class HomebrewFormatFixture implements EcosystemFormatFixture {

    private static final String REPOSITORY = "bottles";

    private static final String BASE = "/homebrew/" + REPOSITORY;

    private static final String FORMULA = "contract-tool";

    private static final String TAG = "x86_64_linux";

    private RepositoryFormat serving;

    @Override
    public String format() {
        return "homebrew";
    }

    @Override
    public Signatures signatures() {
        return Signatures.of(ArtifactSignatures.Scheme.SIGSTORE_BUNDLE);
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.format.homebrew.HomebrewFormat";
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
        return List.of("homebrew", "blobs");
    }

    @Override
    public Published publish(ArtifactStore store, byte[] body) throws IOException {
        String path = bottle("1.0.0");
        put(store, path, body);
        return new Published(path, Packages.sha256(body));
    }

    @Override
    public Seeded seed(ArtifactStore store) throws IOException {
        put(store, bottle("1.0.0"), ("a bottle of " + FORMULA).getBytes(StandardCharsets.UTF_8));
        return new Seeded(FORMULA, "1.0.0", bottle("1.0.0"));
    }

    @Override
    public String probe(String vector) {
        // The repository segment is client-supplied and composes the pointer key, so that is where the vector
        // goes - with a well-formed bottle name after it, so the request reaches the pointer composition rather
        // than being turned away by the file-name screen first.
        return "/homebrew/" + vector + "/" + FORMULA + "-1.0.0." + TAG + ".bottle.tar.gz";
    }

    @Override
    public Optional<Enumerated> enumerated(ArtifactStore store) {
        return Optional.empty();
    }

    @Override
    public Optional<Index> index(ArtifactStore store) {
        return Optional.empty();
    }

    @Override
    public Map<FormatContract.Property, String> unsupported() {
        return Map.of(
                FormatContract.Property.PROXY_VERIFIES_UPSTREAM_INTEGRITY, PROXY,
                FormatContract.Property.PROXY_REFUSAL_IS_NOT_AN_ABSENCE, PROXY,
                FormatContract.Property.PROXY_STREAMS_UPSTREAM_BODY, PROXY,
                FormatContract.Property.WITHHELD_VERSION_LEAVES_EVERY_ENUMERATION, NO_ENUMERATION,
                FormatContract.Property.GENERATED_INDEX_IS_REVALIDATABLE, NO_ENUMERATION,
                FormatContract.Property.GENERATED_INDEX_CARRIES_THE_REQUEST_SCHEME, NO_ENUMERATION,
                FormatContract.Property.COORDINATE_TRAVERSAL_REFUSED,
                "HomebrewFormat implements ArtifactLayout for ecosystem()/describe() only: paths() answers empty "
                        + "by design, because a bottle's pointer lives in the blobs namespace rather than under "
                        + "publish/, so the kit's leg would fail its own non-vacuity check rather than prove "
                        + "anything. The seam this format really has is the BlobLayout, proven over the "
                        + "same hostile coordinates by BlobLayoutCoordinateSeamTest; the request seam is covered "
                        + "by REQUEST_PATH_TRAVERSAL_REFUSED");
    }

    /** Not a gap: a bottle domain publishes no index because the ecosystem asks it for none. */
    private static final String NO_ENUMERATION =
            "a bottle domain has no enumeration surface, and that is the protocol rather than a reduction of it: "
                    + "the FORMULA names the file, its checksum and its platform, and a formula lives in a tap - a "
                    + "git repository this product deliberately does not serve. A client asks this domain for one "
                    + "named file and never asks what it holds, so there is no document for a hold to leave or for "
                    + "a validator to revalidate. The hold itself is proven: a withheld bottle answers 404, which "
                    + "is WITHHELD_ARTIFACT_IS_NOT_SERVED's business";

    private static final String PROXY =
            "homebrew has no proxy leg: this entry is scoped to serving bottles a deployment hosts, and mirroring "
                    + "ghcr.io - which is where Homebrew's own bottles live, in an OCI layout a bottle domain does "
                    + "NOT use - is a separate change that these rows arrive with";

    private static String bottle(String version) {
        return BASE + "/" + FORMULA + "-" + version + "." + TAG + ".bottle.tar.gz";
    }

    private void put(ArtifactStore store, String path, byte[] body) throws IOException {
        seed(store, ContractExchange.of("PUT", path, body), 201);
    }
}
