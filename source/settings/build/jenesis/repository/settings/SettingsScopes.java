package build.jenesis.repository.settings;

import module java.base;

/**
 * The one classifier of a settings key's {@link Setting.Scope} - whether it is a deployment-wide global knob or one a
 * tenant may override for its own artifact space - shared by every administration surface (the repository server's
 * {@code Settings}, the {@code /api/settings} adapter, the console's {@code SettingsAdmin}) so the effective-value
 * chain and the write guards agree on which keys a tenant document may carry. A tenant may override a <em>gate
 * policy</em>, a <em>deny list</em> or a <em>forward target</em> - the artifact-admission and routing knobs that
 * legitimately differ per tenant - while a deployment-wide dial (the default tenant, the maintenance lease, the
 * trusted-proxy list) stays {@link Setting.Scope#GLOBAL global-only} and is refused in a tenant document.
 *
 * <p>A plugin module declares its own settings' scope on its {@link Setting} (a gate dimension marks itself
 * {@link Setting.Scope#TENANT}), discovered through {@link SettingsContributor#scopes()}. The neutral core dials and
 * the map-shaped entries ({@code repositories.*}, {@code format-upstream.*}) - which no {@link SettingsContributor}
 * declares - are classified here: the compliance verdict knobs and deny list, and the two
 * forward-target maps are tenant-overridable; everything else defaults global. {@code java.base} only, like every SPI
 * contract, so any surface can consult it without a heavier dependency.
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

    /** The map-shaped key prefixes a tenant may override: the runtime repository definitions (forward targets) and
     *  the per-format proxy upstreams. A tenant routes its own repositories and pulls through its own upstreams. */
    private static final List<String> TENANT_PREFIXES = List.of(REPOSITORY_PREFIX, UPSTREAM_PREFIX);

    /** The contributor scope map, resolved once. {@link SettingsContributor#scopes()} runs a full {@code ServiceLoader}
     *  discovery - instantiating every contributor and calling its {@code settings()} - and the effective-value chain
     *  consults a key's scope on every publish and proxy fetch for a tenant that carries an override, so a per-key
     *  re-discovery is tens of discovery passes per upload. The installed contributors are fixed for the JVM's life
     *  (which modules are on the path never changes at runtime; only a setting's stored value does, and that is not
     *  cached here), so the structural key -> scope map is computed on first use and reused. */
    private static volatile Map<String, Setting.Scope> contributorScopes;

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
        return scopeOf(key) == Setting.Scope.TENANT;
    }

    /** Whether a tenant may override this key, against a pre-resolved contributor scope map. */
    public static boolean tenantOverridable(String key, Map<String, Setting.Scope> contributorScopes) {
        return scopeOf(key, contributorScopes) == Setting.Scope.TENANT;
    }
}
