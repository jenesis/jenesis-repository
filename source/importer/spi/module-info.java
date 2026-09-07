/**
 * The import-source SPI - the read half of a migration. An {@link build.jenesis.repository.importer.ImportSource}
 * enumerates a foreign repository's assets; an {@link build.jenesis.repository.importer.ImportSourceProvider} builds one
 * for a named incumbent from an {@link build.jenesis.repository.importer.ImportRequest}. A connector ships as its own
 * module that {@code provides} a provider, discovered with {@code ServiceLoader}, so the server supports another
 * incumbent by adding a module without knowing it. A walk that cannot continue fails with an
 * {@link build.jenesis.repository.importer.ImportFailure}, whose {@code Kind} tells a refused credential from an absent
 * repository, an unavailable instance and an unwalkable protocol - one classification for every connector, so a job
 * deciding whether to retry has something better than the message text to key on. Every URL a migration fetches -
 * the operator's own and every one a listing hands back - passes {@link build.jenesis.repository.importer.ImportScreen},
 * which rides on the fetcher a connector is handed rather than inside any connector, so a connector cannot forget it.
 * Depends only on the format SPI (for the shared {@code ProxyFormat.Fetcher}) and java.base; a connector reads and
 * writes its own JSON with Jackson.
 *
 * @jenesis.release 25
 * @jenesis.pin com.github.ben-manes.caffeine/caffeine 3.2.4 SHA-256/9d9d2cfd681fd9272ded3d27c9930db12f89f732345975aa113ebc223bbf1224
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.49.0 SHA-256/3b1003e51b8ae56fdbd7c71073e81d1683b97e6c4dff5a9151164d59b769d13c
 * @jenesis.pin org.jspecify/jspecify 1.0.0 SHA-256/1fad6e6be7557781e4d33729d49ae1cdc8fdda6fe477bb0cc68ce351eafdfbab
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
 */
module build.jenesis.repository.importer {
    requires transitive build.jenesis.repository.format;
    uses build.jenesis.repository.importer.ImportSourceProvider;
    exports build.jenesis.repository.importer;

    // The family extends IconContributor, so every implementation gains the optional mark seam and
    // the console resolves one answer for all of them. Transitive: an implementation overriding
    // icon() names IconResource in its own signature.
    requires transitive build.jenesis.repository.icon;
}
