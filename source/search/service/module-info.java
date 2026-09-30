/**
 * The one search a repository answers, which the API ({@code /api/search}), the console's search bar and, through
 * the API, the {@code jenrepo} CLI all call: a lookup by name while a repository's full-text index is off - the
 * default - and the index while it is on, each a bounded page with a cursor, each hit screened so a held version is
 * no more findable here than it is served. It also declares the setting that chooses between the two.
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
    exports build.jenesis.repository.search.service;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.search.service.SearchModeSettingsContributor;
}
