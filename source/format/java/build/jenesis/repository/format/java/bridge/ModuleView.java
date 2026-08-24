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
 * <p>It has two methods and both write; there is deliberately no removal direction. A cross-view is removed by the
 * same eviction that removes the Maven version it mirrors (through {@code ArtifactLayout.paths}), and the one caller
 * that used to un-link a view by hand - the proxy leg retracting an artifact that failed its upstream checksum - no
 * longer needs to, because that leg now verifies before it links anything at all.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> The Maven format discovers the views once into a static list at class load and holds them
 *       for the process, so one instance serves every publishing request thread and both methods may run concurrently
 *       for different artifacts - and, since {@link #rebuild} is driven from a background rebuild pass, concurrently
 *       with a publish of another version of the same module. An implementation must be stateless and keep no per-call
 *       state in fields.</li>
 *   <li><b>Idempotency / replay.</b> Both methods converge on repetition. {@link #publish} points the view keys at an
 *       already-stored content-addressed blob, so a byte-identical republish re-lands the identical pointer body and a
 *       replayed publish repairs one that crashed half way; {@link #rebuild} is the same write narrowed to the
 *       version-addressed keys, so running it over a view that is already complete leaves the store byte-identical.
 *       Neither method may count, mint or append - a cross-view is a derived pointer, and re-deriving it must be
 *       free.</li>
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
 *       not contain them, so an {@link IOException} fails the publish (or the rebuild pass's segment) rather than being
 *       logged away. The one partial state this leaves is stated in clause 12 and named at the call site: the Maven
 *       coordinate is linked <em>before</em> the views, so a failure here fails the publish while the artifact already
 *       serves under its coordinate and carries no {@code /module/} view yet. That partial state is not merely
 *       documented - it is the one a later pass can finish, which is why the ordering is what it is.</li>
 *   <li><b>Read purity.</b> Not applicable: this is a write seam only. It performs no external I/O of any kind - every
 *       write goes through the scoped store - and it never reads or serves.</li>
 *   <li><b>Lifecycle / ownership.</b> The consumer owns the lifecycle: instances are
 *       {@link java.util.ServiceLoader}-created from a public no-arg constructor once at the consuming class's load
 *       and cached for the life of the process. There are two such consumers - the Maven format's publish path and its
 *       rebuild consumer - so a process holds one instance of each provider per consumer rather than one in total;
 *       that is harmless precisely because clause 1 forbids per-call state. There is no close hook, so an
 *       implementation owns no thread, client or connection and must be a cheap, stateless writer.</li>
 *   <li><b>Ordering / concurrency.</b> Views are applied in discovery order, which is not stable across module-path
 *       arrangements; because every write is an idempotent compare-and-set on the view's own keys, the order is not
 *       observable and an implementation must not depend on another view having run, nor on being the only one. The
 *       view module owns every path it writes - the same module that derives a publish path derives the rebuild of
 *       that path - so the Maven format never hardcodes a parallel {@code /module/} path that could drift out of
 *       step.</li>
 *   <li><b>Bounded work / cancellation.</b> Each call is a fixed, small number of pointer writes derived from its
 *       arguments - no listing, no walk, no read of the blob - and no cancellation signal is passed, so neither method
 *       may block.</li>
 *   <li><b>Durability / delivery.</b> Each pointer write is durable when it lands, but a view is <em>not</em> atomic
 *       across the keys it writes (the versioned view and the "latest" view are two pointers), nor across the view and
 *       the Maven coordinate that triggered it. The sequence and its crash windows are stated once, at the call site
 *       ({@code MavenFormat.layout}), and are the reason this bridge has the shape it does:
 *       <ul>
 *         <li><b>The Maven coordinate is linked first, and it is the commit point.</b> A crash before it leaves an
 *             unreferenced blob and nothing servable; a crash after it leaves the artifact serving under its
 *             coordinate with some or none of its {@code /module/} views linked. Nothing is notified.</li>
 *         <li><b>That residue converges, which is what makes the order the right way round.</b> The coordinate is the
 *             durable record the view is <em>derived</em> from - the module name is read back out of the very blob the
 *             coordinate points at - so a later pass can finish the derivation from what survived. The reverse order
 *             cannot be repaired at all: a {@code /module/} view carries a module name and a version and no Maven
 *             coordinate, so nothing can re-derive the coordinate from it, and deleting it instead would be an
 *             orphan purge over a namespace the Jenesis format also publishes into first-hand.</li>
 *         <li><b>Two repairs, both idempotent.</b> A byte-identical republish re-runs the whole sequence, and the
 *             {@code module-view} {@code WalkConsumer} ({@code MavenFormat}'s {@code ModuleViewRebuild}) re-derives
 *             the version-addressed view for every published Maven jar on each rebuild pass - the walk half of the
 *             two-route contract, and the reason {@link #rebuild} exists as a seam of its own.</li>
 *         <li><b>The "latest" view is deliberately outside that repair.</b> It records which version was published
 *             last, which is an ordering fact about publications rather than a fact about stored state, so no walk can
 *             re-derive it: a pass re-linking it would move {@code /module/<name>/<name>.jar} to whichever version the
 *             walk happened to reach last. {@link #publish} owns it; {@link #rebuild} never touches it, and a latest
 *             view lost to a crash is restored by a republish and by nothing else.</li>
 *       </ul>
 *       The durable source of truth is the store, and the retraction direction is deliberately absent: a proxied
 *       artifact that fails its upstream checksum is now refused <em>before</em> the commit point (nothing is linked,
 *       so nothing needs unlinking), which is how the OCI leg has always held a mismatched digest.</li>
 * </ol>
 */
public interface ModuleView {

    /**
     * Give a published modular jar its whole {@code /module/} view: every pointer this layout addresses the module by,
     * the version-addressed one(s) and the "latest" one, aimed at the content-addressed blob the Maven publish already
     * stored. Called once per publish of a modular jar, after its Maven coordinate has been linked.
     *
     * @param origin the served path the jar was published under - the Maven coordinate this view is a second name
     *               for. An implementation records the relation ({@code ServedAliases}) so that whatever must treat
     *               the names as one artifact can, a reviewer's release above all: neither content hash nor
     *               coordinate version identifies an alias, so the fact exists only where it is created, here.
     */
    void publish(String moduleName, String version, String hash, ArtifactStore store, String origin)
            throws IOException;

    /**
     * Re-derive only the <em>version-addressed</em> part of the view {@link #publish} would link - the half that is a
     * function of stored state alone, so a repair pass can re-run it over an artifact it did not publish and be sure
     * it is restoring rather than deciding. It is the same idempotent compare-and-set write, so a rebuild over an
     * intact view leaves the store byte-identical.
     *
     * <p>This exists as a seam of its own because the two halves of a view have different truth. The version-addressed
     * pointer is fully determined by "this module, at this version, is these bytes", which the walk re-reads from the
     * stored jar; the "latest" pointer records which publish came last, which no walk can recover - re-linking it from
     * a pass would silently move it to whatever the walk reached last. A view whose pointers are all version-addressed
     * simply implements this exactly as {@link #publish}; a view that carries an ordering-dependent pointer must leave
     * that pointer alone here.
     *
     * @param origin as on {@link #publish} - a repair pass re-records the alias relation too, which is what recovers
     *               a record lost to a crash between the pointer write and the record write.
     */
    void rebuild(String moduleName, String version, String hash, ArtifactStore store, String origin)
            throws IOException;
}
