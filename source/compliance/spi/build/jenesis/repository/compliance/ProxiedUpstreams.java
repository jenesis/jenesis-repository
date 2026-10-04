package build.jenesis.repository.compliance;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoreBindings;

/**
 * The upstreams the repositories of one format proxy, as the deployment that built a store answers them for the tenant
 * publishing - the places this deployment already fetches from, and so the places a screen that follows what an
 * artifact pulls in may read from without an operator naming them.
 *
 * <p>A deployment {@linkplain #bindings binds} its answer to the store it builds, resolved per call so it follows the
 * definitions as they change, and an inspection over that store asks it ({@link #of}); a store carrying none answers
 * no upstreams, which is what a deployment that proxies nothing means.
 */
public final class ProxiedUpstreams {

    /** The deployment's answer, by format, as its store carries it. */
    private record Bound(Function<String, List<URI>> upstreams) {
    }

    private ProxiedUpstreams() {
    }

    /** The upstreams the repositories of {@code format} proxy for the deployment that built {@code store}; empty when
     *  it binds no answer. */
    public static List<URI> of(ArtifactStore store, String format) {
        return store == null ? List.of() : store.bindings().get(Bound.class)
                .map(bound -> List.copyOf(bound.upstreams().apply(format))).orElse(List.of());
    }

    /** The deployment's answer as the bindings of the store it builds. */
    public static StoreBindings bindings(Function<String, List<URI>> upstreams) {
        return StoreBindings.of(Bound.class, new Bound(Objects.requireNonNull(upstreams, "upstreams")));
    }
}
