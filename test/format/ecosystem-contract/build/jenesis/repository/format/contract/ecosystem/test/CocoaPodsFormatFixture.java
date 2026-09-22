package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.format.testkit.FormatContract;
import build.jenesis.repository.format.testkit.GeneratedBody;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The CocoaPods CDN's leg of the shared contract. A push reopens the uploaded zip and reads the
 * {@code <name>.podspec.json} inside it (refusing an archive that claims another coordinate), so the artifact is not
 * opaque to the publish protocol and the two publish properties run over a real pod archive through
 * {@link PackagedArtifactContract}.
 *
 * <p><b>Two enumeration surfaces, both sharded by {@code MD5(name)}.</b> The CDN discloses a pod version through the
 * {@code all_pods_versions_<a>_<b>_<c>.txt} listing for its shard <em>and</em> through the version's own
 * {@code Specs/<a>/<b>/<c>/<name>/<version>/<name>.podspec.json}, and they are screened by two different mechanisms (a
 * screened enumeration over the shard, a per-key withheld probe on the podspec read). The fixture computes the shard
 * the way a {@code pod install} does - the first three hex characters of {@code MD5(name)} - so both probes land where
 * a client would look rather than where the store happens to have put them.
 */
final class CocoaPodsFormatFixture implements EcosystemFormatFixture {

    private static final String REGISTRY = "contract";
    private static final String POD = "ContractKit";
    private static final String[] SHARD = shard(POD);
    private static final String SHARD_LISTING = "/cocoapods/" + REGISTRY + "/all_pods_versions_"
            + SHARD[0] + "_" + SHARD[1] + "_" + SHARD[2] + ".txt";
    private static final String SPECS = "/cocoapods/" + REGISTRY + "/Specs/"
            + SHARD[0] + "/" + SHARD[1] + "/" + SHARD[2] + "/" + POD + "/";
    private static final String PODS = "/cocoapods/" + REGISTRY + "/pods/" + POD + "/";
    private static final URI ROOT = URI.create("https://cdn.invalid/");

    /** The proxied pod, deliberately one nothing local publishes, so the pull-through leg is a real local miss. */
    private static final String PROXIED_POD = "ProxiedKit";
    private static final String PROXIED_VERSION = "9.9.9";
    private static final String PROXIED = "/cocoapods/" + REGISTRY + "/pods/" + PROXIED_POD + "/"
            + PROXIED_VERSION + "/" + PROXIED_POD + ".zip";

    /** The download host a podspec's {@code :http} source points at - a different origin from the CDN, which hosts
     *  only metadata, so the podspec's declared checksum is the only thing binding the two. */
    private static final String FILES = "https://dl.invalid/";

    private RepositoryFormat serving;

    @Override
    public String format() {
        return "cocoapods";
    }

    @Override
    public Signatures signatures() {
        return Signatures.none("a podspec points at a source archive and pins it with a :sha256 or :sha1 checksum. There is no "
                + "signing in the CocoaPods protocol - the trust anchor is the spec repository itself.");
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.format.cocoapods.CocoaPodsFormat";
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
        return List.of("cocoapods", "blobs");
    }

    @Override
    public Published publish(ArtifactStore store, byte[] body) {
        throw new AssertionError("cocoapods: a pod push reopens the just-stored zip and reads the .podspec.json inside "
                + "it (refusing an archive that claims another name/version), so an arbitrary byte body is not a "
                + "publishable artifact here. This fixture publishes a real pod archive through publishPackage() and "
                + "runs the same two properties through PackagedArtifactContract.");
    }

    @Override
    public Optional<Packaged> publishPackage(ArtifactStore store) throws IOException {
        byte[] artifact = push(store, "1.0.0");
        return Optional.of(new Packaged(artifact, download("1.0.0"), Packages.sha256(artifact)));
    }

    @Override
    public Seeded seed(ArtifactStore store) throws IOException {
        push(store, "1.0.0");
        return new Seeded(POD, "1.0.0", download("1.0.0"));
    }

    @Override
    public String probe(String vector) {
        // The pod name is the client-supplied element that becomes BOTH key segments - the download pointer
        // cocoapods/<repo>/blob/<name>/<version> and, through MD5(name), the sharded podspec key - so that is where a
        // vector goes, with a well-formed version after it.
        return "/cocoapods/" + REGISTRY + "/" + vector + "/1.0.0";
    }

