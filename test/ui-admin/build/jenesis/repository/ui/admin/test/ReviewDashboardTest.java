package build.jenesis.repository.ui.admin.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cleanup.StoredReport;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.compliance.web.ReviewDashboard;
import build.jenesis.repository.gate.QuarantineLog;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.ui.DashboardContributor;
import build.jenesis.repository.ui.DashboardPanel;
import io.micrometer.observation.ObservationRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The review panel counts what every repository holds off the request path: the first landing starts the count and
 * says so, and the next one reads the stored result - a version's files counted once, the repositories holding most
 * first, each opening its quarantine screen.
 */
class ReviewDashboardTest {

    private static final String TENANT = "acme";

    @TempDir
    Path root;

    @Test
    void the_first_landing_starts_the_count_and_a_later_one_reads_it() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        hold(store.scope(TENANT).scope("libs"), "org.acme:app:1.0", "/maven/org/acme/app/1.0/app-1.0.jar",
                "/maven/org/acme/app/1.0/app-1.0.pom");
        hold(store.scope(TENANT).scope("libs"), "org.acme:lib:2.0", "/maven/org/acme/lib/2.0/lib-2.0.jar");
        hold(store.scope(TENANT).scope("tools"), "org.acme:cli:1.0", "/maven/org/acme/cli/1.0/cli-1.0.jar");
        store.scope(TENANT).scope("empty").write("marker", new ByteArrayInputStream(new byte[0]));
        ReviewDashboard dashboard = new ReviewDashboard(store, () -> TENANT, ObservationRegistry.NOOP);
        DashboardContributor.Viewer viewer = new DashboardContributor.Viewer(TENANT, false);

        List<DashboardPanel> first = dashboard.panels(viewer);
        assertThat(first).as("nothing is counted on the request, and no scan has ranked anything").singleElement()
                .satisfies(panel -> {
                    assertThat(panel.figure()).isEmpty();
                    assertThat(panel.refreshing()).isTrue();
                });

        ArtifactStore space = store.scope(TENANT).scope(Scopes.SYSTEM);
        StoredReport.awaitSettled(space, "dashboard-review", Duration.ofMinutes(1)).orElseThrow();
        List<DashboardPanel> counted = dashboard.panels(viewer);
        assertThat(counted).as("no vulnerability scan has run, so only the review panel speaks").hasSize(1);
        DashboardPanel held = counted.getFirst();
        assertThat(held.title()).isEqualTo("Held for review");
        assertThat(held.href()).as("it opens where most is held").isEqualTo("/ui/repositories/libs/quarantine");
        assertThat(held.figure()).as("a version's files count once").isEqualTo("3");
        assertThat(held.tone()).isEqualTo(DashboardPanel.Tone.ATTENTION);
        assertThat(held.refreshing()).as("a fresh count is not asked for again").isFalse();
        assertThat(held.lines()).containsExactly(
                new DashboardPanel.Line("libs", "2 versions", "/ui/repositories/libs/quarantine"),
                new DashboardPanel.Line("tools", "1 version", "/ui/repositories/tools/quarantine"));
        assertThat(held.asOf()).as("it says when the figures were counted, for the page to show in local time")
                .isPresent();
        assertThat(held.note()).as("and nothing else, with no count running and none failed").isEmpty();
        assertThat(StoredReport.inFlight(space, "dashboard-review")).isFalse();
    }

    private static void hold(ArtifactStore repository, String coordinate, String... paths) throws IOException {
        Publication publication = new Publication(repository);
        QuarantineLog log = new QuarantineLog(repository);
        for (String path : paths) {
            String hash = publication.storeBlob(new ByteArrayInputStream(path.getBytes(StandardCharsets.UTF_8)));
            publication.link("/quarantine" + path, hash);
            log.record(Instant.now(), path, coordinate, Verdict.QUARANTINE, List.of("held"), List.of());
        }
    }
}
