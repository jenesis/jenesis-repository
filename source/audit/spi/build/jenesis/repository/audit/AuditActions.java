package build.jenesis.repository.audit;

/**
 * The {@code action} names of the privileged mutations more than one surface can perform. A console mutation and its
 * {@code /api} twin must land in the trail as the same event, because the trail is queried by action: "who released a
 * held artifact this quarter" is one query, and an answer silently omitting the console's releases is worse than none.
 *
 * <p>An action only one surface performs stays where it is emitted, and moves here the day a second surface gains it.
 *
 * <p>These are wire values written into a durable, queryable record, so renaming one is a data migration. A test
 * asserting on the trail spells the literal out, so a rename fails it rather than travelling with it.
 */
public final class AuditActions {

    private AuditActions() {
    }

    /** Identity, and the point of it: a {@code static final String} initialised from a literal is a compile-time
     *  constant that javac inlines into every reader, so referencing a constant and typing its literal would compile to
     *  identical class files. Routing each value through a method makes a consumer emit a {@code getstatic}, keeping
     *  the literal in this class alone, so a class-file rule can fail a module that spells one out for itself. */
    private static String action(String name) {
        return name;
    }

    /** A compliance hold discarded without release. */
    public static final String QUARANTINE_DISCARD = action("quarantine.discard");

    /** A version held for review by hand. */
    public static final String QUARANTINE_HOLD = action("quarantine.hold");

    /** A compliance hold released into the layout. */
    public static final String QUARANTINE_RELEASE = action("quarantine.release");

    /** A finding about a stored artifact reported from outside, by a scanner the report names. */
    public static final String FINDINGS_REPORT = action("findings.report");

    /** A gate policy value set. */
    public static final String POLICY_SET = action("policy.set");

    /** A setting's value stored at any level - the deployment's, a tenant's, a repository's or a project's; the target
     *  is the key, prefixed by its level. */
    public static final String SETTING_SET = action("setting.set");

    /** A setting's stored value cleared, so it inherits again; targeted as {@link #SETTING_SET} is. */
    public static final String SETTING_CLEAR = action("setting.clear");

    /** A settings bundle restored - the deployment's documents, or one tenant's. */
    public static final String SETTINGS_IMPORT = action("settings.import");

    /** The node-local read caches over the store dropped - one node, never the fleet. */
    public static final String CACHES_CLEAR = action("caches.clear");

    /** The writes one node held in memory asked to land now - one node, never the fleet. */
    public static final String CACHES_FLUSH = action("caches.flush");

    /** A walk of the store requested by an operator - a standing request the next scheduler tick runs. */
    public static final String WALKS_RUN = action("walks.run");

    /** A repository created before anything was published into it. */
    public static final String REPOSITORY_CREATE = action("repository.create");

    /** A repository given a type that holds every format its old one did - {@code maven} made {@code java}, say. */
    public static final String REPOSITORY_RETYPE = action("repository.retype");

    /** A repository's description changed. */
    public static final String REPOSITORY_DESCRIBE = action("repository.describe");

    /** A repository deleted with everything it held - begun, since the objects go off the request path. */
    public static final String REPOSITORY_DELETE = action("repository.delete");

    /** A repository definition written. */
    public static final String REPOSITORY_SET = action("repository.set");

    /** A repository definition removed. */
    public static final String REPOSITORY_REMOVE = action("repository.remove");

    /** A role's grants written. */
    public static final String ROLE_SET = action("role.set");

    /** A role removed. */
    public static final String ROLE_REMOVE = action("role.remove");

    // A trust change is recorded under the names TrustsController owns (SET and REMOVE), which the console references.

    /** A version marked deprecated - by an operator, or by the ecosystem client's own command. */
    public static final String LIFECYCLE_DEPRECATED = action("lifecycle.deprecated");

    /** A version marked yanked - by an operator, or by the ecosystem client's own command. */
    public static final String LIFECYCLE_YANKED = action("lifecycle.yanked");

    /** A version's lifecycle mark cleared. */
    public static final String LIFECYCLE_CLEAR = action("lifecycle.clear");

    /** A version removed by the ecosystem client's own delete - a registry's manifest or tag {@code DELETE}. */
    public static final String ARTIFACT_DELETE = action("artifact.delete");

    /** A format's proxy upstream set. */
    public static final String UPSTREAM_SET = action("upstream.set");

    /** A format's proxy upstream removed. */
    public static final String UPSTREAM_REMOVE = action("upstream.remove");

    /** A per-host upstream credential set. */
    public static final String UPSTREAM_AUTH_SET = action("upstream.auth.set");

    /** A per-host upstream credential removed. */
    public static final String UPSTREAM_AUTH_REMOVE = action("upstream.auth.remove");

    /** A cleanup sweep started over a repository. */
    public static final String REPOSITORY_CLEANUP = action("repository.cleanup");

    /** An ecosystem's records dropped from a repository. */
    public static final String REPOSITORY_FORGET_ECOSYSTEM = action("repository.forget-ecosystem");

    /** A version pinned against retention. */
    public static final String REPOSITORY_PIN = action("repository.pin");

    /** A version's pin lifted. */
    public static final String REPOSITORY_UNPIN = action("repository.unpin");

    /** An import started into a repository. */
    public static final String REPOSITORY_IMPORT = action("repository.import");
}
