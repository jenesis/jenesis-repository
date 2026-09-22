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
 * The RubyGems format's leg of the shared contract. A {@code gem push} reads the gzipped YAML gemspec inside the
 * uploaded {@code .gem}, so the artifact is not opaque to the publish protocol and the two publish properties run over
 * a real gem through {@link PackagedArtifactContract} instead.
 *
 * <p>Its two enumeration surfaces are the per-gem {@code /info/<gem>} document and the repository-wide compact index
 * at {@code /versions}, and the second is <em>cached</em> - which is why holding a version has to invalidate that
 * cache rather than merely screen a live render. Its proxy leg holds a fetched gem to the {@code checksum:<sha256>}
 * the upstream compact index publishes for that version.
 */
final class RubyGemsFormatFixture implements EcosystemFormatFixture {

    private static final String GEM = "contract-lib";
    private static final URI ROOT = URI.create("https://gems.invalid/");

    private static final String PROXIED_VERSION = "9.9.9";
    private static final String PROXIED = "/rubygems/gems/" + GEM + "-" + PROXIED_VERSION + ".gem";

    private RepositoryFormat serving;

    @Override
    public String format() {
        return "rubygems";
    }

    @Override
    public Signatures signatures() {
        return Signatures.of(ArtifactSignatures.Scheme.X509_DETACHED);
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.format.gems.RubyGemsFormat";
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
        // rubygemsindex holds the precomputed compact index (and its withheld-aware twin), which the /versions read
        // rolls forward - a real namespace of this format, not a leak.
        return List.of("rubygemfiles", "rubygems", "rubygemsindex", "blobs");
    }

    @Override
    public Published publish(ArtifactStore store, byte[] body) {
        throw new AssertionError("rubygems: a gem push reads the gzipped YAML gemspec inside the uploaded .gem, so an "
                + "arbitrary byte body is not a publishable artifact here. This fixture publishes a real .gem through "
                + "publishPackage() and runs the same two properties through PackagedArtifactContract.");
    }

    @Override
    public Optional<Packaged> publishPackage(ArtifactStore store) throws IOException {
        byte[] artifact = push(store, "1.0.0");
        return Optional.of(new Packaged(artifact, served("1.0.0"), Packages.sha256(artifact)));
    }

    @Override
    public Seeded seed(ArtifactStore store) throws IOException {
        push(store, "1.0.0");
        return new Seeded(GEM, "1.0.0", served("1.0.0"));
    }

    @Override
    public String probe(String vector) {
        // The gem download path's tail is spliced straight into a rubygemfiles/ pointer key, so that is where the
        // vector goes, with a well-formed .gem filename after it so the route is really taken.
        return "/rubygems/gems/" + vector + "/t202b-probe-1.0.0.gem";
    }

    @Override
    public Optional<Enumerated> enumerated(ArtifactStore store) throws IOException {
        push(store, "1.0.0");
        push(store, "2.0.0");
        // Two surfaces: the per-gem /info document a `gem install` resolves against, and the repository-wide compact
        // index /versions a bundler reads - the latter served from a cache the push rolls forward, so a hold has to
        // reach a document no writer touched.
        return Optional.of(new Enumerated(served("2.0.0"),
                List.of(new Probe("/rubygems/info/" + GEM, "2.0.0"),
                        new Probe("/rubygems/versions", "2.0.0")),
                target -> hold(target, GEM, "2.0.0")));
    }

    @Override
    public Optional<Index> index(ArtifactStore store) throws IOException {
        push(store, "1.0.0");
        return Optional.of(new Index("/rubygems/versions", target -> push(target, "1.1.0")));
    }

    @Override
    public Optional<Upstream> upstream(GeneratedBody body) {
        return Optional.of(new Upstream(PROXIED, ROOT, fetcher(body, body.sha256())));
    }

    @Override
    public Optional<Upstream> tampered(GeneratedBody body) {
        return Optional.of(new Upstream(PROXIED, ROOT, fetcher(body, "0".repeat(64))));
    }

