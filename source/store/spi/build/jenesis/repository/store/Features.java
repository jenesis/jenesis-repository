package build.jenesis.repository.store;

import module java.base;

/**
 * The config-driven enable/disable convention for every discovered SPI implementation, defined once here in the
 * base SPI module and reused verbatim by every distribution - a feature keeps the same key whether it ships in the
 * free image or a commercial one, and relocating a component between them never changes its configuration.
 *
 * <p>The convention, over the shared {@code jenreg.*} namespace:
 * <ul>
 * <li>A <em>parallel</em> SPI (many implementations active at once - formats, import sources, feeds, gate
 *     policies, maintenance tasks) toggles each implementation with {@code jenreg.<feature>=true|false},
 *     where {@code <feature>} is the provider's {@code name()}. Nothing set means <em>enabled</em>; only an explicit
 *     {@code false} disables. A disabled implementation is simply not activated at {@link ServiceLoader} discovery,
 *     so it degrades exactly like a missing module (its endpoint answers {@code 501} / not-found, the rest runs).</li>
 * <li>A <em>singleton</em> SPI (one active implementation - the store backend, the token exchange) selects its
 *     implementation with {@code jenreg.<spi>=<feature>}; nothing set picks the most universally
 *     applicable default (the {@code filesystem} store) or, where the SPI is optional, the single enabled
 *     implementation. Resolution runs through the {@link Providers} primitives, so an explicitly selected
 *     implementation that is absent, switched off or unconfigured fails loudly (&sect;9) and two enabled
 *     implementations are ambiguous - discovery order never picks a winner.</li>
 * <li><em>One key, one meaning:</em> because both shapes live in the one {@code jenreg.*} namespace, an
 *     implementation's {@code name()} may never be the name of an <em>SPI</em>. The two readings of such a key
 *     collide silently in the benign direction and destructively in the other: the walk implementation was called
 *     {@code store}, so its documented off-switch {@code jenreg.store=false} was also the artifact
 *     store's selection key, and using it selected a storage backend named {@code false} and refused to boot -
 *     while every deployment's ordinary {@code jenreg.store=filesystem} was silently doubling as that
 *     walk's toggle. A build guard scans for the collision rather than trusting the convention.</li>
 * <li>An implementation's own settings live under {@code jenreg.<feature>.<property>=<value>} or its documented
 *     settings keys; they are never consulted here.</li>
 * <li><em>Required-config self-disable:</em> a provider declares the config keys it cannot run without (a
 *     credential, a bucket) through its {@code requiredConfig()}; a feature whose required keys are unset disables
 *     itself and logs one line saying which keys are missing and how to silence it.</li>
 * </ul>
 *
 * <p>The lookup is installed once at boot by the application shell ({@link #configure(UnaryOperator)}, handed the
 * Spring {@code Environment} so relaxed binding makes every key settable as an environment variable -
 * {@code JENREG_<FEATURE>=false} in a plain {@code docker run -e}). Outside a shell the default lookup
 * reads system properties and then the environment under the same relaxed spelling, so a bare {@code ServiceLoader}
 * consumer honours the identical keys.
 */
public final class Features {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(Features.class);

    /** Namespace shared with the Spring property schema; a feature toggle is {@code jenreg.<feature>}. */
    private static final String NAMESPACE = "jenreg.";

    private static final Set<String> ANNOUNCED = ConcurrentHashMap.newKeySet();

    private static volatile UnaryOperator<String> config = Features::defaults;

    private Features() {
    }

    /** Install the deployment's config lookup (the application shell hands in the Spring {@code Environment});
     *  until then, and after {@link #reset()}, system properties and environment variables answer directly. */
    public static void configure(UnaryOperator<String> lookup) {
        config = Objects.requireNonNull(lookup);
        ANNOUNCED.clear();
    }

    /** Restore the default system-property / environment lookup - the state before any {@link #configure}. */
    public static void reset() {
        configure(Features::defaults);
    }

    /** The deployment's installed config lookup, for the few core bounds that are read where no lookup is handed in -
     *  a per-process ceiling read on the publish thread ({@link ArchiveInflation#largestEntry()}), not a feature
     *  toggle. Read through it live rather than latched, so {@link #configure} and {@link #reset} are honoured. */
    public static UnaryOperator<String> lookup() {
        return config;
    }

    /**
     * Whether the named feature is enabled: unset means enabled, and only an explicit {@code false} disables - so an
     * image carrying every module runs everything until configured off.
     *
     * <p><b>The one rule of this API: a name is bare, and every key is prefixed here.</b> Callers pass
     * {@code scheduled-scan}, never {@code jenreg.scheduled-scan} - the namespace is applied by
     * {@link #settings()} (or {@link #namespaced(UnaryOperator)} over some other source), which is the only place it
     * is spelled. Which property source answers is {@link #configure}'s business: the shell installs the Spring
     * {@code Environment}, with the persistent settings layered into it, and a consumer module neither knows nor
     * asks.
     */
    public static boolean enabled(String feature) {
        return enabled(settings(), feature);
    }

    /**
     * Whether {@code feature} is enabled in a lookup the CALLER supplies, keyed by the bare feature name.
     *
     * <p>The same question as {@link #enabled(String)}, asked of a key space this class does not own: a maintenance
     * task's per-run settings context, a tenant's overrides, a test's map. The no-arg form above is exactly this one
     * against the installed lookup with {@code jenreg.} applied, which is the whole difference between
     * them and the reason both live here - the namespace is spelled once, in {@link #NAMESPACE}, rather than at
     * every caller that happens to read a toggle.
     */
    public static boolean enabled(UnaryOperator<String> config, String feature) {
        return enabled(config, feature, true);
    }

