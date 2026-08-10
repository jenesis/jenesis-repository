package build.jenesis.repository.format.contract.test;

import module java.base;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.format.testkit.FormatContract;
import build.jenesis.repository.format.testkit.FormatFixture;
import build.jenesis.repository.format.testkit.GeneratedBody;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Withheld;

/**
 * The OCI / Docker registry's leg of the shared contract - the {@code blobs/}-namespace format among the four, which
 * is why it is the one that proves the content-addressed half of several properties: it serves by digest straight out
 * of {@code blobs/<hex>}, so its hold is the {@code withheld/<hash>} marker rather than the interceptor chain, and its
 * proxy reference <em>is</em> the digest a fetched body is held to.
 */
final class OciFormatFixture implements FormatFixture {

    private static final String IMAGE = "contract/lib";
    private static final URI ROOT = URI.create("https://registry.invalid/");

    private RepositoryFormat serving;

    @Override
    public String format() {
        return "oci";
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.format.oci.OciFormat";
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
        return List.of("oci", "blobs");
    }

    @Override
    public Published publish(ArtifactStore store, byte[] body) throws IOException {
        // A monolithic blob push: POST the upload with the digest inline, exactly as a small `docker push` layer goes.
        String hex = sha256(body);
        ContractExchange push = ContractExchange.of("POST", "/v2/" + IMAGE + "/blobs/uploads", body)
                .query("digest", "sha256:" + hex);
        serving().handle(push, store);
        if (push.status() != 201) {
            throw new AssertionError("pushing the blob answered " + push.status() + " rather than 201");
        }
        return new Published("/v2/" + IMAGE + "/blobs/sha256:" + hex, hex);
    }

    @Override
    public String probe(String vector) {
        // The image name is the one multi-segment, client-supplied element of a /v2/ path, so it is where a traversal
        // vector has to be spliced: a manifest reference is a single tag or digest by grammar.
        return "/v2/" + vector + "/manifests/1.0";
    }

    @Override
    public byte[] probeBody() {
        return manifest("probe");
    }

    @Override
    public Optional<Enumerated> enumerated(ArtifactStore store) throws IOException {
        // Two images, one tag each, so the hold on one leaves BOTH enumeration surfaces: its own tags/list (which must
        // stop naming the tag) and the registry catalogue (which must stop naming an image whose every tag is held).
        pushManifest(store, "contract/keep", "latest", manifest("keep"));
        byte[] held = manifest("held");
        pushManifest(store, "contract/held", "latest", held);
        String hex = sha256(held);
        return Optional.of(new Enumerated("/v2/contract/held/manifests/latest",
                List.of(new Probe("/v2/contract/held/tags/list", "latest"),
                        new Probe("/v2/_catalog", "contract/held")),
                target -> Withheld.mark(target, hex)));
    }

    @Override
    public Optional<Index> index(ArtifactStore store) throws IOException {
        pushManifest(store, IMAGE, "1.0", manifest("one"));
        return Optional.of(new Index("/v2/" + IMAGE + "/tags/list",
                target -> pushManifest(target, IMAGE, "2.0", manifest("two"))));
    }

    @Override
    public Optional<Upstream> upstream(GeneratedBody body) {
        // A by-digest blob pull: the reference names the content, so the fetched bytes must hash to it.
        String reference = "sha256:" + body.sha256();
        return Optional.of(new Upstream("/v2/" + IMAGE + "/blobs/" + reference, ROOT,
                blobs(ROOT + "v2/" + IMAGE + "/blobs/" + reference, body::open)));
    }

    @Override
    public Optional<Upstream> tampered(GeneratedBody body) {
        // The client asks for the honest body's digest and the upstream hands over something else - the substitution
        // a content-addressed pull exists to catch. Nothing may be cached under the requested digest.
        String reference = "sha256:" + body.sha256();
        byte[] other = "not the bytes that digest names".getBytes(StandardCharsets.UTF_8);
        return Optional.of(new Upstream("/v2/" + IMAGE + "/blobs/" + reference, ROOT,
                blobs(ROOT + "v2/" + IMAGE + "/blobs/" + reference, () -> new ByteArrayInputStream(other))));
    }

    @Override
    public Map<FormatContract.Property, String> unsupported() {
        return Map.of(FormatContract.Property.COORDINATE_TRAVERSAL_REFUSED,
                "OciFormat implements no ArtifactLayout: an image's coordinate-to-pointer mapping is the downstream "
                        + "OciBlobLayout, which the T-202b fixtures cover. Every client-supplied name this format "
                        + "does splice into a store key - the image name, the tag, the digest - is screened at the "
                        + "request seam by isImageName/isTag/isDigestHex and asserted by "
                        + "REQUEST_PATH_TRAVERSAL_REFUSED");
    }

    /** An upstream answering one URL with a streamed body and everything else with a miss. */
    private static ProxyFormat.Fetcher blobs(String url, Supplier<InputStream> body) {
        return new ProxyFormat.Fetcher.Buffered() {

            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI requested, Map<String, String> requestHeaders) {
                return Optional.of(new ProxyFormat.Fetched(404, new byte[0], Map.of()));
            }

            @Override
            public Optional<ProxyFormat.Download> download(URI requested, Map<String, String> requestHeaders) {
                return requested.toString().equals(url)
                        ? Optional.of(new ProxyFormat.Download(200, body.get(), Map.of()))
                        : Optional.of(new ProxyFormat.Download(404, InputStream.nullInputStream(), Map.of()));
            }
        };
    }

    private void pushManifest(ArtifactStore store, String image, String tag, byte[] body) throws IOException {
        ContractExchange put = ContractExchange.of("PUT", "/v2/" + image + "/manifests/" + tag, body)
                .header("Content-Type", "application/vnd.oci.image.manifest.v1+json");
        serving().handle(put, store);
        if (put.status() != 201) {
            throw new AssertionError("pushing " + image + ":" + tag + " answered " + put.status() + " rather than 201");
        }
    }

    /** A minimal but well-formed OCI image manifest, distinguished by {@code marker} so two seeded images hash - and
     *  therefore hold - independently. */
    private static byte[] manifest(String marker) {
        return ("{\"schemaVersion\":2,"
                + "\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\","
                + "\"annotations\":{\"build.jenesis.contract\":\"" + marker + "\"},"
                + "\"config\":{\"mediaType\":\"application/vnd.oci.image.config.v1+json\",\"size\":0,"
                + "\"digest\":\"sha256:" + "0".repeat(64) + "\"},"
                + "\"layers\":[]}").getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
