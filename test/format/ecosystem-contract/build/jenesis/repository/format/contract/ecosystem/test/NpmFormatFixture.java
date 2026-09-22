package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.format.testkit.FormatContract;
import build.jenesis.repository.format.testkit.GeneratedBody;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.format.ArtifactSignatures;

/**
 * The npm registry's leg of the shared contract. npm carries the whole contract bar the coordinate seam: its publish
 * envelope carries the tarball as opaque base64, so the kit's own publish leg applies verbatim; its packument is the
 * generated document <em>and</em> the enumeration surface (versions and the computed {@code dist-tags} both); and its
 * proxy leg holds a fetched tarball to the {@code dist.integrity} the upstream packument declares for it.
 */
final class NpmFormatFixture implements EcosystemFormatFixture {

    private static final String PACKAGE = "contract-lib";
    private static final String PACKUMENT = "/npm/" + PACKAGE;
    private static final URI ROOT = URI.create("https://registry.invalid/");

    /** The proxied version, deliberately one nothing local publishes, so the pull-through leg is a real local miss. */
    private static final String PROXIED_VERSION = "9.9.9";
    private static final String PROXIED_FILE = PACKAGE + "-" + PROXIED_VERSION + ".tgz";
    private static final String PROXIED = PACKUMENT + "/-/" + PROXIED_FILE;

    private RepositoryFormat serving;

    @Override
    public String format() {
        return "npm";
    }

    @Override
    public Signatures signatures() {
        return Signatures.of(ArtifactSignatures.Scheme.SIGSTORE_BUNDLE);
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.format.npm.NpmFormat";
    }

    /** Discovered once through the SPI, then cached: the contract drives dozens of exchanges per check and a
     *  ServiceLoader sweep per exchange would be the fixture's own cost, not the format's. */
    @Override
    public RepositoryFormat serving() {
        if (serving == null) {
            serving = EcosystemFormatFixture.super.serving();
        }
        return serving;
    }

    @Override
    public List<String> namespaces() {
        return List.of("npm", "blobs");
    }

    @Override
    public Published publish(ArtifactStore store, byte[] body) throws IOException {
        publish(store, "1.0.0", body);
        return new Published(tarball("1.0.0"), Packages.sha256(body));
    }

    @Override
    public Seeded seed(ArtifactStore store) throws IOException {
        byte[] body = "seeded npm tarball".getBytes(StandardCharsets.UTF_8);
        publish(store, "1.0.0", body);
        return new Seeded(PACKAGE, "1.0.0", tarball("1.0.0"));
    }

    @Override
    public String probe(String vector) {
        // The package name is the one client-supplied element of a publish path, so that is where a vector has to go:
        // it becomes npm/<name>/tarballs/... and npm/<name>/versions/... keys on the write path.
        return "/npm/" + vector;
    }

