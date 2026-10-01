/**
 * The Windows Package Manager (winget) REST source format: a {@link build.jenesis.repository.format.RepositoryFormat}
 * for Microsoft's REST source protocol under {@code /winget/<repo>/...}, serving its three read routes -
 * {@code information}, {@code manifestSearch} and {@code packageManifests/<PackageIdentifier>} - plus two publish
 * routes and the installer download this repository adds, since the protocol is read-only (the community source is
 * built from a git repository of YAML by pull request). An installer streams into the store; only the small manifest is
 * materialised.
 *
 * <p>The served manifest is rewritten: each {@code InstallerUrl} points back here and each {@code InstallerSha256} is
 * the digest the store computed, so a client verifies the bytes this server hands it. The per-package version lists and
 * the search index are {@link build.jenesis.repository.store.StoredListing}s each publish updates, kept in step with
 * holds, releases, marks and removals by a {@link build.jenesis.repository.store.PublicationObserver}.
 *
 * <p>It is also an {@link build.jenesis.repository.format.ArtifactLayout} for the {@code "WinGet"} ecosystem and a
 * {@link build.jenesis.repository.format.RepositoryImporter}. There is no proxy leg: the default community source is a
 * pre-built signed index package whose installers live on vendors' hosts, so no upstream speaks these routes.
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
    // Exported for the suites that drive the format directly.
    exports build.jenesis.repository.format.winget;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.winget.WingetFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.winget.WingetListingObserver;
}
