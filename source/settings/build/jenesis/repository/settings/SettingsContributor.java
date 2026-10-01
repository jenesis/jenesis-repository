package build.jenesis.repository.settings;

import module java.base;

/**
 * Contributes a module's runtime-editable settings to the deployment's catalogue, discovered with
 * {@link ServiceLoader}, so the settings screen, {@code /api/settings} and the CLI list exactly the settings of the
 * installed modules. The core's own catalogue is {@link CoreSettingsContributor}; {@link #all()} is the whole of it.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> {@link #settings()} and {@link #neutral()} are pure declarations called from any thread,
 *     concurrently and per administration read. A contributor holds no mutable state and returns an immutable
 *     list.</li>
 * <li><b>Idempotency / replay.</b> {@link #settings()} is a constant function of what is installed: a dial's value
 *     lives in the store, and a contributor never reads it or varies its catalogue by it.</li>
 * <li><b>Absence sentinel.</b> A module that contributes nothing returns an empty list; {@code null} is never legal,
 *     as the list or an element. An absent module's dials vanish from every surface.</li>
 * <li><b>Key ownership.</b> A key is lowercase alphanumeric segments joined by {@code -} or {@code .}, and has exactly
 *     one owner: every static below refuses a key two contributors declare, naming both, since the key decides the
 *     document, the scope and the module toggle. A dotted key's leading segment is a prefix the declaring module also
 *     owns ({@code gc.grace} beside {@code gc}).</li>
 * <li><b>Enablement gate.</b> A module contributes at most one {@link Setting#enablement} setting, the flag the
 *     modules console pairs with its toggle; {@link ModuleSettings#of} would drop a second.</li>
 * <li><b>{@link #neutral()} is a marker, not a value provider.</b> Only {@link CoreSettingsContributor} answers
 *     {@code true}. Its keys are in {@link #all()} and the {@link SettingsDocuments#NEUTRAL neutral} document, and
 *     absent from {@link #attribution()}, {@link #modules()} and {@link #scopes()}, keeping their
 *     {@link SettingsScopes} classification.</li>
 * <li><b>Tenant scoping.</b> A contributor declares each setting's {@link Setting.Scope};
 *     {@link SettingsScopes#scopeOf}, which every write guard consults, must answer that scope, so a plugin may not
 *     declare a key the core classification claims.</li>
 * <li><b>Value validity.</b> A declared {@code defaultValue} is blank (unset) or a value {@link Setting#parses}
 *     accepts, and a {@link Setting.Kind#CHOICE} offers exactly the values its code honours, in both directions.</li>
 * <li><b>Error visibility.</b> A duplicate key, or a contributor that throws from {@link #settings()}, surfaces as an
 *     {@link IllegalStateException} from the statics below and takes the administration surface with it (see
 *     {@link #all()}).</li>
 * <li><b>Lifecycle / ownership.</b> The statics instantiate contributors per call and cache none; a contributor is
 *     cheap to build, owns no thread or client and opens nothing.</li>
 * <li><b>Ordering / determinism.</b> {@link #all()} sorts by group then key, {@link #modules()} by module name, and
 *     the maps are keyed. Within one module, {@link #settings()} order is the render order inside a group.</li>
 * <li><b>Value refusal.</b> {@link #refusal(Setting, String, UnaryOperator)} names a value its kind accepts but its
 *     reader would refuse (a routing that does not parse, a zero retention age), so every write path refuses it before
 *     storing anything. It is pure, and a blank value, which clears the setting, is never passed to it.</li>
 * </ol>
 */
public interface SettingsContributor {

    /** The settings this module contributes, in the order they should render within their groups. */
    List<Setting> settings();

    /**
     * Why {@code value} - non-blank, and one {@code setting}'s kind already accepts - cannot be stored for one of this
     * contributor's settings, or empty when it can. {@code deployment} answers another key's deployment-wide
     * effective value ({@code null} when unset), for a refusal that depends on a deployment dial. Empty by default:
     * most settings are whatever their kind accepts.
     */
    default Optional<String> refusal(Setting setting, String value, UnaryOperator<String> deployment) {
        return Optional.empty();
    }

