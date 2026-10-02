package build.jenesis.repository.ui;

import module java.base;

/**
 * What a console module adds to the landing dashboard: a few panels, each a headline figure with a line or two beside
 * it and the screen it opens. A module contributes one as a bean of the {@link ConsoleModuleProvider#configuration()
 * configuration} it is installed through, so the dashboard shows what is installed and names no module itself; the
 * console's own panels - the repositories, the store, the security posture - are contributed the same way.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> {@link #panels} is called concurrently, once per landing request; a contributor holds no
 *     per-request state.</li>
 * <li><b>Idempotency / replay.</b> Asking twice answers what is known each time; asking never changes what a later
 *     ask answers, except that it may start a count off the request path (below).</li>
 * <li><b>Absence sentinel.</b> A contributor with nothing to say to this viewer returns an empty list; {@code null} is
 *     never legal.</li>
 * <li><b>Selection failure.</b> An {@code ALL} seam: every contributing bean is asked, in {@link #order()} and then by
 *     class name, and none being present leaves an empty dashboard.</li>
 * <li><b>Tenant scoping.</b> {@link Viewer#tenant()} is the tenant the console has selected; a panel speaks of that
 *     tenant's repositories alone, and of the deployment only to a {@link Viewer#superadmin() super-admin}.</li>
 * <li><b>Error visibility.</b> An exception is the panel's failure, not the page's: the dashboard draws that
 *     contributor's place as unreadable, naming it, and the other panels as usual.</li>
 * <li><b>Read purity.</b> A request costs a constant number of point reads and listings of names, whatever the
 *     repositories hold. A figure that has to look into every repository is counted off the request path - a
 *     {@code StoredReport} the panel reads back and asks again for once it is old - and the panel says when it was
 *     counted and, while a count runs, {@link DashboardPanel#refreshing() that it is}.</li>
 * <li><b>Lifecycle / ownership.</b> A contributor is a Spring bean of its module's configuration and owns what that
 *     configuration gives it; the dashboard owns nothing of it.</li>
 * <li><b>Ordering / determinism.</b> Panels render in {@link #order()}, then by the contributor's class name, then in
 *     the order a contributor returns them.</li>
 * <li><b>Bounded work / cancellation.</b> A panel holds a handful of lines, not a list: a long list belongs on the
 *     screen the panel opens. Nothing waits on a count; a landing answered while one runs shows the last.</li>
 * </ol>
 */
public interface DashboardContributor {

    /** Where this contributor's panels sit: lower first. The console's own panels take 10 to 30. */
    default int order() {
        return 100;
    }

    /** The panels {@code viewer} is shown. */
    List<DashboardPanel> panels(Viewer viewer) throws IOException;

    /** Who the dashboard is drawn for: the selected tenant and whether the reader is a super-admin. */
    record Viewer(String tenant, boolean superadmin) {

        public Viewer {
            Objects.requireNonNull(tenant, "tenant");
        }
    }
}
