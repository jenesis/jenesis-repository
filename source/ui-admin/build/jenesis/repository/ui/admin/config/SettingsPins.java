package build.jenesis.repository.ui.admin.config;

import module java.base;

import build.jenesis.repository.store.Features;
import build.jenesis.repository.ui.store.SettingsAdmin;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * The console's runtime-settings precedence probe: whether a setting key is <em>pinned</em> from a source the
 * operator controls at launch - an environment variable, a {@code -D} system property, the command line or an
 * external config file - so the console greys that knob, names what pins it and shows the pin's value as effective.
 * It reads the console's own Spring environment; in the recommended combined deployment (console and repository in
 * one process) that is literally the repository's environment, and a standalone console typically launches from the
 * same image and env, so the view matches what the repository actually applies.
 *
 * <p>It mirrors the repository server's {@code PinnedSettings} rule but does not depend on it: {@code PinnedSettings}
 * lives in the repository-server module the console deliberately keeps off its path (the same decoupling
 * under which {@link SettingsAdmin} reads the settings catalogue through the shared SPI rather than fetch it over
 * HTTP). Rather than reason about the packaged-default boundary, it names the operator-controlled source kinds
 * explicitly, so a shipped classpath default or a stored setting is never mistaken for a pin.
 */
public final class SettingsPins {

    private final ConfigurableEnvironment environment;

    public SettingsPins(ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    /** The pin fixing {@code key} from an operator-controlled source above the store, or empty when nothing does (the
     *  key is unset, or set only in a shipped default or the store). */
    public Optional<SettingsAdmin.Pin> pinned(String key) {
        String property = Features.key(key);
        for (PropertySource<?> source : environment.getPropertySources()) {
            String name = source.getName();
            if (!isOperatorPin(name)) {
                continue;
            }
            if (source.containsProperty(property)) {
                Object value = source.getProperty(property);
                if (value != null) {
                    return Optional.of(new SettingsAdmin.Pin(describe(name), value.toString()));
                }
            }
        }
        return Optional.empty();
    }

    /** Whether a property source is one an operator pins a value through - the OS environment, {@code -D} system
     *  properties, the command line, or an <em>external</em> config file (a {@code file [...]} resource, not a
     *  packaged {@code class path resource}). A shipped classpath default and the runtime settings store are not
     *  pins: the store value applies until an operator fixes the key above it. */
    private static boolean isOperatorPin(String name) {
        return StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME.equals(name)
                || StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME.equals(name)
                || name.startsWith("commandLineArgs")
                || (name.contains("Config resource") && name.contains("file ["));
    }

    /** A human phrase for the kind of source a pin comes from, for the screen to name what fixes a greyed knob. */
    private static String describe(String source) {
        if (StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME.equals(source)) {
            return "environment variable";
        }
        if (StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME.equals(source)) {
            return "system property";
        }
        if (source.startsWith("commandLineArgs")) {
            return "command line";
        }
        return "configuration file";
    }
}
