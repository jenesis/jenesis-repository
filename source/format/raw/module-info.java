/**
 * The generic (raw) repository format as a plugin module: it provides
 * {@link build.jenesis.repository.format.RepositoryFormat} for the {@code /raw/...} layout, a plain
 * content-addressed file store over the {@code Publication} primitives in the store module. Discovered through
 * {@code provides}.
 *
 * @jenesis.release 25
 * @jenesis.pin com.github.ben-manes.caffeine/caffeine 3.2.4 SHA-256/9d9d2cfd681fd9272ded3d27c9930db12f89f732345975aa113ebc223bbf1224
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.50.0 SHA-256/4667724877f1d37a689202da191e23efa7657c62eef93ccdac406eccfe5cdd0a
 * @jenesis.pin org.jspecify/jspecify 1.0.1 SHA-256/070d75f261fe4c5b8202508366715f7f2d4660f88c8ef7e6d3575e48c9683b66
 * @jenesis.pin org.slf4j/slf4j-api 2.0.19 SHA-256/e91ff6d720609e7a194ffe758c3ed5c84e798617ae07b0a0f6a4fe229741b4bb
 */
module build.jenesis.repository.format.raw {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    requires java.xml;
    exports build.jenesis.repository.format.raw to build.jenesis.repository.format.raw.test;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.raw.RawFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.raw.RawListingObserver;
}