    /**
     * Whether {@code feature} is enabled, for a feature whose posture when the key is <em>unset</em> is not the
     * usual on.
     *
     * <p>Most toggles are on unless switched off, which is what the two-argument form above reads. A few are the
     * other way round - a tool that generates synthetic traffic, a console sign-in method meant for demos - and
     * their catalogue entries say so with a {@code "false"} default. Reading those through the two-argument form
     * makes the code answer ENABLED on a deployment that has never stored the key, while the catalogue, the
     * console and the operator all say it is off. Nothing reconciles the two, so the default has to be
     * stated where the gate is read.
     *
     * <p>Blank counts as unset: a key present with an empty value is a cleared setting, not a choice.
     */
    public static boolean enabled(UnaryOperator<String> config, String feature, boolean byDefault) {
        String value = config.apply(feature);
        return value == null || value.isBlank() ? byDefault : !"false".equalsIgnoreCase(value);
    }

    /** The implementation name a singleton SPI is configured to ({@code jenreg.<spi>=<feature>}), or
     *  empty when unset - the caller then applies its own most-universal default, or resolves the single enabled
     *  implementation through {@link Providers}. A <em>present</em> value is an explicit operator decision, so the
     *  resolution primitives fail rather than degrade when nothing answers to it (&sect;9). */
    public static Optional<String> selection(String spi) {
        return selection(settings(), spi);
    }

    /** The implementation name a singleton SPI is configured to, read from a lookup the CALLER supplies and keyed by
     *  the bare SPI name - see {@link #enabled(UnaryOperator, String)} for why both forms live here. */
    public static Optional<String> selection(UnaryOperator<String> config, String spi) {
        String value = config.apply(spi);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }

    /** Whether the named feature is {@link #enabled} <em>and</em> all its required config keys are set. A feature
     *  that is enabled but missing a required key (a credential, a bucket) disables itself and logs one line -
     *  naming the missing keys and the {@code jenreg.<feature>=false} switch that silences it. */
    public static boolean active(String feature, Collection<String> requiredConfig) {
        return active(settings(), feature, requiredConfig);
    }

    /** Whether {@code feature} is enabled and fully configured in a lookup the CALLER supplies, keyed by the bare
     *  feature name and the bare required-config keys - see {@link #enabled(UnaryOperator, String)}. */
    public static boolean active(UnaryOperator<String> config, String feature, Collection<String> requiredConfig) {
        if (!enabled(config, feature)) {
            return false;
        }
        List<String> missing = missing(requiredConfig, config);
        if (missing.isEmpty()) {
            return true;
        }
        if (ANNOUNCED.add(feature)) {
            LOGGER.info(feature + " disabled - missing " + String.join(", ", missing)
                    + "; set " + NAMESPACE + feature + "=false to disable it and silence this.");
        }
        return false;
    }

    /**
     * A bare-name view of a namespaced property source - {@code Features.namespaced(environment::getProperty)}.
     *
     * <p><b>This is the one place {@code jenreg.} is spelled.</b> Every seam that takes a config lookup
     * in this product reads it with BARE names ({@code socket-token}, {@code scheduled-scan}, {@code read-only}) -
     * some hundred and thirty call sites do - while a deployment configures those under the shared namespace. The
     * adapter between the two used to be a lambda written out at each entry point, thirty-five times, which is a
     * spelling of the product's own namespace that a typo makes silently unfindable. It is written once here.
     *
     */
    public static UnaryOperator<String> namespaced(UnaryOperator<String> properties) {
        Objects.requireNonNull(properties, "properties");
        return name -> properties.apply(key(name));
    }

    /**
     * The deployment's settings, keyed by bare name - the view every seam that takes a config lookup expects.
     *
     * <p>What sits behind it is {@link #configure}'s business and no caller's: the application shell hands in the
     * Spring {@code Environment} at boot, a test hands in a map, and outside a shell it is system properties and the
     * environment. A caller that wants to ask the deployment something asks for this, rather than reaching for the
     * {@code Environment} and re-deriving the namespace on the way - which is how the same prefix came to be spelled
     * in thirty-five places.
     *
     * <p>Read live, like {@link #lookup()}: a later {@code configure}/{@code reset} is honoured by a view handed out
     * before it.
     */
    public static UnaryOperator<String> settings() {
        return name -> lookup().apply(key(name));
    }

    /** The configuration key a bare feature or setting name resolves to - the one definition of the namespace. */
    public static String key(String name) {
        return NAMESPACE + name;
    }

    /** The keys of {@code requiredConfig} that are unset or blank in {@code lookup} - the shared check behind
     *  {@link #active} and an exclusive resolver's fail-loud path. */
    public static List<String> missing(Collection<String> requiredConfig, UnaryOperator<String> lookup) {
        List<String> missing = new ArrayList<>();
        for (String key : requiredConfig) {
            String value = lookup.apply(key);
            if (value == null || value.isBlank()) {
                missing.add(key);
            }
        }
        return missing;
    }

    private static String defaults(String key) {
        String property = System.getProperty(key);
        if (property != null) {
            return property;
        }
        return System.getenv(key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'));
    }
}
