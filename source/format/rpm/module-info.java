/**
 * The RPM/yum format as a plugin module: a {@link build.jenesis.repository.format.RepositoryFormat} for
 * {@code /rpm/...} - streaming {@code .rpm} uploads ({@code PUT /rpm/<repo>/<path>/<file>.rpm}), {@code repodata}
 * maintained on the publish from per-package stanzas, and package downloads - that is also a
 * {@link build.jenesis.repository.format.ProxyFormat} mirroring an upstream yum repository, an
 * {@link build.jenesis.repository.format.ArtifactLayout} declaring the {@code "RPM"} ecosystem, and a
 * {@link build.jenesis.repository.format.RepositoryImporter} streaming a {@code yum} repository in. The RPM header is
 * read directly; Bouncy Castle signs {@code repomd.xml}.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.format.rpm {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.format.lifecycle;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.blobs;
    requires build.jenesis.repository.format.signing;
    requires java.xml;
    requires org.slf4j;
    exports build.jenesis.repository.format.rpm;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.rpm.RpmFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.rpm.RpmListingObserver;
}
