package build.jenesis.repository.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.server.NodeConsistency;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Tenants;
import build.jenesis.repository.server.NodeFingerprintPublisher;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import build.jenesis.repository.server.ConsistencyReport;

/**
 * What the node-consistency fingerprint folds, driven through the real publisher.
 *
 * <p>{@code MUST_MATCH} names "the settings that must be byte-for-byte identical on every node, so a differing
 * value on any of them is a real split". Nothing asserted its membership, which is how the routed tenant came to
 * be folded by one edition's publisher and not the other's while there were two: two nodes routing to different
 * default tenants serve different repositories for the same request and the fingerprint called them converged.
 *
 * <p>Driven end to end rather than over the map: two publishers over one store, configs differing in exactly one
 * setting, and the question put to {@code ConsistencyReport} - the surface an operator actually reads. A setting
 * dropped from the fold set makes the report say "converged" here, which is the defect this names.
 */
class NodeFingerprintFoldTest {

    private static final ConsistencyReport.Settings SETTINGS = ConsistencyReport.Settings.defaults();

    /** Every must-match setting at an agreed value, so a caller changes exactly the one it is asking about. */
    private static Map<String, String> agreed(String node) {
        Map<String, String> config = new TreeMap<>();
        config.put("jenreg.consistency.node-id", node);
        config.put("jenreg.store", "filesystem");
        config.put("jenreg.tenant", "acme");
        config.put("jenreg.repository", "main");
        config.put("jenreg.operator-tenant", "operator");
        config.put("jenreg.default-tenant", "acme");
        config.put("jenreg.default-repository", "main");
        config.put("jenreg.auth", "false");
        config.put("jenreg.read-only", "false");
        return config;
    }

    private static boolean divergesOn(Path root, String setting, String other) throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        Map<String, String> a = agreed("node-a");
        Map<String, String> b = agreed("node-b");
        b.put(setting, other);
        for (Map<String, String> config : List.of(a, b)) {
            try (NodeFingerprintPublisher publisher = new NodeFingerprintPublisher(
                    new NodeConsistency(store, SETTINGS), Tenants.fixed("acme"), config::get)) {
                publisher.publishQuietly();
            }
        }
        return !new NodeConsistency(store, SETTINGS).report(System.currentTimeMillis()).converged();
    }

    @Test
    void two_nodes_agreeing_on_every_folded_setting_converge(@TempDir Path root) throws IOException {
        // The vacuity guard: if this said "diverged", every row below would pass for the wrong reason.
        assertThat(divergesOn(root, "jenreg.consistency.node-id", "node-b"))
                .as("nodes that agree on every must-match setting converge; only the node id differs")
                .isFalse();
    }

    @TestFactory
    Stream<DynamicTest> every_must_match_setting_makes_two_nodes_diverge(@TempDir Path root) {
        Map<String, String> differing = new LinkedHashMap<>();
        differing.put("jenreg.store", "s3");
        differing.put("jenreg.tenant", "globex");
        differing.put("jenreg.repository", "other-main");
        differing.put("jenreg.operator-tenant", "other-operator");
        differing.put("jenreg.default-tenant", "globex");
        differing.put("jenreg.default-repository", "other-main");
        differing.put("jenreg.auth", "true");
        differing.put("jenreg.read-only", "true");
        AtomicInteger cell = new AtomicInteger();
        return differing.entrySet().stream().map(entry -> DynamicTest.dynamicTest(entry.getKey(), () ->
                assertThat(divergesOn(root.resolve("cell-" + cell.incrementAndGet()), entry.getKey(),
                        entry.getValue()))
                        .as("%s is a must-match setting: two nodes that disagree on it are a real split, and a "
                                + "fingerprint that folds it says so. If this row is red the setting is missing "
                                + "from NodeFingerprintPublisher.MUST_MATCH", entry.getKey())
                        .isTrue()));
    }
}
