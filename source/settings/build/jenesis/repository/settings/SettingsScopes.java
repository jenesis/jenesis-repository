package build.jenesis.repository.settings;

import module java.base;

/**
 * The one classifier of a settings key's {@link Setting.Scope} - whether it is a deployment-wide global knob, one a
 * tenant may override for its own artifact space, or one a repository or a build-cache project may set for itself -
 * shared by every administration surface (the repository server's {@code Settings}, the {@code /api/settings}
 * adapter, the console's {@code SettingsAdmin}) so the effective-value chain and the write guards agree on which keys
 * a deployment, tenant, repository or project document may carry ({@link #settableAt}). A tenant may override a
 * <em>gate policy</em>, a <em>deny list</em> or a <em>forward target</em> - the artifact-admission and routing knobs
 * that legitimately differ per tenant - while a deployment-wide dial (the default tenant, the maintenance lease, the
 * trusted-proxy list) stays {@link Setting.Scope#GLOBAL global-only} and is refused in a tenant document.
 *
 * <p>A plugin module declares its own settings' scope on its {@link Setting} (a gate dimension marks itself
 * {@link Setting.Scope#TENANT}), discovered through {@link SettingsContributor#scopes()}. The neutral core dials and
 * the map-shaped entries ({@code repositories.*}, {@code format-upstream.*}) - which no {@link SettingsContributor}
 * declares - are classified here: the compliance verdict knobs and deny list, and the per-format upstreams, are
 * tenant-overridable; the deployment's repository definitions and everything else are global. {@code java.base}
 * only, like every SPI contract, so any surface can consult it without a heavier dependency.
 */
public final class SettingsScopes {

    /** The neutral-core keys a tenant may override: the compliance gate's verdict knobs and deny list - the
     *  artifact-admission policy the gate reads per publish and proxy fetch, which legitimately differs per tenant. The
     *  rest of the core catalogue (the default tenant, default repository, maintenance lease, trusted proxies,
     *  private-import block, proxy immaturity hold, proxy internal-target dial) is deployment-wide, read by a consumer
     *  that holds no tenant - and {@code proxy-allow-internal} is deployment-wide for the same reason
     *  {@code forwarding-allow-internal} is: no per-tenant dial may put another tenant's proxy traffic, or the
     *  deployment's per-host upstream credential, on the wire in cleartext. */
    private static final Set<String> TENANT_CORE = Set.of(
            "vulnerability-threshold", "vulnerability-action",
            "malware-action",
            "deny-list", "deny-list-action");

    /**
     * The prefix of the per-format proxy upstream keys.
     *
     * <p>It is public because it was being spelled out by hand in five modules - the console's {@code SettingsAdmin},
     * the {@code /api/settings} adapter, this classifier, and the server's {@code LiveConfig} that reads the value
     * back - and a settings key is a contract between a writer and a reader, not a string each of them happens to
     * agree on. Two surfaces that build the same key from two literals do not share a convention, they share a
     * coincidence: a change to one is silent everywhere else, and the failure it produces is a setting written where
     * nothing reads it.
     */
    public static final String UPSTREAM_PREFIX = "format-upstream.";

    /** The stored settings key carrying a format's proxy upstream. */
    public static String upstreamKey(String format) {
        return UPSTREAM_PREFIX + format;
    }

    /** The prefix of the runtime repository-definition keys, public for the same reason {@link #UPSTREAM_PREFIX}
     *  is: it was spelled out in five modules - both administration surfaces, the server that reads the
     *  definition back, and the rescreen task that rewrites one. */
    public static final String REPOSITORY_PREFIX = "repositories.";

    /** The stored settings key carrying a repository's runtime definition. */
    public static String repositoryKey(String name) {
        return REPOSITORY_PREFIX + name;
    }

    /** The map-shaped key prefixes a tenant may override: the per-format proxy upstreams, so a tenant pulls through
     *  its own. The runtime repository definitions are the deployment's alone: a tenant routes one of its
     *  repositories through that repository's own {@code routing} setting, which its repository document holds. */
    private static final List<String> TENANT_PREFIXES = List.of(UPSTREAM_PREFIX);

    /** The contributor scope map, resolved once. {@link SettingsContributor#scopes()} runs a full {@code ServiceLoader}
     *  discovery - instantiating every contributor and calling its {@code settings()} - and the effective-value chain
     *  consults a key's scope on every publish and proxy fetch for a tenant that carries an override, so a per-key
     *  re-discovery is tens of discovery passes per upload. The installed contributors are fixed for the JVM's life
     *  (which modules are on the path never changes at runtime; only a setting's stored value does, and that is not
     *  cached here), so the structural key -> scope map is computed on first use and reused. */
    private static volatile Map<String, Setting.Scope> contributorScopes;

    /** Every catalogued setting by key, resolved once for the same reason the scope map is: which keys are
     *  {@link Setting#localOnly() local} or {@link Setting#operatorOnly() operator-only} is fixed for the JVM's life,
     *  and a write guard consults it per key. */
    private static volatile Map<String, Setting> declarations;

    private SettingsScopes() {
    }

    private static Map<String, Setting.Scope> contributorScopes() {
        Map<String, Setting.Scope> resolved = contributorScopes;
        if (resolved == null) {
            // A benign race: two threads may both resolve the same fixed map; last write wins and both are equal.
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
            // The same benign race as the scope map: equal maps, last write wins.
            resolved = Map.copyOf(byKey);
            declarations = resolved;
        }
        return resolved;
    }

    /**
     * The modules whose settings documents hold a setting of {@code level}: the owning module of every installed
     * setting declared at that scope. A repository's or a project's documents are these modules' and no others', so
     * they are read by name ({@link StoredSettings#read(build.jenesis.repository.store.ArtifactStore, Setting.Scope)})
     * rather than by listing the space they sit in.
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
     * Whether a value for {@code key} may be stored at {@code level} - the guard every write consults, at every level:
     * a key at its own scope and at every wider level as the default a narrower one inherits, except a
     * {@link Setting#localOnly() local} one, which only its own scope holds ({@link Setting#settableAt}). A key the
     * catalogue does not carry is classified here: a tenant-overridable core dial or map entry at the deployment and
     * tenant levels, anything else at the deployment's alone.
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
     * The values of {@code documents} (module name to its stored values) that {@code level} may hold, the rest dropped:
     * what an export of that level's documents carries, so a value left behind in a document by a move to another
     * level - inert where it lies - is not exported into a bundle whose import would refuse it.
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

    /** The scope of a key against an already-resolved contributor scope map, so a caller that reads many keys does
     *  not re-run {@code ServiceLoader} discovery per key. */
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
