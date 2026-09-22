/**
 * The Composer registry format as a plugin module: it provides {@link build.jenesis.repository.format.RepositoryFormat}
 * for the {@code /composer/...} Composer-v2 layout - a streaming package upload
 * ({@code PUT /composer/<repo>/<vendor>/<package>/<version>}, the package zip as the body, streamed into the
 * content-addressed store while only its embedded {@code composer.json} is materialised), the root
 * {@code packages.json} generated on read and the per-package {@code p2/<vendor>/<package>.json} metadata (and its
 * {@code ~dev} companion) kept as stored listings from a precomputed per-version stanza, and the package downloads. It also provides
 * {@link build.jenesis.repository.format.ArtifactLayout}, declaring the {@code "Packagist"} ecosystem and resolving a
 * dist download path to its {@code <vendor>/<package>} coordinate and version. A package's {@code composer.json} lives
 * inside the archive, so the just-stored blob is reopened and walked with {@code java.util.zip} (java.base) as far as
 * its root {@code composer.json}, whose JSON is parsed and the metadata emitted with the Jackson databind on the
 * server path. It also implements {@link build.jenesis.repository.format.ProxyFormat}: a local miss on a proxy
 * registry is served from an upstream Composer-v2 repository (Packagist by default), the mutable {@code p2/} metadata
 * fetched fresh with each version's {@code dist.url} rewritten to route the download back through this registry, and
 * an immutable package archive streamed from upstream into the content-addressed store and cached. Finally it provides
 * a {@link build.jenesis.repository.format.RepositoryImporter} that migrates a Nexus/Artifactory {@code composer}
 * repository, replaying each package zip through the format's own publish path so the archive streams into the CAS as a
 * hosted import (the coordinate taken from the source path's {@code <vendor>/<package>/<version>.zip} layout).
 * Discovered through {@code provides}. The compliance inspector is a sibling {@code compliance/composer} module.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.format.composer {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.format.lifecycle;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.blobs;
    requires org.slf4j;
    requires tools.jackson.databind;
    exports build.jenesis.repository.format.composer to
            build.jenesis.repository.gateway.test, build.jenesis.repository.gateway.census.test;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.composer.ComposerFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.composer.ComposerListingObserver;
}
