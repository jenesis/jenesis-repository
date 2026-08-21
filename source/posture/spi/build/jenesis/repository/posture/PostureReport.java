package build.jenesis.repository.posture;

import build.jenesis.repository.observation.Contributions;

import module java.base;

/**
 * The single collected view every consumer reads - the console's Security-posture panel, the admin API and the boot log
 * all render <em>this</em>, so an advisory is defined in exactly one place. {@link #from} evaluates a set of
 * {@link SafetyAdvisor}s against the effective {@link Configuration} and sorts the result critical-first (then by id, a
 * stable order); {@link #discover} does the same over the {@link ServiceLoader}-installed advisors. A module that raises
 * nothing (a disabled feature, a safe configuration) simply adds nothing - the report degrades gracefully to whatever is
 * actually unsafe, and an empty report is the healthy state.
 *
 * <p><strong>Silence is load-bearing here, so a failure is never silent.</strong> An empty report means "checked, and
 * nothing is unsafe" - which is why an advisor that throws must not simply vanish, and must certainly not take the
 * report down: every console view renders {@code postureCount} and {@code GET /api/posture} reads the same collection.
 * {@link #from} therefore collects through {@code Contributions}: an advisor that throws (or answers {@code null}) is
 * contained to its own rows and replaced by a {@link Severity#WARN} {@code jenreg.posture.unavailable.<advisor>}
 * advisory saying that whatever it checks is unreported, every other advisor is evaluated, and the failure is logged
 * once with the advisor's class. The badge count rises rather than falls, because a deployment whose posture is
 * partially unknown is not a deployment with less to worry about.
 *
 * <p>The same rule covers the collision this additive SPI has no {@code name()} to refuse: two advisories sharing an id
 * (and, for a tenant-scoped row, a tenant) are both kept - dropping one would hide a real advisory - and a
 * {@code jenreg.posture.collision} advisory reports the duplicated ids, so a packaging accident is visible on the
 * surface instead of rendering as two identically-anchored rows nobody can tell apart. <b>The collision row is filed at
 * the scope it is about</b>: ids that clashed deployment-wide become one {@link Scope#DEPLOYMENT} row, and ids
 * that clashed <em>for a tenant</em> become one {@link Scope#TENANT} row per tenant, carrying that tenant in
 * {@link SecurityAdvisory#tenant()} and naming no tenant in its text. A report is a fan-out that may carry rows for
 * several tenants at once, which is why a row's scope is the only thing a tenant-facing consumer may route on -
 * {@link #forTenant} and {@link #scoped} here, the console's {@code ScopedPosture} and {@code GET /api/admin/posture}
 * downstream - and a deployment-wide row that interpolates one tenant's name defeats every one of them at once
 * (PRINCIPLES &sect;6). Filing it at tenant scope keeps it diagnosable where it can be acted on and routable everywhere
 * else. (The deployment-wide {@code GET /api/posture} renders whatever the report holds without scoping it, so it
 * shows a {@code TENANT} row to any {@code repository:read} caller - true of every tenant-scoped advisory, not of this
 * one in particular, and a property of that endpoint rather than of the collection.)
 */
public record PostureReport(List<SecurityAdvisory> advisories) {

    /** How many clashing ids the collision row names before it starts counting - a row an operator can read. */
    private static final int COLLISIONS_NAMED = 5;

    public PostureReport {
        advisories = List.copyOf(advisories);
    }

    /** Evaluate {@code advisors} against {@code config} and sort critical-first (ties broken by id); an advisor that
     *  throws contributes {@link #unavailable} instead of taking the report down with it. */
    public static PostureReport from(Iterable<? extends SafetyAdvisor> advisors, Configuration config) {
        List<SecurityAdvisory> collected = new ArrayList<>();
        // List.copyOf inside the contribution is deliberate: a null list, or a null advisory inside one, becomes a
        // contained failure of that advisor rather than an NPE out of the collection that every console view runs.
        for (List<SecurityAdvisory> advised : Contributions.collect("safety advisor", advisors,
                advisor -> List.copyOf(advisor.advise(config)), PostureReport::unavailable)) {
            collected.addAll(advised);
        }
        collected.addAll(collisions(collected));
        collected.sort(Comparator.comparing(SecurityAdvisory::severity, Comparator.reverseOrder())
                .thenComparing(SecurityAdvisory::id));
        return new PostureReport(collected);
    }

    /**
     * The row an advisor that threw is reported as. It is filed under the advisor's own implementation class
     * ({@code jenreg.posture.unavailable.<advisor>}), so two failing advisors are two rows rather than one merged
     * one, and it names the <em>kind</em> of failure only - an exception message can quote a configured value, and
     * this surface enumerates a deployment's weaknesses to an operator, so the message goes to the log and never into
     * an advisory (see {@link Contributions#reason}).
     */
    private static List<SecurityAdvisory> unavailable(SafetyAdvisor advisor, Exception failure) {
        return List.of(SecurityAdvisory.deployment(
                "jenreg.posture.unavailable." + Contributions.segment(advisor),
                Severity.WARN,
                "A safety advisor could not be evaluated",
                "The " + advisor.getClass().getName() + " advisor threw " + Contributions.reason(failure)
                        + " instead of answering, so whatever it checks went unchecked in this report: its silence is"
                        + " NOT an all-clear for those settings. Every other advisor was evaluated and the server log"
                        + " carries the failure.",
                "Fix or remove the module that contributes this advisor. An advisor that cannot evaluate a condition"
                        + " answers with an advisory naming what it could not determine, never by throwing.",
                "", "", ""));
    }

