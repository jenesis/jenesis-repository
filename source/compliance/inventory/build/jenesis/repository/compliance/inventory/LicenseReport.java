package build.jenesis.repository.compliance.inventory;

import module java.base;
import build.jenesis.repository.cleanup.Release;
import build.jenesis.repository.cleanup.RepositoryInventory;
import build.jenesis.repository.cleanup.StoredReport;
import build.jenesis.repository.compliance.ComplianceSettings;
import build.jenesis.repository.compliance.License;
import build.jenesis.repository.compliance.LicenseTable;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The licence inventory of one repository: how many versions declare each licence category and each SPDX id, counted on
 * request by a pass over every version and read back as a {@link StoredReport}.
 *
 * <p>Every surface reaches this one implementation - {@code GET /api/licenses}, the console's Licenses screen and
 * {@code jenrepo licenses} - so they cannot disagree. A request never counts: {@link #read} is a point read of the
 * stored report (and of its run's lease while running), and {@link #start} records the count as running, starts it on
 * its own thread and answers at once whether it started one. A surface polls while a count runs.
 *
 * <p><b>What is counted.</b> Every version once: {@link LicenseDerivation#resolve} gives its licences, and it counts
 * once towards each distinct category and SPDX id among them. An unidentified licence, or none declared, counts towards
 * {@code unknown} and no SPDX id, so every version is in some category. The unit is a version: ten versions under one
 * licence count ten.
 *
 * <p><b>What is stored.</b> One tab-separated row per count: {@code versions} with the total, then {@code category}
 * rows, then {@code license} rows, each with its value and count, most versions first. The first
 * {@link StoredReport#SAMPLE} rows are kept with the total, so more distinct licences than that read as cut short; the
 * handful of categories always fit.
 *
 * <p>It needs no index: the count is the same with full-text search on or off. Only a drill-down to the versions behind
 * a count needs the index.
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

    /** The inventory as stored: its state, start and finish ({@code null} until finished), why it failed, how many
     *  versions it counted, the counts per category and SPDX id, how many rows the count produced, whether some were
     *  left out, and - while running or after a failure - the {@code previous} finished count a surface keeps
     *  showing. */
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
     * The repository's inventory as the last count left it, {@link State#NOT_COUNTED} when none was asked for. A report
     * saying it runs while no run holds its lease was left by a run whose node went away, and reads as failed, so a
     * surface does not poll it for ever.
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
     * Start a count of the repository's versions in the background unless one runs on any node; answers whether this
     * call started it. Either way {@link #read} answers its progress.
     *
     * @param repository the repository's scoped store
     */
    public static boolean start(ArtifactStore repository) throws IOException {
        return StoredReport.compute(repository, NAME, new Tally(repository));
    }

    /** Count the repository's versions now, on the calling thread, and answer the rows the report keeps -
     *  {@link #start}'s pass, for a caller already off the request path. */
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

    /** The pass: every version streamed, resolved to its licences and folded into the tallies as it arrives, so only
     *  the tallies are held. A named class because {@link #start} hands over work to run later. */
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
            // The table is read on the count's own thread from the deployment's compliance settings; a value that does
            // not parse fails the count, naming the row.
            derivation = new LicenseDerivation(repository, LicenseTable.of(ComplianceSettings.lookup(repository)));
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
