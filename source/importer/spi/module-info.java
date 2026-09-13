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
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.50.0 SHA-256/4667724877f1d37a689202da191e23efa7657c62eef93ccdac406eccfe5cdd0a
 * @jenesis.pin org.jspecify/jspecify 1.0.1 SHA-256/070d75f261fe4c5b8202508366715f7f2d4660f88c8ef7e6d3575e48c9683b66
 * @jenesis.pin org.slf4j/slf4j-api 2.0.19 SHA-256/e91ff6d720609e7a194ffe758c3ed5c84e798617ae07b0a0f6a4fe229741b4bb
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
