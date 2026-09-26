package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.testkit.GeneratedBody;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.format.testkit.FormatContract;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.format.ArtifactSignatures;

/**
 * The Alpine {@code apk} format's leg of the shared contract.
 *
 * <p>A push reads the {@code .PKGINFO} out of the package's control member and refuses one whose {@code pkgname} and
 * {@code pkgver} disagree with the file name it was deployed to, so the artifact is not opaque to the publish
 * protocol: the two publish properties run over a real {@code .apk} through {@link PackagedArtifactContract}.
 *
 * <p><b>The enumeration is probed on the plain {@code APKINDEX}</b> rather than the {@code APKINDEX.tar.gz} a client
 * downloads. They are the same document - the archive is derived from the text and streams no other bytes - and the
 * text is the one a {@code contains} assertion can read. Probing the archive would assert over deflate output, where
 * a token's absence would prove nothing about what the index discloses.
 *
 * <p>The token is the version's own {@code V:} line rather than {@code <name>-<version>}: an index block states the
 * name and the version on separate lines, so the joined form the file name carries appears nowhere in the document
 * and a probe for it would fail whether or not the version was disclosed.
 */
final class ApkFormatFixture implements EcosystemFormatFixture {

    private static final String REPO = "contract";
    private static final String ARCHITECTURE = "x86_64";
    private static final String PACKAGE = "contract-lib";
    private static final String BASE = "/apk/" + REPO + "/" + ARCHITECTURE;
    private static final String INDEX = BASE + "/APKINDEX";

    private RepositoryFormat serving;

    @Override
    public String format() {
        return "apk";
    }

    @Override
    public Signatures signatures() {
        return Signatures.of(ArtifactSignatures.Scheme.RSA_DETACHED);
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.format.apk.ApkFormat";
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
        return List.of("apk", "blobs");
    }

    @Override
    public Published publish(ArtifactStore store, byte[] body) {
        throw new AssertionError("apk: a push reads the .PKGINFO out of the package's control member and refuses one "
                + "whose pkgname/pkgver disagree with the file it was deployed to, so an arbitrary byte body is not a "
                + "publishable artifact here. This fixture publishes a real .apk through publishPackage() and runs "
                + "the same two properties through PackagedArtifactContract.");
    }

    @Override
    public Optional<Packaged> publishPackage(ArtifactStore store) throws IOException {
        byte[] artifact = push(store, "1.0.0-r0");
        return Optional.of(new Packaged(artifact, download("1.0.0-r0"), Packages.sha256(artifact)));
    }

    @Override
    public Seeded seed(ArtifactStore store) throws IOException {
        push(store, "1.0.0-r0");
        return new Seeded(PACKAGE, "1.0.0-r0", download("1.0.0-r0"));
    }

    @Override
    public String probe(String vector) {
        // The architecture segment is client-supplied and composes the pointer key, so the vector goes there with a
        // well-formed <name>-<version>.apk after it - the shape that really reaches the push.
        return "/apk/" + REPO + "/" + vector + "/t202b-probe-1.0-r0.apk";
    }

    /** A real {@code .apk}, so a probing {@code PUT} reaches the pointer composition rather than being turned away as
     *  an unreadable control - which would leave the one verb that writes untested. */
    @Override
    public byte[] probeBody() {
        try {
            return Packages.apk("t202b-probe", "1.0-r0", ARCHITECTURE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<Enumerated> enumerated(ArtifactStore store) throws IOException {
        push(store, "1.0.0-r0");
        push(store, "2.0.0-r0");
        return Optional.of(new Enumerated(download("2.0.0-r0"),
                List.of(new Probe(INDEX, "V:2.0.0-r0")),
                target -> hold(target, PACKAGE, "2.0.0-r0")));
    }

    @Override
    public Optional<Index> index(ArtifactStore store) throws IOException {
        push(store, "1.0.0-r0");
        return Optional.of(new Index(INDEX, target -> push(target, "1.1.0-r0")));
    }

    @Override
    public Map<FormatContract.Property, String> unsupported() {
        return Map.of(

                FormatContract.Property.PROXY_VERIFIES_UPSTREAM_INTEGRITY,
                "an index's C: is a checksum of a package's control member, and the data is held to the datahash "
                        + "that member carries - so an arbitrary body has no checksum an index could declare for it. "
                        + "Restated, not dropped: ApkProxyTest runs the property over real packages, a checksum and a "
                        + "datahash mismatch each refused",
                FormatContract.Property.PROXY_REFUSAL_IS_NOT_AN_ABSENCE,
                "every path this leg proxies is one a miss on fails the install: APKINDEX.tar.gz is an ENUMERATION, "
                        + "refused as a 502 rather than answered empty, and a package is the file the index named",
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
                "a push reads the .PKGINFO out of the package's control member and refuses one whose pkgname/pkgver "
                        + "disagree with the file it was deployed to, so the protocol PARSES the artifact and an "
                        + "arbitrary byte body publishes nowhere. Restated, not dropped: PackagedArtifactContract "
                        + "runs the identical property over a real .apk",
                FormatContract.Property.HEAD_ANSWERS_FROM_METADATA,
                "same reason: the artifact this property is asked of has to be a real .apk, and "
                        + "PackagedArtifactContract asks it of one",
                FormatContract.Property.COORDINATE_TRAVERSAL_REFUSED,
                "ApkFormat implements ArtifactLayout for ecosystem()/describe() only: paths() answers empty by "
                        + "design, because an apk pointer lives in the blobs namespace rather than under publish/, so "
                        + "the kit's leg would fail its own non-vacuity check rather than prove anything. The seam "
                        + "this format really has is the BlobLayout, proven over the same hostile "
                        + "coordinates by BlobLayoutCoordinateSeamTest; the request seam is covered by "
                        + "REQUEST_PATH_TRAVERSAL_REFUSED");
    }

    /** This entry serves the repository; a pull-through against an upstream Alpine mirror is a separate change, so
     *  these three rows have no subject rather than a failing one. */
    /** An upstream whose index answers without listing the package, the one case a fill streams unverified - which
     *  is what lets an arbitrary body stand in for a package here. */
    @Override
    public Optional<Upstream> upstream(GeneratedBody body) {
        URI root = URI.create("https://alpine.invalid/v3.20/main/");
        String file = "proxied-9.9.9-r0.apk";
        String index = root + ARCHITECTURE + "/APKINDEX.tar.gz";
        String artifact = root + ARCHITECTURE + "/" + file;
        return Optional.of(new Upstream(BASE + "/" + file, root, new ProxyFormat.Fetcher.Buffered() {

            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) {
                return Optional.of(new ProxyFormat.Fetched(404, new byte[0], Map.of()));
            }

            @Override
            public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders) {
                if (url.toString().equals(index)) {
                    return Optional.of(new ProxyFormat.Download(200,
                            new ByteArrayInputStream(ApkProxyTest.index("")), Map.of()));
                }
                return url.toString().equals(artifact)
                        ? Optional.of(new ProxyFormat.Download(200, body.open(), Map.of()))
                        : Optional.of(new ProxyFormat.Download(404, InputStream.nullInputStream(), Map.of()));
            }
        }));
    }

    private static String download(String version) {
        return BASE + "/" + PACKAGE + "-" + version + ".apk";
    }

    private byte[] push(ArtifactStore store, String version) throws IOException {
        byte[] artifact = Packages.apk(PACKAGE, version, ARCHITECTURE);
        seed(store, ContractExchange.of("PUT", download(version), artifact), 201);
        return artifact;
    }
}