    /** A well-formed publish envelope, so a probing {@code PUT} really reaches the pointer composition rather than
     *  being turned away as an unparseable body - which would make the traversal leg vacuous for the one verb that
     *  writes. */
    @Override
    public byte[] probeBody() {
        return Packages.npmEnvelope("t202b-probe", "1.0.0", "t202b-probe".getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Optional<Enumerated> enumerated(ArtifactStore store) throws IOException {
        publish(store, "1.0.0", "keep".getBytes(StandardCharsets.UTF_8));
        publish(store, "2.0.0-held", "held".getBytes(StandardCharsets.UTF_8));
        // The packument is the disclosure surface twice over: the versions map, and the dist-tags block whose computed
        // `latest` is the held version until the hold lands. Both are screened through the tarball pointer the version
        // serves from, so one probe over the whole document catches either half regressing.
        return Optional.of(new Enumerated(tarball("2.0.0-held"),
                List.of(new Probe(PACKUMENT, "2.0.0-held")),
                target -> hold(target, PACKAGE, "2.0.0-held")));
    }

    @Override
    public Optional<Index> index(ArtifactStore store) throws IOException {
        publish(store, "1.0.0", "one".getBytes(StandardCharsets.UTF_8));
        return Optional.of(new Index(PACKUMENT,
                target -> publish(target, "1.1.0", "two".getBytes(StandardCharsets.UTF_8))));
    }

    @Override
    public Optional<Upstream> upstream(GeneratedBody body) {
        // The honest upstream: the packument declaring this tarball's Subresource-Integrity digest, and the tarball
        // itself streamed from the file host.
        return Optional.of(new Upstream(PROXIED, ROOT, fetcher(body, integrity(body.digest("SHA-512")))));
    }

    @Override
    public Optional<Upstream> tampered(GeneratedBody body) {
        // The same bytes advertised under a digest they do not hash to - a body substituted between the registry and
        // here. Nothing may be linked, and the local 404 must stand so a later pull re-hits the upstream.
        return Optional.of(new Upstream(PROXIED, ROOT, fetcher(body, integrity("0".repeat(128)))));
    }

    @Override
    public Map<FormatContract.Property, String> unsupported() {
        return Map.of(


                FormatContract.Property.PROXY_REFUSAL_IS_NOT_AN_ABSENCE, "audited 2026-08-24: npm's proxy leg serves the packument and the tarball, and neither absence is "
                        + "elective - a missing packument is \"no such package\" and a missing tarball fails the install. The "
                        + "packument is an ENUMERATION document, so a refusal on it is already a 502 rather than a miss. ",
               FormatContract.Property.COORDINATE_TRAVERSAL_REFUSED,
                "NpmFormat implements no ArtifactLayout, and cannot: an npm package's pointers live in the blobs "
                        + "namespace (npm/<name>/tarballs/..., npm/<name>/versions/...), not under publish/, so its "
                        + "coordinate-to-pointer mapping is the BlobLayout - blobKeys/servedPaths - rather "
                        + "than ArtifactLayout.paths. That is the seam with teeth here, because blobKeys is what a "
                        + "retention eviction deletes, and it is proven over exactly the same hostile coordinates by "
                        + "BlobLayoutCoordinateSeamTest in this module. Every request-path name npm does splice into a "
                        + "key is screened at the request seam and asserted by REQUEST_PATH_TRAVERSAL_REFUSED");
    }

    /** An upstream registry answering the packument (buffered - it is bounded metadata the proxy must parse) and the
     *  tarball (streamed), exactly as registry.npmjs.org answers them. */
    private static ProxyFormat.Fetcher fetcher(GeneratedBody body, String integrity) {
        String packument = ROOT + PACKAGE;
        String artifact = packument + "/-/" + PROXIED_FILE;
        String document = "{\"name\":\"" + PACKAGE + "\",\"versions\":{\"" + PROXIED_VERSION + "\":{"
                + "\"name\":\"" + PACKAGE + "\",\"version\":\"" + PROXIED_VERSION + "\","
                + "\"dist\":{\"tarball\":\"" + artifact + "\",\"integrity\":\"" + integrity + "\"}}}}";
        return new ProxyFormat.Fetcher.Buffered() {

            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) {
                return url.toString().equals(packument)
                        ? Optional.of(new ProxyFormat.Fetched(200, document.getBytes(StandardCharsets.UTF_8), Map.of()))
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

    /** npm's {@code dist.integrity}: the Subresource-Integrity spelling of a SHA-512, base64 of the raw digest. */
    private static String integrity(String sha512Hex) {
        return "sha512-" + Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha512Hex));
    }

    private static String tarball(String version) {
        return PACKUMENT + "/-/" + PACKAGE + "-" + version + ".tgz";
    }

    private void publish(ArtifactStore store, String version, byte[] tarball) throws IOException {
        seed(store, ContractExchange.of("PUT", PACKUMENT, Packages.npmEnvelope(PACKAGE, version, tarball)), 201);
    }
}
