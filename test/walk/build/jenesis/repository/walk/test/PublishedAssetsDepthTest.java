package build.jenesis.repository.walk.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.walk.PublishedAssets;
import build.jenesis.repository.walk.TraversalException;
import build.jenesis.repository.store.ArtifactStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code publish/} pointer-tree walk over a pathologically deep tree. A published request path's depth is
 * client-controlled, and a deeply nested one used to drive {@link PublishedAssets#walk} into an unbounded recursion
 * that overflowed the stack with a {@link StackOverflowError} - an {@link Error} that slips past the caller's
 * handlers. The walk is an explicit work-list now, bounded by the depth no stored key exceeds
 * ({@link ArtifactStore#MAX_SEGMENTS}), so a {@value #DEPTH}-deep chain - a store nothing here could have written, far
 * past any stack the old recursion could hold - is refused by name rather than overflowing or being walked.
 */
class PublishedAssetsDepthTest {

    private static final int DEPTH = 20_000;

    @Test
    void a_chain_deeper_than_any_storable_key_is_refused_by_name_without_overflowing_the_stack() {
        DeepChainStore store = new DeepChainStore();
        PublishedAssets assets = new PublishedAssets(store);

        assertThatThrownBy(() -> assets.walk(null, Integer.MAX_VALUE, entry -> { }))
                .as("a %d-deep pointer chain is refused by name, iteratively, not by a StackOverflowError", DEPTH)
                .isInstanceOf(TraversalException.class);
        assertThat(store.maxDepth).as("the walk stopped at the depth no stored key exceeds")
                .isLessThanOrEqualTo(ArtifactStore.MAX_SEGMENTS + 1);
    }

    /** A store whose {@code publish/} subtree is a single chain {@code publish/a/a/.../a} exactly {@value DEPTH} levels
     *  deep: each node has one child {@code a} until the leaf, which has none. Nothing else is stored, so the leaf's
     *  pointer resolves to nothing and no entry is emitted - the walk's descent is what the deep chain stresses. */
    private static final class DeepChainStore implements ArtifactStore {
        @Override
        public Object identity() {
            return this;   // a standalone fake IS its own subspace
        }


        private int maxDepth;

        @Override
        public List<String> list(String prefix) {
            // Depth = number of "a" segments already below "publish": list("publish") is depth 0, "publish/a" depth 1...
            int depth = prefix.equals("publish") ? 0 : (int) prefix.chars().filter(c -> c == '/').count();
            maxDepth = Math.max(maxDepth, depth);
            return depth < DEPTH ? List.of("a") : List.of();
        }

        @Override
        public Optional<Versioned> readVersioned(String key) {
            return Optional.empty();          // no pointer resolves - located() returns empty, the leaf is skipped
        }

        @Override
        public boolean exists(String key) {
            return false;
        }

        @Override
        public long size(String key) {
            return -1L;
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return this;
        }

        @Override
        public void read(String key, OutputStream out) {
        }

        @Override
        public InputStream open(String key) {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public void write(String key, InputStream in) {
        }

        @Override
        public String writeBlob(InputStream in) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String key) {
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) {
            return false;
        }
    
    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return ArtifactStore.scanByListing(this, prefix, startAfter, limit, consumer);
    }
}
}
