package build.jenesis.repository.gate.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.gate.QuarantineLog;
import build.jenesis.repository.gate.store.ReviewQueue;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The review queue's pages keep a version whole: a page cut between the files of one version reads on to the last of
 * them, so a version is reviewed - released or discarded - with every file it holds, and the next page starts after it.
 */
class ReviewQueueTest {

    private static final Instant WHEN = Instant.parse("2026-10-01T00:00:00Z");

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("releases");
    }

    /** Hold {@code path} for review as the gate does: the review pointer, and the log row naming its coordinate. */
    private void hold(String path, String coordinate) throws IOException {
        Publication publication = new Publication(store);
        publication.link("/quarantine" + path,
                publication.storeBlob(new ByteArrayInputStream(path.getBytes(StandardCharsets.UTF_8))));
        if (coordinate != null) {
            new QuarantineLog(store).record(WHEN, path, coordinate, Verdict.QUARANTINE, List.of("held for review"), List.of());
        }
    }

    private static List<String> paths(ReviewQueue.Page page) {
        return page.rows().stream().map(ReviewQueue.Row::path).sorted().toList();
    }

    @Test
    void a_page_cut_between_the_files_of_a_version_reads_on_to_the_last_of_them() throws IOException {
        hold("/maven/org/acme/a/1.0/a-1.0.jar", "org.acme:a:1.0");
        hold("/maven/org/acme/a/1.0/a-1.0.pom", "org.acme:a:1.0");
        hold("/maven/org/acme/b/1.0/b-1.0.jar", "org.acme:b:1.0");
        hold("/maven/org/acme/b/1.0/b-1.0.pom", "org.acme:b:1.0");
        hold("/maven/org/acme/b/1.0/b-1.0-sources.jar", "org.acme:b:1.0");
        hold("/maven/org/acme/c/1.0/c-1.0.jar", "org.acme:c:1.0");

        ReviewQueue.Page first = ReviewQueue.page(store, null, 3);
        assertThat(paths(first)).as("three asked for, and the rest of the version the third is a file of")
                .containsExactly("/maven/org/acme/a/1.0/a-1.0.jar", "/maven/org/acme/a/1.0/a-1.0.pom",
                        "/maven/org/acme/b/1.0/b-1.0-sources.jar", "/maven/org/acme/b/1.0/b-1.0.jar",
                        "/maven/org/acme/b/1.0/b-1.0.pom");
        assertThat(first.next()).isNotNull();

        ReviewQueue.Page second = ReviewQueue.page(store, first.next(), 3);
        assertThat(paths(second)).as("the next page starts after the version, not inside it")
                .containsExactly("/maven/org/acme/c/1.0/c-1.0.jar");
        assertThat(second.next()).isNull();
    }

    @Test
    void a_hold_whose_log_row_is_lost_names_no_version_and_is_not_read_on_from() throws IOException {
        hold("/maven/org/acme/a/1.0/a-1.0.jar", null);
        hold("/maven/org/acme/a/1.0/a-1.0.pom", "org.acme:a:1.0");

        ReviewQueue.Page first = ReviewQueue.page(store, null, 1);
        assertThat(paths(first)).containsExactly("/maven/org/acme/a/1.0/a-1.0.jar");
        assertThat(first.rows().getFirst().reasons()).containsExactly("audit row missing");
        assertThat(paths(ReviewQueue.page(store, first.next(), 1)))
                .containsExactly("/maven/org/acme/a/1.0/a-1.0.pom");
    }

    @Test
    void a_release_held_for_an_advisory_is_noted_and_a_cached_copy_or_a_policy_hold_is_not() throws IOException {
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        holdFor("/maven/org/acme/a/1.0/a-1.0.jar", "org.acme:a:1.0", ComplianceGate.KNOWN_EXPLOITED_RULE);
        inventory.record("/maven/org/acme/a/1.0/a-1.0.jar", WHEN);
        holdFor("/maven/org/acme/b/1.0/b-1.0.jar", "org.acme:b:1.0", ComplianceGate.VULNERABILITY_RULE);
        inventory.cache("Maven", "org.acme:b", "1.0", "https://upstream.example/", WHEN);
        holdFor("/maven/org/acme/c/1.0/c-1.0.jar", "org.acme:c:1.0", ComplianceGate.DENY_LIST_RULE);
        inventory.record("/maven/org/acme/c/1.0/c-1.0.jar", WHEN);

        Map<String, List<String>> notes = ReviewQueue.page(store, null, 10).rows().stream()
                .collect(Collectors.toMap(ReviewQueue.Row::path, ReviewQueue.Row::notes));

        assertThat(notes.get("/maven/org/acme/a/1.0/a-1.0.jar")).as("a release held for an advisory")
                .containsExactly(ReviewQueue.RELEASE_UNDER_ADVISORY);
        assertThat(notes.get("/maven/org/acme/b/1.0/b-1.0.jar")).as("a cached copy still answers to the feeds")
                .isEmpty();
        assertThat(notes.get("/maven/org/acme/c/1.0/c-1.0.jar")).as("a deny-list hold is no advisory").isEmpty();
    }

    /** As {@link #hold}, with the rule the log row names. */
    private void holdFor(String path, String coordinate, String rule) throws IOException {
        Publication publication = new Publication(store);
        publication.link("/quarantine" + path,
                publication.storeBlob(new ByteArrayInputStream(path.getBytes(StandardCharsets.UTF_8))));
        new QuarantineLog(store).record(WHEN, path, coordinate, Verdict.QUARANTINE, List.of("held"), List.of(rule));
    }
}
