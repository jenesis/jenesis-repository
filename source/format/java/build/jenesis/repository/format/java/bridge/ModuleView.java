package build.jenesis.repository.format.java.bridge;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The Jenesis-layout side of cross-publishing, provided by the Jenesis format and used by the Maven format: given the
 * content {@code hash} the Maven format stored a modular jar under, it points the jar's {@code /module/} view at that
 * same blob - a pointer, not a re-upload. Not part of the public {@code RepositoryFormat} SPI: a qualified export
 * reaches only the two formats that cross-publish.
 *
 * <p>Both methods write; there is no removal direction. A cross-view goes with the eviction of the Maven version it
 * mirrors ({@code ArtifactLayout.paths}), and the proxy leg verifies before it links anything.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> The views are discovered once ({@link #installed()}) and held for the process, so both
 *       methods run concurrently for different artifacts, and {@link #rebuild} from a background pass concurrently with
 *       a publish of another version of the same module. An implementation is stateless.</li>
 *   <li><b>Idempotency / replay.</b> Both converge: {@link #publish} points the view keys at an already-stored blob, so
 *       a republish re-lands identical pointers and a replay repairs a half-done one; {@link #rebuild} is the same
 *       write narrowed to the version-addressed keys. Neither counts, mints or appends.</li>
 *   <li><b>Absence sentinel.</b> Both are void. With no provider installed a modular jar gains no {@code /module/} view
 *       and still serves under its Maven coordinate. No argument is {@code null}; a view that declines does so
 *       silently.</li>
 *   <li><b>Selection failure.</b> Nothing is selected: additive, over a qualified export, with no name or toggle;
 *       {@link #installed()} is the plain discovered list, and a duplicate registration is harmless because the writes
 *       are idempotent.</li>
 *   <li><b>Streaming.</b> No artifact bytes pass: {@link #publish} gets the hash of a stored blob.</li>
 *   <li><b>Tenant scoping.</b> The store is the same tenant/repository-scoped store the Maven publish used, so the view
 *       lands where the coordinate did; a view never resolves a store of its own.</li>
 *   <li><b>Error visibility.</b> Both propagate: the Maven format calls them inline, so an {@link IOException} fails
 *       the publish or the rebuild segment. The coordinate is linked first, so a failure here leaves the artifact
 *       serving under its coordinate without a {@code /module/} view - the partial state a later pass finishes (clause
 *       12).</li>
 *   <li><b>Read purity.</b> A write seam only: no external I/O, no reads, no serving.</li>
 *   <li><b>Lifecycle / ownership.</b> Instances are {@link java.util.ServiceLoader}-created once from a public no-arg
 *       constructor and shared by the Maven publish path and its rebuild consumer; there is no close hook, so an
 *       implementation owns no thread, client or connection.</li>
 *   <li><b>Ordering / concurrency.</b> Views apply in discovery order, which is unstable; every write is an idempotent
 *       compare-and-set on the view's own keys, so no view may depend on another having run. The view module owns every
 *       path it writes, for publish and rebuild alike, so the Maven format never spells a {@code /module/} path.</li>
 *   <li><b>Bounded work / cancellation.</b> A fixed, small number of pointer writes per call - no listing, walk or blob
 *       read - so neither method blocks.</li>
 *   <li><b>Durability / delivery.</b> Each pointer write is durable when it lands, but a view is not atomic across its
 *       keys (the versioned and the "latest" pointer) nor with the Maven coordinate. The sequence and its crash windows
 *       are stated at {@code MavenFormat.layout}:
 * <ul>
 *   <li><b>The Maven coordinate is linked first and is the commit point.</b> A crash before it leaves an unreferenced
 *       blob; after it, the artifact serves under its coordinate with some or none of its views.</li>
 *   <li><b>That residue converges.</b> The view is derived from the coordinate - the module name is read back out of
 *       the blob it points at - so a later pass finishes it. The reverse could not be repaired: a view carries no Maven
 *       coordinate to re-derive.</li>
 *   <li><b>Two idempotent repairs.</b> A republish re-runs the whole sequence, and the {@code module-view}
 *       {@code WalkConsumer} ({@code MavenFormat}'s {@code ModuleViewRebuild}) re-derives the version-addressed view of
 *       every published Maven jar on each rebuild pass - why {@link #rebuild} is a seam of its own.</li>
 *   <li><b>The "latest" view is outside that repair.</b> It records which version was published last, an ordering fact
 *       no walk can recover; {@link #publish} owns it, {@link #rebuild} never touches it, and a lost latest view is
 *       restored only by a republish.</li>
 * </ul>
 * There is no retraction direction: a proxied artifact failing its upstream checksum is refused before the commit
 * point, so nothing needs unlinking.</li>
 * </ol>
 */
public interface ModuleView {

    /** Every view on the module path, discovered once and held for the process - the one list the Maven format
     *  publishes and repairs through, so a repaired view is byte-identical to a published one. */
    static List<ModuleView> installed() {
        return Views.ALL;
    }

    /**
     * Give a published modular jar its whole {@code /module/} view - the version-addressed pointers and the "latest"
     * one - aimed at the blob the Maven publish stored. Called once per publish of a modular jar, after its coordinate
     * is linked.
     *
     * @param classifier empty for the module's own jar, else the classifier Maven published it under - a classified jar
     *     has version-addressed views of its own and never moves the "latest" one
     * @param origin the served path the jar was published under, which this view is a second name for. An
     *     implementation records the relation ({@code ServedAliases}) so the names can be treated as one artifact - a
     *     reviewer's release above all; neither the hash nor the version identifies an alias, so the fact exists only
     *     where it is created, here.
     */
    void publish(String moduleName, String version, String classifier, String hash, ArtifactStore store,
                 String origin) throws IOException;

    /**
     * Re-derive only the version-addressed part of the view {@link #publish} links - the half that is a function of
     * stored state, so a repair pass restores rather than decides. The same idempotent write, so a rebuild over an
     * intact view changes nothing.
     *
     * <p>The "latest" pointer records which publish came last, which no walk can recover; re-linking it from a pass
     * would move it to whatever the walk reached last. A view with only version-addressed pointers implements this as
     * {@link #publish}; one with an ordering-dependent pointer leaves it alone here.
     *
     * @param origin as on {@link #publish}; a repair re-records the alias relation too, recovering one lost to a crash
     *     between the pointer write and the record write
     */
    void rebuild(String moduleName, String version, String classifier, String hash, ArtifactStore store,
                 String origin) throws IOException;

    /** Give a modular jar's version its descriptor: the POM Maven published beside it, stored under {@code hash}, aimed
     *  at by the module's Maven view. {@code latest} says whether this version is the one the "latest" view names - an
     *  ordering fact only the caller knows, so a rebuild passes {@code false}. */
    void describe(String moduleName, String version, String hash, boolean latest, ArtifactStore store, String origin)
            throws IOException;
}
