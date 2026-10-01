package build.jenesis.repository.settings;

import module java.base;

import build.jenesis.repository.observation.SpiCatalog;

/**
 * The installed and enabled state of one module, for the modules console and {@code /api/capabilities}, from
 * {@link SettingsContributor#modules() discovery} over a configuration lookup. {@code enabled} reads the effective
 * value of the module's {@link Setting#enablement enablement gate} (always on without one), {@code live} whether
 * flipping it applies on the next re-read, and {@code toggleable} whether it is a boolean the console offers as a
 * switch rather than a value such as a numeric ceiling.
 *
 * <p>A module named only by a leftover stored document is {@code installed == false} with no settings. Presence is
 * decided when the image is built, never at runtime.
 */
public record ModuleCapability(String module, boolean installed, String enableKey, boolean enabled, boolean live,
                               boolean toggleable, List<Setting> settings) {

    public ModuleCapability {
        settings = List.copyOf(settings);
    }

    /**
     * The removable modules and their settings, read once since they are fixed by what is installed; whether one is
     * enabled is read on every call to {@link #resolve}, which the deployment report makes per request.
     */
    private static final class Declared {

        private static final List<ModuleSettings> MODULES = SettingsContributor.modules();

        private Declared() {
        }
    }

    /** Whether this module carries an enablement gate (an enable/disable flag), as opposed to being always on once
     *  installed. */
    public boolean gated() {
        return enableKey != null;
    }

    /**
     * The deployment's plug-in surface grouped by SPI ({@link SpiCatalog#of(ModuleLayer, SpiCatalog.Decoration)}),
     * decorated with each module's installed and enabled state. {@code effective} answers a key's effective value and
     * {@code storedModules} names the modules holding a stored document, as for {@link #resolve}.
     */
    public static List<SpiCatalog> catalog(UnaryOperator<String> effective, Set<String> storedModules) {
        Map<String, SpiCatalog.Capability> byModule = new HashMap<>();
        for (ModuleCapability capability : resolve(effective, storedModules)) {
            byModule.put(capability.module(), new SpiCatalog.Capability(capability.installed(), capability.enabled(),
                    capability.enableKey(), capability.settings().stream()
                    .map(setting -> new SpiCatalog.Setting(setting.key(), setting.label())).toList()));
        }
        // A module with no SettingsContributor declares no gate, so it is on once installed.
        return SpiCatalog.of(ModuleLayer.boot(),
                module -> byModule.getOrDefault(module, SpiCatalog.Capability.ALWAYS_ON));
    }

    /** Every discovered module's capability, plus a not-installed row for each module named only by a stored document,
     *  ordered by module name. {@code effective} answers a key's effective value, {@code null} when unset so the gate's
     *  default applies; {@code storedModules} are the modules holding a stored document. */
    public static List<ModuleCapability> resolve(UnaryOperator<String> effective, Set<String> storedModules) {
        List<ModuleCapability> capabilities = new ArrayList<>();
        Set<String> installed = new HashSet<>();
        for (ModuleSettings module : Declared.MODULES) {
            installed.add(module.module());
            Optional<Setting> gate = module.gate();
            String enableKey = gate.map(Setting::key).orElse(null);
            boolean enabled = gate.map(setting -> isEnabled(setting, effective.apply(setting.key()))).orElse(true);
            boolean live = gate.map(Setting::live).orElse(true);
            boolean toggleable = gate.map(setting -> setting.kind() == Setting.Kind.BOOLEAN).orElse(false);
            capabilities.add(new ModuleCapability(module.module(), true, enableKey, enabled, live, toggleable,
                    module.settings()));
        }
        for (String stored : storedModules) {
            if (!installed.contains(stored) && !stored.equals(SettingsDocuments.NEUTRAL)) {
                capabilities.add(new ModuleCapability(stored, false, null, false, false, false, List.of()));
            }
        }
        capabilities.sort(Comparator.comparing(ModuleCapability::module));
        return List.copyOf(capabilities);
    }

    /** Whether a gate resolves to on: a boolean when {@code true}, a number when non-zero, any other kind when
     *  non-blank. An unset value falls back to the gate's default. */
    private static boolean isEnabled(Setting gate, String value) {
        String resolved = value == null || value.isBlank() ? gate.defaultValue() : value.trim();
        return switch (gate.kind()) {
            case BOOLEAN -> Boolean.parseBoolean(resolved);
            case INTEGER, LONG -> {
                try {
                    yield Long.parseLong(resolved) != 0L;
                } catch (NumberFormatException _) {
                    yield false;
                }
            }
            default -> !resolved.isBlank();
        };
    }
}