    /**
     * Why {@code value} cannot be stored for {@code key} at {@code level}, asked by every write path before it writes:
     * an undeclared key, a level the key may not be set at ({@link SettingsScopes#settableAt}), a value its kind does
     * not parse, or one its contributor refuses. A blank value clears the key and is judged only on key and level.
     * Empty when the write may go ahead.
     */
    static Optional<String> refusal(String key, String value, Setting.Scope level,
                                    UnaryOperator<String> deployment) {
        for (SettingsContributor contributor : declared()) {
            for (Setting setting : contributor.settings()) {
                if (setting.key().equals(key)) {
                    if (!SettingsScopes.settableAt(key, level)) {
                        return Optional.of("Setting '" + key + "' cannot be set " + where(level) + ".");
                    }
                    if (value == null || value.isBlank()) {
                        return Optional.empty();
                    }
                    if (!setting.parses(value)) {
                        return Optional.of("'" + value + "' is not a valid value for setting '" + key + "'.");
                    }
                    return contributor.refusal(setting, value.trim(), deployment)
                            .map(reason -> "'" + value + "' is not a valid value for setting '" + key + "': " + reason);
                }
            }
        }
        return Optional.of("Unknown setting '" + key + "'.");
    }

    private static String where(Setting.Scope level) {
        return switch (level) {
            case GLOBAL -> "deployment-wide";
            case TENANT -> "for a tenant";
            case REPOSITORY -> "for a repository";
            case PROJECT -> "for a build-cache project";
        };
    }

    /**
     * The keys this module reads that the catalogue leaves out, as full property names ({@code jenrepo.<key>}): a
     * node's identity, a switch settled at startup, a credential that must never be stored, or, ending in {@code .*},
     * a prefix of operator-named keys. Named only so the boot check for unrecognised settings knows they are read.
     */
    default Set<String> startupKeys() {
        return Set.of();
    }

    /** Every installed contributor's {@link #startupKeys()}, unioned, through the unvalidated discovery, since a
     *  startup key is no catalogue entry and so no collision. */
    static Set<String> allStartupKeys() {
        Set<String> keys = new TreeSet<>();
        for (SettingsContributor contributor : declared()) {
            keys.addAll(contributor.startupKeys());
        }
        return Set.copyOf(keys);
    }

    /** Whether this is the core's own contributor: its dials are not a removable module, belong to the
     *  {@link SettingsDocuments#NEUTRAL neutral} document and are scoped by {@link SettingsScopes}, so
     *  {@link #attribution()}, {@link #modules()} and {@link #scopes()} pass it by. {@code false} for a plugin. */
    default boolean neutral() {
        return false;
    }

    /**
     * Every installed module's contributed settings, the core's included, ordered by group then key: the one catalogue
     * every administration surface lists.
     *
     * <p>This fan-out and {@link #scopes}, {@link #modules} and {@link #attribution} contain nothing: a contributor
     * whose {@code settings()} throws takes the settings surfaces down. A partial {@link #scopes} map would read an
     * absent key as the caller's default scope, handing one plugin's dial another's write guard, and a partial
     * {@link #attribution} would store a value in the deployment-wide document; a settings surface wrong about scope
     * while looking complete is worse than one that is down. An operator fixes it by removing the module.
     */
    static List<Setting> all() {
        return all(installed().stream().map(ServiceLoader.Provider::get).toList());
    }

    /** {@link #all()} over an explicit set of contributors, the seam a test substitutes through. */
    static List<Setting> all(Iterable<SettingsContributor> contributors) {
        List<Setting> settings = new ArrayList<>();
        for (SettingsContributor contributor : contributors) {
            settings.addAll(contributor.settings());
        }
        settings.sort(Comparator.comparing(Setting::group).thenComparing(Setting::key));
        return List.copyOf(settings);
    }

    /** Every contributor on the module path, unvalidated, for a contributor that must read its peers' keys (the module
     *  toggles) and would recurse through {@link #installed()}. */
    public static List<SettingsContributor> declared() {
        return ServiceLoader.load(SettingsContributor.class).stream().map(ServiceLoader.Provider::get).toList();
    }

