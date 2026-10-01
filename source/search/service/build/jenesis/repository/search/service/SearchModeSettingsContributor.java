package build.jenesis.repository.search.service;

import module java.base;
import build.jenesis.repository.search.SearchMode;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Declares the setting that chooses how a repository answers a search. It is a repository setting with a tenant and
 * a deployment default, and essential, so the new-repository wizard asks it and the first boot's wizard asks the
 * default every repository inherits: the index is a cost a repository takes on, and the moment it is created is the
 * moment to decide whether it should.
 */
public final class SearchModeSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(new Setting(SearchMode.SETTING, "Search", "Full-text search",
                "Keep a full-text index of this repository - package names, descriptions, keywords and authors - "
                        + "and answer searches from it. Search can feel like an essential feature, but many "
                        + "repositories rarely use it: people look up the artifacts they already know by name, or "
                        + "follow up what the gate held. Off, a search looks a package up by the start of its name "
                        + "from what the repository already keeps sorted, and nothing is built or stored. On, a "
                        + "background pass builds the index and keeps it current, which costs its build, its "
                        + "storage and the pass that maintains it - weigh the need against that cost, repository by "
                        + "repository. The licence inventory is counted without it; only following a count to the "
                        + "versions behind it needs the index.",
                Setting.Kind.BOOLEAN, SearchMode.DEFAULT, true, Setting.Scope.REPOSITORY).essential());
    }
}
