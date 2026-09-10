package build.jenesis.repository.server;

import module java.base;

import build.jenesis.repository.store.Providers;

/**
 * Discovers the {@link RepositoryRouting} a deployment runs on, so tenancy is an extension point rather than a
 * composition choice.
 *
 * <p>It was neither before this. Four routings existed - one in the free core, three downstream - with no
 * {@code uses} or {@code provides} clause between them, selected by an {@code if}-chain over {@code jenreg.tenancy}
 * inside a Spring configuration. So the <em>setting</em> was real and the <em>seam</em> was not: a deployment could
 * pick one of four, and a fifth could only be added by editing the chain that names the other four. Everything else
 * in this product that has several implementations and one selection is discovered; this is that shape.
 *
 * <h2>Contract</h2>
 *
 * <ol>
 *   <li><b>Selection is {@code EXCLUSIVE_WITH_DEFAULT}.</b> One routing serves a deployment. {@code jenreg.tenancy}
 *       names it; unset, the {@link #FIXED single-tenant} routing binds. A name no installed provider answers to
 *       fails at boot naming what is installed - never a silent fall back to the default, because routing to the
 *       wrong tenant is not a degraded service, it is the wrong data.</li>
 *   <li><b>{@link #name()} is stable and lower case.</b> It is what an operator writes in a setting and what a
 *       failure message prints, so renaming one is a breaking change to a deployment's configuration.</li>
 *   <li><b>{@link #create} is called once, at boot, before any request.</b> A routing decides which store a
 *       request addresses, so it cannot be swapped underneath one; this is a boot-time selection like the store
 *       backend, not a live setting, and the settings catalogue says so.</li>
 *   <li><b>A provider builds only from its {@link RoutingContext}.</b> Anything else it needs it reads from that
 *       context's configuration, so a provider outside this build can be installed without the composition
 *       knowing what it is.</li>
 * </ol>
 */
public interface RepositoryRoutingProvider {

    /** The routing every deployment gets when {@code jenreg.tenancy} names none: one tenant, one repository. */
    String FIXED = "fixed";

    /**
     * The setting that names the routing, unprefixed - a deployment writes {@code jenreg.tenancy}.
     *
     * <p>It is deliberately <strong>not</strong> in the settings catalogue, which is the surface
     * {@code PUT /api/settings/{key}} writes: a routing decides which tenant's data a request addresses, and it is
     * read once at boot, so a live edit could neither take effect nor be safe if it did. It is boot posture, like
     * the store backend and like whether authentication is on at all.
     */
    String SETTING = "tenancy";

    /** The name {@code jenreg.tenancy} selects this routing by; stable, lower case. */
    String name();

    /** Build the routing. Called once, at boot. */
    RepositoryRouting create(RoutingContext context);

    /**
     * The routing this deployment runs on: the installed provider named by {@code selection}, or the
     * {@link #FIXED} one when it names nothing.
     *
     * @throws IllegalStateException when {@code selection} names a routing no installed provider answers to, with
     *         the installed names - a deployment that asked for host routing and silently got fixed would serve
     *         every tenant's request out of one space, and find nothing wrong with that.
     */
    static RepositoryRouting resolve(String selection, RoutingContext context) {
        List<RepositoryRoutingProvider> discovered = ServiceLoader.load(RepositoryRoutingProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .toList();
        return Providers.exclusiveWithDefault(SETTING,
                discovered,
                RepositoryRoutingProvider::name,
                Optional.ofNullable(selection),
                FIXED,
                provider -> List.of(),          // a routing needs no configuration to be usable; it reads its own
                provider -> provider.create(context));
    }
}