    /** Every installed contributor, with key ownership validated: a key two contributors declare throws, naming the key
     *  and both classes, since every view below is keyed by the setting key and no merge rule could be right. */
    private static List<ServiceLoader.Provider<SettingsContributor>> installed() {
        List<ServiceLoader.Provider<SettingsContributor>> providers =
                ServiceLoader.load(SettingsContributor.class).stream().toList();
        Map<String, String> owners = new HashMap<>();
        List<String> collisions = new ArrayList<>();
        for (ServiceLoader.Provider<SettingsContributor> provider : providers) {
            String owner = provider.type().getName();
            for (Setting setting : provider.get().settings()) {
                String previous = owners.putIfAbsent(setting.key(), owner);
                if (previous != null) {
                    collisions.add("'" + setting.key() + "' is declared by " + previous + " and by " + owner);
                }
            }
        }
        if (!collisions.isEmpty()) {
            collisions.sort(Comparator.naturalOrder());
            throw new IllegalStateException("Duplicate settings key(s) across installed contributors - a settings key "
                    + "has exactly one owning module, because the key decides which module's document holds its value, "
                    + "which scope guards its writes and which module's toggle it pairs with; two owners would silently "
                    + "hand one plugin's dial to another: " + String.join("; ", collisions));
        }
        return providers;
    }

    /** Each plugin setting's key mapped to its declared {@link Setting.Scope}, so the effective-value chain and the write
     *  guards know which keys a tenant may override. An undeclared key is absent and {@link SettingsScopes} falls back
     *  to the core classification. */
    static Map<String, Setting.Scope> scopes() {
        return scopes(installed().stream().map(ServiceLoader.Provider::get).toList());
    }

    /** {@link #scopes()} over an explicit set of contributors. */
    static Map<String, Setting.Scope> scopes(Iterable<SettingsContributor> contributors) {
        Map<String, Setting.Scope> scopes = new HashMap<>();
        for (SettingsContributor contributor : contributors) {
            if (contributor.neutral()) {
                continue;
            }
            for (Setting setting : contributor.settings()) {
                scopes.put(setting.key(), setting.scope());
            }
        }
        return scopes;
    }

    /** Each installed plugin module's settings and enablement gate, by module name: the modules console's model. A
     *  module without a {@link Setting#enablement gate} setting is always on when installed. */
    static List<ModuleSettings> modules() {
        Map<String, List<Setting>> byModule = new LinkedHashMap<>();
        for (ServiceLoader.Provider<SettingsContributor> provider : installed()) {
            SettingsContributor contributor = provider.get();
            if (contributor.neutral()) {
                continue;
            }
            Module module = provider.type().getModule();
            if (!module.isNamed() || contributor.settings().isEmpty()) {
                // A contributor that only names startup keys has nothing for a modules-console row to show.
                continue;
            }
            byModule.computeIfAbsent(module.getName(), _ -> new ArrayList<>()).addAll(contributor.settings());
        }
        List<ModuleSettings> modules = new ArrayList<>();
        byModule.forEach((name, settings) -> modules.add(ModuleSettings.of(name, settings)));
        modules.sort(Comparator.comparing(ModuleSettings::module));
        return List.copyOf(modules);
    }

    /** Each plugin setting's key mapped to its declaring module's name, which keys its stored document
     *  ({@link SettingsDocuments}). A core dial or map entry is absent, and the caller falls back to the
     *  {@link SettingsDocuments#NEUTRAL neutral} document. */
    static Map<String, String> attribution() {
        Map<String, String> attribution = new HashMap<>();
        for (ServiceLoader.Provider<SettingsContributor> provider : installed()) {
            SettingsContributor contributor = provider.get();
            if (contributor.neutral()) {
                continue;
            }
            Module module = provider.type().getModule();
            if (!module.isNamed()) {
                continue;
            }
            for (Setting setting : contributor.settings()) {
                attribution.put(setting.key(), module.getName());
            }
        }
        return attribution;
    }
}
