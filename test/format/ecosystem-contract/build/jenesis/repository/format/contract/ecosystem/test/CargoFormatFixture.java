package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.format.testkit.FormatContract;
import build.jenesis.repository.format.testkit.GeneratedBody;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The Cargo sparse-index registry's leg of the shared contract. Cargo carries the whole contract bar the coordinate
 * seam: its publish frame wraps the {@code .crate} archive as opaque length-prefixed bytes, so the kit's own publish
 * leg applies verbatim; the name-sharded per-crate index file is the generated document <em>and</em> the enumeration
 * surface; and its proxy leg holds a fetched {@code .crate} to the {@code cksum} the upstream sparse index publishes
 * for that version.
 */
final class CargoFormatFixture implements EcosystemFormatFixture {

    private static final String REGISTRY = "contract";
    private static final String CRATE = "contract-lib";
    private static final String DOWNLOADS = "/cargo/" + REGISTRY + "/api/v1/crates/";

    /** Cargo's index shard for a name of four or more characters: {@code <first two>/<next two>} - the path a client
     *  computes itself, so the fixture reads the index exactly where cargo would look for it. */
    private static final String INDEX = "/cargo/" + REGISTRY + "/" + CRATE.substring(0, 2) + "/"
            + CRATE.substring(2, 4) + "/" + CRATE;

    private static final URI ROOT = URI.create("https://index.invalid/");

    /** The proxied crate, deliberately one nothing local publishes, so the pull-through leg is a real local miss. */
    private static final String PROXIED_CRATE = "proxied-lib";
    private static final String PROXIED_VERSION = "9.9.9";
    private static final String PROXIED = DOWNLOADS + PROXIED_CRATE + "/" + PROXIED_VERSION + "/download";

    private RepositoryFormat serving;

    @Override
    public String format() {
        return "cargo";
    }

    @Override
    public Signatures signatures() {
        return Signatures.none("crates.io publishes no per-crate signature. Integrity is the sparse index's cksum over the "
                + "registry's own bytes; signing has been proposed repeatedly and never shipped, so there is "
                + "nothing for a publisher signature to verify against.");
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.format.cargo.CargoFormat";
    }

    /** Discovered once through the SPI, then cached: the contract drives dozens of exchanges per check and a
     *  ServiceLoader sweep per exchange would be the fixture's own cost, not the format's. */
    @Override
    public RepositoryFormat serving() {
        if (serving == null) {
            serving = EcosystemFormatFixture.super.serving();
        }
        return serving;
    }

    @Override
    public List<String> namespaces() {
        return List.of("cargo", "blobs");
    }

    @Override
    public Published publish(ArtifactStore store, byte[] body) throws IOException {
        publish(store, "1.0.0", body);
        return new Published(download("1.0.0"), Packages.sha256(body));
    }

    @Override
    public Seeded seed(ArtifactStore store) throws IOException {
        publish(store, "1.0.0", "seeded .crate archive".getBytes(StandardCharsets.UTF_8));
        return new Seeded(CRATE, "1.0.0", download("1.0.0"));
    }

    @Override
    public String probe(String vector) {
        // The registry segment is the one client-supplied element of the publish route, and it becomes the
        // cargo/<registry>/crates/... and cargo/<registry>/index.d/... keys verbatim - so that is where a vector goes,
        // with Cargo's own publish path after it so a probing PUT really reaches the pointer composition.
        return "/cargo/" + vector + "/api/v1/crates/new";
    }

