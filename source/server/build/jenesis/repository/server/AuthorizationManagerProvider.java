package build.jenesis.repository.server;

import module java.base;

import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.spi.KeyUsageTracker;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.Providers;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * The authorization manager a deployment decides requests with, discovered at runtime with {@link ServiceLoader}
 * - so a richer policy (per-tenant scoping, an operator-tenant check, usage recording) is a drop-in module and the
 * security chain names no implementation. With none installed the server's own deny-by-default manager stands.
 *
 * <p><strong>Why this is a seam rather than a bean name.</strong> A deployment used to replace the manager by
 * declaring a bean called {@code repositoryAuthorizationManager}, which the server's own declaration backs off
 * from. That works, and it is undiscoverable: the coupling is a string matched in two modules, nothing fails when
 * it stops matching, and what fails instead is that every access decision is quietly taken by the weaker manager.
 * The alternative of contributing the bean from a discovered {@code ServerModuleProvider} configuration does not
 * work at all - such a configuration is a deferred import, evaluated after the server's own conditional has
 * already been decided - which is why the replacement had to be declared by whichever module happened to be the
 * composition root. Resolving a provider <em>inside</em> the server's own declaration has neither problem: it
 * runs at the moment the manager is built, in every composition that carries the module.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> {@link #name()} is a pure declaration; {@link #create} runs once, on the boot
 *       thread. The manager it returns is consulted on <em>every</em> inbound request from every container
 *       thread, so that object must be thread-safe and cheap.</li>
 *   <li><b>Absence sentinel.</b> No provider installed is not an error: {@link #resolve} answers an empty
 *       {@link Optional} and the caller keeps its own manager. {@link #create} declines the same way;
 *       {@code null} is never a legal return from either, nor from {@link #name()}.</li>
 *   <li><b>Selection failure (&sect;9).</b> An explicit {@code jenreg.authorization-manager=<name>} that no
 *       installed provider answers to, or whose provider declines, throws at resolution naming the selection and
 *       the installed names. It does <em>not</em> fall back to the server's manager: a deployment that asked for
 *       a policy and silently got a weaker one is the §9 defect exactly, and here it is the defect that decides
 *       who may read and write artifacts.</li>
 *   <li><b>Error visibility (&sect;9).</b> Two enabled providers with no selection to separate them throws,
 *       naming both. Which manager decides a deployment's access is never a function of module-path order.</li>
 *   <li><b>Lifecycle / ownership.</b> The composition owns the resolved manager; the provider is created by
 *       {@link ServiceLoader}, consulted once and discarded.</li>
 *   <li><b>Read purity.</b> {@link #create} does no I/O: it reads the configuration it is handed and builds an
 *       object. Anything it needs from the store is read by the manager, on the request path, where a failure
 *       is visible.</li>
 * </ol>
 */
public interface AuthorizationManagerProvider {

    /** The name an operator selects this manager by, and the name reported when two providers collide. */
    String name();

    /**
     * Build this deployment's manager, or decline with an empty {@link Optional}.
     *
     * @param authorization the credential model the server authenticates with
     * @param usage         where a key's use is recorded, when an implementation records it
     * @param routing       how a request path maps to a repository, for an implementation that scopes by it
     * @param config        a property lookup answering {@code null} for anything unset, so a provider reads what
     *                      it needs without the server knowing which keys those are
     */
    Optional<AuthorizationManager<RequestAuthorizationContext>> create(Authorization authorization,
                                                                      KeyUsageTracker usage,
                                                                      RepositoryRouting routing,
                                                                      UnaryOperator<String> config);

    /** The keys this provider cannot work without; while any is unset it declares itself inactive. */
    default Set<String> requiredConfig() {
        return Set.of();
    }

    /** The single enabled manager discovered through {@link ServiceLoader}, or empty when none is installed. */
    static Optional<AuthorizationManager<RequestAuthorizationContext>> resolve(Authorization authorization,
                                                                               KeyUsageTracker usage,
                                                                               RepositoryRouting routing,
                                                                               UnaryOperator<String> config) {
        return Providers.optionalUnique("authorization-manager",
                ServiceLoader.load(AuthorizationManagerProvider.class),
                AuthorizationManagerProvider::name,
                Features.selection(config, "authorization-manager"),
                provider -> Features.active(config, provider.name(), provider.requiredConfig()),
                provider -> provider.create(authorization, usage, routing, config));
    }
}
