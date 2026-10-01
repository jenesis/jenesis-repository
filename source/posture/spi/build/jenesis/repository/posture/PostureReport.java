package build.jenesis.repository.posture;

import module java.base;

import build.jenesis.repository.observation.Contributions;

/**
 * The single collected view every consumer reads - the console's Security-posture panel, the admin API and the boot log
 * all render this, so an advisory is defined in one place. {@link #from} evaluates a set of {@link SafetyAdvisor}s
 * against the effective {@link Configuration} and sorts the result critical-first, then by id; {@link #discover} does
 * the same over the installed advisors. An empty report is the healthy state.
 *
 * <p><strong>Silence is load-bearing, so a failure is never silent.</strong> {@link #from} collects through
 * {@code Contributions}: an advisor that throws or answers {@code null} is contained to its own rows and replaced by a
 * {@link Severity#WARN} {@code jenrepo.posture.unavailable.<advisor>} advisory, every other advisor is evaluated, and
 * the failure is logged once. The badge count rises rather than falls, because a partially unknown posture is not one
 * with less to worry about.
 *
 * <p>Two advisories sharing an id (and, for a tenant-scoped row, a tenant) are both kept, and a
 * {@code jenrepo.posture.collision} advisory names the duplicated ids. <b>The collision row is filed at the scope it is
 * about</b>: a deployment-wide clash is one {@link Scope#DEPLOYMENT} row, a clash for a tenant one {@link Scope#TENANT}
 * row for that tenant, naming no tenant in its text. A row's scope is the only thing a tenant-facing consumer may route
 * on ({@link #forTenant}, {@link #scoped}, the console's {@code ScopedPosture}), so a deployment-wide row interpolating
 * one tenant's name would defeat all of them.
 */
public record PostureReport(List<SecurityAdvisory> advisories) {

    /** How many clashing ids a collision row names before it counts the rest. */
    private static final int COLLISIONS_NAMED = 5;

    public PostureReport {
        advisories = List.copyOf(advisories);
    }

    /** Evaluate {@code advisors} against {@code config} and sort critical-first, ties by id; an advisor that throws
     *  contributes {@link #unavailable} instead. */
    public static PostureReport from(Iterable<? extends SafetyAdvisor> advisors, Configuration config) {
        List<SecurityAdvisory> collected = new ArrayList<>();
        // List.copyOf inside the contribution makes a null list, or a null advisory in one, a contained failure of that
        // advisor rather than an NPE out of the collection.
        for (List<SecurityAdvisory> advised : Contributions.collect("safety advisor", advisors,
                advisor -> List.copyOf(advisor.advise(config)), PostureReport::unavailable)) {
            collected.addAll(advised);
        }
        collected.addAll(collisions(collected));
        collected.sort(Comparator.comparing(SecurityAdvisory::severity, Comparator.reverseOrder())
                .thenComparing(SecurityAdvisory::id));
        return new PostureReport(collected);
    }

    /** The row a throwing advisor is reported as, filed under the advisor's implementation class
     *  ({@code jenrepo.posture.unavailable.<advisor>}) so two failing advisors are two rows. It names the kind of
     *  failure only: an exception message can quote a configured value, so the message goes to the log (see
     *  {@link Contributions#reason}). */
    private static List<SecurityAdvisory> unavailable(SafetyAdvisor advisor, Exception failure) {
        return List.of(SecurityAdvisory.deployment(
                "jenrepo.posture.unavailable." + Contributions.segment(advisor),
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
     * Reports duplicated advisories, since this SPI has no {@code name()} for a provider-level refusal. A row is keyed
     * by its id, plus its tenant when tenant-scoped - the same advisory raised for two tenants is two rows, not a
     * collision. Both duplicates stay in the report and one extra row names the clashing ids, because a duplicate id
     * breaks the row key the docs anchor and the API consumer use.
     *
     * <p><strong>Each collision row is filed at the scope of the rows that collided</strong>, so reporting a clash
     * never widens who can see it: a deployment-wide clash is one {@link Scope#DEPLOYMENT} row naming the ids, a clash
     * between one tenant's rows is a {@link Scope#TENANT} row for that tenant that names the tenant nowhere in its
     * text.
     *
     * <p>The work is bounded (clause 12): a scope only gets a row by contributing at least two rows of its own, so the
     * added rows are at most half the duplicates already returned, and each names at most {@link #COLLISIONS_NAMED}
     * ids.
     */
    private static List<SecurityAdvisory> collisions(List<SecurityAdvisory> advisories) {
        // Keyed by the (scope, id) pair rather than a concatenation, so no name can be spelled to forge another's key.
        Set<Map.Entry<String, String>> seen = new HashSet<>();
        // "" is the deployment-wide bucket, a tenant name its own; sorted so rows and the ids each names are stable.
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

    /** One collision row for one scope: deployment-wide when {@code tenant} is blank, otherwise that tenant's row. The
     *  ids are named (bounded to {@link #COLLISIONS_NAMED}, the rest counted); the tenant is carried by the scope, not
     *  the text. */
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
                ? SecurityAdvisory.deployment("jenrepo.posture.collision", Severity.WARN, title, why, fix, "", "", "")
                : SecurityAdvisory.tenant("jenrepo.posture.collision", Severity.WARN, tenant, title, why, fix,
                        "", "", "");
    }

    /** Evaluate every installed {@link SafetyAdvisor} against {@code config}. */
    public static PostureReport discover(Configuration config) {
        return from(Installed.ADVISORS, config);
    }

    /** The advisors, discovered once for the life of the class loader. Callers are request handlers, and the installed
     *  set cannot differ between two requests of one JVM; the answer depends on the configuration, so {@link #from}
     *  still runs per call. Holding the instances is safe because the contract makes an advisor stateless. */
    private static final class Installed {

        private static final List<SafetyAdvisor> ADVISORS = ServiceLoader.load(SafetyAdvisor.class).stream()
                .map(ServiceLoader.Provider::get)
                .map(SafetyAdvisor.class::cast)
                .toList();

        private Installed() {
        }
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

    /**
     * Everything a caller belonging to {@code tenant} may be shown: every deployment-wide advisory plus that tenant's
     * own. A {@code null} or blank tenant - an anonymous read, or a key belonging to no tenant - sees the
     * deployment-wide rows alone.
     *
     * <p>The composition lives here rather than at each read surface because getting it wrong is a disclosure: this
     * report enumerates a deployment's weaknesses, so a surface rendering {@link #advisories()} whole hands one
     * tenant's unsafe settings to every other tenant's readers. A console showing the two halves as separate panels
     * uses {@link #scoped} and {@link #forTenant}; this is for surfaces that serve one flat list.
     */
    public List<SecurityAdvisory> visibleTo(String tenant) {
        if (tenant == null || tenant.isBlank()) {
            return scoped(Scope.DEPLOYMENT);
        }
        List<SecurityAdvisory> visible = new ArrayList<>(scoped(Scope.DEPLOYMENT));
        visible.addAll(forTenant(tenant));
        return List.copyOf(visible);
    }
}