    /** A real pod archive whose podspec declares only the version, so a probing {@code PUT} reaches the pointer
     *  composition: the name it would otherwise have to declare is the probe vector itself, and the format (correctly)
     *  refuses a podspec that names a different pod than the deploy path. An absent {@code name} is the shape the
     *  format explicitly tolerates - it only cross-checks a value that is present. */
    @Override
    public byte[] probeBody() {
        try {
            return Packages.zip(Map.of("t202c-probe.podspec.json",
                    "{\"version\":\"1.0.0\",\"summary\":\"a contract probe pod\",\"license\":\"MIT\"}"
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<Enumerated> enumerated(ArtifactStore store) throws IOException {
        push(store, "1.0.0");
        push(store, "2.0.0");
        return Optional.of(new Enumerated(download("2.0.0"),
                // The shard listing carries the pod's whole version line (name/v1/v2/...), and the version's podspec
                // carries its source, license and dependencies - a client reads the first to resolve and the second to
                // install, so a withheld version left in either is a pod `pod install` then 404s downloading.
                List.of(new Probe(SHARD_LISTING, "2.0.0"),
                        new Probe(SPECS + "2.0.0/" + POD + ".podspec.json", "2.0.0")),
                target -> hold(target, POD, "2.0.0")));
    }

    @Override
    public Optional<Index> index(ArtifactStore store) throws IOException {
        push(store, "1.0.0");
        return Optional.of(new Index(SHARD_LISTING, target -> push(target, "1.1.0")));
    }

    @Override
    public Optional<Upstream> upstream(GeneratedBody body) {
        return Optional.of(new Upstream(PROXIED, ROOT, fetcher(body, body.sha256())));
    }

    @Override
    public Optional<Upstream> tampered(GeneratedBody body) {
        // The upstream podspec vouches for a :sha256 the download host's bytes do not hash to - the diverging-origin
        // case the podspec checksum exists to catch, since the CDN hosts metadata and someone else hosts the archive.
        return Optional.of(new Upstream(PROXIED, ROOT, fetcher(body, "0".repeat(64))));
    }

    @Override
    public Map<FormatContract.Property, String> unsupported() {
        return Map.of(
                FormatContract.Property.PROXY_REFUSAL_IS_NOT_AN_ABSENCE, "audited 2026-08-24: the CDN shard listing is an ENUMERATION already refused as a 502, and a podspec "
                        + "request names both pod and version, so its absence is \"no such podspec\" and CocoaPods errors rather than "
                        + "resolving past it. ",
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
                "a CocoaPods push reopens the just-stored blob and reads the pod's .podspec.json out of the zip "
                        + "(refusing one that claims a different name or version than the deploy path), so the protocol "
                        + "PARSES the artifact and an arbitrary byte body publishes nowhere. Restated, not dropped: "
                        + "PackagedArtifactContract runs the identical property over a real pod archive published "
                        + "through this format's own PUT",
                FormatContract.Property.HEAD_ANSWERS_FROM_METADATA,
                "same protocol reason as PUBLISH_SERVES_EXACT_BYTES. PackagedArtifactContract runs it over a real pod "
                        + "archive with the same sealed-blob proof and the same non-vacuity check",
                FormatContract.Property.COORDINATE_TRAVERSAL_REFUSED,
                "CocoaPodsFormat DOES implement ArtifactLayout, but only for ecosystem()/describe(): paths() answers "
                        + "empty by design, because a pod's pointers live in the blobs namespace "
                        + "(cocoapods/<repo>/blob/<name>/<version> and the MD5-sharded spec key) rather than under "
                        + "publish/. The kit's leg would therefore fail its own non-vacuity check rather than prove "
                        + "anything. The seam this format really has is the BlobLayout - blobKeys resolves "
                        + "both pointer keys per registry, which is what an eviction deletes - and it is proven over "
                        + "the same hostile coordinates by BlobLayoutCoordinateSeamTest in this module; the request "
                        + "seam is covered by REQUEST_PATH_TRAVERSAL_REFUSED");
    }

    /** An upstream CocoaPods CDN answering the pod's sharded podspec with an {@code :http} zip source and the checksum
     *  it declares for it, plus the download host streaming the archive. */
    private static ProxyFormat.Fetcher fetcher(GeneratedBody body, String sha256) {
        String[] shard = shard(PROXIED_POD);
        String spec = ROOT + "Specs/" + shard[0] + "/" + shard[1] + "/" + shard[2] + "/" + PROXIED_POD + "/"
                + PROXIED_VERSION + "/" + PROXIED_POD + ".podspec.json";
        String archive = FILES + PROXIED_POD + "-" + PROXIED_VERSION + ".zip";
        String podspec = "{\"name\":\"" + PROXIED_POD + "\",\"version\":\"" + PROXIED_VERSION + "\","
                + "\"license\":\"MIT\",\"source\":{\"http\":\"" + archive + "\",\"sha256\":\"" + sha256 + "\"}}";
        return new ProxyFormat.Fetcher.Buffered() {

            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) {
                return url.toString().equals(spec)
                        ? Optional.of(new ProxyFormat.Fetched(200, podspec.getBytes(StandardCharsets.UTF_8), Map.of()))
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

    /** The CDN shard for a pod: the first three hex characters of {@code MD5(name)} - the protocol's own bucketing (a
     *  routing function, not a security digest), recomputed here so a probe lands where a client would look. */
    private static String[] shard(String name) {
        String hex = HexFormat.of().formatHex(Packages.digest("MD5").digest(name.getBytes(StandardCharsets.UTF_8)));
        return new String[]{hex.substring(0, 1), hex.substring(1, 2), hex.substring(2, 3)};
    }

    private static String download(String version) {
        return PODS + version + "/" + POD + ".zip";
    }

    private byte[] push(ArtifactStore store, String version) throws IOException {
        byte[] artifact = Packages.podspec(POD, version);
        seed(store, ContractExchange.of("PUT", "/cocoapods/" + REGISTRY + "/" + POD + "/" + version, artifact), 201);
        return artifact;
    }
}
