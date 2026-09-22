package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.format.testkit.FormatContract;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The winget REST source's leg of the shared contract - the format whose publish is two requests carrying different
 * things. The manifest is a small JSON document naming the licence and the installers; the installer is opaque bytes.
 * The kit's own publish leg therefore applies verbatim to the installer route, which is the one that serves what a
 * client downloads.
 *
 * <p><b>The enumeration a hold has to clear is the package's manifest answer.</b> A held version must leave both the
 * search index and {@code packageManifests}, and it does so through one screen: the version's membership of its
 * package listing is decided by whether its manifest pointer is withheld, and the index line is re-derived from that
 * listing. So a hold on the coordinate removes the version from the client's two ways of finding it at once, which is
 * what this row exercises.
 */
final class WingetFormatFixture implements EcosystemFormatFixture {

    private static final String REGISTRY = "contract";
    private static final String BASE = "/winget/" + REGISTRY;
    private static final String ID = "Acme.Widget";
    private static final String VERSION = "1.0.0";
    private static final String INSTALLER = "widget.exe";

    private static final String MANIFEST = BASE + "/manifests/" + ID + "/" + VERSION;
    private static final String DOWNLOAD = BASE + "/installers/" + ID + "/" + VERSION + "/" + INSTALLER;
    private static final String MANIFESTS = BASE + "/packageManifests/" + ID;

    private RepositoryFormat serving;

    @Override
    public String format() {
        return "winget";
    }

    @Override
    public Signatures signatures() {
        return Signatures.none("a winget manifest is a YAML document in a git repository pointing at an installer, pinned by "
                + "sha256. Any signature lives in the installer's own Authenticode, which is the operating "
                + "system's to check on execution rather than this repository's on publish.");
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.format.winget.WingetFormat";
    }

    @Override
    public RepositoryFormat serving() {
        if (serving == null) {
            serving = EcosystemFormatFixture.super.serving();
        }
        return serving;
    }

    /** The store root is {@code winget/}; pointers and blobs live in the shared blob namespace. */
    @Override
    public List<String> namespaces() {
        return List.of("winget", "blobs");
    }

    @Override
    public Published publish(ArtifactStore store, byte[] body) throws IOException {
        // The manifest first, and it is not optional. An installer belongs to a version, and a version exists only
        // once its manifest has been accepted - so the installer route refuses bytes for a version nothing
        // declared. That is the whole of what one winget publish is: two requests carrying different things, and
        // this fixture publishes both because a fixture that published one was describing something the format
        // does not do.
        put(store, MANIFEST, manifest(VERSION));
        // The installer route takes opaque bytes - an .exe, .msi or .msix - so the kit's generated body is a
        // publishable artifact here without being wrapped in anything.
        put(store, DOWNLOAD, body);
        return new Published(DOWNLOAD, Packages.sha256(body));
    }

    @Override
    public Seeded seed(ArtifactStore store) throws IOException {
        put(store, MANIFEST, manifest(VERSION));
        put(store, DOWNLOAD, "seeded installer".getBytes(StandardCharsets.UTF_8));
        return new Seeded(ID, VERSION, DOWNLOAD);
    }

    @Override
    public String probe(String vector) {
        // The PackageIdentifier is client-supplied and becomes a key directory verbatim, so it is screened segment by
        // segment - a winget identifier is a dotted single segment, so nothing legitimate is refused.
        return BASE + "/installers/" + vector + "/" + VERSION + "/" + INSTALLER;
    }

    @Override
    public Optional<Enumerated> enumerated(ArtifactStore store) throws IOException {
        put(store, MANIFEST, manifest(VERSION));
        put(store, DOWNLOAD, "the held installer".getBytes(StandardCharsets.UTF_8));
        return Optional.of(new Enumerated(DOWNLOAD,
                List.of(new Probe(MANIFESTS, VERSION)),
                target -> hold(target, ID, VERSION)));
    }

    @Override
    public Optional<Index> index(ArtifactStore store) throws IOException {
        put(store, MANIFEST, manifest(VERSION));
        put(store, DOWNLOAD, "the first installer".getBytes(StandardCharsets.UTF_8));
        return Optional.of(new Index(MANIFESTS, target -> {
            put(target, BASE + "/manifests/" + ID + "/2.0.0", manifest("2.0.0"));
            put(target, BASE + "/installers/" + ID + "/2.0.0/" + INSTALLER,
                    "the second installer".getBytes(StandardCharsets.UTF_8));
        }));
    }

    @Override
    public Map<FormatContract.Property, String> unsupported() {
        return Map.of(
                FormatContract.Property.PROXY_VERIFIES_UPSTREAM_INTEGRITY, PROXY,
                FormatContract.Property.PROXY_REFUSAL_IS_NOT_AN_ABSENCE, PROXY,
                FormatContract.Property.PROXY_STREAMS_UPSTREAM_BODY, PROXY,
                FormatContract.Property.COORDINATE_TRAVERSAL_REFUSED,
                "WingetFormat implements ArtifactLayout for ecosystem()/describe() only: paths() answers empty by "
                        + "design, because a package's pointers live in the blobs namespace rather than under "
                        + "publish/, so the kit's leg would fail its own non-vacuity check rather than prove "
                        + "anything. The seam this format really has is the BlobLayout, proven over the "
                        + "same hostile coordinates by BlobLayoutCoordinateSeamTest in this module; the request seam "
                        + "is covered by REQUEST_PATH_TRAVERSAL_REFUSED");
    }

    /** Stated once: the default community source is not the REST protocol at all - it is a pre-built index package
     *  the client downloads - so there is no upstream speaking these routes to pull through. A pull-through against a
     *  REST-speaking source is possible and is a separate change; until it exists these three rows have no subject. */
    private static final String PROXY =
            "winget has no proxy leg: the default community source is a pre-built index package rather than a REST "
                    + "endpoint, so there is no upstream speaking information/manifestSearch/packageManifests to pull "
                    + "through. Serving a REST-speaking upstream is a separate change, and these rows arrive with it";

    private static byte[] manifest(String version) {
        return ("""
                {
                  "PackageIdentifier": "%s",
                  "PackageVersion": "%s",
                  "DefaultLocale": { "PackageLocale": "en-US", "Publisher": "Acme", "PackageName": "Widget",
                                     "License": "MIT" },
                  "Installers": [
                    { "Architecture": "x64", "InstallerType": "exe",
                      "InstallerUrl": "https://acme.invalid/%s",
                      "InstallerSha256": "0000000000000000000000000000000000000000000000000000000000000000" }
                  ]
                }""").formatted(ID, version, INSTALLER).getBytes(StandardCharsets.UTF_8);
    }

    private void put(ArtifactStore store, String path, byte[] body) throws IOException {
        seed(store, ContractExchange.of("PUT", path, body), 201);
    }
}
