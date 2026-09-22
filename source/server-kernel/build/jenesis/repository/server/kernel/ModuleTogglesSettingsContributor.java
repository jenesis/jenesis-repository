package build.jenesis.repository.server.kernel;

import module java.base;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.importer.ImportSourceProvider;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;
import build.jenesis.repository.compliance.GatePolicyProvider;
import build.jenesis.repository.compliance.ProvenanceSignerProvider;
import build.jenesis.repository.compliance.SignalSourceProvider;
import build.jenesis.repository.cleanup.RetentionProvider;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Catalogues the config-driven enable/disable convention's per-implementation toggles
 * ({@code jenreg.<feature>=true|false}, the {@code Features} convention) for the discovered modules
 * that carry no settings contributor of their own: every {@link RepositoryFormat}, {@link ImportSourceProvider}
 * and removable {@link ServerModuleProvider} feature surface, and every provider the maintenance, gate-policy,
 * signal-source and provenance-signer SPI homes switch off by name. Each toggle is a documented {@code BOOLEAN} setting
 * (default {@code true} - one image carries every module and configuration trims it), so the settings screens,
 * the config search and {@code /api/settings} list exactly the switchable modules of this deployment. A module
 * whose own contributor already catalogues a setting under its name (the {@code index} task's gate, say) keeps
 * that richer entry: this contributor enumerates its peers and skips any key another one declares, so no key is
 * listed twice. Toggles apply at boot ({@code live == false}); flipping one off degrades the module exactly as if
 * it were absent from the image.
 */
public final class ModuleTogglesSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        Set<String> taken = new HashSet<>();
        for (SettingsContributor contributor : SettingsContributor.declared()) {
            if (contributor instanceof ModuleTogglesSettingsContributor) {
                continue;
            }
            for (Setting setting : contributor.settings()) {
                taken.add(setting.key());
            }
        }
        Map<String, String> toggles = new TreeMap<>();
        // declared(), not installed(): the toggle of a format configured off is exactly what this catalogue lists.
        for (RepositoryFormat format : RepositoryFormat.declared()) {
            toggles.putIfAbsent(format.name(), "the " + format.name() + " repository format");
        }
        for (ImportSourceProvider source : ImportSourceProvider.declared()) {
            toggles.putIfAbsent(source.name(), "the " + source.label() + " import source");
        }
        // The SPI home's one validated discovery, so the toggle this catalogues is the same key - and the same set of
        // modules - the import selector switches on; two modules answering to one name would give an operator a single
        // switch governing both, so the discovery refuses it rather than cataloguing one and toggling two.
        for (ServerModuleProvider module : ServerModuleProvider.installed()) {
            toggles.putIfAbsent(module.name(), "the " + module.name() + " feature endpoints");
        }
        // The provider families whose SPI homes switch an implementation off by its name (Features.active over
        // provider.name()) and whose names had no catalogue row: the maintenance passes, the gate policy dimensions,
        // the signal sources and the provenance signers. A name is the live switch whether or not it is listed, so
        // listing it is what turns a one-letter near-miss of a catalogued key (private-name beside private-names)
        // from a trap into a row an operator can read.
        for (String task : MaintenanceTaskProvider.installed()) {
            toggles.putIfAbsent(task, "the " + task + " maintenance pass");
        }
        for (GatePolicyProvider policy : GatePolicyProvider.installed()) {
            toggles.putIfAbsent(policy.name(), "the " + policy.name() + " gate policy dimension");
        }
        for (SignalSourceProvider source : SignalSourceProvider.contributors()) {
            toggles.putIfAbsent(source.name(), "the " + source.name() + " signal source");
        }
        for (String signer : ProvenanceSignerProvider.installed()) {
            toggles.putIfAbsent(signer, "the " + signer + " provenance signer");
        }
        for (String engine : RetentionProvider.installed()) {
            toggles.putIfAbsent(engine, "the " + engine + " retention engine");
        }
        List<Setting> settings = new ArrayList<>();
        toggles.forEach((name, what) -> {
            if (!taken.contains(name)) {
                settings.add(new Setting(name, "Modules", name,
                        "Enable " + what + "; false removes it at the next start, degrading exactly like an "
                                + "absent module.",
                        Setting.Kind.BOOLEAN, "true", false));
            }
        });
        return List.copyOf(settings);
    }
}
