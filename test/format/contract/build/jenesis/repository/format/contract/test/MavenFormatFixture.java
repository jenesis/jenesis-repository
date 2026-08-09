package build.jenesis.repository.format.contract.test;

import module java.base;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.format.testkit.ContractHold;
import build.jenesis.repository.format.testkit.FormatContract;
import build.jenesis.repository.format.testkit.FormatFixture;
import build.jenesis.repository.format.testkit.GeneratedBody;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The Maven layout's leg of the shared contract. It runs every property: Maven publishes through {@code /maven/}
 * pointers, carries the {@code ArtifactLayout} coordinate seam, generates {@code maven-metadata.xml} on read, and
 * proxies Maven Central - and its upstream advertises a {@code .sha1} sibling, which is the digest the proxy-integrity
 * leg holds a fetched body to.
 */
final class MavenFormatFixture implements FormatFixture {

    private static final String GROUP = "org/example";
    private static final String ARTIFACT = "lib";
    private static final String COORDINATE = "/maven/" + GROUP + "/" + ARTIFACT;
    private static final String METADATA = COORDINATE + "/maven-metadata.xml";

    /** The proxied artifact is deliberately NOT a {@code .jar}: the Maven layout reads a published jar back from
     *  storage to learn its module name, which would re-read the streaming leg's whole generated body for a reason
     *  that has nothing to do with the property under test. */
    private static final String PROXIED = COORDINATE + "/1.0.0/lib-1.0.0.bin";

    private static final URI ROOT = URI.create("https://upstream.invalid/maven2/");

    private RepositoryFormat serving;

    @Override
    public String format() {
        return "maven";
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.format.maven.MavenFormat";
    }

    /** Discovered once through the SPI, then cached: the contract drives dozens of exchanges per check and a
     *  ServiceLoader sweep per exchange would be the fixture's own cost, not the format's. */
    @Override
    public RepositoryFormat serving() {
        if (serving == null) {
            serving = FormatFixture.super.serving();
        }
        return serving;
    }

    @Override
    public List<String> namespaces() {
        return List.of("publish/maven", "blobs");
    }

    /** The opt-in metadata computation, so the generated-document and enumeration legs run against the derived
     *  {@code maven-metadata.xml} rather than the verbatim-serve default. */
    @Override
    public String setting(String key) {
        return "maven-metadata-compute".equals(key) ? "true" : null;
    }

    @Override
    public Published publish(ArtifactStore store, byte[] body) throws IOException {
        String path = jar("1.0.0");
        put(store, path, body);
        return new Published(path, sha256(body));
    }

    @Override
    public String probe(String vector) {
        return "/maven/" + vector;
    }

    @Override
    public Optional<Enumerated> enumerated(ArtifactStore store) throws IOException {
        put(store, jar("1.0.0"), "keep".getBytes(StandardCharsets.UTF_8));
        String held = jar("2.0.0-held");
        put(store, held, "held".getBytes(StandardCharsets.UTF_8));
        // The derived <versions> list is the disclosure surface: a version whose folder the screen retracts must not
        // appear there, nor survive in the <latest>/<release> the same document names.
        return Optional.of(new Enumerated(held, List.of(new Probe(METADATA, "2.0.0-held")),
                target -> ContractHold.mark(target, held)));
    }

    @Override
    public Optional<Index> index(ArtifactStore store) throws IOException {
        put(store, jar("1.0.0"), "one".getBytes(StandardCharsets.UTF_8));
        return Optional.of(new Index(METADATA,
                target -> put(target, jar("1.1.0"), "two".getBytes(StandardCharsets.UTF_8))));
    }

    @Override
    public Optional<Upstream> upstream(GeneratedBody body) {
        // The honest upstream: the artifact, and beside it the .sha1 sibling Maven publishes as its integrity token.
        return Optional.of(new Upstream(PROXIED, ROOT, fetcher(body, body.digest("SHA-1"))));
    }

    @Override
    public Optional<Upstream> tampered(GeneratedBody body) {
        // The same bytes, advertised under a digest they do not hash to - a body corrupted or substituted between the
        // upstream and here. The proxy must retract what it laid out and let the local 404 stand.
        return Optional.of(new Upstream(PROXIED, ROOT, fetcher(body, "0".repeat(40))));
    }

    /** An upstream serving {@code body} at the proxied coordinate and {@code sha1} at its checksum sibling. The
     *  artifact rides {@code download} (streamed) and the sibling {@code fetch} (buffered), exactly as the real
     *  upstream would answer them. */
    private static ProxyFormat.Fetcher fetcher(GeneratedBody body, String sha1) {
        String artifact = ROOT + PROXIED.substring("/maven/".length());
        return new ProxyFormat.Fetcher() {

            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) {
                if (url.toString().equals(artifact + ".sha1")) {
                    return Optional.of(new ProxyFormat.Fetched(200, sha1.getBytes(StandardCharsets.UTF_8), Map.of()));
                }
                return Optional.of(new ProxyFormat.Fetched(404, new byte[0], Map.of()));
            }

            @Override
            public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders) {
                if (url.toString().equals(artifact)) {
                    return Optional.of(new ProxyFormat.Download(200, body.open(), Map.of()));
                }
                return Optional.of(new ProxyFormat.Download(404, InputStream.nullInputStream(), Map.of()));
            }
        };
    }

    @Override
    public Map<FormatContract.Property, String> unsupported() {
        return Map.of();
    }

    private static String jar(String version) {
        return COORDINATE + "/" + version + "/" + ARTIFACT + "-" + version + ".jar";
    }

    private void put(ArtifactStore store, String path, byte[] body) throws IOException {
        ContractExchange put = ContractExchange.of("PUT", path, body).settings(this::setting);
        serving().handle(put, store);
        if (put.status() != 201) {
            throw new AssertionError("seeding " + path + " answered " + put.status() + " rather than 201");
        }
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
