package build.jenesis.repository.ratelimit;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Describes the rate limiter's ceiling, so the dial surfaces on the settings screens, the first-boot wizard, a
 * tenant's limits and in the boot check for unrecognised settings exactly when an image carries the limiter. It is a
 * tenant setting: the deployment's value is every tenant's default, and a tenant's own value replaces it for that
 * tenant.
 */
public final class RateLimitSettingsContributor implements SettingsContributor {

    /** The ceiling's key. */
    public static final String KEY = "rate-limit";

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting(KEY, "Limits", "Rate limit",
                        "How many requests per minute are admitted before further ones are answered 429 Too Many "
                                + "Requests; 0 disables the ceiling. It caps a runaway or abusive client without "
                                + "biting legitimate parallel CI.",
                        Setting.Kind.LONG, "6000", false, Setting.Scope.TENANT).gate().essential());
    }

    /** A negative ceiling, which no limiter honours. */
    @Override
    public Optional<String> refusal(Setting setting, String value, UnaryOperator<String> deployment) {
        return Long.parseLong(value.trim()) < 0 ? Optional.of("a ceiling is never negative") : Optional.empty();
    }
}
