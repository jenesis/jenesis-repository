package build.jenesis.repository.settings;

import module java.base;

/**
 * The classifier of a settings key's {@link Setting.Scope}, shared by every administration surface so the
 * effective-value chain and the write guards agree on which keys each level's document may carry
 * ({@link #settableAt}). A plugin declares its keys' scope on its {@link Setting}, read through
 * {@link SettingsContributor#scopes()}; the core dials and the map-shaped entries ({@code repositories.*},
 * {@code format-upstream.*}) are classified here: the gate's verdict knobs, the deny list and the per-format upstreams
 * are tenant-overridable, everything else global.
 */
public final class SettingsScopes {

    /** The core keys a tenant may override: the gate's verdict knobs and deny list, which it reads per publish and
     *  proxy fetch for the tenant served. The rest of the core catalogue is read by consumers holding no tenant;
     *  {@code proxy-allow-internal} in particular stays global so no tenant can put the deployment's upstream credential
     *  on the wire in cleartext. */
    private static final Set<String> TENANT_CORE = Set.of(
            "vulnerability-threshold", "vulnerability-action",
            "malware-action",
            "deny-list", "deny-list-action");

    /** The prefix of the per-format proxy upstream keys, composed only through {@link #upstreamKey}. */
    public static final String UPSTREAM_PREFIX = "format-upstream.";

    /** The stored settings key carrying a format's proxy upstream. */
    public static String upstreamKey(String format) {
        return UPSTREAM_PREFIX + format;
    }

    /** The prefix of the runtime repository-definition keys, composed through {@link #repositoryKey}. */
    public static final String REPOSITORY_PREFIX = "repositories.";

    /** The stored settings key carrying a repository's runtime definition. */
    public static String repositoryKey(String name) {
        return REPOSITORY_PREFIX + name;
    }

    /** The map-shaped key prefixes a tenant may override: the per-format proxy upstreams. Repository definitions are
     *  the deployment's; a tenant routes a repository through its own {@code routing} setting. */
    private static final List<String> TENANT_PREFIXES = List.of(UPSTREAM_PREFIX);

    /** The contributor scope map, resolved once: discovery instantiates every contributor, the effective-value chain
     *  asks per publish and proxy fetch, and the installed contributors are fixed for the JVM's life. Values are not
     *  cached here. */
    private static volatile Map<String, Setting.Scope> contributorScopes;

    /** Every catalogued setting by key, resolved once like the scope map. */
    private static volatile Map<String, Setting> declarations;

    private SettingsScopes() {
    }

    private static Map<String, Setting.Scope> contributorScopes() {
        Map<String, Setting.Scope> resolved = contributorScopes;
        if (resolved == null) {
            // A benign race: two threads resolve equal maps.
            resolved = SettingsContributor.scopes();
            contributorScopes = resolved;
        }
        return resolved;
    }

    private static Map<String, Setting> declarations() {
        Map<String, Setting> resolved = declarations;
        if (resolved == null) {
            Map<String, Setting> byKey = new HashMap<>();
            for (Setting setting : SettingsContributor.all()) {
                byKey.put(setting.key(), setting);
            }
            resolved = Map.copyOf(byKey);
            declarations = resolved;
        }
        return resolved;
    }

    /**
     * The modules owning a setting declared at {@code level}, whose documents are read by name
     * ({@link StoredSettings#read(build.jenesis.repository.store.ArtifactStore, Setting.Scope)}) rather than by
     * listing their space.
     */
    public static Set<String> modulesDeclaring(Setting.Scope level) {
        Set<String> modules = new TreeSet<>();
        for (Setting setting : declarations().values()) {
            if (setting.scope() == level) {
                modules.add(SettingsDocuments.moduleOf(setting.key()));
            }
        }
        return modules;
    }

    /** The catalogued setting a key names, or empty for a key no installed contributor declares (a map entry, or a
     *  key of a module this deployment does not carry). */
    public static Optional<Setting> declared(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(declarations().get(key));
    }

    /**
     * Whether a value for {@code key} may be stored at {@code level}: the guard every write consults, applying
     * {@link Setting#settable} to the key's scope here.
     */
    public static boolean settableAt(String key, Setting.Scope level) {
        return settableAt(key, level, contributorScopes());
    }

    private static boolean settableAt(String key, Setting.Scope level, Map<String, Setting.Scope> contributorScopes) {
        Setting.Scope scope = scopeOf(key, contributorScopes);
        boolean local = declared(key).map(Setting::localOnly).orElse(false);
        return Setting.settable(scope, local, level);
    }

    /**
     * The values of {@code documents} (module to stored values) that {@code level} may hold: what an export carries, so
     * an inert value left in a document is not exported into a bundle whose import would refuse it.
     */
    public static SortedMap<String, SortedMap<String, String>> settableOnly(
            Map<String, ? extends Map<String, String>> documents, Setting.Scope level) {
        SortedMap<String, SortedMap<String, String>> kept = new TreeMap<>();
        documents.forEach((module, values) -> {
            SortedMap<String, String> settable = new TreeMap<>();
            values.forEach((key, value) -> {
                if (settableAt(key, level)) {
                    settable.put(key, value);
                }
            });
            if (!settable.isEmpty()) {
                kept.put(module, settable);
            }
        });
        return kept;
    }

    /** Whether only the deployment's operator may set {@code key}, at whatever level - {@link Setting#operatorOnly}. */
    public static boolean operatorOnly(String key) {
        return declared(key).map(Setting::operatorOnly).orElse(false);
    }

    /** The scope of a settings key: a plugin's declared scope where a {@link SettingsContributor} owns the key,
     *  otherwise the core classification (a tenant-overridable core dial or map entry, else global). */
    public static Setting.Scope scopeOf(String key) {
        return scopeOf(key, contributorScopes());
    }

    /** The scope of a key against an already-resolved contributor scope map. */
    public static Setting.Scope scopeOf(String key, Map<String, Setting.Scope> contributorScopes) {
        if (key == null) {
            return Setting.Scope.GLOBAL;
        }
        if (TENANT_CORE.contains(key)) {
            return Setting.Scope.TENANT;
        }
        for (String prefix : TENANT_PREFIXES) {
            if (key.startsWith(prefix)) {
                return Setting.Scope.TENANT;
            }
        }
        return contributorScopes.getOrDefault(key, Setting.Scope.GLOBAL);
    }

    /** Whether a tenant may override this key for its own artifact space - the guard a tenant-document write and the
     *  tenant effective-value lookup both consult. */
    public static boolean tenantOverridable(String key) {
        return settableAt(key, Setting.Scope.TENANT);
    }

    /** Whether a tenant may override this key, against a pre-resolved contributor scope map. */
    public static boolean tenantOverridable(String key, Map<String, Setting.Scope> contributorScopes) {
        return settableAt(key, Setting.Scope.TENANT, contributorScopes);
    }
}
