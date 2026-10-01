package build.jenesis.repository.ui;

import module java.base;

import build.jenesis.repository.posture.PostureReport;
import build.jenesis.repository.posture.Scope;
import build.jenesis.repository.posture.SecurityAdvisory;
import build.jenesis.repository.posture.Severity;

/**
 * One {@link PostureReport} as a single tenant's view, the model the Security-posture screen renders. An advisor may
 * raise a {@code TENANT}-scoped row naming any tenant, so the view takes only this tenant's rows
 * ({@link PostureReport#forTenant}) and the deployment-wide ones ({@link PostureReport#scoped}), and counts over what it
 * renders, so another tenant's advisory cannot leak even as a number.
 *
 * <p>The two halves render as labelled groups: a deployment-wide row is the operator's to fix, a tenant row is this
 * tenant's own admission policy. With no tenant selected, {@link #own()} is empty.
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

    /** How many of the rendered advisories are at {@code severity}. */
    public long count(Severity severity) {
        return Stream.concat(own.stream(), deployment.stream())
                .filter(advisory -> advisory.severity() == severity).count();
    }

    /** The per-severity tallies of the summary line, so the template needs no static {@link Severity} reference. */
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
