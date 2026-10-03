package build.jenesis.repository.store;

import module java.base;

/**
 * What the deployment that built a store hands, through that store, to the code that runs over it: a set of
 * values keyed by type, each the one instance of its type this deployment bound.
 *
 * <p>It exists for the plug-ins a deployment cannot inject. A {@link PublishInterceptor} or a
 * {@link PublicationObserver} is discovered by {@code ServiceLoader} and held once per process, so it has no
 * constructor a container could fill - yet what it decides by is the deployment's: its gate, its meters, its
 * dials. Every call such a plug-in receives carries the store the publication runs over, and that store is the
 * one thing that is certainly the calling deployment's, so the deployment's values ride on it. Two deployments in
 * one process build two stores, and a call through either reads its own deployment's values, where a holder
 * shared by the process would hand both the values of whichever was wired last.
 *
 * <p>A store carries what {@link #over} wrapped it in, and keeps carrying it across {@link ArtifactStore#scope}: a
 * repository's doubly-scoped view answers the bindings of the root it was scoped from. A decorator answers its
 * delegate's {@link ArtifactStore#bindings()}, exactly as it answers its delegate's identity.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> Immutable; {@link #with} and {@link #over} return new values.</li>
 *   <li><b>Absence sentinel.</b> {@link #NONE} is "nothing bound", and {@link #get} answers {@link Optional#empty()}
 *       for a type nothing bound - never {@code null}.</li>
 *   <li><b>Selection.</b> One value per type: binding a type again replaces the earlier value, and {@link #over}
 *       layered over a store that already carries bindings adds to them, its own values winning a type both
 *       name.</li>
 *   <li><b>Propagation.</b> Every view derived from a bound store - a {@link ArtifactStore#scope scope}, a
 *       decorator over it - answers the same bindings. A view that loses them answers {@link #NONE}, which is why a
 *       consumer that must find its binding treats an absence as a failure when one is expected, rather than as
 *       permission to proceed unbound.</li>
 *   <li><b>Lifecycle / ownership.</b> The values are the deployment's, owned and closed by it; a store carrying them
 *       closes nothing.</li>
 * </ol>
 */
public final class StoreBindings {

    /** Nothing bound: what a store answers unless a deployment bound something to it. */
    public static final StoreBindings NONE = new StoreBindings(Map.of());

    private final Map<Class<?>, Object> bound;

    private StoreBindings(Map<Class<?>, Object> bound) {
        this.bound = bound;
    }

    /** {@code value} bound as the one {@code type}. */
    public static <T> StoreBindings of(Class<T> type, T value) {
        return NONE.with(type, value);
    }

    /** These bindings with {@code value} bound as the one {@code type}, replacing a value already bound to it. */
    public <T> StoreBindings with(Class<T> type, T value) {
        Map<Class<?>, Object> next = new LinkedHashMap<>(bound);
        next.put(Objects.requireNonNull(type, "type"), type.cast(Objects.requireNonNull(value, "value")));
        return new StoreBindings(Collections.unmodifiableMap(next));
    }

    /** The value bound as {@code type}, or empty when nothing is. */
    public <T> Optional<T> get(Class<T> type) {
        return Optional.ofNullable(type.cast(bound.get(type)));
    }

    /** Whether nothing is bound. */
    public boolean isEmpty() {
        return bound.isEmpty();
    }

    /**
     * {@code store} carrying these bindings, added to the ones it already carries: every call is forwarded to
     * {@code store}, and every {@link ArtifactStore#scope scope} of the answer carries them too. Binding nothing
     * answers {@code store} itself.
     */
    public ArtifactStore over(ArtifactStore store) {
        return isEmpty() ? store : new Bound(Objects.requireNonNull(store, "store"), this);
    }

    /** Every one of {@code contributed} combined in order, a later one's value winning a type two name - what a
     *  composition binds its store with when several of its parts each contribute bindings. */
    public static StoreBindings all(Stream<StoreBindings> contributed) {
        return contributed.reduce(NONE, StoreBindings::and);
    }

    /** These bindings and {@code others}, the values of {@code others} winning a type both name. */
    public StoreBindings and(StoreBindings others) {
        if (others.isEmpty()) {
            return this;
        }
        if (isEmpty()) {
            return others;
        }
        Map<Class<?>, Object> merged = new LinkedHashMap<>(bound);
        merged.putAll(others.bound);
        return new StoreBindings(Collections.unmodifiableMap(merged));
    }

    @Override
    public String toString() {
        return "StoreBindings" + bound.keySet().stream().map(Class::getName).toList();
    }

    /**
     * A store answering a deployment's bindings over a store that does the work. It forwards every call, the
     * defaulted ones included, because a default here would replace the delegate's own answer - a native page with a
     * listing, a ranged open with a skip - and it re-binds every scope, which is what makes a repository's view
     * carry what its root was given.
     */
    private static final class Bound extends ForwardingArtifactStore {

        private final StoreBindings own;
        private final StoreBindings bindings;

        private Bound(ArtifactStore delegate, StoreBindings own) {
            super(delegate);
            this.own = own;
            this.bindings = delegate.bindings().and(own);
        }

        @Override
        public StoreBindings bindings() {
            return bindings;
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return new Bound(delegate.scope(tenant), own);
        }

        @Override
        public boolean writeVersioned(String key, InputStream content, long length, Object expected)
                throws IOException {
            return delegate.writeVersioned(key, content, length, expected);
        }

        @Override
        public String toString() {
            return delegate + " bound to " + own;
        }
    }
}
