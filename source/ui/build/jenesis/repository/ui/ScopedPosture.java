package build.jenesis.repository.ui;

import build.jenesis.repository.posture.PostureReport;
import build.jenesis.repository.posture.Scope;
import build.jenesis.repository.posture.SecurityAdvisory;
import build.jenesis.repository.posture.Severity;

import module java.base;

/**
 * One collected {@link PostureReport} as a <em>single tenant's view</em> of it - the model the console's
 * Security-posture screen renders, and the containment boundary that makes the free core's
 * {@link PostureReport#forTenant} leg load-bearing rather than decorative.
 *
 * <p>A report is a fan-out over every discovered {@code SafetyAdvisor}, and an advisory names the tenant it concerns
 * itself. Nothing in the SPI stops a provider - a third-party one, or a shipped one after a refactor - from raising a
 * {@code TENANT}-scoped row naming a tenant other than the one being viewed. So the screen does not render the rows
 * it happens to receive: it asks the report for <b>this tenant's</b> rows through {@link PostureReport#forTenant},
 * and for the deployment-wide rows through {@link PostureReport#scoped}. Anything else - a row for another tenant -
 * appears in neither list, and, because the tallies are computed over what is actually rendered rather than over
 * {@code report.count()}, not in the counts either: a foreign advisory cannot leak even as a number.
 *
 * <p>Both halves are shown, because a tenant's operator needs both and they are not interchangeable: a deployment-wide
 * row (authorization off, the dev profile active) is the operator's to fix and applies to every tenant, while a
 * tenant-scoped row is this tenant's own admission policy and affects nobody else. They are rendered as two labelled
 * groups and every tenant row carries its tenant, so which is which is never inferred from position.
 *
 * <p>With no tenant selected {@link #tenant()} is blank and {@link #own()} is empty: the screen degrades to the
 * deployment-wide view it was before, rather than guessing a tenant. A single-tenant deployment is not a special case
 * here - its one tenant is selected implicitly by the session, so it simply always has a tenant.
 */
public record ScopedPosture(String tenant, List<SecurityAdvisory> own, List<SecurityAdvisory> deployment) {

    public ScopedPosture {
        tenant = tenant == null ? "" : tenant.strip();
        own = List.copyOf(own);
        deployment = List.copyOf(deployment);
    }

    /**
     * Split {@code report} into what {@code tenant}'s view may see: that tenant's own advisories and the
     * deployment-wide ones. A {@code null} or blank tenant yields the deployment-wide half alone.
     */
    public static ScopedPosture of(PostureReport report, String tenant) {
        Objects.requireNonNull(report, "report");
        String selected = tenant == null ? "" : tenant.strip();
        return new ScopedPosture(selected,
                selected.isEmpty() ? List.of() : report.forTenant(selected),
                report.scoped(Scope.DEPLOYMENT));
    }

    /** Whether a tenant is selected at all - false only when the session carries none. */
    public boolean scoped() {
        return !tenant.isEmpty();
    }

    /** The advisories actually rendered: this tenant's first, then the deployment-wide ones. Both halves arrive
     *  already severity-sorted from the report, so each group stays critical-first. */
    public List<SecurityAdvisory> rendered() {
        List<SecurityAdvisory> all = new ArrayList<>(own);
        all.addAll(deployment);
        return List.copyOf(all);
    }

    /** How many advisories this view shows - the tally over what is rendered, never over the whole report. */
    public int count() {
        return own.size() + deployment.size();
    }

    /** How many of the rendered advisories are at {@code severity} - counted over the two halves, so a row this view
     *  does not show is not counted into a number this view does. */
    public long count(Severity severity) {
        return Stream.concat(own.stream(), deployment.stream())
                .filter(advisory -> advisory.severity() == severity).count();
    }

    /** The per-severity tallies the screen's summary line shows, kept here so the template needs no static
     *  {@link Severity} reference (it is not on a view layer's module path in a modular graph). */
    public long critical() {
        return count(Severity.CRITICAL);
    }

    public long warn() {
        return count(Severity.WARN);
    }

    public long info() {
        return count(Severity.INFO);
    }

    /** Whether this view has nothing to show - the healthy state, rendered as a friendly empty message. */
    public boolean empty() {
        return count() == 0;
    }
}
