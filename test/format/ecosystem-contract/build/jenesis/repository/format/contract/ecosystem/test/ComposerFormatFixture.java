package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.format.testkit.FormatContract;
import build.jenesis.repository.format.testkit.GeneratedBody;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The Composer (PHP) registry's leg of the shared contract. A push reopens the uploaded zip and reads the
 * {@code composer.json} inside it (refusing an archive that claims another {@code <vendor>/<package>}), so the artifact
 * is not opaque to the publish protocol and the two publish properties run over a real package archive through
 * {@link PackagedArtifactContract}.
 *
 * <p><b>Two enumeration surfaces.</b> Composer v2 discloses a package twice: the per-package
 * {@code p2/<vendor>/<package>.json} names its versions, and the {@code list.json} endpoint names the package itself.
 * They are screened by two different rules - the p2 file drops a withheld <em>version</em>, the list endpoint drops a
 * package none of whose versions is servable - so the fixture publishes a single version and requires the hold to clear
 * both. With two versions published the list endpoint would legitimately keep listing the package and the second
 * surface would go unexercised.
 */
final class ComposerFormatFixture implements EcosystemFormatFixture {

    private static final String REGISTRY = "contract";
    private static final String VENDOR = "contract";
    private static final String PACKAGE = "widget";
    private static final String COORDINATE = VENDOR + "/" + PACKAGE;
    private static final String P2 = "/composer/" + REGISTRY + "/p2/" + COORDINATE + ".json";
    private static final String LIST = "/composer/" + REGISTRY + "/list.json";
    private static final String DISTS = "/composer/" + REGISTRY + "/dists/" + COORDINATE + "/";
    private static final URI ROOT = URI.create("https://packagist.invalid");

    /** The proxied package, deliberately one nothing local publishes, so the pull-through leg is a real local miss. */
    private static final String PROXIED_COORDINATE = VENDOR + "/proxied";
    private static final String PROXIED_VERSION = "9.9.9";
    private static final String PROXIED =
            "/composer/" + REGISTRY + "/dists/" + PROXIED_COORDINATE + "/" + PROXIED_VERSION + ".zip";

    /** The file host an upstream p2 entry links its dist at - a different origin from the metadata, which is exactly
     *  why the {@code dist.shasum} that metadata vouches for is the only thing binding the two. */
    private static final String FILES = "https://dl.invalid/";

    private RepositoryFormat serving;

    @Override
    public String format() {
        return "composer";
    }

    @Override
    public Signatures signatures() {
        return Signatures.none("Packagist distributes source from VCS references and dist archives built from them, with a "
                + "per-entry checksum in the p2 metadata and no publisher signature anywhere in the protocol.");
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.format.composer.ComposerFormat";
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
        return List.of("composer", "blobs");
    }

    @Override
    public Published publish(ArtifactStore store, byte[] body) {
        throw new AssertionError("composer: a package push reopens the just-stored zip and reads the composer.json "
                + "inside it (refusing an archive that claims another <vendor>/<package>), so an arbitrary byte body "
                + "is not a publishable artifact here. This fixture publishes a real package archive through "
                + "publishPackage() and runs the same two properties through PackagedArtifactContract.");
    }

    @Override
    public Optional<Packaged> publishPackage(ArtifactStore store) throws IOException {
        byte[] artifact = push(store, "1.0.0");
        return Optional.of(new Packaged(artifact, DISTS + "1.0.0.zip", Packages.sha256(artifact)));
    }

    @Override
    public Seeded seed(ArtifactStore store) throws IOException {
        push(store, "1.0.0");
        return new Seeded(COORDINATE, "1.0.0", DISTS + "1.0.0.zip");
    }

    @Override
    public String probe(String vector) {
        // The version is the client-supplied element of a Composer publish path that becomes a key segment twice over
        // (composer/<repo>/dist/<vendor>/<pkg>/<version>.zip and .../index/<vendor>/<pkg>/<version>), and unlike the
        // vendor/package pair it is NOT cross-checked against the archive's composer.json - so a probing PUT carrying a
        // real manifest really reaches the pointer composition rather than being refused as a coordinate mismatch.
        return "/composer/" + REGISTRY + "/" + COORDINATE + "/" + vector;
    }

