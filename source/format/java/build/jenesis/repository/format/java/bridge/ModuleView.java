package build.jenesis.repository.format.java.bridge;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The Jenesis-layout side of cross-publishing: provided by the Jenesis format, used by the Maven format. Given the
 * content {@code hash} the Maven format already stored a modular jar under, it points the jar's {@code /module/} view
 * at that same content-addressed blob, so a client resolving by module name reaches the artifact a Maven client
 * published by coordinate - a pointer, not a re-upload. This is not part of the public {@code RepositoryFormat} SPI -
 * it is a bridge exposed (through a qualified export from the shared Java-layout module) only between the Maven and
 * Jenesis layout modules, the only two formats that cross-publish.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> The Maven format discovers the views once into a static list at class load and holds them
 *       for the process, so one instance serves every publishing request thread and both methods may run concurrently
 *       for different artifacts. An implementation must be stateless and keep no per-call state in fields.</li>
 *   <li><b>Idempotency / replay.</b> Both methods converge on repetition. {@link #publish} points the view keys at an
 *       already-stored content-addressed blob, so a byte-identical republish re-lands the identical pointer body and a
 *       replayed publish repairs one that crashed half way; {@link #unpublish} of a view the module never gained is a
 *       no-op, so a retraction may be replayed. Neither method may count, mint or append - a cross-view is a derived
 *       pointer, and re-deriving it must be free.</li>
 *   <li><b>Absence sentinel.</b> Both methods are void. Absence is expressed by the module: with no provider on the
 *       graph the consumer's discovered list is empty and a modular jar simply gains no {@code /module/} view, while
 *       still serving under its Maven coordinate. No argument is ever {@code null}, and a view that declines to publish
 *       does so silently rather than by raising.</li>
 *   <li><b>Selection failure.</b> There is nothing to select. The bridge is additive over a <em>qualified</em> export
 *       to exactly two modules, has no {@code name()}, no selection key and no {@code Features} toggle, so the &sect;9
 *       "explicitly selected but unavailable" case cannot arise; consequently the SPI also carries no {@code resolve}
 *       static and the consumer owns the {@code ServiceLoader} list. Because it does not resolve through
 *       {@code Providers}, a module registered twice would publish the same view twice - harmless only because the
 *       writes are idempotent (clause 2).</li>
 *   <li><b>Streaming (&sect;1).</b> Neither method takes or returns artifact bytes: {@link #publish} is handed the
 *       {@code hash} of a blob the caller already stored, so a cross-publish is a pointer write and never a re-upload,
 *       a second buffering of the jar, or a second pass over its bytes.</li>
 *   <li><b>Tenant scoping (&sect;6).</b> The {@link ArtifactStore} is the same doubly-scoped (tenant/repository) store
 *       the Maven publish routed through, so the {@code /module/} view lands in exactly the space the coordinate did.
 *       A view must not resolve a store of its own; a cross-published artifact never crosses a tenant.</li>
 *   <li><b>Error visibility (&sect;9).</b> Both methods <b>propagate</b> - the Maven format calls them inline and does
 *       not contain them, so an {@link IOException} fails the publish or the proxy retraction rather than being logged
 *       away. That is deliberate in the retraction direction: a tampered jar that lost its Maven coordinate but kept a
 *       {@code /module/} view would still serve, so a failed retraction must be loud. Note the two consequences the
 *       caller's ordering leaves: on the publish leg the Maven pointer is linked <em>before</em> the views, so a
 *       failure here fails the publish while the artifact already serves under its coordinate; on the retraction leg
 *       the views are removed before the coordinate, so a failure leaves the coordinate live. Neither partial state
 *       serves content the store does not hold, and a replay converges both.</li>
 *   <li><b>Read purity.</b> Not applicable: this is a write seam only. It performs no external I/O of any kind - every
 *       write goes through the scoped store - and it never reads or serves.</li>
 *   <li><b>Lifecycle / ownership.</b> The consumer owns the lifecycle: instances are
 *       {@link java.util.ServiceLoader}-created from a public no-arg constructor once at the consuming format's class
 *       load and cached for the life of the process. There is no close hook, so an implementation owns no thread,
 *       client or connection and must be a cheap, stateless writer.</li>
 *   <li><b>Ordering / concurrency.</b> Views are applied in discovery order, which is not stable across module-path
 *       arrangements; because every write is an idempotent compare-and-set on the view's own keys, the order is not
 *       observable and an implementation must not depend on another view having run, nor on being the only one. The
 *       view module owns both directions - the same module that derives a publish path derives its removal - so the
 *       Maven format never hardcodes a parallel {@code /module/} path that could drift out of step.</li>
 *   <li><b>Bounded work / cancellation.</b> Each call is a fixed, small number of pointer writes derived from its
 *       arguments - no listing, no walk, no read of the blob - and no cancellation signal is passed, so neither method
 *       may block.</li>
 *   <li><b>Durability / delivery.</b> Each pointer write is durable when it lands, but a view is <em>not</em> atomic
 *       across the keys it writes (the versioned view and the "latest" view are two pointers), nor across the view and
 *       the Maven coordinate that triggered it. A crash between them leaves the artifact reachable under the pointers
 *       already written and absent under the rest - the under-exposing direction - and nothing is notified. The durable
 *       source of truth is the store: a replay of the same publish completes the view, and the full artifact walk is
 *       the heal-all for a view that was never linked.</li>
 * </ol>
 */
public interface ModuleView {

    void publish(String moduleName, String version, String hash, ArtifactStore store) throws IOException;

    /**
     * Retract the {@code /module/} view {@link #publish} linked for a module - the exact counterpart of a publish, so
     * an artifact retracted from its Maven coordinate (a proxied jar that failed its upstream checksum) is unreachable
     * by module name too, not merely by coordinate. The same view module that owns the publish-side path derivation
     * owns its removal, so the Maven format never hardcodes a parallel {@code /module/} path. Best-effort per pointer:
     * a view the module never gained is a no-op.
     */
    void unpublish(String moduleName, String version, ArtifactStore store) throws IOException;
}
