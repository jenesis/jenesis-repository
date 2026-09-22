package build.jenesis.repository.settings;

import module java.base;

/**
 * One installed module's contributed settings and its {@link Setting#enablement enablement gate}, as
 * {@link SettingsContributor#modules()} discovers it. Pure discovery: it names the module and lists what it
 * contributes, without reading the store or resolving any effective value - {@link ModuleCapability} layers the
 * installed/enabled state over this from a configuration lookup. A module contributes a gate when exactly one of its
 * settings is marked {@link Setting#gate()}; that gate's {@link Setting#live() live} flag says whether flipping it
 * applies on the next scheduled re-read or only on restart.
 */
public record ModuleSettings(String module, List<Setting> settings, Optional<Setting> gate) {

    public ModuleSettings {
        settings = List.copyOf(settings);
    }

    /** Group a module's settings and pick its enablement gate (the first setting marked {@link Setting#gate()}, none
     *  for an always-on module). */
    static ModuleSettings of(String module, List<Setting> settings) {
        Optional<Setting> gate = settings.stream().filter(Setting::enablement).findFirst();
        return new ModuleSettings(module, settings, gate);
    }

    /** The key of this module's enablement gate, or empty when the module is always on once installed. */
    public Optional<String> gateKey() {
        return gate.map(Setting::key);
    }
}
