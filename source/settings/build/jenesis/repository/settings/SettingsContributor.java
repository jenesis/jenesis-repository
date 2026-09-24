package build.jenesis.repository.settings;

import module java.base;

/**
 * Contributes a plugin module's runtime-editable settings to the deployment's catalogue, discovered with
 * {@link ServiceLoader} - so the settings screen, the {@code /api/settings} endpoint and the CLI list exactly the
 * settings of the modules installed on this deployment, and neutral code enumerates no backend's keys. A feed or
 * feature module {@code provides} one of these next to its functional provider; the neutral core appends
 * {@link #all()} to its own catalogue.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> {@link #settings()} and {@link #neutral()} are pure declarations the server calls from
 *     any thread, including concurrently, and re-calls per administration read. A contributor holds no mutable state
 *     and returns an immutable list; it must never lazily initialise anything a second caller could observe
 *     half-built.</li>
 * <li><b>Idempotency / replay.</b> {@link #settings()} is a constant function of what is installed, not of when it
 *     is called or of what is stored: two calls in one JVM return equal descriptors. A dial's <em>value</em> lives
 *     in the store; a contributor never reads it and never varies its catalogue by it, so a settings screen renders
 *     the same rows before and after a save.</li>
 * <li><b>Absence sentinel.</b> A module that contributes nothing returns an empty list; {@code null} - as the list,
 *     as an element, or from {@link #settings()} itself - is never a legal return. A module absent from the
 *     deployment contributes no keys at all, which is the SPI's whole no-op-by-absence contract: its dials vanish
 *     from every surface with no change to neutral code.</li>
 * <li><b>Key ownership (&sect;2, &sect;12).</b> A key is <em>lowercase dotted</em> - lowercase alphanumeric segments
 *     joined by {@code -} or {@code .} - and has exactly <b>one</b> owner. Two contributors declaring one key is a
 *     packaging error, not a merge: the loser would lose its scope, its module attribution and its stored document,
 *     silently. Every static below therefore refuses it, naming the key and both declaring classes, rather than
 *     letting the last {@code put} win. A dotted key's leading segment is an ownership prefix the declaring module
 *     also owns ({@code redirect-dns.strict} beside {@code redirect-dns}), so one module's namespace can never grow
 *     over another's.</li>
 * <li><b>Enablement gate.</b> A module contributes <em>at most one</em> {@link Setting#enablement} setting. It is
 *     the one flag the modules console pairs with the module's enable/disable toggle; a second would be silently
 *     dropped by {@link ModuleSettings#of}, leaving an operator a switch that reaches only one of two gates.</li>
 * <li><b>{@link #neutral()} is a marker, not a value provider.</b> It says "these dials are the non-removable core
 *     catalogue", nothing more. Exactly one contributor - {@link CoreSettingsContributor} - answers {@code true}; a
 *     plugin never does. Its keys stay in {@link #all()} and stay in the {@link SettingsDocuments#NEUTRAL neutral}
 *     document, and they are deliberately absent from {@link #attribution()}, {@link #modules()} and
 *     {@link #scopes()} - so a core dial adds no modules-console row and keeps its {@link SettingsScopes}
 *     classification.</li>
 * <li><b>Tenant scoping (&sect;6).</b> A contributor declares each setting's {@link Setting.Scope}; the classifier
 *     every write guard consults is {@link SettingsScopes#scopeOf}, which layers the core classification over the
 *     declaration. A plugin key's declared scope is therefore what {@code scopeOf} must answer - a plugin may not
 *     declare a key the core classification already claims.</li>
 * <li><b>Value validity.</b> A declared {@code defaultValue} is either blank ("unset", the cleared state every write
 *     path short-circuits) or a value {@link Setting#parses} accepts for its kind, and a {@link Setting.Kind#CHOICE}
 *     offers exactly the values its code honours, in both directions ((c)): a value the code honours but
 *     the list omits is reachable only by environment variable, and one the list offers that the code refuses fails
 *     the operator's write on a value the product itself listed.</li>
 * <li><b>Error visibility (&sect;9).</b> None of the failures above degrade. A duplicate key, like a contributor
 *     that throws from {@link #settings()}, surfaces as an {@link IllegalStateException} out of the statics below and
 *     takes the administration surface with it, because a half-built catalogue would render a dial that edits
 *     nothing.</li>
 * <li><b>Lifecycle / ownership.</b> {@code ServiceLoader} instances are created by the statics below, are not cached
 *     across calls, own no threads or clients and are never closed. A contributor must therefore be cheap to build
 *     and must not open anything.</li>
 * <li><b>Ordering / determinism.</b> Results never depend on module-path order: {@link #all()} sorts by group then
 *     key, {@link #modules()} by module name, and the two maps are keyed. Within one module, {@link #settings()}
 *     order is the render order inside a group.</li>
 * </ol>
 */
