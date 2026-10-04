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
                        "The GitHub Advisory Database: advisories for Maven, npm, PyPI, NuGet, RubyGems and Go "
                                + "packages, with severity and fixed versions, and GitHub's marking of a package as "
                                + "malware. The gate checks each version against them and the scan re-checks what is "
                                + "published. A lookup calls GitHub's REST API with the token in JENREPO_GITHUB_TOKEN; "
                                + "without one the feed disables itself. It fails closed: while unreachable, a publish "
                                + "it would screen is held for review.",
                        Setting.Kind.BOOLEAN, "false", true).essential(),
                new Setting("github-endpoint", "Compliance", "GitHub endpoint",
                        "The GitHub REST API base URL, for a self-hosted GitHub or a proxy.",
                        Setting.Kind.URI, "https://api.github.com", true).advanced());
    }
}
