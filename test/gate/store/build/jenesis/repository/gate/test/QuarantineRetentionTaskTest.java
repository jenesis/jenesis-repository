package build.jenesis.repository.gate.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.gate.QuarantineLog;
import build.jenesis.repository.gate.store.QuarantineRetentionTask;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.maintenance.UnitFailures;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scheduled quarantine-log retention over a real filesystem store, driven through {@link QuarantineRetentionTask}'s
 * per-repository hook: the two dials are read live off the pass configuration, and the parse of each degrades rather
 * than throwing out of the pass - an unparseable {@code quarantine-log-retention} falls back to the product default
 * (so pruning still happens), a blank or {@code PT0S} retention disables age pruning, an unparseable
 * {@code quarantine-log-cap} reads as off ({@code 0}), and with both dials off the hook prunes nothing at all.
 */
class QuarantineRetentionTaskTest {

    private static final Instant OLD = Instant.parse("2000-01-01T00:00:00Z");
    private static final Instant NEW = Instant.parse("2001-01-01T00:00:00Z");
    private static final Instant NOW = Instant.parse("3000-01-01T00:00:00Z");
    private static final String OLD_PATH = "/maven/org/old/lib/1.0/lib-1.0.pom";
    private static final String NEW_PATH = "/maven/org/new/lib/1.0/lib-1.0.pom";

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() throws IOException {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        QuarantineLog log = new QuarantineLog(store);
        log.record(OLD, OLD_PATH, "org.old:lib:1.0", Verdict.REJECT, List.of("old"));
        log.record(NEW, NEW_PATH, "org.new:lib:1.0", Verdict.REJECT, List.of("recent"));
    }

    @Test
    void both_dials_off_prunes_nothing() throws IOException {
        new QuarantineRetentionTask(Duration.ofHours(1)).repository(context(key -> switch (key) {
            case "quarantine-log-retention" -> "";                    // blank disables age pruning
            case "quarantine-log-cap" -> "";                          // blank disables the count cap
            default -> null;
        }));
        assertThat(new QuarantineLog(store).events()).as("both dials off: the hook returns before any prune").hasSize(2);
    }

    @Test
    void a_zero_retention_disables_age_pruning() throws IOException {
        new QuarantineRetentionTask(Duration.ofHours(1)).repository(context(key ->
                "quarantine-log-retention".equals(key) ? "PT0S" : null));
        assertThat(new QuarantineLog(store).events()).as("PT0S disables age pruning, and no cap is set").hasSize(2);
    }

    @Test
    void an_unparseable_retention_falls_back_to_the_default_and_still_prunes() throws IOException {
        // A garbled live dial (an env/property value bypasses the DURATION write-validation) must degrade to the
        // product default - a positive age - never throw a DateTimeParseException out of the pass and silently stop
        // every prune. NOW is a thousand years past both rows, so any positive default ages them both out; a
        // blank/disabled read would instead have kept them (the both-dials-off cell), which distinguishes the branch.
        new QuarantineRetentionTask(Duration.ofHours(1)).repository(context(key ->
                "quarantine-log-retention".equals(key) ? "not-a-duration" : null));
        assertThat(new QuarantineLog(store).events())
                .as("the unparseable retention fell back to the positive default, so the aged rows were pruned")
                .isEmpty();
    }

    @Test
    void an_unparseable_cap_reads_as_off() throws IOException {
        // maxCount forgives a bad count as 0 (off); with retention also unset this leaves both dials off, so nothing
        // is pruned - the parse degraded to off rather than throwing.
        new QuarantineRetentionTask(Duration.ofHours(1)).repository(context(key ->
                "quarantine-log-cap".equals(key) ? "not-a-number" : ""));
        assertThat(new QuarantineLog(store).events()).as("an unparseable cap reads as off, so nothing is pruned")
                .hasSize(2);
    }

    @Test
    void a_positive_cap_prunes_beyond_the_newest() throws IOException {
        new QuarantineRetentionTask(Duration.ofHours(1)).repository(context(key -> switch (key) {
            case "quarantine-log-retention" -> "";
            case "quarantine-log-cap" -> "1";
            default -> null;
        }));
        assertThat(new QuarantineLog(store).events()).as("a cap of 1 keeps only the newest row")
                .extracting(QuarantineLog.Event::coordinate).containsExactly("org.new:lib:1.0");
    }

    /** A minimal maintenance context over the test's own store at a fixed wall clock, mirroring {@code StagingReapTest}. */
    private RepositoryContext context(UnaryOperator<String> config) {
        return new RepositoryContext() {
            @Override
            public UnitFailures failures(String work, String consequence) {
                return new UnitFailures(work, consequence);
            }

            @Override
            public String tenant() {
                return "acme";
            }

            @Override
            public String repository() {
                return "releases";
            }

            @Override
            public ArtifactStore store() {
                return store;
            }

            @Override
            public UnaryOperator<String> config() {
                return config;
            }

            @Override
            public Instant now() {
                return NOW;
            }

            @Override
            public void gauge(String name, String description, Map<String, String> tags, double value) {
            }
        };
    }
}
