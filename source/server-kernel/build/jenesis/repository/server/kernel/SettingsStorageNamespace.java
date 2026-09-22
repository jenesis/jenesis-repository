package build.jenesis.repository.server.kernel;

import module java.base;

import build.jenesis.repository.maintenance.StorageNamespace;
import build.jenesis.repository.settings.SettingsDocuments;

/**
 * The storage manifest for the deployment's runtime settings: the per-module documents under
 * {@link SettingsDocuments#ROOT} that {@link Settings} and {@link SettingsEnvironmentLayer} read and write, declared so
 * the orphan diagnostic can name them and the operator purge can reach them.
 *
 * <p><strong>Why the declaration lives here and not in the settings module.</strong> The space is defined by
 * {@code build.jenesis.repository.settings}, which is an SPI contract module the maintenance SPI itself
 * {@code requires} - it cannot require the manifest back without inverting that dependency. The module that actually
 * <em>persists</em> there is this one, so this is where the ownership is declared: purging
 * {@code build.jenesis.repository.server.kernel} reclaims the deployment's stored settings, which is exactly the
 * statement a manifest entry makes.
 *
 * <p><strong>What it covers, and what it deliberately does not.</strong> The deployment-wide documents at
 * {@code config/settings/<module>.json} are declared; a tenant's own overrides live under its store scope
 * ({@code <tenant>/config/settings/<module>.json}) and are <em>not</em> declared here, because a tenant-scoped
 * declaration must be a reserved dot-space - the purge enumerates a tenant's non-dot children as repositories - and
 * {@code config} is not one. Those documents are reclaimed with their tenant instead: removing a tenant deletes the
 * whole {@code <tenant>/} scope, so they can never outlive the tenant that owns them and are never orphaned data in
 * the sense this manifest is about.
 *
 * <p>Like the manifest module's own entry, this is a declaration by a module that is never removed. That is not
 * decoration: an installed module is never an orphan candidate, and the entry is what lets an operator see - and, with
 * the target named explicitly, reclaim - the one deployment-global space the settings surfaces write to.
 */
public final class SettingsStorageNamespace implements StorageNamespace {

    @Override
    public Set<String> sharedPrefixes() {
        return Set.of(SettingsDocuments.ROOT);
    }
}
