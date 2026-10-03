package build.jenesis.repository.ratelimit;

import module java.base;
import build.jenesis.repository.settings.CoreDefaults;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Describes the rate limiter's ceilings, so the dials surface on the settings screens, the first-boot wizard, a
 * tenant's limits and in the boot check for unrecognised settings exactly when an image carries the limiter. The
 * tenant's and the credential's are tenant settings: the deployment's value is every tenant's default, and a tenant's
 * own value replaces it for that tenant. The address's is the deployment's alone, since an address is no tenant's.
 */
public final class RateLimitSettingsContributor implements SettingsContributor {

    /** The tenant's ceiling's key. */
    public static final String KEY = "rate-limit";

    /** The per-credential ceiling's key. */
    public static final String ACCOUNT = "rate-limit-account";

    /** The per-address ceiling's key. */
    public static final String ADDRESS = "rate-limit-address";

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting(KEY, "Limits", "Rate limit",
                        "How many requests a minute a tenant's credentials make together to its repositories and "
                                + "build cache before further ones are answered 429 Too Many Requests; keyless "
                                + "requests share one such ceiling at the deployment's value. Zero sets no ceiling. "
                                + "The console and the management API are never limited.",
                        Setting.Kind.LONG, CoreDefaults.RATE_LIMIT, true, Setting.Scope.TENANT).gate().standard(),
                new Setting(ACCOUNT, "Limits", "Rate limit per credential",
                        "How many requests a minute one credential makes to the repositories and build cache before "
                                + "further ones are answered 429, so one runaway job cannot spend its tenant's whole "
                                + "rate limit. Zero sets no ceiling of its own: the tenant's still applies.",
                        Setting.Kind.LONG, CoreDefaults.RATE_LIMIT_ACCOUNT, true, Setting.Scope.TENANT).gate()
                        .advanced(),
                new Setting(ADDRESS, "Limits", "Rate limit per client address",
                        "How many requests a minute one client address makes to the repositories and build cache, "
                                + "keyed or not, before further ones are answered 429 - a thousand a second by "
                                + "default, which no build machine sustains. Behind a reverse proxy, name it in "
                                + "trusted-proxies so each client counts on its own; a proxy on a private address "
                                + "is read through its X-Forwarded-For either way, while one that forwards no "
                                + "address makes every client one. Zero sets no ceiling.",
                        Setting.Kind.LONG, CoreDefaults.RATE_LIMIT_ADDRESS, true).gate().advanced());
    }

    /** A negative ceiling, which no limiter honours. */
    @Override
    public Optional<String> refusal(Setting setting, String value, UnaryOperator<String> deployment) {
        return Long.parseLong(value.trim()) < 0 ? Optional.of("a ceiling is never negative") : Optional.empty();
    }
}
