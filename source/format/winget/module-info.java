/**
 * The Windows Package Manager (winget) REST source format as a plugin module: it provides
 * {@link build.jenesis.repository.format.RepositoryFormat} for Microsoft's published <i>REST source</i> protocol, so a
 * client that has run {@code winget source add -n <name> -a <url>/winget/<repo> -t Microsoft.Rest} can
 * {@code winget search} and {@code winget install} against the shared store. It owns {@code /winget/<repo>/...} and
 * serves the three routes the protocol defines - {@code GET information} (the source identifier and the protocol
 * versions this server speaks), {@code POST manifestSearch} (the query a {@code winget search} sends) and
 * {@code GET packageManifests/<PackageIdentifier>} (the manifest a {@code winget install} resolves) - plus the two
 * publish routes and the installer download this repository adds, because the protocol itself defines no way to put a
 * package into a source.
 *
 * <p><b>Why publish is ours to define.</b> The REST specification is a read protocol: the community source is
 * populated by pull request against a git repository of YAML manifests and served as a pre-built index, so there is no
 * upstream {@code PUT} to be compatible with. This format therefore accepts a manifest at
 * {@code PUT /winget/<repo>/manifests/<id>/<version>} and each installer's bytes at
 * {@code PUT /winget/<repo>/installers/<id>/<version>/<file>}, and serves the installer back from the same path. That
 * split is what keeps the publish streaming: an installer is an {@code .exe}, {@code .msi} or {@code .msix} that can
 * run to gigabytes and goes straight through {@link build.jenesis.repository.store.ArtifactStore#writeBlob} into the
 * content-addressed store, while only the small JSON manifest is ever materialised.
 *
 * <p><b>The served manifest is rewritten, and that is the integrity story.</b> A winget manifest names each installer
 * by {@code InstallerUrl} and {@code InstallerSha256}, and the client verifies the download against that digest. A
 * manifest is stored as published but served with each {@code InstallerUrl} pointing back at this repository and each
 * {@code InstallerSha256} restated from the digest the content-addressed store computed when the bytes landed - so the
 * hash a client checks is the hash of the bytes this server will actually hand it, rather than a number a publisher
 * typed. An installer that was never uploaded leaves its manifest entry unserved rather than advertising a download
 * that would 404.
 *
 * <p>The client-read documents are stored, not generated per request: a per-package version listing
 * ({@code winget/<repo>/packages/<id>}) and the repository's search index ({@code winget/<repo>/index}), derived from
 * it, are {@link build.jenesis.repository.store.StoredListing}s that each publish updates by its one entry, so
 * {@code manifestSearch} is one read of one document rather than a scan of every package. A
 * {@link build.jenesis.repository.store.PublicationObserver} keeps both in step with a hold, a release, a lifecycle
 * mark and a removal.
 *
 * <p>It also provides {@link build.jenesis.repository.format.ArtifactLayout}, declaring the {@code "WinGet"} ecosystem
 * and resolving an installer download path to its {@code <PackageIdentifier>} coordinate and version, and a
 * {@link build.jenesis.repository.format.RepositoryImporter} that replays an exported winget repository through this
 * format's own publish paths. OSV carries no winget advisory feed, so vulnerability screening finds nothing while
 * license and malicious-package screening still key on the coordinate.
 *
 * <p><b>No proxy leg, deliberately.</b> The default community source is not the REST protocol at all - it is a
 * pre-built, signed index package the client downloads, whose installers live on each vendor's own host - so there is
 * no upstream speaking these routes to pull through, and no artifact manager proxies it. This format is hosted only by
 * decision, and its ecosystem fixture declares the proxy rows inapplicable with that reason rather than omitting them.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.format.winget {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.format.lifecycle;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.blobs;
    requires org.slf4j;
    requires tools.jackson.databind;
    // Exported to test modules only; the unit suite is named here because its assertions
    // are about the path grammar, which belongs in the fastest lane.
    exports build.jenesis.repository.format.winget to
            build.jenesis.repository.gateway.test,
            build.jenesis.repository.gateway.census.test,
            build.jenesis.repository.format.winget.test;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.winget.WingetFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.winget.WingetListingObserver;
}
