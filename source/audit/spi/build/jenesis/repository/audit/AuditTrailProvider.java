package build.jenesis.repository.audit;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Providers;

/**
 * A named factory for an {@link AuditTrail}, discovered at runtime with {@link ServiceLoader} - so the audit
 * persistence is a drop-in module and the composition names no implementation. Each provider reads its own
 * configuration through the {@code config} lookup (a property/setting accessor returning {@code null} when unset)
 * and persists into the given root store, staying free of any framework dependency. With no module installed,
 * {@link #resolve} answers the {@link AuditTrail#none() none} trail: nothing records, the audit endpoints and
 * screens say the feature is not installed.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> {@link #name()} is a pure declaration callable from any thread; {@link #create} runs
 *     once, on the boot thread. The {@link AuditTrail} it returns is a shared singleton every request thread
 *     records through, so <em>that</em> object must be thread-safe.</li>
 * <li><b>Idempotency / replay.</b> {@link #create} may run more than once over the same root store (a second
 *     application context, a test harness) and must then expose the same recorded history; building a trail neither
 *     writes nor prunes a record.</li>
 * <li><b>Absence sentinel.</b> {@link AuditTrail#none()} is the sentinel: with no module installed, or with the
 *     installed provider declining, {@link #resolve} answers it, nothing records and the audit surface says the
 *     feature is not installed. {@link #create} declares "I decline" with an empty {@link Optional}; {@code null}
 *     is never a legal return from it or from {@link #name()}. Note that a trail configured
 *     <em>off</em> is not the sentinel: the store-backed trail still answers queries over what it recorded before,
 *     so switching audit off never hides history - only removing the module does.</li>
 * <li><b>Selection failure (&sect;9).</b> This SPI has <em>no</em> selection key: the {@code audit} setting is the
 *     installed trail's own on/off dial, not a provider name, so there is no explicitly-selected miss to fail on.
 *     The one resolution failure is ambiguity - two installed providers would make module-path order decide where a
 *     deployment's compliance record lands, so {@link #resolve} <em>throws</em> naming both rather than picking a
 *     discovery-order winner. Resolution runs through the shared {@link Providers#optionalUnique} primitive, never
 *     a hand-rolled loop.</li>
 * <li><b>Tenant scoping (&sect;6).</b> The trail is built over the deployment's <em>root</em> store and carries the
 *     tenant on each record, so it can answer "who did what to what, per tenant" without a per-tenant instance; a
 *     query names the tenant it may read.</li>
 * <li><b>Error visibility (&sect;9).</b> A lost audit record is a compliance gap, not a contained best-effort loss:
 *     a write failure is surfaced rather than swallowed into a silent no-op.</li>
 * <li><b>Lifecycle / ownership.</b> The composition owns the trail: {@link #resolve} builds at most one instance
 *     per call and hands it over, caching nothing and closing nothing. Provider instances are created by
 *     {@link ServiceLoader}, consulted and discarded, so a provider must be a cheap, stateless factory.</li>
 * <li><b>Ordering / determinism.</b> The resolved trail and {@link #installed()} are functions of what is installed
 *     and configured, never of discovery order.</li>
 * <li><b>Bounded work / cancellation.</b> A trail this provider resolves must answer
 *     {@link AuditTrail#query(String, Instant, Instant, String, int, int)} by reading only the page's objects and
 *     {@link AuditTrail#stream} by holding one rotation window at a time, so a console render and a CSV export of a
 *     very large trail both stay within a flat memory envelope. The inherited defaults are a small-trail fallback
 *     whose bound is <em>visible</em>: they refuse past {@link ArtifactStore#MAX_INHERITED_CHILDREN} events with an
 *     {@link IllegalStateException} naming the class and the override, rather than putting the whole unrotated trail
 *     in heap.</li>
 * </ol>
 */
public interface AuditTrailProvider {

    /** The trail name this provider answers to, e.g. {@code store}. */
    String name();

    /** Build the trail over the deployment's root store, reading settings through {@code config}; empty when off. */
    Optional<AuditTrail> create(ArtifactStore store, UnaryOperator<String> config);

    /** The single installed trail, resolved through the shared {@link Providers#optionalUnique} policy, or the
     *  {@link AuditTrail#none() none} trail when no module is installed or the installed one declines. A
     *  <em>second</em> installed provider throws rather than letting module-path order decide where the compliance
     *  record lands. */
    static AuditTrail resolve(ArtifactStore store, UnaryOperator<String> config) {
        return Providers.optionalUnique("audit-trail",
                        ServiceLoader.load(AuditTrailProvider.class),
                        AuditTrailProvider::name,
                        _ -> true,
                        provider -> provider.create(store, config))
                .orElseGet(AuditTrail::none);
    }

    /** The provider names installed on this deployment, regardless of configuration - the capability signal a
     *  console or API gates its surface on. */
    static Set<String> installed() {
        return Providers.installedNames("audit-trail",
                ServiceLoader.load(AuditTrailProvider.class),
                AuditTrailProvider::name,
                _ -> true);
    }
}
