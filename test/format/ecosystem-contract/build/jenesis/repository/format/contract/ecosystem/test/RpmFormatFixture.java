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
 * The RPM/yum format's leg of the shared contract. A push parses the binary RPM header off the front of the upload, so
 * the artifact is not opaque to the publish protocol and the two publish properties run over a real {@code .rpm}
 * through {@link PackagedArtifactContract}.
 *
 * <p>Its enumeration surface is the generated {@code primary.xml}, rendered from a per-package stanza the push
 * precomputes - so a hold has to reach a document derived from a cache no writer touched at hold time.
 */
final class RpmFormatFixture implements EcosystemFormatFixture {

    private static final String REPO = "contract";
    private static final String PACKAGE = "contract-lib";
    private static final String ARCHITECTURE = "x86_64";
    private static final String RELEASE = "1";
    private static final String POOL = "/rpm/" + REPO + "/pool/";
    private static final String PRIMARY = "/rpm/" + REPO + "/repodata/primary.xml";
    private static final URI ROOT = URI.create("https://yum.invalid/");

    private static final String PROXIED_FILE = "upstream-9.9.9-1." + ARCHITECTURE + ".rpm";
    private static final String PROXIED = POOL + PROXIED_FILE;

    private RepositoryFormat serving;

    @Override
    public String format() {
        return "rpm";
    }

    /** The publisher's signature in the package's own signature header, read through the seam since 2026-09-12 -
     *  the declaration and the implementation agree, and the contract's signature-story check holds them to it. */
    @Override
    public Signatures signatures() {
        return Signatures.of(ArtifactSignatures.Scheme.OPENPGP_DETACHED);
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.format.rpm.RpmFormat";
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
        return List.of("rpm", "blobs");
    }

    @Override
    public Published publish(ArtifactStore store, byte[] body) {
        throw new AssertionError("rpm: a .rpm push parses the binary header off the front of the upload, so an "
                + "arbitrary byte body is not a publishable artifact here. This fixture publishes a real .rpm through "
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
        return new Seeded(REPO + "/" + PACKAGE, version("1.0.0"), pool("1.0.0"));
    }

    @Override
    public String probe(String vector) {
        // The location below the yum repository is client-supplied and becomes the rpm/<repo>/... pointer key
        // verbatim, so the vector goes there with a well-formed NEVRA filename after it.
        return "/rpm/" + REPO + "/" + vector + "/t202b-probe-1.0-1." + ARCHITECTURE + ".rpm";
    }

    /** A real {@code .rpm}, so a probing {@code PUT} reaches the pointer composition rather than being turned away by
     *  the header parse - which would leave the one verb that writes untested. */
    @Override
    public byte[] probeBody() {
        try {
            return Packages.rpm("t202b-probe", "1.0", RELEASE, ARCHITECTURE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<Enumerated> enumerated(ArtifactStore store) throws IOException {
        push(store, "1.0.0");
        push(store, "2.0.0");
        // primary.xml is the disclosure surface: a <package> stanza carries the location a dnf install fetches and the
        // pkgid checksum it verifies, so a withheld package left in it is a coordinate dnf then 404s on - and the
        // repomd checksums are computed over exactly this body.
        return Optional.of(new Enumerated(pool("2.0.0"),
                List.of(new Probe(PRIMARY, file("2.0.0"))),
                target -> hold(target, REPO + "/" + PACKAGE, version("2.0.0"))));
    }

    @Override
    public Optional<Index> index(ArtifactStore store) throws IOException {
        push(store, "1.0.0");
        return Optional.of(new Index(PRIMARY, target -> push(target, "1.1.0")));
    }

    /** The honest pull-through leg: the repository's own {@code repodata} declares the SHA-256 the upstream really
     *  serves, so the package verifies against its declaring index and is cached. */
    @Override
    public Optional<Upstream> upstream(GeneratedBody body) {
        return Optional.of(proxied(body, body.digest("SHA-256")));
    }

    /** The same leg with the declaring index publishing a different SHA-256 - a tampered or corrupted mirror, which is
     *  precisely what an index-declared checksum exists to catch. */
    @Override
    public Optional<Upstream> tampered(GeneratedBody body) {
        return Optional.of(proxied(body, "0".repeat(64)));
    }

    /** One pull-through leg over {@code body}, with the repository's {@code repodata} declaring {@code checksum} for
     *  the proxied pool path - the repomd.xml -> primary.xml.gz chain a real yum mirror publishes. */
    private Upstream proxied(GeneratedBody body, String checksum) {
        String artifact = ROOT + REPO + "/pool/" + PROXIED_FILE;
        String repodata = ROOT + REPO + "/repodata/";
        byte[] primary = gzip(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<metadata xmlns=\"http://linux.duke.edu/metadata/common\" packages=\"1\">"
                + "<package type=\"rpm\"><name>upstream</name>"
                + "<checksum type=\"sha256\" pkgid=\"YES\">" + checksum + "</checksum>"
                + "<location href=\"pool/" + PROXIED_FILE + "\"/>"
                + "</package></metadata>\n").getBytes(StandardCharsets.UTF_8));
        byte[] repomd = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<repomd xmlns=\"http://linux.duke.edu/metadata/repo\"><revision>1</revision>"
                + "<data type=\"primary\"><checksum type=\"sha256\">" + sha256(primary) + "</checksum>"
                + "<location href=\"repodata/primary.xml.gz\"/></data></repomd>\n")
                .getBytes(StandardCharsets.UTF_8);
        return new Upstream(PROXIED, ROOT, new ProxyFormat.Fetcher.Buffered() {

            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) {
                if (url.toString().equals(repodata + "repomd.xml")) {
                    return Optional.of(new ProxyFormat.Fetched(200, repomd, Map.of()));
                }
                if (url.toString().equals(repodata + "primary.xml.gz")) {
                    return Optional.of(new ProxyFormat.Fetched(200, primary, Map.of()));
                }
                return Optional.of(new ProxyFormat.Fetched(404, new byte[0], Map.of()));
            }

            @Override
            public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders) {
                if (url.toString().equals(artifact)) {
                    return Optional.of(new ProxyFormat.Download(200, body.open(), Map.of()));
                }
                if (url.toString().equals(repodata + "primary.xml.gz")) {
                    return Optional.of(new ProxyFormat.Download(200, new ByteArrayInputStream(primary), Map.of()));
                }
                return Optional.of(new ProxyFormat.Download(404, InputStream.nullInputStream(), Map.of()));
            }
        });
    }