    @Override
    public Map<FormatContract.Property, String> unsupported() {
        return Map.of(

                FormatContract.Property.PROXY_REFUSAL_IS_NOT_AN_ABSENCE, ELECTIVE_PATH_NOT_AUDITED,
                FormatContract.Property.PUBLISH_PATHS_ARE_DESCRIBED,
                "same protocol reason as PUBLISH_SERVES_EXACT_BYTES: the kit's arbitrary body publishes nowhere here. "
                        + "Restated, not dropped: PackagedArtifactContract records where a real package publish "
                        + "writes and requires the format to place every one of those paths",
                FormatContract.Property.HELD_THEN_RELEASED_SERVES_AGAIN,
                "same protocol reason as PUBLISH_SERVES_EXACT_BYTES: the kit's arbitrary body publishes nowhere here. "
                        + "Restated, not dropped: PackagedArtifactContract holds and releases a real package and "
                        + "requires it to serve again",
                FormatContract.Property.GONE_BLOB_IS_A_CLEAN_404,
                "same protocol reason as PUBLISH_SERVES_EXACT_BYTES: the kit's arbitrary body publishes nowhere here. "
                        + "Restated, not dropped: PackagedArtifactContract deletes the blob behind a real package and "
                        + "requires the clean 404",
                FormatContract.Property.PUBLISH_SERVES_EXACT_BYTES,
                "a gem push is a .gem whose metadata.gz gemspec names the coordinate the gem is stored under, so the "
                        + "protocol PARSES the artifact and an arbitrary byte body publishes nowhere. Restated, not "
                        + "dropped: PackagedArtifactContract runs the identical property over a real .gem published "
                        + "through this format's own POST /rubygems/api/v1/gems",
                FormatContract.Property.HEAD_ANSWERS_FROM_METADATA,
                "same protocol reason as PUBLISH_SERVES_EXACT_BYTES - the leg needs a published artifact and a gem "
                        + "push takes only a parseable .gem. PackagedArtifactContract runs it over a real one with the "
                        + "same sealed-blob proof and the same non-vacuity check",
                FormatContract.Property.COORDINATE_TRAVERSAL_REFUSED,
                "RubyGemsFormat implements no ArtifactLayout: a gem's pointers live in the blobs namespace "
                        + "(rubygemfiles/<name>-<version>.gem, rubygems/<name>/versions/<version>), not under "
                        + "publish/, so its coordinate-to-pointer mapping is the BlobLayout - and there it "
                        + "matters especially, because the coordinate and the version are CONCATENATED into one "
                        + "filename. Proven over the same hostile coordinates by BlobLayoutCoordinateSeamTest in this "
                        + "module; the request seam is covered by REQUEST_PATH_TRAVERSAL_REFUSED");
    }

    /** An upstream mirror answering the compact-index {@code /info} document that declares this version's checksum,
     *  and the gem itself as a stream. */
    private static ProxyFormat.Fetcher fetcher(GeneratedBody body, String sha256) {
        String info = ROOT + "info/" + GEM;
        String artifact = ROOT + "gems/" + GEM + "-" + PROXIED_VERSION + ".gem";
        String index = "---\n" + PROXIED_VERSION + " |checksum:" + sha256 + "\n";
        return new ProxyFormat.Fetcher.Buffered() {

            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) {
                return url.toString().equals(info)
                        ? Optional.of(new ProxyFormat.Fetched(200, index.getBytes(StandardCharsets.UTF_8), Map.of()))
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

    private static String served(String version) {
        return "/rubygems/gems/" + GEM + "-" + version + ".gem";
    }

    private byte[] push(ArtifactStore store, String version) throws IOException {
        byte[] artifact = Packages.gem(GEM, version);
        seed(store, ContractExchange.of("POST", "/rubygems/api/v1/gems", artifact), 200);
        return artifact;
    }
}
