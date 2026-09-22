package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.Signals;
import build.jenesis.repository.store.metering.MeteringArtifactStore;
import build.jenesis.repository.store.metering.StoreOperationsObservability;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The store's operations reach the observability report by name, summed as reads and writes - and every name obeys
 * the signal grammar, because a {@link Metric} with a name that does not is refused and the report then drops the
 * whole source as unavailable: that is how a booted node answered "no store operation counters" to the e2e standard
 * while the metering store had counted every call. The counting needs no Micrometer registry.
 */
class StoreOperationsObservabilityTest {

    @TempDir
    Path root;

    @Test
    void every_operation_is_counted_under_a_well_formed_name_and_summed_as_reads_and_writes() throws IOException {
        ArtifactStore backend = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        ArtifactStore store = new MeteringArtifactStore(backend, null, "filesystem");
        Map<String, Long> before = MeteringArtifactStore.operations();
        long readsBefore = sum(new StoreOperationsObservability().metrics(), "jenreg.store.ops.reads");
        long writesBefore = sum(new StoreOperationsObservability().metrics(), "jenreg.store.ops.writes");

        store.write("blobs/aaaa", new ByteArrayInputStream("bytes".getBytes(StandardCharsets.UTF_8)));
        store.readVersioned("blobs/aaaa");
        store.exists("blobs/aaaa");
        store.list("blobs");
        store.delete("blobs/aaaa");

        List<Metric> metrics = new StoreOperationsObservability().metrics();   // throws on a name the grammar refuses
        assertThat(metrics).extracting(Metric::name)
                .as("the operations by name, camel case split into signal segments")
                .contains("jenreg.store.ops.write", "jenreg.store.ops.read.versioned", "jenreg.store.ops.exists",
                        "jenreg.store.ops.list", "jenreg.store.ops.delete", "jenreg.store.ops.reads",
                        "jenreg.store.ops.writes");
        assertThat(sum(metrics, "jenreg.store.ops.reads") - readsBefore)
                .as("the read, the existence probe and the listing are read-class").isGreaterThanOrEqualTo(3);
        assertThat(sum(metrics, "jenreg.store.ops.writes") - writesBefore)
                .as("the write and the delete are write-class").isGreaterThanOrEqualTo(2);
        assertThat(MeteringArtifactStore.operations().getOrDefault("readVersioned", 0L))
                .isGreaterThan(before.getOrDefault("readVersioned", 0L));
    }

    /**
     * The family breakdown is derived from store keys, so it is the one place a signal name is not written by an
     * author - and the grammar it has to obey does not admit everything a key contains.
     *
     * <p>This is the regression: a date-shaped family ({@code .system/audit/2026-09-09}) cleaned to a name with a
     * {@code 09} segment, {@link Metric} refused it, and the report dropped the whole source - so a node with
     * {@code jenreg.store-families} on answered with NO {@code jenreg.store.ops.*} counters at all and read as a
     * node whose store was never metered. The assertion is on every name rather than on the one that broke,
     * because the next key shape will be a different one.
     */
    @Test
    void a_family_derived_from_any_store_key_still_makes_a_legal_signal_name() throws IOException {
        ArtifactStore backend = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        ArtifactStore store = new MeteringArtifactStore(backend, null, "filesystem");
        MeteringArtifactStore.families(true);
        try {
            for (String key : List.of(".system/audit/2026-09-09/entry", ".system/config/settings",
                    "default/releases/publish/maven/com/acme/lib/1.0/lib-1.0.jar",
                    "default/releases/blobs/0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    "gc/17/refs/ab/batch-1", "default/releases/2026/09/09")) {
                store.write(key, new ByteArrayInputStream("bytes".getBytes(StandardCharsets.UTF_8)));
            }

            List<Metric> metrics = new StoreOperationsObservability().metrics();

            assertThat(metrics).extracting(Metric::name)
                    .as("every name the family breakdown derives is a legal signal name")
                    .allMatch(Signals::valid);
            assertThat(metrics).extracting(Metric::name)
                    .as("and the operation counters are still there - a refused family name used to take them with it")
                    .contains("jenreg.store.ops.reads", "jenreg.store.ops.writes");
            assertThat(metrics).extracting(Metric::name)
                    .as("the date is named as a number rather than dropped or mangled into an illegal segment")
                    .anyMatch(name -> name.startsWith("jenreg.store.family.write.system.audit."));
        } finally {
            MeteringArtifactStore.families(false);
        }
    }

    private static long sum(List<Metric> metrics, String name) {
        return metrics.stream().filter(metric -> metric.name().equals(name)).mapToLong(metric -> (long) metric.value()).sum();
    }
}
