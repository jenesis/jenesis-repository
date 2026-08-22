package build.jenesis.repository.test;

import build.jenesis.repository.server.RebuildScheduler;
import build.jenesis.repository.server.RepositoryAutoConfiguration;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.walk.WalkConsumer;
import build.jenesis.repository.walk.WalkPass;
import build.jenesis.repository.walk.WalkProvider;
import module org.junit.jupiter.api;
import org.springframework.core.env.StandardEnvironment;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The free edition drives the shared rebuild pass itself: the auto-configuration schedules {@link RebuildScheduler}
 * over the deployment's one repository, daily unless {@code jenreg.rebuild.interval} says otherwise, inert without a
 * walk or a consumer, and a driven pass streams every retained pointer to every consumer - so a consumer's view
 * converges without an embedder and without a republish. Before this, {@code RebuildPass} shipped in the free core
 * with nothing to run it.
 */
class RebuildSchedulerTest {

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    @Test
    void the_auto_configuration_schedules_the_pass_daily_over_the_deployments_repository() throws Exception {
        RepositoryProperties properties = new RepositoryProperties();
        try (RebuildScheduler scheduler = new RepositoryAutoConfiguration(new StandardEnvironment())
                .rebuildScheduler(store, properties, new StandardEnvironment())) {
            assertThat(scheduler.interval()).as("a day between passes unless configured").isEqualTo(Duration.ofDays(1));
            scheduler.start();
            assertThat(scheduler.status().name()).isEqualTo("jenreg.rebuild.pass");
            assertThat(new RebuildScheduler.Observability().taskStatuses())
                    .as("the discovered observability reports the started driver").hasSize(1);
        }
    }

    @Test
    void the_interval_setting_reads_durations_and_off() {
        assertThat(RebuildScheduler.interval(null)).isEqualTo(Duration.ofDays(1));
        assertThat(RebuildScheduler.interval("PT6H")).isEqualTo(Duration.ofHours(6));
        assertThat(RebuildScheduler.interval("30m")).isEqualTo(Duration.ofMinutes(30));
        assertThat(RebuildScheduler.interval("off")).isZero();
        assertThat(RebuildScheduler.interval("0")).isZero();
        assertThatThrownBy(() -> RebuildScheduler.interval("soon")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jenreg.rebuild.interval");
    }

    @Test
    void a_driven_pass_streams_every_retained_pointer_to_every_consumer() throws Exception {
        ArtifactStore repository = store.scope("default").scope("releases");
        Publication publication = new Publication(repository);
        publication.link("/maven/com/acme/lib/1.0/lib-1.0.jar",
                publication.storeBlob(new ByteArrayInputStream("a".getBytes(StandardCharsets.UTF_8))));
        publication.link("/raw/files/readme.txt",
                publication.storeBlob(new ByteArrayInputStream("b".getBytes(StandardCharsets.UTF_8))));
        List<String> seen = new CopyOnWriteArrayList<>();
        WalkConsumer consumer = new WalkConsumer() {
            @Override
            public String name() {
                return "recording";
            }

            @Override
            public void onRetained(ArtifactDescriptor artifact, ArtifactStore scoped) {
                seen.add(artifact.path());
            }
        };
        try (RebuildScheduler scheduler = new RebuildScheduler(repository, key -> null,
                WalkProvider.resolve(key -> null), List.of(consumer))) {
            assertThat(scheduler.active()).isTrue();
            Optional<WalkPass> pass = scheduler.runNow();

            assertThat(pass).isPresent();
            assertThat(pass.get().complete()).as("one worker finishes the pass").isTrue();
            assertThat(seen).containsExactlyInAnyOrder("/maven/com/acme/lib/1.0/lib-1.0.jar", "/raw/files/readme.txt");
            assertThat(scheduler.status().lastRun()).isNotNull();
            assertThat(scheduler.status().outcome()).isEqualTo("complete");
        }
    }

    @Test
    void the_driver_is_inert_without_a_consumer_or_when_switched_off() throws Exception {
        try (RebuildScheduler none = new RebuildScheduler(store, key -> null, WalkProvider.resolve(key -> null),
                List.of())) {
            assertThat(none.active()).isFalse();
            assertThat(none.runNow()).isEmpty();
            assertThat(none.status().outcome()).isEqualTo("no walk consumer discovered");
        }
        try (RebuildScheduler off = new RebuildScheduler(store,
                key -> RebuildScheduler.INTERVAL.equals(key) ? "off" : null, WalkProvider.resolve(key -> null),
                List.of(new WalkConsumer() {
                    @Override
                    public String name() {
                        return "idle";
                    }

                    @Override
                    public void onRetained(ArtifactDescriptor artifact, ArtifactStore scoped) {
                    }
                }))) {
            assertThat(off.active()).isFalse();
            assertThat(off.status().outcome()).isEqualTo("off");
        }
    }
}
