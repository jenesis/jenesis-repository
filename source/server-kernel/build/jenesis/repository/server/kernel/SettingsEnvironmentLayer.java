package build.jenesis.repository.server.kernel;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.store.Features;
import build.jenesis.repository.settings.SettingsDocuments;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

/**
 * Layers the stored runtime settings (the per-module {@code config/settings/<module>.json} documents in the artifact
 * store, {@link SettingsDocuments}) over the file/env configuration at startup, so a setting an operator changed
 * through the API/console/CLI takes effect on the next boot even when it is one of the values that cannot change live
 * - the worker toggles, the cleanup schedule, the OSV switch, the audit trail. It runs once the environment is
 * prepared, resolving the store from the very properties already bound, merges every module's document and inserts
 * the stored values into the environment.
 *
 * <p><strong>Precedence.</strong> A stored setting sits <em>below</em> the operator's explicit pins - the OS
 * environment, {@code -D} system properties, the command line and any <em>external</em> config file - but
 * <em>above</em> the app's packaged classpath config file ({@code repository.properties} and its siblings; Spring keeps
 * those as separate sources, which is exactly what distinguishes an operator's explicit pin from a shipped default,
 * and {@link #isPackagedDefault} is the one place that tells them apart). So a value an operator pins wins
 * over what the store holds, and a value the operator stored wins over the shipped default; an explicit launch
 * override remains the escape hatch should a stored value ever wedge a boot. The live values ({@link LiveConfig}) do
 * not need this - they are re-read per request and consult {@link PinnedSettings} for the same rule - but seeding
 * them here too keeps the first requests after a restart consistent with the rest. Failure to read the store never
 * blocks boot.
 */
public final class SettingsEnvironmentLayer implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    private static final Logger LOGGER = LoggerFactory.getLogger(SettingsEnvironmentLayer.class);

    /** The name of the property source the stored settings are inserted under. */
    public static final String NAME = "jenesisStoredSettings";

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment environment = event.getEnvironment();
        try {
            ArtifactStore store = ArtifactStoreProvider.resolve(
                    environment.getProperty("jenreg.store", "filesystem"), environment::getProperty);
            Map<String, Object> overrides = new HashMap<>();
            for (String child : store.list(SettingsDocuments.ROOT)) {
                if (!child.endsWith(".json")) {
                    continue;
                }
                Optional<ArtifactStore.Versioned> object = store.readVersioned(SettingsDocuments.ROOT + "/" + child);
                if (object.isPresent()) {
                    for (Map.Entry<String, String> entry : SettingsDocuments.parse(object.get().content()).entrySet()) {
                        overrides.put(Features.key(entry.getKey()), entry.getValue());
                    }
                }
            }
            if (overrides.isEmpty()) {
                return;
            }
            insert(environment.getPropertySources(), overrides);
        } catch (Exception e) {
            LOGGER.warn("Could not layer stored runtime settings; booting from file/env configuration only", e);
        }
    }

    /** Re-seed the stored-settings property source from the current runtime overrides so this node converges on
     *  another node's change to a key that is read through an {@link org.springframework.core.env.Environment} lookup
     *  - rather than the live {@link Settings} snapshot - within the refresh interval; the environment is mutable at
     *  runtime, so the scheduled {@link SettingsRefresh} calls this after re-reading the store. Keys are namespaced
     *  under {@code jenreg.} exactly as the boot-time seeding does, and the source is re-inserted in place,
     *  so the precedence rule (stored below an operator's pins, above the packaged defaults) holds on every refresh and
     *  a cleared key stops shadowing the packaged default rather than leaving a stale value behind. */
    public static void refresh(ConfigurableEnvironment environment, Map<String, String> overrides) {
        Map<String, Object> namespaced = new HashMap<>();
        overrides.forEach((key, value) -> namespaced.put(Features.key(key), value));
        insert(environment.getPropertySources(), namespaced);
    }

    /** Insert the stored settings into {@code sources} directly above the packaged classpath config file (so a shipped
     *  default is overridden but an external config file, {@code -D}, the environment and the command line still win),
     *  or at the bottom when no packaged default is present. The bottom is a <em>fallback</em>, not a resting place -
     *  every app this listener is registered on ships a config file - so it says so rather than degrading in silence
     *  (&sect;9): landing there means a stored setting is outranked by the shipped default it is meant to override, and
     *  {@link PinnedSettings} then reports that default as an operator's pin. That is exactly what a stale name matcher
     *  produced for a year, invisibly. */
    public static void insert(MutablePropertySources sources, Map<String, Object> overrides) {
        sources.remove(NAME);
        MapPropertySource stored = new MapPropertySource(NAME, overrides);
        for (PropertySource<?> source : sources) {
            if (isPackagedDefault(source.getName())) {
                sources.addBefore(source.getName(), stored);
                return;
            }
        }
        List<String> names = new ArrayList<>();
        sources.forEach(source -> names.add(source.getName()));
        LOGGER.warn("No packaged classpath configuration found among {}; the stored runtime settings are layered at "
                + "the BOTTOM of the environment, so any of them a shipped default also sets will not take effect",
                names);
        sources.addLast(stored);
    }

    /**
     * Whether a property source is the app's packaged (classpath) configuration file - the shipped defaults a stored
     * setting overrides. Spring names a config-data source after the resource it came from, e.g. {@code Config resource
     * 'class path resource [repository.properties]' via location 'optional:classpath:/'}; an <em>external</em> config
     * file is a {@code file [...]} resource and is not matched, so it keeps its precedence above the store.
     *
     * <p><b>The base name is deliberately not matched.</b> This test used to require the substring
     * {@code "application"} as well, from a time when the apps' config file was {@code application.properties}. 
     * renamed all four - {@code repository}, {@code cache}, {@code combined}, {@code ui}, precisely so no dependency's
     * root {@code application.properties} could race them - and the matcher was left behind, matching nothing. Being
     * on the classpath at all <em>is</em> the property that makes a config resource a shipped default, and it is the
     * one an app cannot rename out from under this class; the configuration contract binds the recogniser
     * to every {@code spring.config.name} the tree actually declares so the pair cannot drift apart again.
     */
    public static boolean isPackagedDefault(String source) {
        return source != null && source.contains("class path resource");
    }
}
