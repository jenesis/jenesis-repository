package build.jenesis.repository.search.service;

import module java.base;
import build.jenesis.repository.search.SearchMode;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Declares the setting choosing how a repository answers a search: a repository setting with tenant and deployment
 * defaults, and essential, so the new-repository wizard asks it and the first boot's wizard asks the inherited default
 * - the index is a cost a repository takes on, decided when it is created.
 */
public final class SearchModeSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(new Setting(SearchMode.SETTING, "Search", "Full-text search",
                "Keep a full-text index of this repository - package names, descriptions, keywords and authors - and "
                        + "answer searches from it. Off, a search matches a package by the start of its name and "
                        + "nothing is built or stored. On, a background pass builds and maintains the index, at the "
                        + "cost of that pass and its storage; many repositories are rarely searched, so weigh it per "
                        + "repository. Following a licence count to its versions needs the index.",
                Setting.Kind.BOOLEAN, SearchMode.DEFAULT, true, Setting.Scope.REPOSITORY).essential());
    }
}