public interface SettingsContributor {

    /** The settings this module contributes, in the order they should render within their groups. */
    List<Setting> settings();

    /**
     * The keys this module reads that the catalogue deliberately leaves out, each as its full property name
     * ({@code jenreg.<key>}): a node's own identity, a switch the context settles as it starts, a credential that must
     * never be stored, or - ending in {@code .*} - a prefix under which the operator names the keys, such as one
     * entry per format. No surface lists them as settings; they are named here only so the boot check for
     * unrecognised settings knows that something reads them. Empty by default.
     */
    default Set<String> startupKeys() {
        return Set.of();
    }

    /** Every installed contributor's {@link #startupKeys()}, unioned. Read through the unvalidated discovery, since a
     *  key a module reads at startup is no one's catalogue entry and so no collision to refuse. */
    static Set<String> allStartupKeys() {
        Set<String> keys = new TreeSet<>();
        for (SettingsContributor contributor : declared()) {
            keys.addAll(contributor.startupKeys());
        }
        return Set.copyOf(keys);
    }

    /** Whether this is the neutral core's own contributor rather than a plugin module's. The core dogfoods this SPI so
     *  its catalogue is authored once ({@link CoreSettingsContributor}), but its dials are not a removable module: they
     *  belong to the {@link SettingsDocuments#NEUTRAL neutral} document and their scope is classified by
     *  {@link SettingsScopes}. So {@link #all()} lists a neutral contributor's settings while the plugin-oriented views
     *  - {@link #attribution()}, {@link #modules()} and {@link #scopes()} - pass it by, keeping a core dial's document,
     *  module row and scope exactly as they were when the catalogue was inlined. A plugin contributor leaves this
     *  {@code false}.
     *
     *  <p>It is a <em>marker</em>, never a value provider: it decides which document and which scope classification a
     *  key belongs to, and nothing about the key's value. */
    default boolean neutral() {
        return false;
    }

    /**
     * Every installed module's contributed settings, ordered by group then key for a deterministic listing. Includes
     * the neutral core's own dials ({@link #neutral()}), so this is the single catalogue every administration
     * surface lists rather than each inlining the core by hand.
     *
     * <h2>Why a throwing contributor takes the whole surface, deliberately</h2>
     *
     * <p>This fan-out and its three siblings - {@link #scopes}, {@link #modules}, {@link #attribution} - contain
     * nothing. A contributor whose {@code settings()} throws takes the console settings page, the modules console,
     * {@code SettingsScopes}' write guards, {@code SettingsSecrets}, {@code SettingsDocuments} and the
     * effective-value chain with it, and with them an operator's ability to change <em>any</em> dial - including
     * the one that would disable the offending module. That is a hard failure and it is the intended one.
     *
     * <p>The alternative is worse in a way that is easy to miss, because it looks like graceful degradation.
     * {@link #scopes} decides <b>which scope may write a key</b>: a partial map is one where a key that should be
     * {@code Scope.GLOBAL} is simply absent, and an absent key does not read as "unknown", it reads as whatever the
     * caller's default is. So containing per contributor hands one plugin's dial another module's write guard -
     * a mis-scoping, not a cosmetic gap - and {@link #attribution} has the same shape one surface over: a missing
     * entry does not say "I could not tell you which document stores this", it says "the deployment-wide one".
     * A settings surface that is wrong about scope while looking complete is worse than one that is plainly down.
     *
     * <p>So the position is: these four are all-or-nothing, and a broken contributor is a broken deployment that
     * an operator fixes by removing the module, not a row the console renders in red. It is stated here because the
     * duplicate-key refusal a few lines below is argued at length and this was not, which made the silence read as
     * an oversight rather than as the decision it is. {@code SettingsFanOutFailsWholeTest} is the leg.
     */
    static List<Setting> all() {
        return all(installed().stream().map(ServiceLoader.Provider::get).toList());
    }

    /** {@link #all()} over an explicit set of contributors - the substitution seam a hostile-contributor leg needs,
     *  since the discovery above admits no stand-in and a {@code provides} clause would make one visible to every
     *  other suite in its module. */
    static List<Setting> all(Iterable<SettingsContributor> contributors) {
        List<Setting> settings = new ArrayList<>();
        for (SettingsContributor contributor : contributors) {
            settings.addAll(contributor.settings());
        }
        settings.sort(Comparator.comparing(Setting::group).thenComparing(Setting::key));
        return List.copyOf(settings);
    }

