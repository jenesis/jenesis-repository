package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.testkit.GeneratedBody;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.format.testkit.ContractHold;
import build.jenesis.repository.format.testkit.FormatContract;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.format.ArtifactSignatures;

/**
 * The Ivy repository's leg of the shared contract.
 *
 * <p>An Ivy artifact is opaque to its own publish protocol - the server stores what it is handed, as Maven and raw
 * do - so this runs the kit's generic publish legs with an arbitrary body rather than needing a real package, and
 * it inherits {@code VERSION_PREFIXES_ARE_EXCLUSIVE} with no work of its own as the third layout to publish under
 * {@code publish/} after Maven and Jenesis.
 *
 * <p><b>The enumeration probed is the module's revision listing</b>, which is the one document an Ivy repository
 * publishes - and the one a resolver asked for {@code 1.+} chooses from. A revision left in it after its bytes are
 * withheld is a revision the resolver <em>selects</em> and then fails to download, so the hold leaving this
 * document is not bookkeeping: it is the difference between "that version is not offered" and "that version is
 * offered and broken".
 */
final class IvyFormatFixture implements EcosystemFormatFixture {

    private static final String ORGANISATION = "com.contract";

    private static final String MODULE = "widget";

    private static final String REVISION = "1.0.0";

    /** The coordinate these artifacts report, in the space they are declared to: {@code organisation:module}, which
     *  is a Maven {@code groupId:artifactId} precisely because the accepted layout is restricted to the one Gradle
     *  writes the group into. */
    private static final String COORDINATE = ORGANISATION + ":" + MODULE;

    /** The upstream Ivy repository a proxy leg reads, and the revision it serves. */
    private static final URI ROOT = URI.create("https://ivy.invalid/repository/");
    private static final String PROXIED_REVISION = "9.9.9";
    private static final String PROXIED_DIRECTORY = ORGANISATION + "/" + MODULE + "/" + PROXIED_REVISION + "/";
    private static final String PROXIED_JAR = PROXIED_DIRECTORY + MODULE + "-" + PROXIED_REVISION + ".jar";
    private static final String PROXIED_DESCRIPTOR = PROXIED_DIRECTORY + "ivy-" + PROXIED_REVISION + ".xml";

    private RepositoryFormat serving;

    @Override
    public String format() {
        return "ivy";
    }

    @Override
    public Signatures signatures() {
        return Signatures.of(ArtifactSignatures.Scheme.OPENPGP_DETACHED, ArtifactSignatures.Scheme.SIGSTORE_BUNDLE);
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.format.ivy.IvyFormat";
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
        // `listing` because this format maintains one: the module's revisions, which is where a resolver reads a
        // dynamic revision from. Declaring it is not paperwork - the kit's traversal probes assert that a hostile
        // coordinate composes no key OUTSIDE these, so an undeclared namespace reads as an escape.
        return List.of("publish", "blobs", "listing");
    }

    @Override
    public Published publish(ArtifactStore store, byte[] body) throws IOException {
        return publish(store, body, REVISION);
    }

    private Published publish(ArtifactStore store, byte[] body, String revision) throws IOException {
        String path = artifact(revision);
        put(store, path, body);
        return new Published(path, Packages.sha256(body));
    }

    @Override
    public Seeded seed(ArtifactStore store) throws IOException {
        publish(store, ("an ivy artifact of " + COORDINATE).getBytes(StandardCharsets.UTF_8));
        return new Seeded(COORDINATE, REVISION, artifact(REVISION));
    }

    @Override
    public String probe(String vector) {
        // The organisation is the first client-supplied segment and composes the pointer key, so that is where the
        // vector goes - with a well-formed module, revision and file after it, so the request reaches the path
        // composition rather than being turned away by the segment-count screen first.
        return "/ivy/" + vector + "/" + MODULE + "/" + REVISION + "/" + MODULE + "-" + REVISION + ".jar";
    }