    private static byte[] gzip(byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Map<FormatContract.Property, String> unsupported() {
        return Map.of(
                FormatContract.Property.PROXY_REFUSAL_IS_NOT_AN_ABSENCE, "audited 2026-08-24: repomd.xml and the repodata it names are ENUMERATIONs already refused as 502s, and "
                        + "the .rpm is a named package whose absence dnf reports. The detached repomd.xml.asc is generated locally "
                        + "for hosted metadata, never proxied, so no refusal on it can be dressed as a miss. ",
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
                "an .rpm push parses the binary RPM header off the front of the upload (and refuses a package whose "
                        + "header names a different NEVRA than the filename), so the protocol PARSES the artifact and "
                        + "an arbitrary byte body publishes nowhere. Restated, not dropped: PackagedArtifactContract "
                        + "runs the identical property over a real .rpm published through this format's own pool PUT",
                FormatContract.Property.HEAD_ANSWERS_FROM_METADATA,
                "same protocol reason as PUBLISH_SERVES_EXACT_BYTES. PackagedArtifactContract runs it over a real "
                        + ".rpm with the same sealed-blob proof and the same non-vacuity check",
                FormatContract.Property.COORDINATE_TRAVERSAL_REFUSED,
                "RpmFormat DOES implement ArtifactLayout, but only for ecosystem()/describe(): both paths() overloads "
                        + "answer empty by design, because an RPM package's pointers live in the blobs namespace "
                        + "(rpm/<repo>/<location>) and nothing is enumerable from the coordinate alone through the "
                        + "publish/-namespace seam. The kit's leg would therefore fail its own non-vacuity check "
                        + "rather than prove anything. The seam this format really has is the BlobLayout - "
                        + "blobKeys walks the repo's pool tree for the NEVRA, and that is what an eviction deletes - "
                        + "and it is proven over the same hostile coordinates by BlobLayoutCoordinateSeamTest in this "
                        + "module; the request seam is covered by REQUEST_PATH_TRAVERSAL_REFUSED");
    }

    private static String version(String upstream) {
        return upstream + "-" + RELEASE + "." + ARCHITECTURE;
    }

    private static String file(String upstream) {
        return PACKAGE + "-" + version(upstream) + ".rpm";
    }

    private static String pool(String upstream) {
        return POOL + file(upstream);
    }

    private byte[] push(ArtifactStore store, String upstream) throws IOException {
        byte[] artifact = Packages.rpm(PACKAGE, upstream, RELEASE, ARCHITECTURE);
        seed(store, ContractExchange.of("PUT", pool(upstream), artifact), 201);
        return artifact;
    }
}
