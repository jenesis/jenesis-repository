package build.jenesis.repository.settings;

import module java.base;

/**
 * One installed module's contributed settings and its {@link Setting#enablement enablement gate}, as
 * {@link SettingsContributor#modules()} discovers it, without reading the store; {@link ModuleCapability} layers the
 * enabled state over it.
 */
public record ModuleSettings(String module, List<Setting> settings, Optional<Setting> gate) {

    public ModuleSettings {
        settings = List.copyOf(settings);
    }

    /** A module's settings with its gate, the first setting marked {@link Setting#gate()}. */
    static ModuleSettings of(String module, List<Setting> settings) {
        Optional<Setting> gate = settings.stream().filter(Setting::enablement).findFirst();
        return new ModuleSettings(module, settings, gate);
    }

    /** The key of this module's enablement gate, or empty when the module is always on once installed. */
    public Optional<String> gateKey() {
        return gate.map(Setting::key);
    }
}
