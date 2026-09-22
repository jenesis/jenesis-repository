package build.jenesis.repository.settings;

import module java.base;

import build.jenesis.repository.observation.SpiCatalog;

/**
 * The installed/enabled state of one discovered module, resolved for the modules console and {@code /api/capabilities}
 * from {@link SettingsContributor#modules() discovery} layered over a configuration lookup - no maintained table of
 * modules and their flags. A discovered module is {@code installed}; its {@code enabled} state reads the effective
 * value of its {@link Setting#enablement enablement gate} (a module with no gate is always on once installed).
 * {@code live} says whether flipping the gate applies on the next scheduled re-read or only on restart, and
 * {@code toggleable} whether the gate is a plain boolean the console offers as an enable/disable switch (a numeric
 * ceiling like {@code rate-limit} is edited as a value, not switched).
 *
 * <p>A module named only by a leftover stored settings document but absent from the module path renders
 * {@code installed == false} with no settings - the leftovers case, so an operator sees a stored document for a
 * module this image was not built with. Presence is an image-build decision (the {@code build-images.sh} feature set),
 * never a runtime one; the screen says so.
 */
public record ModuleCapability(String module, boolean installed, String enableKey, boolean enabled, boolean live,
                               boolean toggleable, List<Setting> settings) {

    public ModuleCapability {
        settings = List.copyOf(settings);
    }

    /**
     * The removable modules and the settings each declares, read once.
     *
     * <p>{@link #resolve} is called from the deployment report per request and walked every contributor to build
     * this each time. A module's identity and the keys it owns are properties of what is installed; whether it is
     * <em>enabled</em> is not, and that is still read from the effective configuration on every call below.
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
     * The deployment's plug-in surface grouped by SPI: the shared module-graph walk, decorated with what this
     * deployment knows about each implementation's module.
     *
     * <p>The walk itself is not here. There were two of them - one in the console that could report only which
     * providers existed, and one here that also knew each module's installed and enabled state - enumerating the same
     * graph with the same filter and the same ordering into two records the same screen was written against twice.
     * This supplies the half a deployment reading stored settings can add; the enumeration is
     * {@link SpiCatalog#of(ModuleLayer, SpiCatalog.Decoration)}.
     *
     * <p>{@code effective} answers the effective value of a settings key - an operator's pin over the stored value
     * over the product default, which is the chain the running server resolves a gate through - and
     * {@code storedModules} are the module names holding a stored document, so an uninstalled leftover still
     * surfaces.
     */
    public static List<SpiCatalog> catalog(UnaryOperator<String> effective, Set<String> storedModules) {
        Map<String, SpiCatalog.Capability> byModule = new HashMap<>();
        for (ModuleCapability capability : resolve(effective, storedModules)) {
            byModule.put(capability.module(), new SpiCatalog.Capability(capability.installed(), capability.enabled(),
                    capability.enableKey(), capability.settings().stream()
                    .map(setting -> new SpiCatalog.Setting(setting.key(), setting.label())).toList()));
        }
        // A module the capability resolution never saw contributes no SettingsContributor at all, so it declares no
        // gate and is on once installed - the same rule resolve() applies to a discovered module without one.
        return SpiCatalog.of(ModuleLayer.boot(),
                module -> byModule.getOrDefault(module, SpiCatalog.Capability.ALWAYS_ON));
    }

    /** The resolved capability of every discovered module plus a not-installed row for any module named only by a
     *  leftover stored document. {@code effective} answers the effective value of a settings key (an operator's pin
     *  over the store, {@code null} when neither is set, so the gate's product default applies); {@code storedModules}
     *  are the module names that hold a stored settings document, so an uninstalled one still surfaces. Ordered by
     *  module name. */
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

    /** Whether an enablement gate resolves to on: a boolean reads {@code true}, a numeric ceiling is on when non-zero,
     *  any other kind when its value is non-blank. A {@code null} lookup falls back to the gate's product default, so a
     *  module never installs disabled merely because nothing has been stored yet. */
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
