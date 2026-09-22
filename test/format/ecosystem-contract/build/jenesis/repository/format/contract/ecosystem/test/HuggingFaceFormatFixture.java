package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.format.testkit.FormatContract;
import build.jenesis.repository.format.testkit.GeneratedBody;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The Hugging Face Hub's leg of the shared contract - the format whose artifacts are the largest in the distribution (a
 * {@code model.safetensors} runs to tens of gigabytes), which is why its streaming and metadata-answered-{@code HEAD}
 * legs matter most here. A {@code PUT} to a resolve path takes opaque bytes (the coordinate rides in the path, never in
 * the archive), so the kit's own publish leg applies verbatim.
 *
 * <p><b>Two enumeration surfaces.</b> A repository's files are disclosed by the repo-info {@code siblings} list and by
 * the {@code tree} document, which additionally carries each file's {@code oid} and {@code size} - the content hash
 * itself. Both route through one screened enumeration, and the fixture probes both, because a hold that cleared the
 * siblings list while the tree kept publishing the held file's oid would leak the very digest the hold marks.
 *
 * <p><b>The proxy leg is pinned to an immutable commit</b> rather than to a branch, and deliberately: a branch resolve
 * caches under the commit the upstream reports, so the request path a branch was fetched through stays a local miss and
 * the kit's "nothing was cached" assertion would pass however badly the refusal behaved. Against a commit-pinned
 * revision the cache key <em>is</em> the requested path, so the refused-and-not-cached check has teeth.
 */
final class HuggingFaceFormatFixture implements EcosystemFormatFixture {

    private static final String REGISTRY = "contract";
    private static final String REPO_ID = "acme/contract-model";
    private static final String REVISION = "main";
    private static final String RESOLVE = "/huggingface/" + REGISTRY + "/" + REPO_ID + "/resolve/" + REVISION + "/";
    private static final String INFO = "/huggingface/" + REGISTRY + "/api/models/" + REPO_ID;
    private static final String TREE = INFO + "/tree/" + REVISION;
    private static final URI ROOT = URI.create("https://hub.invalid/");

    /** An immutable commit sha the pull-through leg caches under - a 40-hex revision, so the proxy needs no upstream
     *  HEAD to resolve a branch and the cached key is the requested path itself. */
    private static final String COMMIT = "1111111111111111111111111111111111111111";
    private static final String PROXIED =
            "/huggingface/" + REGISTRY + "/" + REPO_ID + "/resolve/" + COMMIT + "/proxied.safetensors";

    private RepositoryFormat serving;

    @Override
    public String format() {
        return "huggingface";
    }

    @Override
    public Signatures signatures() {
        return Signatures.none("a model repository pins its large files by git-lfs oid. Commits may be GPG-signed in git, but "
                + "nothing signs the artifact bytes this serves, and the resolve endpoint exposes no signature.");
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.format.huggingface.HuggingFaceFormat";
    }

    @Override
    public RepositoryFormat serving() {
        if (serving == null) {
            serving = EcosystemFormatFixture.super.serving();
        }
        return serving;
    }

    /** The store root is {@code hf/} - the {@code /huggingface/} prefix is only the request path, which is exactly the
     *  kind of mismatch a namespace declaration has to state rather than assume. */
    @Override
    public List<String> namespaces() {
        return List.of("hf", "blobs");
    }

    @Override
    public Published publish(ArtifactStore store, byte[] body) throws IOException {
        put(store, RESOLVE + "model.safetensors", body);
        return new Published(RESOLVE + "model.safetensors", Packages.sha256(body));
    }

    @Override
    public Seeded seed(ArtifactStore store) throws IOException {
        put(store, RESOLVE + "model.safetensors", "seeded weights".getBytes(StandardCharsets.UTF_8));
        return new Seeded(REPO_ID, REVISION, RESOLVE + "model.safetensors");
    }

    @Override
    public String probe(String vector) {
        // The repo_id is the client-supplied element of every resolve path and becomes the nested
        // hf/<registry>/models/<repo_id>/... key directories verbatim - and it is legitimately MULTI-segment (a
        // namespaced repository), which is why the format validates it segment by segment rather than as a whole.
        return "/huggingface/" + REGISTRY + "/" + vector + "/resolve/" + REVISION + "/config.json";
    }

