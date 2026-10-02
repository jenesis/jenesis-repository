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
                        "The GitHub Advisory Database: GitHub's security advisories for Maven, npm, PyPI, NuGet, "
                                + "RubyGems and Go packages, each with its GHSA id, CVE aliases, severity and the "
                                + "version that fixes it, and GitHub's marking of a package as malware. The gate "
                                + "checks each version against them - the vulnerability threshold and action decide on "
                                + "an advisory, the malware action on a package marked as malware - and the scheduled "
                                + "scan re-checks what is already published. A lookup is an outbound call to GitHub's "
                                + "REST API, answered for an hour from memory, and needs a GitHub token in the "
                                + "environment (JENREPO_GITHUB_TOKEN); without one the feed disables itself. It fails "
                                + "closed: while it is on and cannot be reached, a publish it would have screened is "
                                + "held for review rather than admitted unscreened - so switch it on only where this "
                                + "deployment can reach the endpoint below, and read a hold's reason before taking it "
                                + "for a verdict on the content.",
                        Setting.Kind.BOOLEAN, "false", false).essential(),
                new Setting("github-endpoint", "Compliance", "GitHub endpoint",
                        "The GitHub REST API base URL, for a self-hosted GitHub or a proxy.",
                        Setting.Kind.URI, "https://api.github.com", false).advanced());
    }
}
