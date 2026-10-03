package build.jenesis.repository.store;

import module java.base;

/**
 * An {@link ArtifactStore} that implements the primitives and derives the rest from them: what an in-memory or scratch
 * store, and a backend with no native form of an operation, answer with. Every derivation here is correct and none is
 * cheap - a page lists and sorts its container, a version reads the whole object, a streamed compare-and-set buffers -
 * which is why {@link ArtifactStore} leaves them abstract: a decorator that inherited one would replace its delegate's
 * native answer with this one, so it extends {@link ForwardingArtifactStore} instead, and a store that means to derive
 * them says so by implementing this.
 */
public interface PrimitiveArtifactStore extends ArtifactStore {

    @Override
    default StoreBindings bindings() {
        return StoreBindings.NONE;
    }

    @Override
    default InputStream open(String key, long offset) throws IOException {
        InputStream in = open(key);
        try {
            in.skipNBytes(offset);
            return in;
        } catch (IOException | RuntimeException e) {
            in.close();
            throw e;
        }
    }

    @Override
    default Optional<URI> presign(String key, Duration ttl) {
        return Optional.empty();
    }

    @Override
    default Optional<Listed> listed(String key) throws IOException {
        return exists(key) ? Optional.of(Listed.of(key)) : Optional.empty();
    }

    @Override
    default Optional<Capacity> capacity() throws IOException {
        return Optional.empty();
    }

    @Override
    default void touch(String key) throws IOException {
    }

    @Override
    default boolean isEmpty(String prefix) throws IOException {
        boolean[] any = {false};
        page(prefix, "", 1, _ -> any[0] = true);
        return !any[0];
    }

    @Override
    default void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
        pageListed(prefix, startAfter, limit, listed -> consumer.accept(ArtifactStore.name(listed.key())));
    }

    @Override
    default void pageListed(String prefix, String startAfter, int limit, Consumer<Listed> consumer) {
        // The container name normalised as every backend normalises it, so a trailing-slash prefix keys its children
        // exactly as the bare one does (never kit/listing//alpha).
        String container = ArtifactStore.container(prefix);
        ArtifactStore.pageByListing(this, prefix, startAfter, limit,
                name -> consumer.accept(Listed.of(child(container, name))));
    }

    @Override
    default Optional<Object> version(String key) throws IOException {
        return readVersioned(key).map(Versioned::token);
    }

    @Override
    default boolean writeVersioned(String key, InputStream content, long length, Object expected) throws IOException {
        return writeVersioned(key, content.readAllBytes(), expected);
    }

    /** A child's key under {@code prefix} - the root's children are keyed by their bare names. */
    private static String child(String prefix, String name) {
        return prefix == null || prefix.isEmpty() ? name : prefix + "/" + name;
    }
}
