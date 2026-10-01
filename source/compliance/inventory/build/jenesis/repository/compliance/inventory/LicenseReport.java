package build.jenesis.repository.compliance.inventory;

import module java.base;
import build.jenesis.repository.cleanup.Release;
import build.jenesis.repository.cleanup.RepositoryInventory;
import build.jenesis.repository.cleanup.StoredReport;
import build.jenesis.repository.compliance.License;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The licence inventory of one repository: how many of its versions declare each licence category and each SPDX id,
 * counted on request by a pass over every version and read back as a {@link StoredReport}.
 *
 * <p>This is the one implementation every surface reaches - {@code GET /api/licenses}, the console's Licenses screen
 * and {@code jenrepo licenses} through the API - so they cannot disagree about what was counted or how. A request
 * never counts: {@link #read} is one point read of the stored report (and, while it says it is running, one of the
 * lease its run holds), and {@link #start} records the count as running, starts it on a thread of its own and answers
 * at once whether this call started it or found one already under way. A surface shows what it read and polls while a
 * count runs; nothing here waits for one to finish.
 *
 * <p><b>What is counted.</b> Every version the repository holds, once: {@link LicenseDerivation#resolve} gives a
 * version's licences the way the gate recorded them, or re-derives them from its stored metadata where it recorded
 * none, and a version counts once towards each distinct category and each distinct SPDX id among them. A licence
 * nothing identifies counts towards the {@code unknown} category and towards no SPDX id, as does a version that
 * declares none, so every version is in at least one category. The unit is a version, not a coordinate: a library
 * published in ten versions under one licence counts ten.
 *
 * <p><b>What is stored.</b> One row per count, tab-separated: {@code versions} with an empty value and the number of
 * versions counted, then {@code category} rows and then {@code license} rows, each with its value and its count of
 * versions, most versions first. The report keeps the first {@link StoredReport#SAMPLE} rows and records how many
 * there were, so a repository declaring more distinct licences than that reads as cut short rather than complete -
 * the categories, a handful, always fit.
 *
 * <p>It depends on nothing that indexes: the count is the same whether or not a repository's full-text search is on.
 * Only a drill-down from a count to the versions behind it needs the index, which is the surfaces' concern.
 */
public final class LicenseReport {

    /** The stored report's name, one per repository. */
    public static final String NAME = "license-inventory";

    private static final String VERSIONS = "versions";
    private static final String CATEGORY = "category";
    private static final String LICENSE = "license";

    private LicenseReport() {
    }

    /** Where a repository's count stands. */
    public enum State {
        /** No count has been asked for. */
        NOT_COUNTED,
        /** A count is under way. */
        RUNNING,
        /** The last count finished, and these are its numbers. */
        DONE,
        /** The last count stopped before it finished, for the reason given. */
        FAILED
    }

    /** One count: a category or an SPDX id, and how many versions carry it. */
    public record Count(String value, long versions) {
    }

    /**
     * The inventory as it is stored: its state, when the count started and when it finished ({@code null} until it
     * has), why it failed if it did, how many versions it counted, the counts per category and per SPDX id, how many
     * rows the count produced, whether some of them were left out of what was kept, and - while a count runs or
     * after one failed - the {@code previous} finished count, which a surface keeps showing until a new one lands.
     */
    public record Inventory(State state, Instant startedAt, Instant finishedAt, String failure, long versions,
                            List<Count> categories, List<Count> licenses, int rows, boolean truncated,
                            Inventory previous) {

        public Inventory {
            categories = List.copyOf(categories);
            licenses = List.copyOf(licenses);
        }

        static Inventory notCounted() {
            return new Inventory(State.NOT_COUNTED, null, null, null, 0, List.of(), List.of(), 0, false, null);
        }

        /** The finished count a surface shows: this one when it is done, otherwise the previous one, if any. */
        public Inventory shown() {
            return state == State.DONE ? this : previous;
        }

        /** Whether a count is under way, so a surface keeps polling. */
        public boolean running() {
            return state == State.RUNNING;
        }

        /** The instant the inventory is as of: when the count finished, or when it started while it runs. */
        public Instant asOf() {
            return finishedAt != null ? finishedAt : startedAt;
        }
    }

    /**
     * The repository's inventory as the last count left it, {@link State#NOT_COUNTED} when none was ever asked for.
     * A report still saying it runs while no run holds its lease was left by a run that died - its node went away -
     * and reads as failed, so a surface does not poll a count that will never finish.
     *
     * @param repository the repository's scoped store
     */
    public static Inventory read(ArtifactStore repository) throws IOException {
        Optional<StoredReport.Report> stored = StoredReport.read(repository, NAME);
        if (stored.isEmpty()) {
            return Inventory.notCounted();
        }
        StoredReport.Report report = stored.get();
        Inventory previous = report.previous() == null ? null : parse(report.previous());
        return switch (report.status()) {
            case RUNNING -> StoredReport.inFlight(repository, NAME)
                    ? new Inventory(State.RUNNING, report.startedAt(), null, null, 0, List.of(), List.of(), 0, false,
                            previous)
                    : new Inventory(State.FAILED, report.startedAt(), null,
                            "the count stopped before it finished: the node running it went away", 0, List.of(),
                            List.of(), 0, false, previous);
            case FAILED -> new Inventory(State.FAILED, report.startedAt(), report.finishedAt(), report.failure(), 0,
                    List.of(), List.of(), 0, false, previous);
            case DONE -> parse(report);
        };
    }

    /**
     * Start a count of the repository's versions in the background, unless one is already running on any node.
     * Answers whether this call started it; either way the count's progress is what {@link #read} answers next.
     *
     * @param repository the repository's scoped store
     */
    public static boolean start(ArtifactStore repository) throws IOException {
        return StoredReport.compute(repository, NAME, new Tally(repository));
    }

    /**
     * Count the repository's versions now, on the calling thread, and answer the rows the report keeps - the pass
     * {@link #start} runs, for a caller already off the request path.
     */
    public static StoredReport.Rows count(ArtifactStore repository) throws IOException {
        return new Tally(repository).run();
    }

    /** The finished report's rows read back into counts; a row this reader does not recognise is skipped. */
    private static Inventory parse(StoredReport.Report report) {
        long versions = 0;
        List<Count> categories = new ArrayList<>();
        List<Count> licenses = new ArrayList<>();
        for (String row : report.rows()) {
            String[] parts = row.split("\t", 3);
            if (parts.length != 3) {
                continue;
            }
            long count;
            try {
                count = Long.parseLong(parts[2]);
            } catch (NumberFormatException _) {
                continue;
            }
            switch (parts[0]) {
                case VERSIONS -> versions = count;
                case CATEGORY -> categories.add(new Count(parts[1], count));
                case LICENSE -> licenses.add(new Count(parts[1], count));
                default -> {
                }
            }
        }
        return new Inventory(State.DONE, report.startedAt(), report.finishedAt(), null, versions, categories,
                licenses, report.count(), report.count() > report.rows().size(), null);
    }

    /**
     * The pass: a stream of every version the repository holds, each resolved to its licences and folded into the
     * tallies as it arrives, so nothing but the tallies is held. A named class rather than a lambda at the call site,
     * because what {@link #start} hands over is work to run later, not work to do now.
     */
    private static final class Tally implements StoredReport.Pass, RepositoryInventory.ReleaseVisitor {

        private final ArtifactStore repository;
        private final Map<String, Long> categories = new HashMap<>();
        private final Map<String, Long> licenses = new HashMap<>();
        private LicenseDerivation derivation;
        private long versions;

        private Tally(ArtifactStore repository) {
            this.repository = repository;
        }

        @Override
        public StoredReport.Rows run() throws IOException {
            derivation = new LicenseDerivation(repository);
            new StoreRepositoryInventory(repository).releases(this);
            List<String> rows = new ArrayList<>();
            rows.add(VERSIONS + "\t\t" + versions);
            rows(CATEGORY, categories, rows);
            rows(LICENSE, licenses, rows);
            return StoredReport.Rows.of(rows);
        }

        @Override
        public void visit(Release release) throws IOException {
            Set<String> seenCategories = new HashSet<>();
            Set<String> seenLicenses = new HashSet<>();
            for (License license : derivation.resolve(release)) {
                if (seenCategories.add(license.category())) {
                    categories.merge(license.category(), 1L, Long::sum);
                }
                if (license.identified() && seenLicenses.add(license.spdxId())) {
                    licenses.merge(license.spdxId(), 1L, Long::sum);
                }
            }
            versions++;
        }

        /** One kind's rows, most versions first and then by value, so a cut sample keeps the largest counts. */
        private static void rows(String kind, Map<String, Long> counts, List<String> rows) {
            counts.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                            .thenComparing(Map.Entry.comparingByKey()))
                    .forEach(entry -> rows.add(kind + "\t" + entry.getKey() + "\t" + entry.getValue()));
        }
    }
}
