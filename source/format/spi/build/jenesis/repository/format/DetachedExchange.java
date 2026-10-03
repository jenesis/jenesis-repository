package build.jenesis.repository.format;

import module java.base;

/**
 * A {@link FormatExchange} no edge wraps and no connection carries - a request a node synthesizes to replay a stored
 * body into its format, an internal push, a headless embed, a test double - answering what such an exchange can say:
 * the path it was given is the one requested and the whole URI, the scheme {@code http}, no peer, no settings, no
 * range, nothing audited, and no right to read elsewhere, to read held content or to administer.
 *
 * <p>{@link FormatExchange} leaves these abstract because each is wrong for an exchange that does sit behind an edge:
 * a decorator that answered them itself would serve a range from byte 0, drop the audit line, and read the caller's
 * rights as none. A decorator extends {@link ForwardingExchange}; an exchange with nothing behind it implements this.
 */
public interface DetachedExchange extends FormatExchange {

    @Override
    default String requestedPath() {
        return path();
    }

    @Override
    default String requestUri() {
        return path();
    }

    @Override
    default String scheme() {
        return "http";
    }

    @Override
    default String remoteAddress() {
        return null;
    }

    @Override
    default String setting(String key) {
        return null;
    }

    @Override
    default long from(long contentLength) {
        return 0L;
    }

    @Override
    default void audit(String action, String target) {
    }

    @Override
    default Optional<build.jenesis.repository.store.ArtifactStore> readable(String path) {
        return Optional.empty();
    }

    @Override
    default boolean readsHeld() {
        return false;
    }

    @Override
    default boolean administers() {
        return false;
    }
}
