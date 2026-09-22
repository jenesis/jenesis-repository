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
 * The Debian/apt format's leg of the shared contract. A push reads the {@code control} out of the {@code .deb}'s
 * {@code ar}/tar, so the artifact is not opaque to the publish protocol and the two publish properties run over a real
 * {@code .deb} through {@link PackagedArtifactContract}.
 *
 * <p>Its enumeration surface is the generated binary {@code Packages} index, and its pool key is the request path
 * <em>verbatim</em> - which is what made the request-seam traversal screen load-bearing here rather than cosmetic.
 */
final class DebianFormatFixture implements EcosystemFormatFixture {

    private static final String SUITE = "sid";
    private static final String COMPONENT = "main";
    private static final String PACKAGE = "contract-lib";
    private static final String ARCHITECTURE = "amd64";
    private static final String POOL = "/debian/" + SUITE + "/pool/" + COMPONENT + "/";
    private static final String PACKAGES = "/debian/dists/" + SUITE + "/" + COMPONENT
            + "/binary-" + ARCHITECTURE + "/Packages";
    private static final URI ROOT = URI.create("http://deb.invalid/debian/");

    private static final String PROXIED_FILE = "upstream_9.9.9_" + ARCHITECTURE + ".deb";
    private static final String PROXIED = POOL + PROXIED_FILE;

    private RepositoryFormat serving;

    @Override
    public String format() {
        return "debian";
    }

    @Override
    public Signatures signatures() {
        return Signatures.of(ArtifactSignatures.Scheme.OPENPGP_DETACHED);
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.format.debian.DebianFormat";
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
        return List.of("debian", "blobs");
    }

    @Override
    public Published publish(ArtifactStore store, byte[] body) {
        throw new AssertionError("debian: a .deb push reads the control stanza inside the package's ar/tar, so an "
                + "arbitrary byte body is not a publishable artifact here. This fixture publishes a real .deb through "
                + "publishPackage() and runs the same two properties through PackagedArtifactContract.");
    }

    @Override
    public Optional<Packaged> publishPackage(ArtifactStore store) throws IOException {
        byte[] artifact = push(store, "1.0.0");
        return Optional.of(new Packaged(artifact, pool("1.0.0"), Packages.sha256(artifact)));
    }

    @Override
    public Seeded seed(ArtifactStore store) throws IOException {
        push(store, "1.0.0");
        return new Seeded(PACKAGE, "1.0.0", pool("1.0.0"));
    }

    @Override
    public String probe(String vector) {
        // The pool path below the component is client-supplied and becomes the debian/ pointer key verbatim, so the
        // vector goes there with a well-formed <pkg>_<version>_<arch>.deb name after it - the shape that really
        // reaches the push, rather than one the .deb suffix gate turns away first.
        return POOL + vector + "/t202b-probe_1.0_" + ARCHITECTURE + ".deb";
    }

    /** A real {@code .deb}, so a probing {@code PUT} reaches the pointer composition rather than being rejected as an
     *  unparseable control - which would leave the one verb that writes untested. */
    @Override
    public byte[] probeBody() {
        try {
            return Packages.deb("t202b-probe", "1.0", ARCHITECTURE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<Enumerated> enumerated(ArtifactStore store) throws IOException {
        push(store, "1.0.0");
        push(store, "2.0.0");
        // The generated Packages index is the disclosure surface: a stanza names the pool Filename (and the package's
        // checksums), so leaving a withheld .deb's stanza would advertise a package apt then cannot fetch - and the
        // Release checksums are computed over exactly this body.
        return Optional.of(new Enumerated(pool("2.0.0"),
                List.of(new Probe(PACKAGES, file("2.0.0"))),
                target -> hold(target, PACKAGE, "2.0.0")));
    }

    @Override
    public Optional<Index> index(ArtifactStore store) throws IOException {
        push(store, "1.0.0");
        return Optional.of(new Index(PACKAGES, target -> push(target, "1.1.0")));
    }

    @Override
    public Optional<Upstream> upstream(GeneratedBody body) {
        String artifact = ROOT + SUITE + "/pool/" + COMPONENT + "/" + PROXIED_FILE;
        return Optional.of(new Upstream(PROXIED, ROOT, new ProxyFormat.Fetcher.Buffered() {

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
                FormatContract.Property.PROXY_REFUSAL_IS_NOT_AN_ABSENCE, "audited 2026-08-24: the dists/ documents apt resolves against (InRelease, Release, Packages) are "
                        + "ENUMERATIONs already refused as 502s. Under pool/ lie bodies apt reached BY name out of one of those "
                        + "indexes, so a miss there is a broken mirror it reports rather than a fact it resolves around. ",
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
                "a .deb push reads the control stanza out of the package's ar/control.tar, so the protocol PARSES the "
                        + "artifact and an arbitrary byte body publishes nowhere. Restated, not dropped: "
                        + "PackagedArtifactContract runs the identical property over a real .deb published through "
                        + "this format's own pool PUT",
                FormatContract.Property.HEAD_ANSWERS_FROM_METADATA,
                "same protocol reason as PUBLISH_SERVES_EXACT_BYTES. PackagedArtifactContract runs it over a real "
                        + ".deb with the same sealed-blob proof and the same non-vacuity check",
                FormatContract.Property.COORDINATE_TRAVERSAL_REFUSED,
                "DebianFormat implements no ArtifactLayout: a .deb's pointer is its pool key in the blobs namespace, "
                        + "not a publish/ pointer, so its coordinate-to-pointer mapping is the BlobLayout - "
                        + "and blobKeys here is a paged walk of every suite's pool tree, which is exactly the shape a "
                        + "traversal-shaped package name must not be able to aim. Proven over the same hostile "
                        + "coordinates by BlobLayoutCoordinateSeamTest in this module; the request seam is covered by "
                        + "REQUEST_PATH_TRAVERSAL_REFUSED",
                FormatContract.Property.PROXY_VERIFIES_UPSTREAM_INTEGRITY,
                "Debian does publish a per-.deb SHA-256 - but only inside a signed Packages index, keyed by "
                        + "`Filename: pool/.../x.deb` under one specific dists/<suite>/<component>/binary-<arch>/ "
                        + "path, while the pool GET this leg serves carries no suite, component or architecture at "
                        + "all. There is no reliable mapping from the requested pool path back to the (large) index "
                        + "that declares its checksum, so the digest is not addressable at this seam. It is not lost: "
                        + "the proxy relays the signed Release -> InRelease -> Packages chain through BYTE FOR BYTE, "
                        + "so apt itself verifies every .deb against the signed index we forwarded. Fabricating a "
                        + "second, weaker check here would be the kit's refusal case; npm, PyPI, NuGet and RubyGems "
                        + "prove the property where the artifact URL maps directly to a checksum-bearing document");
    }

    private static String file(String version) {
        return PACKAGE + "_" + version + "_" + ARCHITECTURE + ".deb";
    }

    private static String pool(String version) {
        return POOL + file(version);
    }

    private byte[] push(ArtifactStore store, String version) throws IOException {
        byte[] artifact = Packages.deb(PACKAGE, version, ARCHITECTURE);
        seed(store, ContractExchange.of("PUT", pool(version), artifact), 201);
        return artifact;
    }
}
