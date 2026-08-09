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
 * The generic (raw) format's leg of the shared contract: a plain file store under {@code /raw/}, whose trailing-slash
 * directory listing is both its enumeration surface and its generated document, and which mirrors an upstream file
 * tree. It excludes exactly one property, and for a protocol reason rather than an implementation one - a raw mirror
 * publishes no digest beside its files, so there is nothing for the proxy leg to hold a fetched body to.
 */
final class RawFormatFixture implements FormatFixture {

    private static final String DIRECTORY = "/raw/contract/";
    private static final URI ROOT = URI.create("https://upstream.invalid/files/");
    private static final String PROXIED = DIRECTORY + "upstream.bin";

    private RepositoryFormat serving;

    @Override
    public String format() {
        return "raw";
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.format.raw.RawFormat";
    }

    @Override
    public RepositoryFormat serving() {
        if (serving == null) {
            serving = FormatFixture.super.serving();
        }
        return serving;
    }

    @Override
    public List<String> namespaces() {
        return List.of("publish/raw", "blobs");
    }

    @Override
    public Published publish(ArtifactStore store, byte[] body) throws IOException {
        String path = DIRECTORY + "artifact.bin";
        put(store, path, body);
        return new Published(path, sha256(body));
    }

    @Override
    public String probe(String vector) {
        return "/raw/" + vector;
    }

    @Override
    public Optional<Enumerated> enumerated(ArtifactStore store) throws IOException {
        put(store, DIRECTORY + "keep.bin", "keep".getBytes(StandardCharsets.UTF_8));
        String held = DIRECTORY + "held.bin";
        put(store, held, "held".getBytes(StandardCharsets.UTF_8));
        // The directory listing is the disclosure surface: a leaf a GET would 404 must not be linked from it, since
        // the name alone tells a caller the artifact exists.
        return Optional.of(new Enumerated(held, List.of(new Probe(DIRECTORY, "held.bin")),
                target -> ContractHold.mark(target, held)));
    }

    @Override
    public Optional<Index> index(ArtifactStore store) throws IOException {
        put(store, DIRECTORY + "one.bin", "one".getBytes(StandardCharsets.UTF_8));
        return Optional.of(new Index(DIRECTORY,
                target -> put(target, DIRECTORY + "two.bin", "two".getBytes(StandardCharsets.UTF_8))));
    }

    @Override
    public Optional<Upstream> upstream(GeneratedBody body) {
        String artifact = ROOT + PROXIED.substring("/raw/".length());
        return Optional.of(new Upstream(PROXIED, ROOT, new ProxyFormat.Fetcher() {

            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) {
                return Optional.of(new ProxyFormat.Fetched(404, new byte[0], Map.of()));
            }

            @Override
            public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders) {
                return url.toString().equals(artifact)
                        ? Optional.of(new ProxyFormat.Download(200, body.open(), Map.of()))
                        : Optional.of(new ProxyFormat.Download(404, InputStream.nullInputStream(), Map.of()));
            }
        }));
    }

    @Override
    public Map<FormatContract.Property, String> unsupported() {
        return Map.of(
                FormatContract.Property.PROXY_VERIFIES_UPSTREAM_INTEGRITY,
                "the raw protocol is a plain HTTP file tree: it publishes no checksum sibling, no digest header and "
                        + "no content-addressed reference, so an upstream body carries nothing to hold it to. The kit "
                        + "refuses to fabricate a check the protocol does not have - Maven's .sha1 sibling and OCI's "
                        + "digest reference prove the property where a digest really is advertised. A raw mirror that "
                        + "wants integrity must proxy an upstream that publishes one",
                FormatContract.Property.COORDINATE_TRAVERSAL_REFUSED,
                "RawFormat implements no ArtifactLayout, and deliberately: a raw asset has no ecosystem coordinate, "
                        + "so its request path IS its identity and there is no coordinate-to-path composition to "
                        + "screen. Every client-supplied name it handles arrives as a request path and is screened by "
                        + "REQUEST_PATH_TRAVERSAL_REFUSED; the Maven and Jenesis legs prove the coordinate seam");
    }

    private void put(ArtifactStore store, String path, byte[] body) throws IOException {
        ContractExchange put = ContractExchange.of("PUT", path, body);
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
