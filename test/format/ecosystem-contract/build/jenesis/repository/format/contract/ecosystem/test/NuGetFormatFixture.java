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
 * The NuGet v3 format's leg of the shared contract - the format with the most enumeration surfaces here: a held
 * version has to leave the flat-container version list, the registration index a {@code dotnet restore} resolves
 * against, <em>and</em> the v3 search service, and all three are proven at once.
 *
 * <p>A NuGet push resolves its coordinate from the {@code .nuspec} inside the uploaded {@code .nupkg}, so the artifact
 * is not opaque to the publish protocol and the kit's arbitrary-body publish leg cannot apply; the same two properties
 * run over a real {@code .nupkg} through {@link PackagedArtifactContract}.
 */
final class NuGetFormatFixture implements EcosystemFormatFixture {

    private static final String ID = "contract.lib";
    private static final String FLAT = "/nuget/v3-flatcontainer/";
    private static final URI ROOT = URI.create("https://registry.invalid/");

    private static final String PROXIED_VERSION = "9.9.9";
    private static final String PROXIED = FLAT + ID + "/" + PROXIED_VERSION + "/" + nupkg(PROXIED_VERSION);

    private RepositoryFormat serving;

    @Override
    public String format() {
        return "nuget";
    }

    @Override
    public Signatures signatures() {
        return Signatures.of(ArtifactSignatures.Scheme.PKCS7);
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.format.nuget.NuGetFormat";
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
        return List.of("nuget", "blobs");
    }

