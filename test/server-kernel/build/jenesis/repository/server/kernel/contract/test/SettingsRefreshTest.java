package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;

import build.jenesis.repository.store.Features;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.server.kernel.MaintenanceScheduler;
import build.jenesis.repository.server.kernel.RepositoryProperties;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.server.kernel.SettingsRefresh;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.springframework.core.env.StandardEnvironment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The scheduled convergence pass ({@link SettingsRefresh}) completes the interval re-read: it re-reads the stored
 * settings and, when they changed on another node, re-seeds both runtime surfaces a write elsewhere would otherwise
 * leave stale here - the live {@link LiveConfig} snapshot (for live keys) and the mutable environment's
 * stored-settings source (for keys read through a {@code jenreg.*} lookup). A malformed stored value is
 * rolled back to the last good live configuration and the pass never throws out of the scheduler.
 */
class SettingsRefreshTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private StandardEnvironment environment;
    private Settings settings;
    private LiveConfig live;
    private SettingsRefresh refresh;

    @BeforeEach
    void setUp() throws IOException {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        environment = new StandardEnvironment();
        settings = new Settings(store);
        // The live config reads a plugin key's file/env fallback through the environment, exactly as the deployment
        // wires it, so a cleared lookup-consumed key must stop shadowing the file default once the pass re-seeds it.
        live = new LiveConfig(settings, new RepositoryProperties(), AdvisorySource.none(),
                Features.namespaced(environment::getProperty));
        // A no-op scheduler (no task installed): this test exercises the LiveConfig/environment convergence, and the
        // maintenance re-resolve is covered by MaintenanceSchedulerTest.
        MaintenanceScheduler maintenance = new MaintenanceScheduler(null, store, List.of(),
                _ -> null, Duration.ofMinutes(10), null);
        refresh = new SettingsRefresh(settings, live, environment, maintenance);
    }

    @Test
    void a_second_settings_instance_converges_on_refresh() throws IOException {
        Settings other = new Settings(store);
        other.set("demo", "true");   // another node writes

        assertThat(settings.getOrDefault("demo", "false")).as("not seen before the interval fires").isEqualTo("false");
        settings.refresh();
        assertThat(settings.getOrDefault("demo", "false")).as("the re-read converged this instance").isEqualTo("true");
    }

    @Test
    void the_interval_refresh_converges_a_live_key_into_the_live_config() throws IOException {
        assertThat(live.defaultTenant()).as("the file default before any override").isEqualTo("default");

        new Settings(store).set("default-tenant", "acme");   // another node writes
        assertThat(live.defaultTenant()).as("not yet converged before the interval fires").isEqualTo("default");

        refresh.refresh();
        assertThat(live.defaultTenant()).as("the pass re-read the store and rebuilt the live config").isEqualTo("acme");
    }

    @Test
    void the_interval_refresh_converges_a_lookup_consumed_key_into_the_environment() throws IOException {
        assertThat(environment.getProperty("jenreg.default-tenant")).isNull();

        new Settings(store).set("default-tenant", "acme");   // another node writes
        refresh.refresh();
        assertThat(environment.getProperty("jenreg.default-tenant"))
                .as("the environment's stored-settings source converged for a lookup-consumed key").isEqualTo("acme");

        new Settings(store).set("default-tenant", null);     // another node clears it
        refresh.refresh();
        assertThat(environment.getProperty("jenreg.default-tenant"))
                .as("a cleared key stops shadowing the file default rather than leaving a stale value").isNull();
    }

    @Test
    void a_malformed_stored_value_is_rolled_back_to_the_last_good_config_and_the_pass_never_throws() throws IOException {
        new Settings(store).set("immaturity-hold-days", "7");
        refresh.refresh();
        assertThat(live.holdDays()).isEqualTo(7);

        new Settings(store).set("vulnerability-threshold", "NOT-A-SEVERITY");   // another node writes a bad value
        assertThatCode(refresh::refresh).as("the scheduled re-read logs and keeps serving, never throwing")
                .doesNotThrowAnyException();
        assertThat(live.holdDays()).as("the rebuild committed nothing, so the last good config stands").isEqualTo(7);

        // A later good write still converges: recording the bad state as converged does not wedge the pass.
        new Settings(store).set("vulnerability-threshold", "HIGH");
        new Settings(store).set("default-tenant", "acme");
        refresh.refresh();
        assertThat(live.defaultTenant()).as("the corrected settings rebuild cleanly and apply").isEqualTo("acme");
    }
}
