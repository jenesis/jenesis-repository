package build.jenesis.repository.compliance.github;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.SignalContext;
import build.jenesis.repository.compliance.SignalSource;
import build.jenesis.repository.compliance.SignalSourceProvider;

/**
 * Discovers the GitHub Advisory Database feed: enabled by {@code github}, pointed at {@code github-endpoint} (default
 * {@code https://api.github.com}), and <b>requiring</b> a {@code github-token}. Composed with any other enabled feed
 * and de-duplicated.
 *
 * <p>The token is required: without one {@code /advisories} answers {@code 403}, and an advisory feed fails closed, so
 * every publish would be held. The feed self-disables when the credential is unset, as the other credentialed feeds do,
 * and screening continues on every other enabled feed.
 */
public final class GitHubAdvisorySourceProvider implements SignalSourceProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(GitHubAdvisorySourceProvider.class);

    @Override
    public String name() {
        return "github";
    }

    @Override
    public Set<Class<? extends SignalSource>> signals() {
        return Set.of(AdvisorySource.class);
    }

    @Override
    public Optional<SignalSource> create(SignalContext context) {
        if (!context.enabled("github", false)) {
            return Optional.empty();
        }
        String token = context.setting("github-token");
        if (token == null || token.isBlank()) {
            // Enabled but uncredentialed: not installed, rather than installed and failing every publish.
            LOGGER.warn("The github advisory feed is enabled but no github-token is set, so it is not installed: "
                    + "GitHub answers /advisories 403 without a credential, and an advisory feed fails closed, which "
                    + "would quarantine every publish. Set github-token, or set github=false to say so deliberately.");
            return Optional.empty();
        }
        String endpoint = context.setting("github-endpoint");
        return Optional.of(GitHubAdvisorySource.over(
                URI.create(endpoint == null || endpoint.isBlank() ? "https://api.github.com" : endpoint),
                token, context.clock(), context::snapshots));
    }
}