    /**
     * The duplicate refusal this SPI has no {@code name()} to inherit from the shared provider primitives, applied
     * where it is actually observable: over the collected advisories rather than over the advisors. A row is keyed by
     * its id, plus its tenant when it is tenant-scoped - the same advisory legitimately raised for two tenants is two
     * rows, not a collision. Both duplicates stay in the report (an id clash must never cost an operator a real
     * advisory) and one extra row names the clashing ids, because a duplicate id is a collision between modules
     * rather than a merge, and it silently ruins the row key the docs anchor and the API consumer use.
     *
     * <p><strong>Each collision row is filed at the scope of the rows that collided</strong>, so reporting the
     * clash never widens who can see it. A clash between deployment-wide rows is one {@link Scope#DEPLOYMENT} row
     * naming the ids; a clash between one tenant's rows is a {@link Scope#TENANT} row for <em>that</em> tenant, which
     * carries the tenant in {@link SecurityAdvisory#tenant()} and names it nowhere in its text. The alternative - the
     * single deployment-wide row this used to emit, whose message interpolated {@code "<id> (tenant <name>)"} - is a
     * tenant name and an advisory id handed to every other tenant's viewer by a fan-out that is explicitly allowed to
     * return rows for more than one tenant (PRINCIPLES &sect;6). Keying without the tenant instead would have kept one
     * row at the price of the diagnosis: an id that legitimately holds for several tenants would report a clash with
     * no way to tell which tenant's rows actually duplicated it.
     *
     * <p>The work stays bounded (clause 12), which one row per scope is worth arguing rather than assuming: a scope
     * only enters the map by contributing <em>at least two</em> rows of its own, so the rows added here are at most
     * half the duplicates the fan-out already returned - the report grows in proportion to its own input, never faster
     * - and each row's message still names at most {@link #COLLISIONS_NAMED} ids and counts the rest.
     */
    private static List<SecurityAdvisory> collisions(List<SecurityAdvisory> advisories) {
        // The row key is the (scope, id) pair rather than a concatenation of the two, so no tenant name or id can be
        // spelled to forge another's key.
        Set<Map.Entry<String, String>> seen = new HashSet<>();
        // Keyed by the scope the clash belongs to: "" is the deployment-wide bucket, a tenant name its own bucket.
        // A SortedMap of SortedSets so both the rows emitted and the ids each names are in a stable, readable order.
        SortedMap<String, SortedSet<String>> duplicated = new TreeMap<>();
        for (SecurityAdvisory advisory : advisories) {
            String scope = advisory.scope() == Scope.TENANT ? advisory.tenant() : "";
            if (!seen.add(Map.entry(scope, advisory.id()))) {
                duplicated.computeIfAbsent(scope, _ -> new TreeSet<>()).add(advisory.id());
            }
        }
        List<SecurityAdvisory> rows = new ArrayList<>();
        duplicated.forEach((scope, ids) -> rows.add(collision(scope, ids)));
        return List.copyOf(rows);
    }

    /** One collision row for one scope: deployment-wide when {@code tenant} is blank, otherwise that tenant's own row.
     *  The ids are named in the message (bounded to {@link #COLLISIONS_NAMED}, the rest counted) because a collision
     *  report that cannot say what collided is not a report; the tenant is carried by the row's scope rather than by
     *  its text, so the diagnosis reaches the viewer who can act on it and nobody else. */
    private static SecurityAdvisory collision(String tenant, SortedSet<String> ids) {
        List<String> named = ids.stream().limit(COLLISIONS_NAMED).toList();
        String listed = String.join(", ", named)
                + (ids.size() > named.size() ? " (and " + (ids.size() - named.size()) + " more)" : "");
        String title = "Two advisors raised the same advisory id";
        String why = "More than one discovered advisor raised these advisory ids"
                + (tenant.isEmpty() ? "" : " for this tenant") + ": " + listed + ". An id is the row key and the docs"
                + " anchor, so a clash means two modules are describing different conditions under one name and an"
                + " operator cannot tell the rows apart. Both rows are kept - none is dropped.";
        String fix = "Rename one of the colliding advisories, or remove the duplicate module registration that raised"
                + " it twice.";
        return tenant.isEmpty()
                ? SecurityAdvisory.deployment("jenreg.posture.collision", Severity.WARN, title, why, fix, "", "", "")
                : SecurityAdvisory.tenant("jenreg.posture.collision", Severity.WARN, tenant, title, why, fix,
                        "", "", "");
    }

    /** Evaluate every {@link ServiceLoader}-discovered {@link SafetyAdvisor} against {@code config}. */
    public static PostureReport discover(Configuration config) {
        return from(ServiceLoader.load(SafetyAdvisor.class).stream()
                .map(ServiceLoader.Provider::get)
                .toList(), config);
    }

    /** The total number of advisories - the count the console badge shows. */
    public int count() {
        return advisories.size();
    }

    /** The number of advisories at {@code severity}. */
    public long count(Severity severity) {
        return advisories.stream().filter(advisory -> advisory.severity() == severity).count();
    }

    /** The most severe advisory's severity, or empty when the report is clean. */
    public Optional<Severity> highest() {
        return advisories.stream().map(SecurityAdvisory::severity).max(Comparator.naturalOrder());
    }

    /** The advisories at {@code scope} - deployment-wide ones for a superadmin, tenant-scoped ones for a tenant admin. */
    public List<SecurityAdvisory> scoped(Scope scope) {
        return advisories.stream().filter(advisory -> advisory.scope() == scope).toList();
    }

    /** The tenant-scoped advisories concerning {@code tenant} - what that tenant's admins may see. */
    public List<SecurityAdvisory> forTenant(String tenant) {
        return advisories.stream()
                .filter(advisory -> advisory.scope() == Scope.TENANT && advisory.tenant().equals(tenant))
                .toList();
    }
}
