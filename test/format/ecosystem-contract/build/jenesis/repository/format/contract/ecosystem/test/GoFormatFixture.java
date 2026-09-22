package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.format.testkit.FormatContract;
import build.jenesis.repository.format.testkit.GeneratedBody;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The Go module proxy's leg of the shared contract - the one format here whose coordinate is legitimately
 * <em>multi-segment</em>, which is exactly why its blob-key screen has to judge a module path part by part rather than
 * as a whole. A {@code PUT} of a {@code .info} / {@code .mod} / {@code .zip} takes opaque bytes, so the kit's publish
 * leg applies verbatim, and the GOPROXY protocol exposes <em>two</em> version-discovery surfaces ({@code @v/list} and
 * {@code @latest}) that must both drop a held version.
 */
final class GoFormatFixture implements EcosystemFormatFixture {

    private static final String MODULE = "example.com/contract";
    private static final String BASE = "/go/" + MODULE + "/@v/";
    private static final URI ROOT = URI.create("https://proxy.invalid/");

    private static final String PROXIED_VERSION = "v9.9.9";
    private static final String PROXIED = BASE + PROXIED_VERSION + ".zip";

    private RepositoryFormat serving;

    @Override
    public String format() {
        return "go";
    }

    @Override
    public Signatures signatures() {
        return Signatures.none("Go has no per-module publisher signature at all: integrity comes from the checksum database, a "
                + "transparency log every client cross-checks through go.sum, which answers a different question - "
                + "that everyone saw the same bytes, rather than that the author vouched for them.");
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.format.go.GoFormat";
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
        return List.of("go", "blobs");
    }

    @Override
    public Published publish(ArtifactStore store, byte[] body) throws IOException {
        put(store, "v1.0.0.zip", body);
        return new Published(BASE + "v1.0.0.zip", Packages.sha256(body));
    }

    @Override
    public Seeded seed(ArtifactStore store) throws IOException {
        version(store, "v1.0.0");
        return new Seeded(MODULE, "v1.0.0", BASE + "v1.0.0.zip");
    }

    @Override
    public String probe(String vector) {
        // The module path is the multi-segment, client-supplied element of every /go/ path, and it becomes the store
        // key verbatim - so that is where the vector goes, with a well-formed @v/ file after it.
        return "/go/" + vector + "/@v/v1.0.0.info";
    }

    @Override
    public Optional<Enumerated> enumerated(ArtifactStore store) throws IOException {
        version(store, "v1.0.0");
        version(store, "v2.0.0");
        // Two surfaces, and a hold must leave both: @v/list is what a build resolves a version range against, and
        // @latest is what `go get module@latest` reads. A version present in either after its bytes are withheld is a
        // coordinate the go client would then fail to download.
        return Optional.of(new Enumerated(BASE + "v2.0.0.zip",
                List.of(new Probe("/go/" + MODULE + "/@v/list", "v2.0.0"),
                        new Probe("/go/" + MODULE + "/@latest", "v2.0.0")),
                target -> hold(target, MODULE, "v2.0.0")));
    }

    @Override
    public Optional<Index> index(ArtifactStore store) throws IOException {
        version(store, "v1.0.0");
        return Optional.of(new Index("/go/" + MODULE + "/@v/list", target -> version(target, "v1.1.0")));
    }

    /**
     * The honest pull-through leg. The checksum database answers {@code 404} for this module version, which is the
     * <em>declared</em> half of clause 5 and the honest answer for a fixture module no public database carries: the
     * body is cached unverified, so the positive control proves the fetcher and the cache-then-serve round trip
     * without asserting a digest the ecosystem would not have. The matching path - a real module zip against a real
     * {@code h1:} record - is proven by {@code GoProxyIntegrityTest}, which is where a body that is actually a zip
     * can be built.
     */
    @Override
    public Optional<Upstream> upstream(GeneratedBody body) {
        return Optional.of(proxied(body, null));
    }

    /**
     * The same leg with the database advertising a dirhash for these bytes that they do not have. A GOPROXY body is
     * never content-addressed, so this is the only way the mismatch can arise - and it is exactly the shape a
     * compromised or corrupted mirror produces, since the digest comes from a different origin than the bytes.
     */
    @Override
    public Optional<Upstream> tampered(GeneratedBody body) {
        return Optional.of(proxied(body, "h1:" + Base64.getEncoder().encodeToString(new byte[32])));
    }

    /** One pull-through leg over {@code body}, with the checksum database either silent ({@code advertised == null})
     *  or advertising {@code advertised} for the module zip. */
    private Upstream proxied(GeneratedBody body, String advertised) {
        String artifact = ROOT + MODULE + "/@v/" + PROXIED_VERSION + ".zip";
        // Whatever the deployment's configured database is, the format asks it for this record; the fixture answers
        // for any lookup URL so the leg never depends on a host.
        String record = advertised == null ? null
                : MODULE + " " + PROXIED_VERSION + " " + advertised + "\n";
        return new Upstream(PROXIED, ROOT, new ProxyFormat.Fetcher.Buffered() {

            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) {
                if (record != null && url.getPath().contains("/lookup/")) {
                    return Optional.of(new ProxyFormat.Fetched(200,
                            ("1\n" + record + "\n").getBytes(StandardCharsets.UTF_8), Map.of()));
                }
                return Optional.of(new ProxyFormat.Fetched(404, new byte[0], Map.of()));
            }

            @Override
            public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders) {
                return url.toString().equals(artifact)
                        ? Optional.of(new ProxyFormat.Download(200, body.open(), Map.of()))
                        : Optional.of(new ProxyFormat.Download(404, InputStream.nullInputStream(), Map.of()));
            }
        });
    }

    @Override
    public Map<FormatContract.Property, String> unsupported() {
        return Map.of(
                FormatContract.Property.PROXY_REFUSAL_IS_NOT_AN_ABSENCE, "audited 2026-08-24: the module proxy's @v list is an ENUMERATION whose refusal is already a 502, and "
                        + ".info, .mod and .zip are each a named version's file whose absence the go command reports rather than "
                        + "resolves around - GONOSUMDB/GOFLAGS change what is verified, never whether a miss is an answer. ",
                FormatContract.Property.COORDINATE_TRAVERSAL_REFUSED,
                "GoFormat implements no ArtifactLayout: a module version's pointers live in the blobs namespace "
                        + "(go/<module>/@v/<version>.{info,mod,zip}), not under publish/, so its coordinate-to-pointer "
                        + "mapping is the BlobLayout - and a Go coordinate is a multi-segment module path, "
                        + "which is precisely why that seam screens it part by part. Proven over the same hostile "
                        + "coordinates by BlobLayoutCoordinateSeamTest in this module; the request seam is covered by "
                        + "REQUEST_PATH_TRAVERSAL_REFUSED");
    }

    /** One published module version: the {@code .info} the version list and {@code @latest} are derived from, and the
     *  {@code .zip} that carries the bytes - the trio's two halves a hold has to reach together. */
    private void version(ArtifactStore store, String version) throws IOException {
        put(store, version + ".info",
                ("{\"Version\":\"" + version + "\",\"Time\":\"2026-01-01T00:00:00Z\"}")
                        .getBytes(StandardCharsets.UTF_8));
        put(store, version + ".zip", ("module archive of " + version).getBytes(StandardCharsets.UTF_8));
    }

    private void put(ArtifactStore store, String file, byte[] body) throws IOException {
        seed(store, ContractExchange.of("PUT", BASE + file, body), 201);
    }
}
