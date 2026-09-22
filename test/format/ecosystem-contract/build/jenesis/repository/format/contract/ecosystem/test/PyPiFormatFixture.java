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
 * The PyPI format's leg of the shared contract. A {@code twine} upload carries the distribution as an opaque multipart
 * part, so the kit's own publish leg applies verbatim; the PEP 503 project page is both the generated document and the
 * enumeration surface; and the proxy leg holds a fetched distribution to the {@code #sha256=} fragment the upstream
 * Simple index publishes beside its link.
 */
final class PyPiFormatFixture implements EcosystemFormatFixture {

    private static final String PROJECT = "contract-lib";
    private static final String DISTRIBUTION = "contract_lib";
    private static final String INDEX = "/pypi/simple/" + PROJECT + "/";
    private static final String BOUNDARY = "t202b-twine-boundary";
    private static final URI ROOT = URI.create("https://index.example/");

    /** The file host a Simple index links its distributions at - a different host from the index, which is why the
     *  digest the index vouches for is the only thing binding the two. */
    private static final String FILES = "https://files.example/packages/";

    private static final String PROXIED_VERSION = "9.9.9";
    private static final String PROXIED_FILE = wheel(PROXIED_VERSION);
    private static final String PROXIED = INDEX + PROXIED_FILE;

    private RepositoryFormat serving;

    @Override
    public String format() {
        return "pypi";
    }

    @Override
    public Signatures signatures() {
        return Signatures.of(ArtifactSignatures.Scheme.SIGSTORE_BUNDLE);
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.format.pypi.PyPiFormat";
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
        return List.of("pypi", "blobs");
    }

    @Override
    public Published publish(ArtifactStore store, byte[] body) throws IOException {
        upload(store, "1.0.0", body);
        return new Published(INDEX + wheel("1.0.0"), Packages.sha256(body));
    }

    @Override
    public Seeded seed(ArtifactStore store) throws IOException {
        upload(store, "1.0.0", "seeded wheel".getBytes(StandardCharsets.UTF_8));
        return new Seeded(PROJECT, "1.0.0", INDEX + wheel("1.0.0"));
    }

    @Override
    public String probe(String vector) {
        // The project segment and the filename are the two client-supplied elements of a Simple-API read; splicing the
        // vector between them exercises both, since the served key is pypi/<project>/files/<filename>.
        return "/pypi/simple/" + vector + "/t202b_probe-1.0.0-py3-none-any.whl";
    }

    @Override
    public Optional<Enumerated> enumerated(ArtifactStore store) throws IOException {
        upload(store, "1.0.0", "keep".getBytes(StandardCharsets.UTF_8));
        upload(store, "2.0.0", "held".getBytes(StandardCharsets.UTF_8));
        // The per-project Simple page is the disclosure surface: a distribution a hold withholds must leave it, or pip
        // would read a link (with its #sha256) for a file the download now 404s.
        return Optional.of(new Enumerated(INDEX + wheel("2.0.0"),
                List.of(new Probe(INDEX, wheel("2.0.0"))),
                target -> hold(target, PROJECT, "2.0.0")));
    }

    @Override
    public Optional<Index> index(ArtifactStore store) throws IOException {
        upload(store, "1.0.0", "one".getBytes(StandardCharsets.UTF_8));
        return Optional.of(new Index(INDEX,
                target -> upload(target, "1.1.0", "two".getBytes(StandardCharsets.UTF_8))));
    }

    @Override
    public Optional<Upstream> upstream(GeneratedBody body) {
        return Optional.of(new Upstream(PROXIED, ROOT, fetcher(body, body.sha256())));
    }

    @Override
    public Optional<Upstream> tampered(GeneratedBody body) {
        // The index vouches for a digest the file host's bytes do not hash to: exactly the diverging-file-host case
        // the #sha256 fragment exists to catch, since the two are different origins.
        return Optional.of(new Upstream(PROXIED, ROOT, fetcher(body, "0".repeat(64))));
    }

    @Override
    public Map<FormatContract.Property, String> unsupported() {
        return Map.of(

                FormatContract.Property.COORDINATE_TRAVERSAL_REFUSED,
                "PyPiFormat implements no ArtifactLayout: a distribution's pointers live in the blobs namespace "
                        + "(pypi/<project>/files/<file>), not under publish/, so its coordinate-to-pointer mapping is "
                        + "the BlobLayout - blobKeys/servedPaths - which is what a retention eviction "
                        + "deletes and what a retroactive hold marks. It is proven over the same hostile coordinates "
                        + "by BlobLayoutCoordinateSeamTest in this module; the request seam is covered by "
                        + "REQUEST_PATH_TRAVERSAL_REFUSED");
    }

    /**
     * PyPI's elective path is the PEP 658 {@code .metadata} sidecar.
     *
     * <p>Most distributions publish none, so a {@code 404} is the ordinary answer and pip acts on it: it downloads the
     * whole wheel and reads {@code METADATA} out of that instead, and reports success. That makes this the one PyPI
     * path where a refusal spelled as a miss becomes a different resolution rather than a failed install - and the
     * consequence is not cosmetic, because pip resolves DEPENDENCIES from this document, so a substituted one changes
     * which packages are installed while every wheel that is eventually fetched still passes its own digest check.
     */
    @Override
    public Optional<Elective> elective(GeneratedBody body) {
        return Optional.of(new Elective(PROXIED + ".metadata", ROOT, nothing(), sidecar(body, "0".repeat(64))));
    }

    /** An upstream index that publishes the distribution but declares no sidecar, and a file host with nothing at the
     *  sidecar path - the common, legal case a client resolves around. */
    private static ProxyFormat.Fetcher nothing() {
        return sidecar(null, null);
    }

    /** An index whose anchor declares {@code metadataSha256} for the sidecar, and a file host serving {@code body} at
     *  the sidecar path - so a disagreeing digest is a refusal the client must be able to see. */
    private static ProxyFormat.Fetcher sidecar(GeneratedBody body, String metadataSha256) {
        String index = ROOT + "simple/" + PROJECT + "/";
        String file = FILES + PROXIED_FILE;
        String attribute = metadataSha256 == null ? "" : " data-core-metadata=\"sha256=" + metadataSha256 + "\"";
        String page = "<!DOCTYPE html><html><body><a href=\"" + file + "#sha256=" + "0".repeat(64) + "\""
                + attribute + ">" + PROXIED_FILE + "</a><br/></body></html>";
        return new ProxyFormat.Fetcher.Buffered() {

            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) {
                return url.toString().equals(index)
                        ? Optional.of(new ProxyFormat.Fetched(200, page.getBytes(StandardCharsets.UTF_8), Map.of()))
                        : Optional.of(new ProxyFormat.Fetched(404, new byte[0], Map.of()));
            }

            @Override
            public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders) {
                return body != null && url.toString().equals(file + ".metadata")
                        ? Optional.of(new ProxyFormat.Download(200, body.open(), Map.of()))
                        : Optional.of(new ProxyFormat.Download(404, InputStream.nullInputStream(), Map.of()));
            }
        };
    }

    /** An upstream Simple index linking the distribution on a different host, with the digest that index vouches for,
     *  plus that host answering the download as a stream. */
    private static ProxyFormat.Fetcher fetcher(GeneratedBody body, String sha256) {
        String index = ROOT + "simple/" + PROJECT + "/";
        String file = FILES + PROXIED_FILE;
        String page = "<!DOCTYPE html><html><body><a href=\"" + file + "#sha256=" + sha256 + "\">"
                + PROXIED_FILE + "</a><br/></body></html>";
        return new ProxyFormat.Fetcher.Buffered() {

            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) {
                return url.toString().equals(index)
                        ? Optional.of(new ProxyFormat.Fetched(200, page.getBytes(StandardCharsets.UTF_8), Map.of()))
                        : Optional.of(new ProxyFormat.Fetched(404, new byte[0], Map.of()));
            }

            @Override
            public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders) {
                return url.toString().equals(file)
                        ? Optional.of(new ProxyFormat.Download(200, body.open(), Map.of()))
                        : Optional.of(new ProxyFormat.Download(404, InputStream.nullInputStream(), Map.of()));
            }
        };
    }

    private static String wheel(String version) {
        return DISTRIBUTION + "-" + version + "-py3-none-any.whl";
    }

    private void upload(ArtifactStore store, String version, byte[] distribution) throws IOException {
        byte[] form = Packages.twineForm(BOUNDARY, PROJECT, wheel(version), distribution);
        seed(store, ContractExchange.of("POST", "/pypi/", form)
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY), 200);
    }
}