    @Override
    public Optional<Enumerated> enumerated(ArtifactStore store) throws IOException {
        // Both revisions are published HERE: the kit hands a fresh store, so a document that named a version
        // nothing had published would be asserting against an emptiness. The second one exists so the listing has
        // something to keep while it loses the held one - a probe over a document that empties entirely would pass
        // against a format that simply deleted it.
        publish(store, ("an artifact of " + COORDINATE).getBytes(StandardCharsets.UTF_8), REVISION);
        publish(store, ("another artifact of " + COORDINATE).getBytes(StandardCharsets.UTF_8), "2.0.0");
        String held = artifact(REVISION);
        return Optional.of(new Enumerated(held,
                List.of(new Probe("/ivy/" + ORGANISATION + "/" + MODULE, REVISION)),
                holding -> ContractHold.mark(holding, held)));
    }

    @Override
    public Optional<Index> index(ArtifactStore store) {
        return Optional.empty();
    }

    @Override
    public Map<FormatContract.Property, String> unsupported() {
        return Map.of(
                FormatContract.Property.GENERATED_INDEX_IS_REVALIDATABLE, STORED_NOT_GENERATED,
                FormatContract.Property.GENERATED_INDEX_CARRIES_THE_REQUEST_SCHEME, STORED_NOT_GENERATED);
    }

    /** Not a gap: the revision listing is a STORED document streamed as it is, not one rendered per request, so
     *  there is nothing generated here for these two to be about. */
    private static final String STORED_NOT_GENERATED =
            "the revision listing is a stored document maintained on the write path and streamed as it is, not a "
                    + "document rendered on read - so it carries no request-derived content to complete on the way "
                    + "out and nothing to revalidate a rendering against. That it empties when a hold takes the "
                    + "last revision is WITHHELD_VERSION_LEAVES_EVERY_ENUMERATION's business, which this fixture "
                    + "does assert";

    @Override
    public Optional<Upstream> upstream(GeneratedBody body) {
        return Optional.of(new Upstream("/ivy/" + PROXIED_JAR, ROOT,
                fetcher(PROXIED_JAR, body, body.digest("SHA-1"))));
    }

    @Override
    public Optional<Upstream> tampered(GeneratedBody body) {
        return Optional.of(new Upstream("/ivy/" + PROXIED_JAR, ROOT, fetcher(PROXIED_JAR, body, "0".repeat(40))));
    }

    /** The descriptor: with none, Ivy assumes a module of one jar and no dependencies, so a refusal of it must not
     *  read as an absence. */
    @Override
    public Optional<Elective> elective(GeneratedBody body) {
        return Optional.of(new Elective("/ivy/" + PROXIED_DESCRIPTOR, ROOT, fetcher(PROXIED_DESCRIPTOR, null, null),
                fetcher(PROXIED_DESCRIPTOR, body, "0".repeat(40))));
    }

    /** An upstream Ivy repository serving {@code file} and a {@code .sha1} beside it, or nothing at all when there is
     *  no body. */
    private static ProxyFormat.Fetcher fetcher(String file, GeneratedBody body, String sha1) {
        String artifact = ROOT + file;
        return new ProxyFormat.Fetcher.Buffered() {

            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) {
                return body != null && url.toString().equals(artifact + ".sha1")
                        ? Optional.of(new ProxyFormat.Fetched(200, sha1.getBytes(StandardCharsets.UTF_8), Map.of()))
                        : Optional.of(new ProxyFormat.Fetched(404, new byte[0], Map.of()));
            }

            @Override
            public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders) {
                if (body != null && url.toString().equals(artifact)) {
                    return Optional.of(new ProxyFormat.Download(200, body.open(), Map.of()));
                }
                return fetch(url, requestHeaders).map(fetched -> new ProxyFormat.Download(fetched.status(),
                        new ByteArrayInputStream(fetched.body()), Map.of()));
            }
        };
    }

    private void put(ArtifactStore store, String path, byte[] body) throws IOException {
        seed(store, ContractExchange.of("PUT", path, body), 201);
    }

    private static String artifact(String revision) {
        return "/ivy/" + ORGANISATION + "/" + MODULE + "/" + revision + "/" + MODULE + "-" + revision + ".jar";
    }
}