    /** A well-formed Cargo publish frame, so a probing {@code PUT} reaches the pointer composition rather than being
     *  turned away as an unparseable frame - which would make the traversal leg vacuous for the one verb that writes. */
    @Override
    public byte[] probeBody() {
        try {
            return Packages.cargoFrame("t202c-probe", "1.0.0", "t202c-probe".getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<Enumerated> enumerated(ArtifactStore store) throws IOException {
        publish(store, "1.0.0", "keep".getBytes(StandardCharsets.UTF_8));
        publish(store, "2.0.0-held", "held".getBytes(StandardCharsets.UTF_8));
        // The per-crate sparse index is the disclosure surface: its line carries the version's cksum and dependency
        // edges, so a withheld crate left in it is a coordinate cargo resolves against and then 404s downloading.
        return Optional.of(new Enumerated(download("2.0.0-held"),
                List.of(new Probe(INDEX, "2.0.0-held")),
                target -> hold(target, CRATE, "2.0.0-held")));
    }

    @Override
    public Optional<Index> index(ArtifactStore store) throws IOException {
        publish(store, "1.0.0", "one".getBytes(StandardCharsets.UTF_8));
        return Optional.of(new Index(INDEX,
                target -> publish(target, "1.1.0", "two".getBytes(StandardCharsets.UTF_8))));
    }

    @Override
    public Optional<Upstream> upstream(GeneratedBody body) {
        // The honest upstream: the sparse index line declaring this version's cksum, and the .crate itself streamed
        // from the download endpoint the upstream's own config.json `dl` template names.
        return Optional.of(new Upstream(PROXIED, ROOT, fetcher(body, body.sha256())));
    }

    @Override
    public Optional<Upstream> tampered(GeneratedBody body) {
        // The same bytes advertised under a cksum they do not hash to - an archive substituted between the index and
        // the download host. Nothing may be linked, and the local 404 must stand so a later pull re-hits the upstream.
        return Optional.of(new Upstream(PROXIED, ROOT, fetcher(body, "0".repeat(64))));
    }

    @Override
    public Map<FormatContract.Property, String> unsupported() {
        return Map.of(
                FormatContract.Property.PUBLISH_PATHS_ARE_DESCRIBED,
                "a crate is published at the registry's one API endpoint, api/v1/crates/new, whose path names no "
                        + "crate - the coordinate is in the framed body - so describe() has nothing to place there. A "
                        + "review pointer the gate links at it keeps no sibling hashes, and a release's cross-alias "
                        + "guard answers Absent for a path an installed format handles and no layout describes "
                        + "(CrossAliasWithholdTest holds it to that)",
                FormatContract.Property.PROXY_REFUSAL_IS_NOT_AN_ABSENCE, "audited 2026-08-24: the sparse index file is an ENUMERATION already refused as a 502; the .crate is a "
                        + "named version's artifact whose absence cargo reports. No sidecar is fetched on this leg. ",
               FormatContract.Property.COORDINATE_TRAVERSAL_REFUSED,
                "CargoFormat DOES implement ArtifactLayout, but only for ecosystem()/describe(): both paths() "
                        + "overloads answer empty by design, because a crate's pointers live in the blobs namespace "
                        + "(cargo/<registry>/crates/..., cargo/<registry>/index.d/...) rather than under publish/, and "
                        + "nothing is enumerable from the coordinate alone through the publish/-namespace seam. The "
                        + "kit's leg would therefore fail its own non-vacuity check rather than prove anything. The "
                        + "seam this format really has is the BlobLayout - blobKeys discovers the registry "
                        + "segment by listing and resolves the crate pointer plus its index line, and that is what an "
                        + "eviction deletes - and it is proven over the same hostile coordinates by "
                        + "BlobLayoutCoordinateSeamTest in this module; the request seam is covered by "
                        + "REQUEST_PATH_TRAVERSAL_REFUSED");
    }

    /**
     * An upstream sparse-index registry: its {@code config.json} names the download base (same host, so the format's
     * cross-origin SSRF screen passes it without a lookup), its name-sharded index file declares the version's
     * {@code cksum}, and the download endpoint streams the archive - exactly the three reads index.crates.io answers.
     */
    private static ProxyFormat.Fetcher fetcher(GeneratedBody body, String cksum) {
        String config = ROOT + "config.json";
        String index = ROOT + PROXIED_CRATE.substring(0, 2) + "/" + PROXIED_CRATE.substring(2, 4) + "/"
                + PROXIED_CRATE;
        String artifact = ROOT + "api/v1/crates/" + PROXIED_CRATE + "/" + PROXIED_VERSION + "/download";
        String line = "{\"name\":\"" + PROXIED_CRATE + "\",\"vers\":\"" + PROXIED_VERSION + "\",\"deps\":[],"
                + "\"cksum\":\"" + cksum + "\",\"features\":{},\"yanked\":false,\"links\":null}\n";
        return new ProxyFormat.Fetcher.Buffered() {

            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) {
                if (url.toString().equals(config)) {
                    return Optional.of(new ProxyFormat.Fetched(200,
                            ("{\"dl\":\"" + ROOT + "api/v1/crates\",\"api\":\"" + ROOT + "\"}")
                                    .getBytes(StandardCharsets.UTF_8), Map.of()));
                }
                return url.toString().equals(index)
                        ? Optional.of(new ProxyFormat.Fetched(200, line.getBytes(StandardCharsets.UTF_8), Map.of()))
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

    private static String download(String version) {
        return DOWNLOADS + CRATE + "/" + version + "/download";
    }

    private void publish(ArtifactStore store, String version, byte[] crate) throws IOException {
        seed(store, ContractExchange.of("PUT", "/cargo/" + REGISTRY + "/api/v1/crates/new",
                Packages.cargoFrame(CRATE, version, crate)), 200);
    }
}
