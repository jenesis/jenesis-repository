package build.jenesis.repository.gc.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.gc.GarbageCollector;
import build.jenesis.repository.gc.GarbageCollectorProvider;
import build.jenesis.repository.gc.walk.CollectionSettingsContributor;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Known;
import build.jenesis.repository.store.Publication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The collector's grace ships at two hours, and a deployment that names none gets it.
 *
 * <p>Every suite that reclaims inside its own window names {@code jenreg.gc.grace=PT0S}, as it should, which leaves
 * the shipped value the one no suite reads - so this one does. The catalogue leg reads what the settings screen and
 * the generated reference render; the second leg has the content, asking a collector resolved with nothing set to
 * run its confirming pass over a blob condemned a moment ago, and requiring it spared - with the dial at zero, as the
 * control, the same pass deletes it.
 */
class GcGraceDefaultTest {

    @TempDir
    Path root;

    @Test
    void the_declared_grace_is_two_hours() {
        Setting grace = new CollectionSettingsContributor().settings().stream()
                .filter(setting -> "gc.grace".equals(setting.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("gc.grace is not in the collector's setting catalogue"));

        assertThat(grace.defaultValue()).isEqualTo("PT2H").isEqualTo(GarbageCollector.defaultGrace().toString());
    }

    @Test
    void a_collector_with_no_grace_named_spares_a_blob_condemned_a_moment_ago() throws IOException {
        assertThat(confirmingPassDeletes(root.resolve("unset"), key -> null))
                .as("the shipped grace holds a freshly condemned blob past the next pass").isFalse();
        assertThat(confirmingPassDeletes(root.resolve("zero"), key -> "gc.grace".equals(key) ? "PT0S" : null))
                .as("the control: with no grace the confirming pass deletes it").isTrue();
    }

    private static boolean confirmingPassDeletes(Path at, UnaryOperator<String> config) throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? at.toString() : null);
        String orphan = new Publication(store).storeBlob(
                new ByteArrayInputStream("unreferenced".getBytes(StandardCharsets.UTF_8)));
        GarbageCollector collector = GarbageCollectorProvider.resolve(config).orElseThrow();
        collector.collect(store, Known.known(List.of("publish")), Instant.now());
        collector.collect(store, Known.known(List.of("publish")), Instant.now());
        return !store.exists("blobs/" + orphan);
    }
}
