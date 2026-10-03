package build.jenesis.repository.blobs.test;

import module java.base;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The layouts that store a request body through {@link Blobs} - a chart, a Debian package, a Composer archive, a pod,
 * a conda package, a Swift source archive, a Hugging Face file - are handed the body the ingress edge already stored
 * and screened, and get its hash back without the blob being written or read again.
 */
class BlobsStoreOnceTest {

    private static final byte[] BODY = "a chart the edge stored".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path root;

    @Test
    void an_edge_stored_body_is_answered_by_its_hash() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        String hash = new Blobs(store).store(new ByteArrayInputStream(BODY));
        ArtifactDescriptor accepted = ArtifactDescriptor.at("helm", "/helm/chart-1.0.0.tgz")
                .withBlob(hash, BODY.length);
        FaultInjectingStore counting = FaultInjectingStore.wrap(store);
        Blobs blobs = new Blobs(counting);

        assertThat(blobs.store(stored(accepted, counting))).isEqualTo(hash);
        assertThat(blobs.stored(stored(accepted, counting))).isEqualTo(new Publication.Blob(hash, BODY.length));
        blobs.write("charts/chart-1.0.0.tgz", stored(accepted, counting));

        assertThat(counting.calls(FaultInjectingStore.Op.WRITE_BLOB)).as("the blob is not written again").isZero();
        assertThat(counting.calls(FaultInjectingStore.Op.OPEN)).as("nor read to hash it again").isZero();
        assertThat(blobs.open(hash).readAllBytes()).isEqualTo(BODY);
    }

    /** The stream the edge hands a layout: the stored blob, opened lazily on a first read. */
    private static Publication.Stored stored(ArtifactDescriptor accepted, ArtifactStore store) {
        return new Publication.Stored(store, new Publication.Blob(accepted.hash(), accepted.size()));
    }
}