    /** A real package archive naming this fixture's coordinate, so a probing {@code PUT} reaches the pointer
     *  composition rather than being turned away as an unreadable manifest. */
    @Override
    public byte[] probeBody() {
        try {
            return Packages.composer(COORDINATE, "a contract probe package");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<Enumerated> enumerated(ArtifactStore store) throws IOException {
        push(store, "1.0.0");
        return Optional.of(new Enumerated(DISTS + "1.0.0.zip",
                // The per-package Composer-v2 metadata file names the version (with its dist url and dependency
                // constraints); the list endpoint a migration walk reads names the package itself. A hold has to leave
                // both - a package whose every version is withheld still listed in list.json is a coordinate composer
                // resolves a metadata-url for and then 404s on.
                List.of(new Probe(P2, "1.0.0"), new Probe(LIST, COORDINATE)),
                target -> hold(target, COORDINATE, "1.0.0")));
    }

    @Override
    public Optional<Index> index(ArtifactStore store) throws IOException {
        push(store, "1.0.0");
        return Optional.of(new Index(P2, target -> push(target, "1.1.0")));
    }

    @Override
    public Optional<Upstream> upstream(GeneratedBody body) {
        // Composer's dist.shasum is a SHA-1 of the archive - the digest a `composer install` itself checks a download
        // against - so that is the digest this leg is held to, computed off the generated body without reading it.
        return Optional.of(new Upstream(PROXIED, ROOT, fetcher(body.digest("SHA-1"), body)));
    }

    @Override
    public Optional<Upstream> tampered(GeneratedBody body) {
        // The p2 entry vouches for a shasum the file host's bytes do not hash to - exactly the diverging-origin case
        // dist.shasum exists to catch, since the metadata and the dist are different hosts.
        return Optional.of(new Upstream(PROXIED, ROOT, fetcher("0".repeat(40), body)));
    }

    @Override
    public Map<FormatContract.Property, String> unsupported() {
        return Map.of(
                FormatContract.Property.PROXY_REFUSAL_IS_NOT_AN_ABSENCE, "audited 2026-08-24: packages.json and the p2 metadata files are ENUMERATIONs already refused as 502s, "
                        + "and the dist archive is a named version's artifact whose absence fails the install. ",
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
                "a Composer push reopens the just-stored blob and reads the package's root composer.json out of the "
                        + "zip (refusing one that claims a different <vendor>/<package> than the deploy path), so the "
                        + "protocol PARSES the artifact and an arbitrary byte body publishes nowhere. Restated, not "
                        + "dropped: PackagedArtifactContract runs the identical property over a real package archive "
                        + "published through this format's own PUT",
                FormatContract.Property.HEAD_ANSWERS_FROM_METADATA,
                "same protocol reason as PUBLISH_SERVES_EXACT_BYTES. PackagedArtifactContract runs it over a real "
                        + "package archive with the same sealed-blob proof and the same non-vacuity check",
                FormatContract.Property.COORDINATE_TRAVERSAL_REFUSED,
                "ComposerFormat DOES implement ArtifactLayout, but only for ecosystem()/describe(): paths() answers "
                        + "empty by design, because a package's pointers live in the blobs namespace "
                        + "(composer/<repo>/dist/<vendor>/<pkg>/<version>.zip) rather than under publish/. The kit's "
                        + "leg would therefore fail its own non-vacuity check rather than prove anything. The seam this "
                        + "format really has is the BlobLayout - and a Composer coordinate is a "
                        + "legitimately two-segment <vendor>/<package>, which is precisely why that seam screens it "
                        + "part by part. Proven over the same hostile coordinates by BlobLayoutCoordinateSeamTest in "
                        + "this module; the request seam is covered by REQUEST_PATH_TRAVERSAL_REFUSED");
    }

    /** An upstream Composer-v2 registry: the per-package {@code p2} file carrying the version's {@code dist.url} and
     *  the {@code dist.shasum} (Composer's SHA-1) it vouches for, plus the file host streaming the archive. */
    private static ProxyFormat.Fetcher fetcher(String shasum, GeneratedBody body) {
        String metadata = ROOT + "/p2/" + PROXIED_COORDINATE + ".json";
        String archive = FILES + "proxied-" + PROXIED_VERSION + ".zip";
        String document = "{\"minified\":\"composer/2.0\",\"packages\":{\"" + PROXIED_COORDINATE + "\":["
                + "{\"name\":\"" + PROXIED_COORDINATE + "\",\"version\":\"" + PROXIED_VERSION + "\","
                + "\"dist\":{\"type\":\"zip\",\"url\":\"" + archive + "\",\"reference\":\"cafebabe\","
                + "\"shasum\":\"" + shasum + "\"}}]}}";
        return new ProxyFormat.Fetcher.Buffered() {

            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) {
                return url.toString().equals(metadata)
                        ? Optional.of(new ProxyFormat.Fetched(200, document.getBytes(StandardCharsets.UTF_8), Map.of()))
                        : Optional.of(new ProxyFormat.Fetched(404, new byte[0], Map.of()));
            }

            @Override
            public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders) {
                return url.toString().equals(archive)
                        ? Optional.of(new ProxyFormat.Download(200, body.open(), Map.of()))
                        : Optional.of(new ProxyFormat.Download(404, InputStream.nullInputStream(), Map.of()));
            }
        };
    }

    private byte[] push(ArtifactStore store, String version) throws IOException {
        byte[] artifact = Packages.composer(COORDINATE, "a contract fixture package " + version);
        seed(store, ContractExchange.of("PUT", "/composer/" + REGISTRY + "/" + COORDINATE + "/" + version, artifact),
                201);
        return artifact;
    }
}
