package build.jenesis.repository.server;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Describes the one dial {@link RepositoryPresence} reads, beside it: whether a publish may create the repository it
 * names. Live, since the presence check reads it on every request that names a repository nobody created.
 */
public final class RepositoryPresenceSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(new Setting(RepositoryPresence.SETTING, "Defaults", "Create repository on publish",
                "Let a publish create the repository it names. Off by default: a repository is created in the "
                        + "console under Repositories or with a definition, and a request to one that does not exist "
                        + "answers 404 - so a misspelled name in a build's configuration is refused rather than "
                        + "becoming a repository, and a read through an upstream fills only a repository that was "
                        + "created. The one repository a deployment serves by default always exists.",
                Setting.Kind.BOOLEAN, "false", true));
    }
}
