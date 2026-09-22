package build.jenesis.repository.inventory.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.inventory.TornWriteConsumer;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.walk.ArtifactWalk;
import build.jenesis.repository.walk.RebuildPass;
import build.jenesis.repository.walk.WalkPass;
import build.jenesis.repository.walk.WalkProvider;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** The torn-write reconcile as a listener: the dangling pointer is the pass's own negative size, the orphan count a
 *  merge of two prefix sets at the end of the pass. */
class TornWriteConsumerTest {

    private static final String MISSING = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final ArtifactWalk WALK = WalkProvider.resolve(key -> null).orElseThrow();

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("releases");
    }

    @AfterEach
    void reset() {
        Features.reset();
    }

    @Test
    void a_dry_run_counts_the_dangling_pointer_and_the_orphan_and_removes_neither() throws IOException {
        Publication publication = new Publication(store);
        String referenced = publication.storeBlob(new ByteArrayInputStream("kept".getBytes(StandardCharsets.UTF_8)));
        publication.link("/raw/kept", referenced);
        store.writeVersioned("publish/raw/torn", MISSING.getBytes(StandardCharsets.UTF_8), null);
        String orphan = publication.storeBlob(new ByteArrayInputStream("orphan".getBytes(StandardCharsets.UTF_8)));
        TornWriteConsumer consumer = new TornWriteConsumer();

        Optional<WalkPass> pass = RebuildPass.run(WALK, store, new Publication(store), roots(), List.of(consumer));

        assertThat(pass).hasValueSatisfying(result -> assertThat(result.complete()).isTrue());
        assertThat(TornWriteConsumer.last().get(store.identity()))
                .isEqualTo(new TornWriteConsumer.Result(1, 0, 1, false));
        assertThat(store.readVersioned("publish/raw/torn")).as("a dry run removes nothing").isPresent();
        assertThat(store.exists("blobs/" + orphan)).as("an orphan is the collector's, never removed here").isTrue();
        assertThat(new TornWriteConsumer.Observability().metrics()).extracting(Metric::name, Metric::value)
                .contains(tuple("jenreg.reconcile.torn.dangling", 1.0), tuple("jenreg.reconcile.torn.orphans", 1.0),
                        tuple("jenreg.reconcile.torn.removed", 0.0));
    }

    @Test
    void with_apply_on_the_dangling_pointer_is_removed_through_the_guarded_delete() throws IOException {
        Features.configure(key -> ("jenreg." + TornWriteConsumer.APPLY).equals(key) ? "true" : null);
        store.writeVersioned("publish/raw/torn", MISSING.getBytes(StandardCharsets.UTF_8), null);

        RebuildPass.run(WALK, store, new Publication(store), roots(), List.of(new TornWriteConsumer()));

        assertThat(store.readVersioned("publish/raw/torn")).as("the dangling pointer is gone").isEmpty();
        assertThat(TornWriteConsumer.last().get(store.identity()).removed()).isEqualTo(1);
    }

    private static RebuildPass.Roots roots() {
        return new RebuildPass.Roots(List.of("publish"), List.of(), List.of("blobs"), List.of());
    }
}
