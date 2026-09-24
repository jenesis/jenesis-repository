package build.jenesis.repository.compliance.github;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.SignalContext;
import build.jenesis.repository.compliance.SignalSource;
import build.jenesis.repository.compliance.SignalSourceProvider;

/**
 * Discovers the GitHub Advisory Database feed: enabled by the {@code github} setting, pointed at
 * {@code github-endpoint} (default {@code https://api.github.com}), and <b>requiring</b> a {@code github-token}.
 * Composed with any other enabled feed and de-duplicated.
 *
 * <p><b>The token is required, not a rate-limit nicety.</b> It used to be described as optional, and without one the
 * source issued an unauthenticated request - which {@code /advisories} answers {@code 403}, not with a smaller page.
 * An advisory feed fails CLOSED, so that 403 held every publish in quarantine: a bundled deployment, which turns
 * this feed on by default, could not accept a single artifact until someone found the setting. The feed now
 * self-disables when the credential is unset, exactly as the credentialed feeds beside it do and exactly as the
 * secure floor already promised ("their provider self-disables when the credential is unset"). Screening continues
 * on every other enabled feed rather than stopping on this one's absence.
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
            // Enabled but uncredentialed: not installed at all, rather than installed and erroring on every publish.
            LOGGER.warn("The github advisory feed is enabled but no github-token is set, so it is not installed: "
                    + "GitHub answers /advisories 403 without a credential, and an advisory feed fails closed, which "
                    + "would quarantine every publish. Set github-token, or set github=false to say so deliberately.");
            return Optional.empty();
        }
        String endpoint = context.setting("github-endpoint");
        return Optional.of(GitHubAdvisorySource.over(
                URI.create(endpoint == null || endpoint.isBlank() ? "https://api.github.com" : endpoint),
                token, context.clock()));
    }
}
