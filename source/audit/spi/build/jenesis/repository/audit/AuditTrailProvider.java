package build.jenesis.repository.audit;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Providers;

/**
 * A named factory for an {@link AuditTrail}, discovered with {@link ServiceLoader}, so the composition names no
 * implementation. Each provider reads its configuration through {@code config} and persists into the given root store.
 * With none installed {@link #resolve} answers the {@link AuditTrail#none() none} trail and the audit surfaces say the
 * feature is not installed.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> {@link #name()} is a pure declaration; {@link #create} runs once, on the boot thread. The
 *       returned trail is a shared singleton every request records through, so it is thread-safe.</li>
 *   <li><b>Idempotency / replay.</b> {@link #create} may run more than once over one root store and then exposes the
 *       same history; building a trail writes and prunes nothing.</li>
 *   <li><b>Absence sentinel.</b> {@link AuditTrail#none()}: with no module, or a declining provider, {@link #resolve}
 *       answers it. {@link #create} declines with an empty {@link Optional}; {@code null} is never returned from it or
 *       {@link #name()}. A trail configured <em>off</em> is not the sentinel - it still answers queries over its
 *       history, so switching audit off never hides history; only removing the module does.</li>
 *   <li><b>Selection failure.</b> No selection key: {@code audit} is the installed trail's on/off dial, not a provider
 *       name. The one failure is ambiguity - two installed providers make {@link #resolve} throw naming both rather
 *       than let module-path order decide where the compliance record lands - through the shared
 *       {@link Providers#optionalUnique}.</li>
 *   <li><b>Tenant scoping.</b> The trail is built over the root store and carries the tenant on each record; a query
 *       names the tenant it may read.</li>
 *   <li><b>Error visibility.</b> A lost audit record is a compliance gap: a write failure is surfaced, not
 *       swallowed.</li>
 *   <li><b>Lifecycle / ownership.</b> The composition owns the trail; {@link #resolve} builds at most one per call and
 *       caches and closes nothing. Providers are cheap, stateless factories.</li>
 *   <li><b>Ordering / determinism.</b> The resolved trail and {@link #installed()} depend on what is installed and
 *       configured, never on discovery order.</li>
 *   <li><b>Bounded work / cancellation.</b> A resolved trail answers
 *       {@link AuditTrail#query(String, Instant, Instant, String, int, int)} by reading only the page's objects and
 *       {@link AuditTrail#stream} one rotation window at a time. The inherited defaults refuse past
 *       {@link ArtifactStore#MAX_INHERITED_CHILDREN} events, naming the class and the override.</li>
 * </ol>
 */
public interface AuditTrailProvider {

    /** The trail name this provider answers to, e.g. {@code store}. */
    String name();

    /** Build the trail over the deployment's root store, reading settings through {@code config}; empty when off. */
    Optional<AuditTrail> create(ArtifactStore store, UnaryOperator<String> config);

    /** The single installed trail, through the shared {@link Providers#optionalUnique} policy, or the
     *  {@link AuditTrail#none() none} trail when none is installed or it declines; a second installed provider
     *  throws. */
    static AuditTrail resolve(ArtifactStore store, UnaryOperator<String> config) {
        return Providers.optionalUnique("audit-trail",
                        ServiceLoader.load(AuditTrailProvider.class),
                        AuditTrailProvider::name,
                        _ -> true,
                        provider -> provider.create(store, config))
                .orElseGet(AuditTrail::none);
    }

    /** The provider names installed, regardless of configuration - the capability a console or API gates its surface
     *  on. */
    static Set<String> installed() {
        return Providers.installedNames("audit-trail",
                ServiceLoader.load(AuditTrailProvider.class),
                AuditTrailProvider::name,
                _ -> true);
    }
}
