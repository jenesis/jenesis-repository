package build.jenesis.repository.cleanup;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Lease;
import build.jenesis.repository.store.LineDocument;

/**
 * A report a screen reads instead of computing: the result of a pass over the whole repository - what retention
 * would evict, what a licence policy would newly hold - written once by the pass that computed it and read back as
 * one small object, with the time it was computed at and a bounded sample of its rows.
 *
 * <p>The rule it enforces is the one every console screen has to hold to: a request renders what is stored, and a
 * walk of the published set happens only in the background. A screen that wants a fresh answer {@linkplain #compute
 * starts} the pass and shows it running; it never waits for it. The report keeps at most {@link #SAMPLE} rows - the
 * count is exact, the rows are the head - so the object stays small however large the repository.
 */
public final class StoredReport {

    /** How many rows a report keeps; the count says how many there were. */
    public static final int SAMPLE = 200;

    /** How long a running computation is believed before a new one may be started over it: the ttl of the lease a
     *  run holds, so a node that died mid-computation frees the report after an hour without anyone's help. */
    private static final Duration STALE_RUN = Duration.ofHours(1);

    private static final String ROOT = "reports";

    /** This process's holder id for the report leases. */
    private static final String HOLDER = "report/" + UUID.randomUUID();

    private StoredReport() {
    }

    public enum Status { RUNNING, DONE, FAILED }

    /** One stored report: its status, when the pass started and finished, how many rows it found, the first
     *  {@link #SAMPLE} of them, and the failure that stopped it, if one did. */
    public record Report(Status status, Instant startedAt, Instant finishedAt, int count, List<String> rows,
                         String failure) {

        public boolean running() {
            return status == Status.RUNNING;
        }
    }

    /** The rows a pass found, in the order it found them, and how many there were in all. */
    public record Rows(int count, List<String> sample) {

        public static Rows of(List<String> all) {
            return new Rows(all.size(), List.copyOf(all.subList(0, Math.min(all.size(), SAMPLE))));
        }
    }

    @FunctionalInterface
    public interface Pass {
        Rows run() throws IOException;
    }

    public static Optional<Report> read(ArtifactStore store, String name) throws IOException {
        return store.readVersioned(ROOT + "/" + name).map(versioned -> parse(versioned.content()));
    }

    /** Write a finished report - the face a scheduled pass uses when it already holds the result. */
    public static void write(ArtifactStore store, String name, Instant startedAt, Instant finishedAt, Rows rows)
            throws IOException {
        store.write(ROOT + "/" + name, new ByteArrayInputStream(
                serialize(new Report(Status.DONE, startedAt, finishedAt, rows.count(), rows.sample(), null))));
    }

    /**
     * Start {@code pass} on a thread of its own, recording it as running first, unless a run is already under way
     * on any node and younger than an hour. Answers whether a run was started. The pass writes its own report when
     * it finishes, or the failure that stopped it.
     *
     * <p>The run holds a {@link Lease} named for the report in the repository's own {@code .system/locks} space, so
     * two nodes asked for the same report at the same moment start one run, not two: the stored {@code RUNNING}
     * status used to be the only guard, and two nodes reading "not running" in the same instant both computed. The
     * lease's ttl is the hour the status used to be believed for, and a finished run releases it so the next request
     * can start at once.
     */
    public static boolean compute(ArtifactStore store, String name, Pass pass) throws IOException {
        Lease lease = new Lease(store, STALE_RUN);
        String lock = "report-" + name;
        Instant now = Instant.now();
        if (!lease.acquire(lock, HOLDER, now)) {
            return false;                                   // running on some node, and younger than the lease
        }
        store.write(ROOT + "/" + name, new ByteArrayInputStream(
                serialize(new Report(Status.RUNNING, now, null, 0, List.of(), null))));
        Thread.ofVirtual().name("report-" + name).start(() -> {
            Report finished;
            try {
                Rows rows = pass.run();
                finished = new Report(Status.DONE, now, Instant.now(), rows.count(), rows.sample(), null);
            } catch (IOException | RuntimeException failure) {
                finished = new Report(Status.FAILED, now, Instant.now(), 0, List.of(), String.valueOf(failure));
            }
            try {
                store.write(ROOT + "/" + name, new ByteArrayInputStream(serialize(finished)));
                lease.release(lock, HOLDER, Instant.now());
            } catch (IOException unwritable) {
                throw new UncheckedIOException("the report '" + name + "' could not be stored", unwritable);
            }
        });
        return true;
    }

    /**
     * Whether a run of {@code name} still holds the report's lease. The run's thread writes the finished report and
     * then releases the lease, so a reader that sees {@code DONE} may still be racing that last write; a caller that
     * must not outlive the run - a test whose store is about to be deleted, a round that starts the next run - waits
     * for this to be {@code false}, not for the status alone.
     */
    public static boolean inFlight(ArtifactStore store, String name) throws IOException {
        return new Lease(store, STALE_RUN).holder("report-" + name, Instant.now()).isPresent();
    }

    /**
     * Wait until {@code name} has settled - a report is stored, it is no longer RUNNING, and no run of it is
     * {@linkplain #inFlight in flight} - and answer it, or empty once {@code patience} has run out. A test that drives
     * a computation and reads what it persisted waits here rather than on the status alone: the run's last store write
     * is the release of its lease, after the report, and a late compare-and-set recreates the store's lock directory
     * under a temp dir JUnit is deleting, which fails the delete on a root that is not empty. Two suites had written
     * this loop, and only one of them had learnt that.
     */
    public static Optional<Report> awaitSettled(ArtifactStore store, String name, Duration patience) throws IOException {
        Instant deadline = Instant.now().plus(patience);
        while (Instant.now().isBefore(deadline)) {
            Optional<Report> stored = read(store, name);
            if (stored.isPresent() && stored.get().status() != Status.RUNNING && !inFlight(store, name)) {
                return stored;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted waiting for the report " + name, interrupted);
            }
        }
        return Optional.empty();
    }

    /** The report's own name on its first line, so a torn or foreign object reads as unreadable rather than as a
     *  report; this used to be positional lines with no name and no version, the one stored shape a reader could not
     *  tell from garbage. */
    private static final String MAGIC = "jenesis-report";

    private static byte[] serialize(Report report) {
        LineDocument.Builder document = LineDocument.of(MAGIC, 1)
                .field("status", report.status())
                .field("started", report.startedAt())
                .field("finished", report.finishedAt())
                .field("count", report.count())
                .field("failure", report.failure());
        for (String row : report.rows()) {
            document.line(row);
        }
        return document.bytes();
    }

    private static Report parse(byte[] content) {
        Optional<LineDocument> document = LineDocument.parse(content, MAGIC);
        if (document.isEmpty()) {
            return new Report(Status.FAILED, null, null, 0, List.of(), "unreadable report");
        }
        LineDocument read = document.get();
        Status status;
        try {
            status = Status.valueOf(read.field("status").orElse(""));
        } catch (IllegalArgumentException _) {
            status = Status.FAILED;
        }
        int count;
        try {
            count = Integer.parseInt(read.field("count").orElse("0"));
        } catch (NumberFormatException _) {
            count = 0;
        }
        String failure = read.field("failure").orElse("");
        return new Report(status, instant(read.field("started").orElse("")), instant(read.field("finished").orElse("")),
                count, read.lines(), failure.isEmpty() ? null : failure);
    }

    private static Instant instant(String text) {
        if (text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text.trim());
        } catch (DateTimeParseException _) {
            return null;
        }
    }
}
