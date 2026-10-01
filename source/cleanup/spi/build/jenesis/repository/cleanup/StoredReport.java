package build.jenesis.repository.cleanup;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Lease;
import build.jenesis.repository.store.LineDocument;

/**
 * A report a screen reads instead of computing: the result of a pass over the whole repository - what retention would
 * evict, what a licence policy would newly hold - written by that pass and read back as one small object with its
 * timestamps and a bounded sample of rows.
 *
 * <p>A request renders what is stored; a walk of the published set happens only in the background. A screen wanting a
 * fresh answer {@linkplain #compute starts} the pass and shows it running, never waiting for it. At most
 * {@link #SAMPLE} rows are kept - the count is exact, the rows are the head.
 */
public final class StoredReport {

    /** How many rows a report keeps; the count says how many there were. */
    public static final int SAMPLE = 200;

    /** How long a running computation is believed before another may start over it: the ttl of the lease a run holds,
     *  so a node that died mid-run frees the report after an hour. */
    private static final Duration STALE_RUN = Duration.ofHours(1);

    private static final String ROOT = "reports";

    /** This process's holder id for the report leases. */
    private static final String HOLDER = "report/" + UUID.randomUUID();

    private StoredReport() {
    }

    public enum Status { RUNNING, DONE, FAILED }

    /** One stored report: its status, start and finish, row count, the first {@link #SAMPLE} rows, the failure that
     *  stopped it, and - while running or after a failure - the {@code previous} finished report, so a screen keeps
     *  showing the last result. A finished report carries no previous one. */
    public record Report(Status status, Instant startedAt, Instant finishedAt, int count, List<String> rows,
                         String failure, Report previous) {

        public Report(Status status, Instant startedAt, Instant finishedAt, int count, List<String> rows,
                      String failure) {
            this(status, startedAt, finishedAt, count, rows, failure, null);
        }

        public boolean running() {
            return status == Status.RUNNING;
        }

        /** The last finished result: this report when it is done, otherwise the one it carries, if any. */
        public Optional<Report> lastFinished() {
            return status == Status.DONE ? Optional.of(this) : Optional.ofNullable(previous);
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
     * Start {@code pass} on its own thread, recording it as running first, unless a run younger than an hour is under
     * way on any node. Answers whether a run was started; the pass writes its report, or its failure, when it ends.
     *
     * <p>The run holds a {@link Lease} named for the report in the repository's {@code .system/locks} space, so two
     * nodes asked at the same moment start one run: two nodes reading "not running" at once would both compute. The
     * lease's ttl is the hour a {@code RUNNING} status is believed, and a finished run releases it.
     */
    public static boolean compute(ArtifactStore store, String name, Pass pass) throws IOException {
        Lease lease = new Lease(store, STALE_RUN);
        String lock = "report-" + name;
        Instant now = Instant.now();
        if (!lease.acquire(lock, HOLDER, now)) {
            return false;                                   // running on some node, and younger than the lease
        }
        // The last finished result rides along with the run, and with its failure, until a new one replaces it.
        Report previous = read(store, name).flatMap(Report::lastFinished).orElse(null);
        store.write(ROOT + "/" + name, new ByteArrayInputStream(
                serialize(new Report(Status.RUNNING, now, null, 0, List.of(), null, previous))));
        Thread.ofVirtual().name("report-" + name).start(() -> {
            Report finished;
            try {
                Rows rows = pass.run();
                finished = new Report(Status.DONE, now, Instant.now(), rows.count(), rows.sample(), null);
            } catch (IOException | RuntimeException failure) {
                finished = new Report(Status.FAILED, now, Instant.now(), 0, List.of(), String.valueOf(failure),
                        previous);
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

    /** Whether a run of {@code name} still holds the report's lease. The run writes its report and then releases the
     *  lease, so a caller that must not outlive the run - a test about to delete its store, a round starting the next
     *  run - waits for this to be {@code false}, not for the status alone. */
    public static boolean inFlight(ArtifactStore store, String name) throws IOException {
        return new Lease(store, STALE_RUN).holder("report-" + name, Instant.now()).isPresent();
    }

    /** Wait until {@code name} has settled - stored, no longer RUNNING, and not {@linkplain #inFlight in flight} - and
     *  answer it, or empty once {@code patience} runs out. The run's last write is the release of its lease, after the
     *  report, and a late compare-and-set would recreate the store's lock directory under a temp directory a test is
     *  deleting. */
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

    /** The report's name on its first line, so a torn or foreign object reads as unreadable rather than as a report. */
    private static final String MAGIC = "jenesis-report";

    /** A report carrying a previous one is running or failed and has no rows of its own: the lines are the previous
     *  report's, described by its {@code previous.} fields. */
    private static byte[] serialize(Report report) {
        LineDocument.Builder document = LineDocument.of(MAGIC, 1)
                .field("status", report.status())
                .field("started", report.startedAt())
                .field("finished", report.finishedAt())
                .field("count", report.count())
                .field("failure", report.failure());
        List<String> lines = report.rows();
        if (report.previous() != null) {
            document.field("previous.started", report.previous().startedAt())
                    .field("previous.finished", report.previous().finishedAt())
                    .field("previous.count", report.previous().count());
            lines = report.previous().rows();
        }
        for (String row : lines) {
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
        Instant previousFinished = instant(read.field("previous.finished").orElse(""));
        if (previousFinished != null) {
            Report previous = new Report(Status.DONE, instant(read.field("previous.started").orElse("")),
                    previousFinished, integer(read.field("previous.count").orElse("0")), read.lines(), null);
            return new Report(status, instant(read.field("started").orElse("")), instant(read.field("finished").orElse("")),
                    count, List.of(), failure.isEmpty() ? null : failure, previous);
        }
        return new Report(status, instant(read.field("started").orElse("")), instant(read.field("finished").orElse("")),
                count, read.lines(), failure.isEmpty() ? null : failure);
    }

    private static int integer(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException _) {
            return 0;
        }
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
