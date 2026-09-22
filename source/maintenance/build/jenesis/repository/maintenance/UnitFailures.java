package build.jenesis.repository.maintenance;

import module java.base;

/**
 * The failures one {@link MaintenanceTask} unit contained while it swept the rest of its subjects, and the single
 * exception it raises for them before the unit returns.
 *
 * <p><b>Why containment and a throw rather than one or the other.</b> {@code MaintenanceTask} contract clause 4 says a
 * unit that could not do its work must throw - a swallowed failure reaches neither
 * {@code jenreg.maintenance.failures} nor the task's reported status, so a store that refused every write
 * reads exactly like a clean pass. But a unit that aborts on its <em>first</em> refused subject leaves the other
 * million coordinates unswept for one poisoned jar, which is the bounded-work half of clause 6. Both are satisfied by
 * containing each subject, naming what failed, and raising once at the end: every subject that could be swept was
 * swept, and the pass is still counted as failed. {@code SignalRefreshTask} is the pattern this generalises -
 * "containing it here is not swallowing it".
 *
 * <p><b>Bounded on purpose.</b> The failure this exists to report is usually a store outage, which fails
 * <em>every</em> subject in the walk; a collector that kept them all, or a message that named them all, would turn a
 * store outage into a heap outage on a repository with millions of versions. So the first few subjects are named and
 * the rest are counted - enough for an operator to see what kind of thing failed, bounded regardless of how much of
 * the walk went with it.
 *
 * <p>Method-local by construction: one instance belongs to one unit, is handed down that unit's own call tree and is
 * never shared across the units the scheduler fans out concurrently (clause 1), so it needs no synchronisation.
 */
public final class UnitFailures {

    /** How many failed subjects the raised failure names before it falls back to counting them. */
    private static final int NAMED = 5;

    private final String work;
    private final String consequence;
    private final List<String> named = new ArrayList<>();
    private long count;

    /**
     * @param work        what this unit was doing, named so the raised failure says which pass over which
     *                    repository gave way rather than only that something did
     * @param consequence what the deployment should read into the failure - which derived state is missing, what
     *                    was deliberately not stamped, and when it converges
     */
    public UnitFailures(String work, String consequence) {
        this.work = Objects.requireNonNull(work, "work");
        this.consequence = Objects.requireNonNull(consequence, "consequence");
    }

    /** Contain one subject's failure. The caller logs it with its stack trace at the site, where the context is;
     *  this keeps only what the raised failure needs to name it. */
    public void record(String subject, Throwable failure) {
        count++;
        if (named.size() < NAMED) {
            named.add(subject + " (" + failure + ")");
        }
    }

    /** Whether any subject failed - the question a freshness stamp, a marker flip or any other "this view is current"
     *  claim must ask before it is written, since a unit that did not do its work may not report itself fresh. */
    public boolean any() {
        return count > 0;
    }

    /** Raise the contained failures as the unit's own, so the scheduler logs, counts and reports them (clause 4).
     *  A no-op when nothing failed. Called by the scheduler once the unit returns - a pass records and does not
     *  raise, because the step a pass can forget is the one that made a failed sweep read as a clean one. */
    public void rethrow() throws IOException {
        if (count == 0) {
            return;
        }
        StringBuilder message = new StringBuilder(work).append(" failed for ").append(count).append(" subject(s): ")
                .append(String.join(", ", named));
        if (count > named.size()) {
            message.append(" and ").append(count - named.size()).append(" more");
        }
        throw new IOException(message.append(". ").append(consequence).toString());
    }
}
