package build.jenesis.repository.ratelimit;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Describes the rate limiter's deployment-default ceiling, so the dial surfaces on the settings screens and in the
 * boot check for unrecognised settings exactly when an image carries the limiter.
 */
public final class RateLimitSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting("rate-limit", "Defaults", "Rate limit",
                        "Default request ceiling in permits per minute per tenant; 0 disables. Defaults to 6000 "
                                + "(100 req/s per tenant) - a sane ceiling that caps a runaway or abusive client "
                                + "without biting legitimate parallel CI; an operator raises, lowers, or sets 0 to "
                                + "disable, and a per-tenant value overrides it.",
                        Setting.Kind.LONG, "6000", false).gate());
    }
}
