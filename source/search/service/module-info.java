/**
 * The one search a repository answers, which the API ({@code /api/search}), the console's search bar and the CLI call:
 * a lookup by name while a repository's full-text index is off (the default) and the index while it is on, each a
 * bounded page with a cursor and each hit screened. It also declares the setting choosing between them.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.search.service {
    requires transitive build.jenesis.repository.search;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.cleanup;
    requires build.jenesis.repository.settings;
    requires build.jenesis.repository.blobs;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    exports build.jenesis.repository.search.service;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.search.service.SearchModeSettingsContributor;
}
