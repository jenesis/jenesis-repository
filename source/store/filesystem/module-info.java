/**
 * The default filesystem artifact-store backend: blobs under a mounted root directory, keyed by object path. It
 * provides the {@link build.jenesis.repository.store.ArtifactStoreProvider} answering to {@code filesystem}, the one
 * {@code ArtifactStoreProvider.resolve} falls back to when no backend is named.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.store.filesystem {
    requires build.jenesis.repository.store;
    exports build.jenesis.repository.store.filesystem to build.jenesis.repository.store.filesystem.test;
    provides build.jenesis.repository.store.ArtifactStoreProvider
            with build.jenesis.repository.store.filesystem.FilesystemArtifactStoreProvider;
}
