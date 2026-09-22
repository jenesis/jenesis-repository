/**
 * The Conda channel format as a plugin module: it provides {@link build.jenesis.repository.format.RepositoryFormat}
 * for the {@code /conda/...} layout - a streaming package upload
 * ({@code PUT /conda/<repo>/<subdir>/<name>-<version>-<build>.conda} or the legacy {@code .tar.bz2}, the raw archive
 * as the body, streamed into the content-addressed store while only its embedded {@code info/index.json} is
 * materialised), the per-subdir {@code repodata.json} (and its {@code .bz2}) maintained on write from a precomputed
 * per-package record, and the package downloads. It also provides {@link build.jenesis.repository.format.ArtifactLayout},
 * declaring the {@code "conda"} ecosystem and resolving a package path to its {@code name}/{@code version} coordinate.
 * A package's {@code info/index.json} lives inside the archive, so the just-stored blob is reopened and walked with
 * Commons Compress ({@code org.apache.commons.compress}) - a {@code .tar.bz2} as a bzip2 tar (pure-Java), a
 * {@code .conda} as a zip whose {@code info-*.tar.zst} member is a Zstandard tar
 * ({@code com.github.luben.zstd_jni}), the same compression stack the Debian format uses - and its JSON parsed and the
 * index emitted with the Jackson databind on the server path. The same layout is also a
 * {@link build.jenesis.repository.format.ProxyFormat}, pull-through mirroring an upstream conda channel (an immutable
 * package streamed into the store and cached, a mutable {@code repodata} index streamed through fresh). Finally it
 * provides a {@link build.jenesis.repository.format.RepositoryImporter} that migrates a Nexus/Artifactory
 * {@code conda} channel, replaying each {@code .conda}/{@code .tar.bz2} through the format's own publish path so the
 * archive streams into the CAS as a hosted import (the subdir taken from the source path's {@code <subdir>/<file>}
 * layout). Discovered through {@code provides}.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.format.conda {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.format.lifecycle;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.blobs;
    requires org.slf4j;
    requires org.apache.commons.compress;
    requires com.github.luben.zstd_jni;
    requires tools.jackson.databind;
    exports build.jenesis.repository.format.conda to
            build.jenesis.repository.gateway.conda.test, build.jenesis.repository.gateway.census.test;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.conda.CondaFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.conda.CondaListingObserver;
}
