package build.jenesis.repository.server.spi;

import module java.base;

import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.Providers;

/**
 * A core signal SPI through which a richer distribution claims ownership of the import edge - the
 * {@code POST /api/repository/import} / {@code GET /api/repository/import/<id>} surface the free
 * {@code ImportEdgeController} serves - discovered at runtime with {@link ServiceLoader}, exactly like the
 * {@link CapabilityContributor} SPI and the format / import-source plugins. When any provider is {@link #installed()
 * installed}, the {@code ImportEdgeController} bean is simply not registered (see
 * {@code RepositoryAutoConfiguration}), so its mapping never joins the handler mapping and the distribution's own
 * import controller - a composition's tenant-scoped {@code /api/repository/import} with its audited,
 * SSRF-screened choreography - is the <em>only</em> import edge at boot.
 *
 * <p>This retires the cross-layer stopgap exists to remove: the downstream edition previously dropped the free
 * import mapping with a {@code WebMvcRegistrations} bean (a bean/mapping override reaching across the layer). With
 * this hook the downstream instead ships an {@code ImportEdgeProvider} service - its mere presence on the module path
 * makes the built-in edge yield - and contributes its own controller bean, so the two contribute
 * <em>separate, non-colliding</em> controllers with no mapping-suppression bean and no endpoint-mapping collision.
 *
 * <p>With no provider installed (the product) the import edge is served exactly as before, byte-for-byte
 * unchanged - the same guarantee the {@link CapabilityContributor} zero-contributor case gives. Discovery honours the
 * shared {@link Features} enable/disable convention (a {@code jenreg.<name>=false} switch and the
 * required-config self-disable), so a provider that is present but not configured for is inert, exactly as a missing
 * module would be.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> {@link #name()} and {@link #requiredConfig()} may be called from any thread and must be
 *       safe to call concurrently. In practice {@link #installed()} runs once, on the boot thread, before the web
 *       layer is up.</li>
 *   <li><b>Absence sentinel.</b> {@link #installed()} answers {@code false} - never {@code null}, never an exception -
 *       when no provider is installed, when every discovered provider is switched off, and when a provider's
 *       {@link #requiredConfig()} is unset. {@code false} means the import edge is served byte-for-byte as it is
 *       without this SPI. Neither {@link #name()} nor {@link #requiredConfig()} may return {@code null}.</li>
 *   <li><b>Selection failure.</b> There is nothing to select: this is a presence signal, not a named capability, so no
 *       configuration can name an edge that is absent. Unlike the named singleton SPIs beside it there is no
 *       {@code jenreg.import-edge=<name>} key, so the §9 "explicitly selected but unavailable" case
 *       cannot arise here and setting such a key changes nothing. A provider that is installed but inert (switched
 *       off, required config unset) is indistinguishable from an absent module <em>by design</em>, and yielding the
 *       edge back to the controller is the intended outcome rather than a silent fallback.</li>
 *   <li><b>Read purity.</b> {@link #name()} and {@link #requiredConfig()} are pure declarations: no store access, no
 *       network, no filesystem, no lazy initialisation. They are read while the application context is still being
 *       built, so any I/O here happens before the store, the settings layer or the tenant directory exist.</li>
 *   <li><b>Error visibility.</b> A throw from either method propagates out of {@link #installed()} and fails the boot.
 *       That is deliberate and must not be softened: a swallowed failure here would silently register <em>both</em>
 *       import edges or <em>neither</em>, and an import surface that is quietly missing or quietly duplicated is worse
 *       than a refused start (PRINCIPLES §9).</li>
 *   <li><b>Lifecycle / ownership.</b> The core owns the lifecycle: every {@link #installed()} call loads the
 *       service afresh through {@link ServiceLoader}, so instances are created, consulted and discarded - they are not
 *       cached and never closed. A provider must therefore be a cheap, stateless declaration: it may not open threads,
 *       clients, connections or files, and it may not carry state a later call depends on. The distribution's actual
 *       import controller is contributed as an ordinary bean, not by this provider.</li>
 *   <li><b>Ordering / concurrency.</b> The answer must not depend on discovery order: any single active provider
 *       claims the edge, so the result is order-independent by construction, and the shared
 *       {@link Providers#installedNames} primitive additionally refuses a duplicate provider name or a provider
 *       registered twice - packaging errors a presence poll would otherwise absorb silently. At most one distribution
 *       may install a provider - two active providers would contribute two colliding controllers, which this SPI
 *       exists to prevent - so the outcome is well-defined only while that holds.</li>
 * </ol>
 */
public interface ImportEdgeProvider {

    /** The distribution-owned import edge's feature name, e.g. {@code downstream-import}. Toggled off with
     *  {@code jenreg.<name>=false} through the shared {@link Features} convention, so a deployment can fall
     *  back to the import edge without removing the module. */
    String name();

    /** The config keys this provider cannot run without; empty (the default) for one that claims the edge on presence
     *  alone. A provider whose required keys are unset {@link Features#active self-disables} at discovery, so the free
     *  edge is served until the distribution is configured for. */
    default Set<String> requiredConfig() {
        return Set.of();
    }

    /**
     * Whether this provider's edge is applicable to the deployment it finds itself in. {@code true} by default:
     * a provider that ships with its surface and needs nothing else says nothing.
     *
     * <p>It exists because {@link #requiredConfig()} can only ask whether an operator set a key, and a provider
     * may depend on what is *installed* rather than on what is configured. A tenant-scoped import edge is the
     * case: it replaces a repository-less route with one that names a repository, which is only meaningful where
     * something routes repositories at all. Claiming the edge regardless leaves a deployment with neither route -
     * the free one suppressed, the scoped one addressing a repository the URL space does not carry.
     *
     * <p>Consulted by {@link #installed()}, so a provider that declines is indistinguishable from one that is
     * absent, and the free edge stands exactly as it would have.
     */
    default boolean applicable() {
        return true;
    }

    /** Whether any {@link ServiceLoader}-discovered {@link ImportEdgeProvider} is active under the shared
     *  {@link Features} convention - the single question the {@code RepositoryAutoConfiguration} asks to decide
     *  whether to register the {@code ImportEdgeController}. {@code false} (no provider, or every discovered one
     *  configured off / missing its required config) means the import edge is served; {@code true} means a
     *  distribution owns the import edge and the controller yields, its mapping never registered. */
    static boolean installed() {
        return !Providers.installedNames("import-edge",
                ServiceLoader.load(ImportEdgeProvider.class),
                ImportEdgeProvider::name,
                provider -> Features.active(provider.name(), provider.requiredConfig())
                        && provider.applicable()).isEmpty();
    }
}
