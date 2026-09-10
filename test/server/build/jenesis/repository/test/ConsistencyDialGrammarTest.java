package build.jenesis.repository.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.server.NodeConsistency;
import build.jenesis.repository.server.NodeFingerprintPublisher;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every {@code jenreg.consistency.*} duration reads the deployment's one grammar, and a value it cannot read stops
 * the node rather than being swallowed.
 *
 * <p>These were the only durations in the product spelled as bare millisecond counts, and they swallowed anything
 * else: an operator who typed {@code 5m} got the default, silently, and a fleet then judged nodes late or dead on a
 * window nobody chose. Five of the six were brought into the shared grammar; the heartbeat was missed, so one
 * family carried two grammars and the surviving one still swallowed - and nothing failed, because no test drove the
 * readers and the only caller in this repository sets them from a container-gated kit.
 *
 * <p>That is what this suite is for. It drives the readers rather than the settings validator (which
 * {@code DurationGrammarTest} covers) and asserts both halves: the suffixed and ISO-8601 forms are read as the
 * durations they name, and a value in neither throws naming the key.
 */
class ConsistencyDialGrammarTest {

    @TempDir
    Path root;

    private UnaryOperator<String> config(Map<String, String> values) {
        return values::get;
    }

    @Test
    void a_sweep_interval_is_read_in_the_suffixed_grammar_every_other_dial_takes() {
        var settings = NodeConsistency.settingsFrom(config(Map.of(
                "jenreg.consistency.sweep-interval", "2s",
                "jenreg.consistency.dead-after", "5m")));
        assertThat(settings.sweepIntervalMillis()).isEqualTo(Duration.ofSeconds(2).toMillis());
        assertThat(settings.deadAfterMillis()).isEqualTo(Duration.ofMinutes(5).toMillis());
    }

    @Test
    void a_dial_that_cannot_be_read_stops_the_node_and_names_itself() {
        // The §9 half. A swallowed value is worse than a refused one here: the node runs, and it runs on a window
        // the operator did not choose, which is what a fleet judges its peers late and dead on.
        assertThatThrownBy(() -> NodeConsistency.settingsFrom(config(Map.of(
                "jenreg.consistency.sweep-interval", "2000"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jenreg.consistency.sweep-interval")
                .hasMessageContaining("2000");
    }

    @Test
    void the_heartbeat_is_in_the_same_grammar_as_its_five_siblings() {
        // The dial that was missed. A bare millisecond count is refused here exactly as it is for the other five -
        // one family, one grammar - and the message says what to write instead.
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        NodeConsistency consistency = new NodeConsistency(store, NodeConsistency.settingsFrom(config(Map.of())));
        assertThatThrownBy(() -> new NodeFingerprintPublisher(consistency, store, config(Map.of(
                "jenreg.consistency.enabled", "true",
                "jenreg.consistency.heartbeat", "2000"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jenreg.consistency.heartbeat")
                .hasMessageContaining("2000");
    }

    @Test
    void a_heartbeat_in_the_shared_grammar_is_accepted() {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        NodeConsistency consistency = new NodeConsistency(store, NodeConsistency.settingsFrom(config(Map.of())));
        assertThat(new NodeFingerprintPublisher(consistency, store, config(Map.of(
                "jenreg.consistency.enabled", "true",
                "jenreg.consistency.heartbeat", "2s"))))
                .as("the spelling the fleet kit and every other duration dial use")
                .isNotNull();
    }
}