    @Override
    public Optional<Enumerated> enumerated(ArtifactStore store) throws IOException {
        put(store, RESOLVE + "config.json", "{\"model_type\":\"bert\"}".getBytes(StandardCharsets.UTF_8));
        put(store, RESOLVE + "model.safetensors", "the held weights".getBytes(StandardCharsets.UTF_8));
        return Optional.of(new Enumerated(RESOLVE + "model.safetensors",
                // The repo-info siblings list is what snapshot_download enumerates a repository from; the tree document
                // additionally publishes each file's oid (its content hash) and size. A hold has to clear both.
                List.of(new Probe(INFO, "model.safetensors"), new Probe(TREE, "model.safetensors")),
                target -> hold(target, REPO_ID, REVISION)));
    }

    @Override
    public Optional<Index> index(ArtifactStore store) throws IOException {
        put(store, RESOLVE + "config.json", "{\"model_type\":\"bert\"}".getBytes(StandardCharsets.UTF_8));
        return Optional.of(new Index(TREE, target ->
                put(target, RESOLVE + "tokenizer.json", "{\"version\":\"1.0\"}".getBytes(StandardCharsets.UTF_8))));
    }

    @Override
    public Optional<Upstream> upstream(GeneratedBody body) {
        return Optional.of(new Upstream(PROXIED, ROOT, fetcher(body, "\"" + body.sha256() + "\"")));
    }

    @Override
    public Optional<Upstream> tampered(GeneratedBody body) {
        // The Hub's X-Linked-Etag is the git-lfs object id - the content sha256 - so a body that does not hash to it
        // has been substituted somewhere between the LFS store and here. Nothing may be linked under the commit key.
        return Optional.of(new Upstream(PROXIED, ROOT, fetcher(body, "\"" + "0".repeat(64) + "\"")));
    }

    @Override
    public Map<FormatContract.Property, String> unsupported() {
        return Map.of(
                FormatContract.Property.PROXY_REFUSAL_IS_NOT_AN_ABSENCE, "audited 2026-08-24: the model and dataset info documents are ENUMERATIONs already refused as 502s, and "
                        + "an LFS-resolved file is named by the info document, so its absence is reported. ",
               FormatContract.Property.COORDINATE_TRAVERSAL_REFUSED,
                "HuggingFaceFormat DOES implement ArtifactLayout, but only for ecosystem()/describe(): paths() answers "
                        + "empty by design, because a repository file's pointer lives in the blobs namespace "
                        + "(hf/<registry>/<type>/<repo_id>/revs/<revision>/files/<encoded path>) rather than under "
                        + "publish/. The kit's leg would therefore fail its own non-vacuity check rather than prove "
                        + "anything. The seam this format really has is the BlobLayout - and a Hugging Face "
                        + "coordinate is a legitimately two-segment <namespace>/<name>, which is precisely why that "
                        + "seam screens it part by part. Proven over the same hostile coordinates by "
                        + "BlobLayoutCoordinateSeamTest in this module; the request seam is covered by "
                        + "REQUEST_PATH_TRAVERSAL_REFUSED");
    }

    /** An upstream Hub streaming a resolve download and declaring the file's git-lfs oid as its {@code X-Linked-Etag} -
     *  the LFS object's own content sha256, and the only validator on this response that is a digest of the raw bytes
     *  (a non-LFS file's {@code ETag} is a git-blob sha1 over {@code "blob <len>\0" + content}, which is why the format
     *  refuses to treat that one as a point checksum). */
    private static ProxyFormat.Fetcher fetcher(GeneratedBody body, String linkedEtag) {
        String artifact = ROOT + REPO_ID + "/resolve/" + COMMIT + "/proxied.safetensors";
        return new ProxyFormat.Fetcher.Buffered() {

            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) {
                return Optional.of(new ProxyFormat.Fetched(404, new byte[0], Map.of()));
            }

            @Override
            public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders) {
                return url.toString().equals(artifact)
                        ? Optional.of(new ProxyFormat.Download(200, body.open(),
                                Map.of("X-Repo-Commit", COMMIT, "X-Linked-Etag", linkedEtag)))
                        : Optional.of(new ProxyFormat.Download(404, InputStream.nullInputStream(), Map.of()));
            }
        };
    }

    private void put(ArtifactStore store, String path, byte[] body) throws IOException {
        seed(store, ContractExchange.of("PUT", path, body), 201);
    }
}
