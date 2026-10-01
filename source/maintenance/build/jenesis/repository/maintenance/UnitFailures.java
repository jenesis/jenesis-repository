package build.jenesis.repository.maintenance;

import module java.base;

/**
 * The failures one {@link MaintenanceTask} unit contained while it swept the rest of its subjects, raised as one
 * exception once the unit returns: every subject that could be swept was, and the pass still counts as failed.
 *
 * <p>The first {@value #NAMED} subjects are named and the rest counted, because the usual cause is a store outage that
 * fails every subject, and keeping them all would turn it into a heap outage.
 *
 * <p>One instance belongs to one unit and is never shared across concurrent units, so it needs no synchronisation.
 */
public final class UnitFailures {

    /** How many failed subjects the raised failure names before it falls back to counting them. */
    private static final int NAMED = 5;

    private final String work;
    private final String consequence;
    private final List<String> named = new ArrayList<>();
    private long count;

    /**
     * @param work        what this unit was doing, so the raised failure names the pass and repository
     * @param consequence what the failure means for the deployment: which derived state is missing, what was not
     *                    stamped, and when it converges
     */
    public UnitFailures(String work, String consequence) {
        this.work = Objects.requireNonNull(work, "work");
        this.consequence = Objects.requireNonNull(consequence, "consequence");
    }

    /** Contains one subject's failure; the caller logs its stack trace at the site. */
    public void record(String subject, Throwable failure) {
        count++;
        if (named.size() < NAMED) {
            named.add(subject + " (" + failure + ")");
        }
    }

    /** Whether any subject failed, which a freshness stamp or marker flip asks before it is written. */
    public boolean any() {
        return count > 0;
    }

    /** Raises the contained failures as the unit's own; a no-op when nothing failed. The scheduler calls it once the
     *  unit returns, so a pass only records. */
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
