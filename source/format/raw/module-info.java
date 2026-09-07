/**
 * The generic (raw) repository format as a plugin module: it provides
 * {@link build.jenesis.repository.format.RepositoryFormat} for the {@code /raw/...} layout, a plain
 * content-addressed file store over the {@code Publication} primitives in the store module. Discovered through
 * {@code provides}.
 *
 * @jenesis.release 25
 * @jenesis.pin com.github.ben-manes.caffeine/caffeine 3.2.4 SHA-256/9d9d2cfd681fd9272ded3d27c9930db12f89f732345975aa113ebc223bbf1224
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.49.0 SHA-256/3b1003e51b8ae56fdbd7c71073e81d1683b97e6c4dff5a9151164d59b769d13c
 * @jenesis.pin org.jspecify/jspecify 1.0.0 SHA-256/1fad6e6be7557781e4d33729d49ae1cdc8fdda6fe477bb0cc68ce351eafdfbab
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
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
