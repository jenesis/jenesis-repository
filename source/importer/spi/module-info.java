/**
 * The import-source SPI, the read half of a migration: an {@link build.jenesis.repository.importer.ImportSource}
 * enumerates a foreign repository's assets, built by an {@link build.jenesis.repository.importer.ImportSourceProvider}
 * from an {@link build.jenesis.repository.importer.ImportRequest}; a connector ships as a module that {@code provides}
 * a provider. A walk that cannot continue fails with an {@link build.jenesis.repository.importer.ImportFailure}
 * classified by kind. Every URL a migration fetches passes {@link build.jenesis.repository.importer.ImportScreen} on
 * the fetcher a connector is handed.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.importer {
    requires transitive build.jenesis.repository.format;
    uses build.jenesis.repository.importer.ImportSourceProvider;
    exports build.jenesis.repository.importer;

    // The family extends IconContributor; transitive, since an implementation overriding icon() names IconResource.
    requires transitive build.jenesis.repository.icon;
}
