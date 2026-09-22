package build.jenesis.repository.blobs.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.DocumentMemory;
import build.jenesis.repository.store.NodeMemoStore;
import build.jenesis.repository.store.MissMemory;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/** A format's own pointer namespace rides the same memory of misses the generic serve probe does: a
 *  key read and found absent is absent from memory until the ttl, and a link through the store forgets it. */
class BlobsMissMemoryTest {

    private static final String KEY = "npm/@acme/widget/1.0.0/widget-1.0.0.tgz";

    @TempDir
    Path root;

    private FaultInjectingStore counting;
    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        counting = FaultInjectingStore.wrap(ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null));
        store = NodeMemoStore.over(counting, new MissMemory(Duration.ofSeconds(10)), new DocumentMemory(Duration.ZERO));
    }

    @Test
    void a_probe_of_an_absent_key_reads_the_store_once_within_the_ttl() throws IOException {
        Blobs blobs = new Blobs(store);
        assertThat(blobs.locate(KEY)).isEmpty();
        assertThat(blobs.locate(KEY)).isEmpty();
        assertThat(counting.calls(FaultInjectingStore.Op.READ_VERSIONED)).isEqualTo(1);
    }

    @Test
    void a_link_through_the_store_is_located_at_once() throws IOException {
        Blobs blobs = new Blobs(store);
        assertThat(blobs.locate(KEY)).isEmpty();
        String hash = store.writeBlob(new ByteArrayInputStream("tgz".getBytes(StandardCharsets.UTF_8)));
        blobs.link(KEY, hash);
        assertThat(blobs.locate(KEY)).as("the link forgot the key on this node").isPresent()
                .get().satisfies(located -> assertThat(located.hash()).isEqualTo(hash));
    }

    @Test
    void a_raw_store_probes_the_store_every_time() throws IOException {
        Blobs blobs = new Blobs(counting);
        blobs.locate(KEY);
        blobs.locate(KEY);
        assertThat(counting.calls(FaultInjectingStore.Op.READ_VERSIONED)).isEqualTo(2);
    }
}
