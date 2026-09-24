package build.jenesis.repository.audit;

/**
 * The {@code action} names of the privileged mutations more than one surface can perform.
 *
 * <p><b>Why these are constants.</b> A console mutation and its {@code /api} twin must land in the trail as the
 * same event, because the trail is queried by action: an operator asking "who released a held artifact this
 * quarter" runs one query, and an answer that silently omits every release done through the console is worse than
 * no answer. Until this class existed the guarantee was two string literals in two modules that happened to
 * agree - fourteen of them - and the only thing keeping them equal was that nobody had edited one. Both javadocs
 * asserted the property ({@code TenantScope.audit} claimed "the same action the /api twin emits"), which is the
 * shape of a rule that is restated rather than shared, and restated rules drift.
 *
 * <p><b>What belongs here.</b> An action name emitted from more than one surface. An action only one surface can
 * perform stays where it is emitted - a constant referenced once is indirection, not sharing - and moves here the
 * day a second surface gains the capability.
 *
 * <p><b>What these names are.</b> Wire values: they are written into a durable, queryable record that outlives the
 * code, so renaming one is a data migration and not a refactor. A test that asserts on the trail should spell the
 * literal out rather than reference the constant, so that a rename fails the test instead of silently travelling
 * with it.
 */
public final class AuditActions {

    private AuditActions() {
    }

    /**
     * Identity, and the whole point of it.
     *
     * <p>A {@code static final String} initialised from a literal is a compile-time constant, so javac <em>inlines
     * its value</em> into every class that reads it. Referencing {@code AuditActions.QUARANTINE_RELEASE} and
     * typing {@code "quarantine.release"} then produce byte-for-byte identical class files, and no inspection can
     * tell the two apart - which would leave the sharing here a convention again, enforced by nothing, exactly the
     * state this class exists to end. Routing each value through a method makes it a run-time read: a consumer
     * emits a {@code getstatic}, the literal stays in this class alone, and
     * {@code AuditActionOwnershipRule} can fail a module that spells one out for itself.
     */
    private static String action(String name) {
        return name;
    }

    /** A compliance hold discarded without release. */
    public static final String QUARANTINE_DISCARD = action("quarantine.discard");

    /** A compliance hold released into the layout. */
    public static final String QUARANTINE_RELEASE = action("quarantine.release");

    /** A finding about a stored artifact reported from outside, by a scanner the report names. */
    public static final String FINDINGS_REPORT = action("findings.report");

    /** A gate policy value set. */
    public static final String POLICY_SET = action("policy.set");

    /** A repository storage quota set. */
    public static final String QUOTA_SET = action("quota.set");

    /** The node-local read caches over the store dropped - one node, never the fleet. */
    public static final String CACHES_CLEAR = action("caches.clear");

    /** A walk of the store requested by an operator - a standing request the next scheduler tick runs. */
    public static final String WALKS_RUN = action("walks.run");

    /** A repository created before anything was published into it. */
    public static final String REPOSITORY_CREATE = action("repository.create");

    /** A repository given a type that holds every format its old one did - {@code maven} made {@code java}, say. */
    public static final String REPOSITORY_RETYPE = action("repository.retype");

    /** A repository definition written. */
    public static final String REPOSITORY_SET = action("repository.set");

    /** A repository definition removed. */
    public static final String REPOSITORY_REMOVE = action("repository.remove");

    /** A role's grants written. */
    public static final String ROLE_SET = action("role.set");

    /** A role removed. */
    public static final String ROLE_REMOVE = action("role.remove");

    // A trust change is recorded by the core's own trusts surface, which owns those two names; the console
    // references build.jenesis.repository.server.TrustsController.SET / .REMOVE rather than restating them here.

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

    /** A repository's retention policy written. */
    public static final String REPOSITORY_RETENTION = action("repository.retention");

    /** A version pinned against retention. */
    public static final String REPOSITORY_PIN = action("repository.pin");

    /** A version's pin lifted. */
    public static final String REPOSITORY_UNPIN = action("repository.unpin");

    /** An import started into a repository. */
    public static final String REPOSITORY_IMPORT = action("repository.import");
}
