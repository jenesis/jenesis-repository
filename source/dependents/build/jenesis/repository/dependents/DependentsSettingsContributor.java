package build.jenesis.repository.dependents;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Describes the declared-dependencies pass's settings, so they surface exactly when this module is installed. The
 * cadence renders its key and default from {@link DependentsIndexTaskProvider#INTERVAL}.
 */
public final class DependentsSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting("dependents-index", "Dependencies", "Declared-dependencies index",
                        "Index, on the background pass, the dependencies each published version's manifest declares, "
                                + "with the requirement each states - what \"who declares a dependency on X\" answers "
                                + "from. Which versions rely on X as their resolved closures reach it is the closure's "
                                + "relied-on index.",
                        Setting.Kind.BOOLEAN, "true", true).gate().standard(),
                new Setting(DependentsIndexTaskProvider.INTERVAL.key(), "Dependencies",
                        "Declared-dependencies pass interval",
                        "How often the declared-dependencies pass runs.",
                        Setting.Kind.DURATION, DependentsIndexTaskProvider.INTERVAL.fallbackText(), true).advanced());
    }
}
