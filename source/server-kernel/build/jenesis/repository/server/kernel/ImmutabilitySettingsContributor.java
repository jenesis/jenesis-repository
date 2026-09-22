package build.jenesis.repository.server.kernel;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Surfaces the release-version immutability opt-out on the settings screens, API and CLI. The one dial,
 * {@code allow-redeploy}, defaults {@code false}: release-version immutability is <b>on by default</b>, so the
 * the deploy path refuses ({@code 409}) re-pointing an already-published immutable RELEASE coordinate at
 * different bytes - a supply-chain / dependency-confusion guard. An operator opts a tenant out by setting it
 * {@code true}, allowing a release coordinate to be overwritten in that tenant's space.
 *
 * <p>{@link Setting.Scope#TENANT tenant-scoped}, like the sibling publish-path admission dials (the gate's verdict
 * knobs and deny list): immutability is an artifact-admission policy that legitimately differs per tenant (a
 * hybrid deployment may keep it strict for a release space and relaxed for a sandbox), while it reads through the
 * same effective chain {@link LiveConfig#allowRedeploy} resolves. It applies live - a change takes effect on the
 * nodes' next settings re-read, no restart.
 */
public final class ImmutabilitySettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting("allow-redeploy", "Compliance", "Allow release re-deploy",
                        "Off by default: release-version immutability refuses re-pointing an already-published "
                                + "immutable release coordinate at different bytes (a 409), a supply-chain / "
                                + "dependency-confusion guard. Turn it on to let a release version be overwritten with "
                                + "different content. Snapshots and mutable channels are always re-deployable and are "
                                + "unaffected.",
                        Setting.Kind.BOOLEAN, "false", true, Setting.Scope.TENANT));
    }
}
