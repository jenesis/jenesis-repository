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
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
 */
module build.jenesis.repository.importer {
    requires transitive build.jenesis.repository.format;
    exports build.jenesis.repository.importer;

    // The family extends IconContributor, so every implementation gains the optional mark seam and
    // the console resolves one answer for all of them. Transitive: an implementation overriding
    // icon() names IconResource in its own signature.
    requires transitive build.jenesis.repository.icon;
}
