package build.jenesis.repository.server;

import module java.base;

import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;
import io.micrometer.observation.ObservationRegistry;

/**
 * The framework-neutral core of the repository dispatch: it offers a {@link FormatExchange} to the
 * {@link RepositoryFormat} plugins over a scoped {@link ArtifactStore} and serves the first whose {@code handles(path)}
 * is true, either directly through {@link RepositoryFormat#handle} or, when an upstream is configured for that format
 * and the format is a {@link ProxyFormat}, through the {@link PullThroughCache} from that upstream. It holds no Spring
 * (or servlet) type, so both the Spring MVC {@link RepositoryController} and any other dispatcher (a multi-tenant
 * controller, a JDK-httpserver embedder) reuse the same loop rather than re-implementing it. The store
 * and the {@link FormatExchange#path() path} it matches on are already tenant-and-repository scoped by the caller (see
 * {@link RepositoryRouting}); this component only picks the format and drives it. The upstream a format pulls through
 * is the serving tenant's, since a tenant pulls through its own upstreams.
 */
public final class FormatDispatcher {

    /** The upstream each format pulls its local misses through, as one tenant sees it. */
    @FunctionalInterface
    public interface Upstreams {

        /** The upstream {@code tenant}'s {@code format} pulls through, or {@code null} when it fetches from nowhere.
         *  A {@code null} tenant asks for the deployment's. */
        URI upstream(String tenant, String format);

        /** The same upstreams for every tenant. */
        static Upstreams of(Map<String, URI> upstreams) {
            return (_, format) -> upstreams.get(format);
        }
    }

    private final List<RepositoryFormat> formats;
    private final Upstreams upstreams;
    private final ProxyFormat.Fetcher fetcher;
    private final ObservationRegistry observations;
    private final PullThroughHooks hooks;
    private final Map<String, FormatDispatcher> restricted = new ConcurrentHashMap<>();

    public FormatDispatcher(List<RepositoryFormat> formats, Map<String, URI> upstreams, ProxyFormat.Fetcher fetcher) {
        this(formats, Upstreams.of(upstreams), fetcher, ObservationRegistry.NOOP, PullThroughHooks.NONE);
    }

    public FormatDispatcher(List<RepositoryFormat> formats, Map<String, URI> upstreams, ProxyFormat.Fetcher fetcher,
                            ObservationRegistry observations) {
        this(formats, Upstreams.of(upstreams), fetcher, observations, PullThroughHooks.NONE);
    }

    public FormatDispatcher(List<RepositoryFormat> formats, Map<String, URI> upstreams, ProxyFormat.Fetcher fetcher,
                            ObservationRegistry observations, PullThroughHooks hooks) {
        this(formats, Upstreams.of(upstreams), fetcher, observations, hooks);
    }

    /**
     * Bind an edition's {@link PullThroughHooks} into the pull-through the loop drives. The other constructors delegate
     * here with {@link PullThroughHooks#NONE}, so a call site that binds none serves its proxy legs with no edition's
     * hooks.
     */
    public FormatDispatcher(List<RepositoryFormat> formats, Upstreams upstreams, ProxyFormat.Fetcher fetcher,
                            ObservationRegistry observations, PullThroughHooks hooks) {
        this.formats = formats;
        this.upstreams = upstreams;
        this.fetcher = fetcher;
        this.observations = observations;
        this.hooks = hooks;
    }

    /**
     * Offer the exchange to the first format that claims its {@link FormatExchange#path() path}, serving or accepting
     * the request against the scoped store. A format with an upstream {@code tenant} pulls through that is a
     * {@link ProxyFormat} serves a local miss through the {@link PullThroughCache}. Returns {@code true} when a format
     * claimed the path (the caller has its response), or {@code false} when none did, so the caller answers a
     * {@code 404}.
     */
    public boolean dispatch(String tenant, FormatExchange exchange, ArtifactStore store) throws IOException {
        String path = exchange.path();
        for (RepositoryFormat format : formats) {
            if (format.handles(path)) {
                URI base = upstreams.upstream(tenant, format.name());
                if (base != null && fetcher != ProxyFormat.Fetcher.NONE && format instanceof ProxyFormat proxy) {
                    new PullThroughCache(fetcher, observations, hooks.forTenant(tenant))
                            .serve(format, proxy, base, exchange, store);
                } else {
                    format.handle(exchange, store);
                }
                return true;
            }
        }
        return false;
    }

    /** The formats this dispatcher offers a request to. */
    public List<RepositoryFormat> formats() {
        return formats;
    }

    /** The installed format of this name, or empty when this deployment carries none - what a repository's document
     *  names is looked up here before anything is offered to it. */
    public Optional<RepositoryFormat> format(String name) {
        for (RepositoryFormat format : formats) {
            if (format.name().equals(name)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }

    /** A dispatcher that offers a request to {@code formats} alone - the formats a repository holds, since a path
     *  another format would claim is not that repository's to serve. Built once per set and held. */
    public FormatDispatcher only(List<RepositoryFormat> formats) {
        String key = String.join(",", formats.stream().map(RepositoryFormat::name).toList());
        return restricted.computeIfAbsent(key,
                _ -> new FormatDispatcher(formats, upstreams, fetcher, observations, hooks));
    }

    /**
     * The format that owns a request path - the first whose {@link RepositoryFormat#handles handles} it - so a
     * read-side concern that only has a stored path can recover its format without re-implementing the matching. The
     * {@code /api/assets} enumeration uses it to label a publication pointer with its format
     * {@link RepositoryFormat#name() name} and, when the owner is an
     * {@link build.jenesis.repository.format.ArtifactLayout ArtifactLayout}, its neutral coordinate through
     * {@link build.jenesis.repository.format.ArtifactLayout#describe describe} - from the path alone, no blob opened.
     * Empty when no installed format claims the path.
     */
    public Optional<RepositoryFormat> owner(String path) {
        for (RepositoryFormat format : formats) {
            if (format.handles(path)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }
}
