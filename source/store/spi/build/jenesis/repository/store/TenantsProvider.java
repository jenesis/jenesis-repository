package build.jenesis.repository.store;

import module java.base;

/**
 * A named factory for the {@link Tenants} directory, discovered at runtime with {@link ServiceLoader} - so how a
 * deployment keeps its tenant directory (a multi-tenant edition's store-backed one) is a drop-in module and the
 * composition names no implementation. Each provider reads its own configuration through the {@code config} lookup
 * (a property accessor returning {@code null} when unset); the deployment's root store is passed for a store-backed
 * directory, whose tenants are the top-level scopes of the shared {@code <tenant>/<repository>/...} layout. With no
 * module installed, {@link #resolve} answers the {@link Tenants#fixed fixed} directory over the configured tenant,
 * so the console's tenancy chrome follows the resolved directory. It does not gate on {@link #installed()}, which
 * answers a weaker, packaging question and is read by no production surface at all (see the method).
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> {@link #name()} and {@link #requiredConfig()} are pure declarations callable from any
 *     thread; {@link #create} runs once, on the boot thread. The {@link Tenants} directory it returns is a shared
 *     singleton every request thread reads, so <em>that</em> object must be thread-safe.</li>
 * <li><b>Idempotency / replay.</b> {@link #create} may run more than once over the same root store and must then
 *     expose the same tenants; building a directory neither creates nor removes a tenant. {@link Tenants#create} is
 *     itself idempotent - re-creating an existing tenant converges rather than failing or duplicating state.</li>
 * <li><b>Absence sentinel.</b> The unselected absence of a tenants module is <em>not</em> an error: {@link #resolve}
 *     answers the {@link Tenants#fixed fixed} directory over the configured tenant, which lists exactly that one
 *     tenant and refuses to grow. {@link #installed()} is {@code false} in the same situation, but it is not the
 *     signal anything gates on and answers a weaker question than {@code resolve} (see the method).
 *     {@link #create} declares "I decline" with an empty {@link Optional};
 *     {@code null} is never a legal return from it, from {@link #name()} or from {@link #requiredConfig()}.</li>
 * <li><b>Selection failure (&sect;9).</b> An <em>explicitly selected</em> {@code jenreg.tenants=<name>}
 *     that no installed provider answers to, or whose provider declines, throws {@link IllegalStateException} at
 *     resolution naming the selection and the installed provider names - it does <em>not</em> degrade to the fixed
 *     single-tenant directory. Degrading would collapse a multi-tenant deployment onto one tenant and hide every
 *     other tenant's artifacts behind a 404 that looks like an empty repository. An explicit selection also outranks
 *     the {@code jenreg.<name>=false} toggle: naming a directory that is also switched off is
 *     contradictory configuration and fails rather than silently resolving to something else. Only an
 *     <em>unselected</em> deployment degrades, and only to the fixed directory.</li>
 * <li><b>Tenant scoping (&sect;6).</b> The directory is deployment-global by construction - it is the thing that
 *     enumerates tenants - and is built over the deployment's <em>root</em> store, before any tenant scope is
 *     applied. It answers which tenants exist and creates them; it never reads a tenant's artifacts, and a caller
 *     scopes the store itself before touching content.
 *     <p>That root-level position is an <em>exception</em> this SPI holds by necessity, and it defines the rule for
 *     everything downstream of it: all plugin state is per-tenant, written under the tenant scope a caller derives
 *     from this directory. The only other deployment-global data is authorization and user management under
 *     {@code auth/} (and superadmin configuration under {@code config/}), because a credential must be resolvable
 *     before a tenant is known; a plugin that finds itself wanting the root store is almost always a plugin that has
 *     not scoped itself yet. A console or API view is likewise always a <em>tenant</em> view - implicitly so when
 *     {@link #installed()} is {@code false} and the fixed directory names the single tenant, which is why a
 *     single-tenant deployment shows no tenancy chrome rather than a different data model.</li>
 * <li><b>Error visibility (&sect;9).</b> Nothing is swallowed. Two providers answering to one name, one provider
 *     registered twice, and more than one enabled directory with no selection to disambiguate them are all
 *     configuration errors that throw, naming the candidates and the setting that resolves them - never a
 *     discovery-order winner, because which directory a deployment gets decides which tenants exist.</li>
 * <li><b>Lifecycle / ownership.</b> The composition owns the directory: {@link #resolve} builds at most one instance
 *     per call and hands it over, caching nothing and closing nothing. Provider instances are created by
 *     {@link ServiceLoader}, consulted and discarded, so a provider must be a cheap, stateless factory; anything a
 *     directory needs to close, it owns itself.</li>
 * <li><b>Ordering / determinism.</b> The resolved directory is a function of the configuration and the installed
 *     providers only, never of discovery order. {@link #installed()} reports the same answer on every module
 *     path.</li>
 * </ol>
 */
public interface TenantsProvider {

    /** The directory name this provider answers to, e.g. {@code store-tenants}. */
    String name();

    /** Build the directory over the deployment's root store, reading settings through {@code config}; empty when
     *  off. */
    Optional<Tenants> create(ArtifactStore root, UnaryOperator<String> config);

    /** The config keys this directory cannot run without; empty (the default) for one that needs nothing. A
     *  provider whose required keys are unset {@link Features#active self-disables} at discovery. */
    default Set<String> requiredConfig() {
        return Set.of();
    }

    /**
     * Whether a tenants module is installed and not switched off.
     *
     * <p><b>No production surface reads this</b>, and this javadoc asserted that a console gated its tenant management
     * on it for as long as none did -. The tenant kernel resolves a directory through
     * {@link #resolve(ArtifactStore, UnaryOperator, String) resolve}, and the console's tenancy chrome follows the
     * resolved directory: with none resolved the directory is the fixed single tenant and the chrome is hidden,
     * which is the same decision taken one layer lower and against the stronger question.
     *
     * <p><b>It is also not the same question, which is why it must not be adopted as one.</b> This answers
     * {@link Features#enabled}: a provider whose {@link #requiredConfig} keys are unset counts as installed here while
     * {@code resolve} - which asks {@link Features#active} - falls back to the fixed directory, and two enabled
     * providers count here while {@code resolve} refuses them as ambiguous. A console gated on this would offer tenant
     * management over a directory that never grows. Its reader is {@code test/store/spi}.
     */
    static boolean installed() {
        return !Providers.installedNames("tenants",
                ServiceLoader.load(TenantsProvider.class),
                TenantsProvider::name,
                provider -> Features.enabled(provider.name())).isEmpty();
    }

    /** The single enabled directory discovered via {@link ServiceLoader}, resolved through the shared
     *  {@link Providers#optionalUnique} policy: an explicit {@code jenreg.tenants=<name>} selects one by
     *  name and a selection nothing answers to <em>throws</em> rather than degrading (&sect;9), a
     *  {@code jenreg.<name>=false} switches one off, more than one enabled directory is ambiguous rather
     *  than a discovery-order winner, and only an <em>unselected</em> deployment with no directory installed gets the
     *  {@link Tenants#fixed fixed} directory over the configured {@code tenant}. */
    static Tenants resolve(ArtifactStore root, UnaryOperator<String> config, String tenant) {
        return Providers.optionalUnique("tenants",
                        ServiceLoader.load(TenantsProvider.class),
                        TenantsProvider::name,
                        Features.selection("tenants"),
                        provider -> Features.active(provider.name(), provider.requiredConfig()),
                        provider -> provider.create(root, config))
                .orElseGet(() -> Tenants.fixed(tenant));
    }
}
