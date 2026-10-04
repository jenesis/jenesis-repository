package build.jenesis.repository.store;

import module java.base;
import module org.slf4j;

/**
 * What a resolution over the deployment's settings answers now, held for as long as the settings it read stand.
 *
 * <p>A component that builds a client from its settings - an advisory feed, a provenance signer - is resolved through
 * {@link #get()}, which records every key the resolution reads and the value it read. The next call reads those keys
 * again and answers the held value while every one of them is unchanged, so a write to an unrelated setting keeps the
 * component and whatever it has warmed; once one of them moves, the component is resolved afresh, so switching a
 * feed on, re-pointing it or giving it a credential takes effect without a restart.
 *
 * <p>The holder owns what it resolved. A value it replaces is released once the new one is in place, and
 * {@link #close()} releases the one it holds, so a client a component opened is closed when the component is switched
 * off or reconfigured, and when the deployment shuts down - a composition closes the holder with its context. Release
 * is best-effort: a release that throws is logged and does not fail the resolution or the shutdown. A caller that took
 * a value before it was replaced may still be using it, so a released component must answer a late call as a
 * component whose client is gone answers, never by corrupting state.
 *
 * <p>The lookup is the deployment's live one, so the answer follows a settings write on the node that made it at once
 * and on every other node at its next settings re-read. Asking costs one lookup per key the last resolution read.
 *
 * @param <T> what the resolution answers
 */
public final class LiveResolution<T> implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(LiveResolution.class);

    private final UnaryOperator<String> config;
    private final Function<UnaryOperator<String>, T> resolver;
    private final Function<T, Collection<?>> owned;
    private Resolved<T> resolved;
    private boolean closed;

    /** Resolve through {@code resolver} over the live lookup {@code config}; {@code owned} names the objects a value
     *  carries that are closed with it - each one that is {@link AutoCloseable}. */
    public LiveResolution(UnaryOperator<String> config, Function<UnaryOperator<String>, T> resolver,
                          Function<T, Collection<?>> owned) {
        this.config = Objects.requireNonNull(config, "config");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.owned = Objects.requireNonNull(owned, "owned");
    }

    /** A resolution whose value is itself what is closed with it, when it is {@link AutoCloseable}. */
    public LiveResolution(UnaryOperator<String> config, Function<UnaryOperator<String>, T> resolver) {
        this(config, resolver, value -> value == null ? List.of() : List.of(value));
    }

    /** The answer for the settings as they stand, resolved again only when a setting the last resolution read
     *  has changed.
     *
     *  @throws IllegalStateException once the holder is closed */
    public synchronized T get() {
        if (closed) {
            throw new IllegalStateException("the resolution is closed with the deployment it served");
        }
        if (resolved != null && resolved.current(config)) {
            return resolved.value();
        }
        Map<String, Optional<String>> read = new LinkedHashMap<>();
        T value = resolver.apply(key -> {
            String answer = config.apply(key);
            read.putIfAbsent(key, Optional.ofNullable(answer));
            return answer;
        });
        Resolved<T> replaced = resolved;
        resolved = new Resolved<>(Collections.unmodifiableMap(read), value);
        if (replaced != null) {
            release(replaced.value(), value);
        }
        return value;
    }

    /** Release what the holder holds; idempotent. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (resolved != null) {
            release(resolved.value(), null);
            resolved = null;
        }
    }

    /** Close every closeable object {@code value} owns that {@code successor} does not own too - a resolution can
     *  answer some of the same objects again, which stay open. */
    private void release(T value, T successor) {
        Set<Object> kept = Collections.newSetFromMap(new IdentityHashMap<>());
        if (successor != null) {
            kept.addAll(owned.apply(successor));
        }
        for (Object each : owned.apply(value)) {
            if (each instanceof AutoCloseable closeable && !kept.contains(each)) {
                try {
                    closeable.close();
                } catch (Exception failed) {
                    LOGGER.warn("Could not close {} as it was released", each.getClass().getName(), failed);
                }
            }
        }
    }

    /** One resolution and the value of every key it read. */
    private record Resolved<T>(Map<String, Optional<String>> read, T value) {

        boolean current(UnaryOperator<String> config) {
            for (Map.Entry<String, Optional<String>> entry : read.entrySet()) {
                if (!Objects.equals(config.apply(entry.getKey()), entry.getValue().orElse(null))) {
                    return false;
                }
            }
            return true;
        }
    }
}
