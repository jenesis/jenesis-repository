package build.jenesis.repository.compliance;

import module java.base;

/**
 * The role a {@link ContentScanner} takes when it runs on a tool an operator has to keep fit - a command on this node,
 * or a service beside the deployment - and can say what state that tool is in: which version runs, which databases it
 * matches against and how current each is, and what makes it unfit to scan. Detected by {@code instanceof} on an
 * installed scanner, the way {@link RefreshableSource} is on a signal source: it is not a kind of scanner a repository
 * selects, but how one reports on what it runs on.
 *
 * <p>A scanner that does not implement it reports nothing beyond its scans; the host's status pass passes it by.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> {@link #placement} is a constant. {@link #inspect} and {@link #refresh} are called by
 *       one pass at a time and hold nothing between calls.</li>
 *   <li><b>Idempotency / replay.</b> {@link #inspect} changes nothing. {@link #refresh} asked again fetches nothing a
 *       tool already holds current - a tool inside its own update window answers its state without a download - so a
 *       pass may ask it on every run.</li>
 *   <li><b>Absence sentinel.</b> A {@link Status} names no databases where the tool keeps none it reports, and no
 *       reasons where it is fit; a field the tool does not state is {@code null}. {@code null} is never a legal
 *       return.</li>
 *   <li><b>Error visibility.</b> A tool that cannot be reached or started throws an {@link IOException}, which the host
 *       records as the tool's failure; a tool that answers but is unfit to scan answers a {@link Status} whose
 *       {@link Status#unfit} says why, since its version and databases are still worth showing.</li>
 *   <li><b>Read purity.</b> Both calls reach the tool, so neither is made on a request path: the host's pass makes
 *       them and records what they answered, and a surface reads the record.</li>
 *   <li><b>Staleness.</b> Each {@link Database} carries its {@link Freshness}: when it was fetched, and whether it may
 *       still be matched against - stale once the tool's own next-update instant has passed without a fetch.</li>
 *   <li><b>Bounded work / cancellation.</b> Both calls are bounded in time by the scanner's own timeout and every
 *       answer they read in bytes.</li>
 * </ol>
 */
public interface ScannerTool {

    /** Where a scanner's tool runs. */
    enum Placement {
        /** A command this node starts for each scan. */
        COMMAND,
        /** A service beside the deployment that this node asks over the network. */
        SERVICE
    }

    /** Where the tool runs. */
    Placement placement();

    /** The tool's state as it answers now, over the deployment's {@code config}: asks the tool, changes nothing. */
    Status inspect(UnaryOperator<String> config) throws IOException;

    /** Have the tool fetch whatever database it holds that is no longer current, then answer its state; a tool that
     *  updates itself - a service keeping its own databases - answers {@link #inspect}. */
    Status refresh(UnaryOperator<String> config) throws IOException;

    /**
     * What the tool reported: the {@code version} that runs, the version this product pins it at ({@code null} where
     * the operator supplies the tool and nothing is pinned), each database it matches against, and why it is unfit to
     * scan - empty where it is fit.
     */
    record Status(String version, String pinned, List<Database> databases, List<String> unfit) {

        public Status {
            databases = List.copyOf(databases);
            unfit = List.copyOf(unfit);
        }
    }

    /**
     * One database a tool matches against: its {@code name}, the {@code build} it is (a schema or release name, as the
     * tool states it), when it was {@code built} upstream, when this tool {@code fetched} it, when the tool next
     * expects a newer one ({@code nextUpdate}), its {@code digest} and the {@code source} it is fetched from - each
     * {@code null} where the tool does not say - and its {@link Freshness} at the moment it was reported.
     */
    record Database(String name, String build, Instant built, Instant fetched, Instant nextUpdate, String digest,
                    String source, Freshness freshness) {

        /** A database fetched at {@code fetched}, current until {@code nextUpdate} as of {@code now}: authoritative
         *  while that instant is ahead or unknown, stale once it has passed, and {@link Freshness#NEVER} where nothing
         *  was fetched. */
        public static Freshness freshness(Instant fetched, Instant nextUpdate, Instant now) {
            if (fetched == null) {
                return Freshness.NEVER;
            }
            return nextUpdate != null && !nextUpdate.isAfter(now) ? Freshness.stale(fetched)
                    : Freshness.at(fetched);
        }
    }
}
