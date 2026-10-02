package build.jenesis.repository.server.kernel;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * The tenant storage quota: how many bytes of stored content a tenant's repositories may hold together, enforced on
 * every publish by the metering store the kernel wraps a quota'd tenant's repositories in ({@link Repositories}).
 * A tenant setting - the deployment's value is every tenant's quota, a tenant's own replaces it - so the first-boot
 * wizard asks the deployment's and a tenant's limits screen edits its own, both through the catalogue.
 *
 * <p>Not {@code quota}: that key is the deployment-wide cap over the whole store the environment sets at boot
 * ({@code jenrepo.quota}), which is not this setting and must not be written into by the stored one.
 */
public final class QuotaSettingsContributor implements SettingsContributor {

    /** The quota's key. */
    public static final String KEY = "tenant-quota";

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting(KEY, "Limits", "Storage quota",
                        "How many bytes of stored content a tenant's repositories may hold together; zero sets no "
                                + "limit. A publish past it is refused with 507. The usage is recounted by the "
                                + "scheduled cleanup, so a lowered quota bites from the next count.",
                        Setting.Kind.LONG, "0", true, Setting.Scope.TENANT).standard());
    }

    /** A negative quota, which no count could be under. */
    @Override
    public Optional<String> refusal(Setting setting, String value, UnaryOperator<String> deployment) {
        return Long.parseLong(value.trim()) < 0 ? Optional.of("a quota is never negative") : Optional.empty();
    }
}
