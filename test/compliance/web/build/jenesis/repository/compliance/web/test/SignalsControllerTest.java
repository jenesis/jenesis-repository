package build.jenesis.repository.compliance.web.test;

import module java.base;
import module org.junit.jupiter.api;
import org.junit.jupiter.api.io.TempDir;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.Freshness;
import build.jenesis.repository.compliance.RefreshableSource;
import build.jenesis.repository.compliance.scan.SignalRefreshTask;
import build.jenesis.repository.compliance.scan.SignalStatus;
import build.jenesis.repository.compliance.web.SignalsController;
import build.jenesis.repository.compliance.web.SignalsScreenController;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.springframework.ui.ExtendedModelMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The signal sources as an operator reads them: before the refresh pass has recorded anything the answer says so
 * rather than listing no source, and after it the API and the console's screen read back the one record the pass
 * wrote - each source's freshness, and a mirroring feed's copies.
 */
class SignalsControllerTest {

    private static final Instant DRAWN = Instant.parse("2026-10-05T00:00:00Z");

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
    }

    @Test
    void before_the_pass_has_run_the_answer_is_not_recorded_rather_than_empty() throws IOException {
        SignalsController.SignalsView view = new SignalsController(store).signals();

        assertThat(view.state()).isEqualTo("not-recorded");
        assertThat(view.recorded()).isNull();
        assertThat(view.sources()).isEmpty();
    }

    @Test
    void the_api_and_the_screen_read_back_what_the_pass_recorded() throws IOException {
        SignalRefreshTask task = new SignalRefreshTask(Duration.ofMinutes(5),
                Map.<String, RefreshableSource>of("osv-mirror", new Mirror()), Map.of(),
                () -> SignalStatus.space(store));
        task.completed(Instant.now());

        SignalsController.SignalsView view = new SignalsController(store).signals();
        assertThat(view.state()).isEqualTo("recorded");
        assertThat(view.recorded()).isNotNull();
        assertThat(view.sources()).singleElement().satisfies(source -> {
            assertThat(source.name()).isEqualTo("osv-mirror");
            assertThat(source.refreshed()).isEqualTo(DRAWN);
            assertThat(source.authoritative()).isTrue();
            assertThat(source.copies()).containsExactly(new AdvisorySource.Mirror.Copy("Maven", DRAWN, DRAWN),
                    new AdvisorySource.Mirror.Copy("npm", null, null));
        });

        ExtendedModelMap model = new ExtendedModelMap();
        assertThat(new SignalsScreenController(store).signals(model)).isEqualTo("compliance/signals");
        assertThat(model.get("signals")).as("the screen renders the record the API answers").isEqualTo(view);
    }

    /** A mirror keeping two ecosystems, one built and one waiting for its first build. */
    private static final class Mirror implements AdvisorySource.Mirror {

        @Override
        public List<Copy> copies() {
            return List.of(new Copy("Maven", DRAWN, DRAWN), new Copy("npm", null, null));
        }

        @Override
        public void mirror(Set<String> ecosystems) {
        }

        @Override
        public Freshness refresh() {
            return Freshness.at(DRAWN);
        }

        @Override
        public Optional<String> snapshot() {
            return Optional.empty();
        }

        @Override
        public List<Advisory> advisories(String ecosystem, String coordinate, String version) {
            return List.of();
        }

        @Override
        public Set<String> ecosystems() {
            return Set.of("Maven", "npm");
        }

        @Override
        public Freshness freshness() {
            return Freshness.at(DRAWN);
        }
    }
}
