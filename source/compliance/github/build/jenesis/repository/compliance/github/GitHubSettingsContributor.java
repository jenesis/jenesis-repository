package build.jenesis.repository.compliance.github;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Describes the GitHub Advisory Database feed's settings, so they surface on the settings screens exactly when this
 * module is installed. The API token stays environment-only ({@code JENREG_GITHUB_TOKEN}): the settings
 * store is readable over {@code /api/settings}, so a credential does not belong in it.
 */
public final class GitHubSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting("github", "Compliance", "GitHub advisories",
                        "Consult the GitHub Advisory Database.",
                        Setting.Kind.BOOLEAN, "false", false),
                new Setting("github-endpoint", "Compliance", "GitHub endpoint",
                        "The GitHub REST API base URL, for a self-hosted GitHub or a proxy.",
                        Setting.Kind.URI, "https://api.github.com", false));
    }
}
