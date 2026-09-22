package build.jenesis.repository.server.kernel;

import module java.base;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * The origin probe for the runtime-settings precedence rule, implemented once here and honoured by every consumer:
 * whether a setting key is <em>pinned</em> by a source that ranks above the stored settings - an environment
 * variable, a {@code -D} system property, the command line or an external config file. A pinned key ignores the store
 * entirely: the operator has fixed it, so a value written through the API/console/CLI is inert (the store value may
 * exist, but it does not apply), and a surface can say so. {@link LiveConfig} consults this so a pinned key resolves
 * to the operator's pin rather than the store, and the modules/settings screen greys a pinned knob and names what
 * pins it.
 *
 * <p>The rule reads straight off the prepared environment, whose sources {@link SettingsEnvironmentLayer} has already
 * ordered: anything above the stored-settings source (or, equivalently, above the app's packaged classpath config file
 * the store sits just over) pins the key; the stored source and the packaged defaults below it do not. It is robust to
 * the stored source being absent (no settings stored yet) by also stopping at the packaged defaults, so a value present
 * only in the shipped {@code repository.properties} is never mistaken for a pin.
 *
 * <h2>A pin's value is resolved; its origin is walked</h2>
 * A {@link Pin} carries two things that come from two different places, and it is worth being explicit about why.
 * The <em>origin</em> can only come from the walk: an {@link Environment} answers what a key resolves to and never
 * which source it came from, so naming the pinning source means finding it by hand. The <em>value</em> can only
 * come from the {@link Environment}: a {@link PropertySource} hands back its raw text, and an operator's own config
 * file is exactly where {@code jenreg.vulnerability-threshold=${THRESHOLD:HIGH}} is written, so the raw text is a
 * placeholder that nothing below the environment expands.
 *
 * <p>Reporting that raw text was not merely untidy. {@link #effective} hands a pin's value on as <em>the effective
 * value of the key</em>, so every consumer then parsed the literal: {@code Configuration.flag} read
 * {@code ${JENREG_AUTH:true}} as {@code false}, {@code Configuration.number} fell through to its default, and the
 * settings screen greyed the knob and offered the placeholder as the thing pinning it. A dial resolving to the
 * opposite of what the operator wrote is the kind of quiet wrong answer a pin exists to prevent.
 *
 * <h2>The whole chain, not just the probe</h2>
 * {@link #pinned} answers only the top leg. A surface that wants the <em>effective</em> value of a key needs all three
 * - pin over stored over the deployment environment - and every hand-written spelling of that composition is a chance
 * to leave a leg out. {@link #effective} and {@link #effectiveProperty} are that composition, so a reader consults the
 * chain by asking for it rather than by re-deriving it.
 *
 * <p>Leaving the pin leg out is not a cosmetic slip, which is why the composition lives here rather than at each call
 * site: for a key an operator has pinned <em>and</em> the store also holds, a stored-over-environment read reports the
 * <b>stored</b> value, which is inert - the server runs on the pin. On a read whose job is to report how safe the
 * deployment is, that reports the deployment as configured rather than as running: {@code jenreg.auth}
 * pinned {@code false} with {@code true} in the store makes a pin-blind posture read say authorization is on while
 * every request is being served anonymously.
 */
public final class PinnedSettings {

    /** The namespace every runtime settings key sits under as a deployment property; the store holds the bare key. */
    private static final String PREFIX = "jenreg.";

    /** Spring Boot attaches a {@code ConfigurationPropertySourcesPropertySource} named {@code configurationProperties}
     *  at the top of the environment; it is not an origin but an aggregating view over every other source, so it would
     *  always shadow the real source of a value. Skip it when locating what pins a key. */
    private static final String CONFIGURATION_PROPERTIES_AGGREGATOR = "configurationProperties";

    private final ConfigurableEnvironment environment;

    public PinnedSettings(ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    /** The source pinning {@code key} from above the stored-settings layer, or empty when nothing does (the key is
     *  unset, or set only in the store or the packaged defaults). */
    public Optional<Pin> pinned(String key) {
        String property = PREFIX + key;
        for (PropertySource<?> source : environment.getPropertySources()) {
            String name = source.getName();
            if (CONFIGURATION_PROPERTIES_AGGREGATOR.equals(name)) {
                // Not an origin - an aggregating view over the sources below it; the real source names the pin.
                continue;
            }
            if (SettingsEnvironmentLayer.NAME.equals(name) || SettingsEnvironmentLayer.isPackagedDefault(name)) {
                return Optional.empty();
            }
            for (String spelling : spellings(source, property)) {
                if (source.containsProperty(spelling)) {
                    Object raw = source.getProperty(spelling);
                    return raw == null
                            ? Optional.empty()
                            : Optional.of(new Pin(describe(name), resolved(property, raw.toString())));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The names under which a source may hold a property. Every source answers its canonical name; the process
     * environment also answers the two variable spellings Spring Boot binds - upper case with dots as underscores,
     * the hyphens either kept as underscores ({@code JENREG_PROXY_ALLOW_INTERNAL}) or dropped
     * ({@code JENREG_PROXYALLOWINTERNAL}). The environment source resolves only the first of those itself, while the
     * binder and {@code Environment.getProperty} resolve both - so without asking for the second here, a deployment
     * that sets the dropped-hyphen form has a working setting everywhere except in the pin chain, where a reader with
     * a literal fallback ({@code proxy-allow-internal}) then never sees it.
     */
    private static List<String> spellings(PropertySource<?> source, String property) {
        if (!(source instanceof SystemEnvironmentPropertySource)) {
            return List.of(property);
        }
        String upper = property.toUpperCase(Locale.ROOT).replace('.', '_');
        return List.of(property, upper.replace('-', '_'), upper.replace("-", ""));
    }

    /**
     * A pinned key's value as the running server resolves it, rather than as its source spells it.
     *
     * <p>The walk above has already established that {@code property}'s highest-precedence source is the pinning
     * one, so asking the environment for the same key reaches that same source - and expands the placeholders the
     * source's own raw text still carries.
     *
     * <p>An unresolvable {@code ${...}} with no default is reported at its spelling. Spring refuses to expand it
     * rather than inventing a value, and that refusal must not become a failure here: this is the surface an
     * operator opens to find out what their configuration is doing, so showing them the placeholder nothing sets
     * is the answer they need, and failing the screen is not.
     */
    private String resolved(String property, String raw) {
        try {
            String expanded = environment.getProperty(property);
            return expanded == null ? raw : expanded;
        } catch (IllegalArgumentException unresolvable) {
            return raw;
        }
    }

    /**
     * The whole runtime-settings precedence chain for a <em>bare</em> settings key, as one lookup: an operator's pin
     * from above the store wins outright, else the stored deployment-wide override, else the deployment's
     * {@code jenreg.<key>} environment value ({@code null} when nothing sets it). This is the chain
     * {@link LiveConfig} resolves the running server's dials through, so a surface that reports an effective value
     * reports what the server is running on rather than what the store happens to hold.
     */
    public UnaryOperator<String> effective(Settings settings, Environment environment) {
        return effective(settings, environment, null);
    }

    /**
     * The same chain resolved for one named tenant: pin over that tenant's own document over the deployment-wide
     * document over the environment - the order {@link Settings#getOrDefault(String, String, String)} already
     * implements below the pin, and the order a tenant's gate really resolves in. A {@code null} or blank tenant is
     * the deployment-wide chain, so a caller with an optional tenant needs no branch of its own.
     */
    public UnaryOperator<String> effective(Settings settings, Environment environment, String tenant) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(environment, "environment");
        String named = tenant == null ? "" : tenant.strip();
        return key -> {
            Optional<Pin> pin = pinned(key);
            if (pin.isPresent()) {
                return pin.get().value();
            }
            String fallback = environment.getProperty(PREFIX + key);
            return named.isEmpty()
                    ? settings.getOrDefault(key, fallback)
                    : settings.getOrDefault(named, key, fallback);
        };
    }

    /**
     * The same chain behind a <em>full</em> property name, for a reader (the posture {@code Configuration}) whose keys
     * arrive namespaced. A {@code jenreg.*} property resolves through {@link #effective}; anything else -
     * {@code spring.profiles.active}, {@code jenreg.ui.admins} - has neither a stored form nor a pin above one, so it
     * resolves from the environment alone rather than being silently answered from an unrelated store key.
     */
    public UnaryOperator<String> effectiveProperty(Settings settings, Environment environment, String tenant) {
        UnaryOperator<String> bare = effective(settings, environment, tenant);
        return property -> property.startsWith(PREFIX)
                ? bare.apply(property.substring(PREFIX.length()))
                : environment.getProperty(property);
    }

    /** A human phrase for the kind of source a pin comes from, for a screen to name what fixes a greyed knob. */
    static String describe(String source) {
        if (StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME.equals(source)) {
            return "environment variable";
        }
        if (StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME.equals(source)) {
            return "system property";
        }
        if (source.startsWith("commandLineArgs")) {
            return "command line";
        }
        if (source.contains("Config resource")) {
            return "configuration file";
        }
        return source;
    }

    /** A pin: the kind of source that fixes the key, and the value it fixes it to. */
    public record Pin(String source, String value) {
    }
}