    /** Unreachable: this format excludes both kit publish legs, so nothing calls this. It fails loudly rather than
     *  silently publishing something the caller would then compare the wrong bytes against. */
    @Override
    public Published publish(ArtifactStore store, byte[] body) {
        throw new AssertionError("nuget: a NuGet push reads the .nuspec inside the uploaded .nupkg, so an arbitrary "
                + "byte body is not a publishable artifact here. This fixture publishes a real .nupkg through "
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
        return new Seeded(ID, "1.0.0", served("1.0.0"));
    }

    @Override
    public String probe(String vector) {
        // The flat container's id segment is the client-supplied element that becomes a nuget/<id>/... key.
        return FLAT + vector + "/1.0.0/t202b.probe.1.0.0.nupkg";
    }

    @Override
    public Optional<Enumerated> enumerated(ArtifactStore store) throws IOException {
        push(store, "1.0.0");
        push(store, "2.0.0");
        // Three surfaces, one hold: the flat-container version list, the registration index (which would otherwise
        // advertise the held version's dependency graph and its download URL) and the search service's reported
        // versions and `latest`. A version left in any of them is a coordinate a restore then fails to download.
        return Optional.of(new Enumerated(served("2.0.0"),
                List.of(new Probe(FLAT + ID + "/index.json", "2.0.0"),
                        new Probe("/nuget/v3/registrations/" + ID + "/index.json", "2.0.0"),
                        new Probe("/nuget/v3/search", "2.0.0")),
                target -> hold(target, ID, "2.0.0")));
    }

    @Override
    public Optional<Index> index(ArtifactStore store) throws IOException {
        push(store, "1.0.0");
        return Optional.of(new Index(FLAT + ID + "/index.json", target -> push(target, "1.1.0")));
    }

    @Override
    public Optional<Upstream> upstream(GeneratedBody body) {
        return Optional.of(new Upstream(PROXIED, ROOT, fetcher(body, hash(body.digest("SHA-512")))));
    }

    @Override
    public Optional<Upstream> tampered(GeneratedBody body) {
        return Optional.of(new Upstream(PROXIED, ROOT, fetcher(body, hash("0".repeat(128)))));
    }

    @Override
    public Map<FormatContract.Property, String> unsupported() {
        return Map.of(

                FormatContract.Property.PROXY_REFUSAL_IS_NOT_AN_ABSENCE, "audited 2026-08-24: the service index, registration and flat-container pages are ENUMERATIONs already "
                        + "refused as 502s, and the .nupkg is a named version's artifact whose absence the client reports. ",
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
                "a NuGet push is a .nupkg whose .nuspec names the coordinate the package is stored under, so the "
                        + "protocol PARSES the artifact and an arbitrary byte body publishes nowhere - there is no "
                        + "request path that would then serve those bytes back. Restated, not dropped: "
                        + "PackagedArtifactContract runs the identical property over a real .nupkg published through "
                        + "this format's own push",
                FormatContract.Property.HEAD_ANSWERS_FROM_METADATA,
                "same protocol reason as PUBLISH_SERVES_EXACT_BYTES - the leg needs a published artifact and a NuGet "
                        + "publish takes only a parseable .nupkg. PackagedArtifactContract runs it over a real one, "
                        + "with the same sealed-blob proof (the HEAD fails if it opens the package) and the same "
                        + "non-vacuity check that a GET trips the seal",
                FormatContract.Property.COORDINATE_TRAVERSAL_REFUSED,
                "NuGetFormat implements no ArtifactLayout: a package's pointers live in the blobs namespace "
                        + "(nuget/<id>/<version>/...), not under publish/, so its coordinate-to-pointer mapping is the "
                        + "enterprise BlobLayout - blobKeys/servedPaths - which is what a retention eviction deletes. "
                        + "Proven over the same hostile coordinates by BlobLayoutCoordinateSeamTest in this module; "
                        + "the request seam is covered by REQUEST_PATH_TRAVERSAL_REFUSED");
    }

    /** An upstream v3 registry: the service index naming its registrations base, the registration leaf carrying the
     *  package's declared SHA-512, and the flat container streaming the package. */
    private static ProxyFormat.Fetcher fetcher(GeneratedBody body, String packageHash) {
        String index = ROOT + "v3/index.json";
        String registrations = ROOT + "v3/registrations/";
        String leaf = registrations + ID + "/" + PROXIED_VERSION + ".json";
        String artifact = ROOT + "v3-flatcontainer/" + ID + "/" + PROXIED_VERSION + "/" + nupkg(PROXIED_VERSION);
        String service = "{\"version\":\"3.0.0\",\"resources\":[{\"@id\":\"" + registrations
                + "\",\"@type\":\"RegistrationsBaseUrl\"}]}";
        String registration = "{\"packageHash\":\"" + packageHash + "\",\"packageHashAlgorithm\":\"SHA512\"}";
        return new ProxyFormat.Fetcher.Buffered() {

            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) {
                String requested = url.toString();
                if (requested.equals(index)) {
                    return Optional.of(new ProxyFormat.Fetched(200,
                            service.getBytes(StandardCharsets.UTF_8), Map.of()));
                }
                if (requested.equals(leaf)) {
                    return Optional.of(new ProxyFormat.Fetched(200,
                            registration.getBytes(StandardCharsets.UTF_8), Map.of()));
                }
                return Optional.of(new ProxyFormat.Fetched(404, new byte[0], Map.of()));
            }

            @Override
            public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders) {
                return url.toString().equals(artifact)
                        ? Optional.of(new ProxyFormat.Download(200, body.open(), Map.of()))
                        : Optional.of(new ProxyFormat.Download(404, InputStream.nullInputStream(), Map.of()));
            }
        };
    }

    /** NuGet's {@code packageHash}: base64 of the raw SHA-512, the spelling the v3 registration publishes. */
    private static String hash(String sha512Hex) {
        return Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha512Hex));
    }

    private static String nupkg(String version) {
        return ID + "." + version + ".nupkg";
    }

    private static String served(String version) {
        return FLAT + ID + "/" + version + "/" + nupkg(version);
    }

    /** Push one version through the format's own bare-body publish endpoint and hand back the exact bytes uploaded. */
    private byte[] push(ArtifactStore store, String version) throws IOException {
        byte[] artifact = Packages.nupkg(ID, version);
        seed(store, ContractExchange.of("PUT", "/nuget/v3/package", artifact), 201);
        return artifact;
    }
}