    /**
     * Every installed contributor, with the deployment's key ownership validated once: a key two contributors declare
     * is a packaging error and <b>throws</b> here rather than letting the last writer win.
     *
     * <p>Every view below is a map or a grouping keyed by the setting key - the scope map the write guards consult,
     * the attribution map that decides which module document a value is stored in, the modules-console rows - so a
     * duplicate silently resolved by iteration order does not merely list a dial twice: it moves an installed
     * plugin's setting into another module's document, hands it another module's scope, and pairs it with another
     * module's enable/disable toggle. There is no merge rule that could be right, so there is no merge: the fan-out
     * fails fast, naming the key and both declaring classes, and an operator sees which two modules collide instead
     * of a dial that quietly edits nothing.
     */
    /** Every contributor on the module path, discovered from this SPI's home, unvalidated: the validating face,
     *  {@link #installed()}, reads every contributor's settings to find a collision, so a contributor that must learn
     *  its peers' keys to avoid one (the module toggles) cannot go through it without recursing into itself. */
    public static List<SettingsContributor> declared() {
        return ServiceLoader.load(SettingsContributor.class).stream().map(ServiceLoader.Provider::get).toList();
    }

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

    /** Each contributed setting's key mapped to the {@link Setting.Scope} its declaring module gives it, discovered
     *  the same way as {@link #all()} - so the effective-value chain and the write guards know which plugin keys a
     *  tenant may override ({@link Setting.Scope#TENANT}) and which stay deployment-wide ({@link Setting.Scope#GLOBAL},
     *  the default). A key no installed contributor declares is simply absent; {@link SettingsScopes} then falls back
     *  to the core classification. The {@code ServiceLoader} call lives here, in the SPI home that {@code uses} the
     *  service. */
    static Map<String, Setting.Scope> scopes() {
        return scopes(installed().stream().map(ServiceLoader.Provider::get).toList());
    }

    /** {@link #scopes()} over an explicit set of contributors - see {@link #all(Iterable)}. */
    static Map<String, Setting.Scope> scopes(Iterable<SettingsContributor> contributors) {
        Map<String, Setting.Scope> scopes = new HashMap<>();
        for (SettingsContributor contributor : contributors) {
            if (contributor.neutral()) {
                // A neutral core dial's scope is classified by SettingsScopes, not its Setting.scope, so it stays out
                // of the contributor scope map that layers over that classification.
                continue;
            }
            for (Setting setting : contributor.settings()) {
                scopes.put(setting.key(), setting.scope());
            }
        }
        return scopes;
    }

    /** Each installed module's contributed settings and its enablement gate, discovered the same way as {@link #all()}
     *  and keyed by the contributor's JPMS module name - the modules console's model of what is installed and how to
     *  toggle it. A module that contributes no {@link Setting#enablement gate} setting is always on when installed; one
     *  that does pairs with an enable/disable toggle. Ordered by module name for a deterministic listing. */
    static List<ModuleSettings> modules() {
        Map<String, List<Setting>> byModule = new LinkedHashMap<>();
        for (ServiceLoader.Provider<SettingsContributor> provider : installed()) {
            SettingsContributor contributor = provider.get();
            if (contributor.neutral()) {
                // The neutral core is not a removable module, so it never becomes a modules-console row.
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

    /** Each contributed setting's key mapped to the JPMS module name of the contributor that declares it, discovered
     *  the same way as {@link #all()} - so a stored document can be keyed by its owning module ({@link
     *  SettingsDocuments}) and the console can attribute a setting to the module it came from. A key no installed
     *  <em>plugin</em> contributor declares (a neutral core dial, a map entry) is simply absent, and the caller falls
     *  back to the {@link SettingsDocuments#NEUTRAL neutral} document - so the core dogfooding this SPI does not move a
     *  core setting out of that document. The {@code ServiceLoader} call lives here, in the SPI home that {@code uses}
     *  the service. */
    static Map<String, String> attribution() {
        Map<String, String> attribution = new HashMap<>();
        for (ServiceLoader.Provider<SettingsContributor> provider : installed()) {
            SettingsContributor contributor = provider.get();
            if (contributor.neutral()) {
                // A neutral core key is unattributed, so it keeps the NEUTRAL document rather than the settings module's.
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
