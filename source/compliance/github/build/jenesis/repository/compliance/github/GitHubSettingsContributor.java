package build.jenesis.repository.compliance.github;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Describes the GitHub feed's settings, so they appear exactly when this module is installed. The token stays
 * environment-only ({@code JENREPO_GITHUB_TOKEN}), since the settings store is readable over {@code /api/settings}.
 */
public final class GitHubSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting("github", "Compliance", "GitHub advisories",
                        "Consult the GitHub Advisory Database.",
                        Setting.Kind.BOOLEAN, "false", false).essential(),
                new Setting("github-endpoint", "Compliance", "GitHub endpoint",
                        "The GitHub REST API base URL, for a self-hosted GitHub or a proxy.",
                        Setting.Kind.URI, "https://api.github.com", false).advanced());
    }
}
