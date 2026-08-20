package build.jenesis.repository.icon;

import module java.base;

/**
 * A plug-in that may lend the console a small mark of its own. This is not a discovered SPI in its own right: it is
 * the interface a <em>family's</em> SPI extends, so that every implementation of that family gains the same optional
 * mark and the same attribution identity without the family re-declaring either.
 *
 * <p>Two families extend it today and they share nothing else. A {@code RepositoryFormat} is a wire protocol over the
 * artifact store, and its mark appears beside the repositories and browse rows its layout backs. A plug-in that
 * contributes <em>findings</em> - an advisory feed, an inspector, a gate policy, a classifier, a scan marker - is not
 * a layout at all, and its mark appears beside the findings it produced, answering "which plug-in said this, and is
 * it still installed?". What the two have in common is exactly this: a stable name a surface attributes a row to, and
 * an optional document to draw beside it. Anything more specific belongs on the family's own interface.
 *
 * <p>The mark is optional and defaults to absent, so a family adopting this interface forces no implementation to
 * carry one, and the core carries none at all ({@link Marks} holds only the neutral fallback and the generated
 * scheme, never a brand mark). What a surface renders for a contributor that declares none, and for one that is no
 * longer installed, is {@link Marks}' business and is a resolved answer rather than a hole.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> An implementation is the family's discovered singleton, held for the life of the process
 *     and called from every render thread at once: {@link #name()} and {@link #icon()} must be safe to call
 *     concurrently and must keep no per-call state. In practice both answer a constant - the name is a literal and
 *     the mark is a document embedded in the implementation's own module - so a correct implementation has nothing
 *     to synchronise.</li>
 * <li><b>Idempotency / replay.</b> Both methods are pure declarations: calling either any number of times, in any
 *     order, from any thread, yields the same answer for the life of the process and changes nothing an operator can
 *     observe. {@link #name()} is additionally stable <em>across releases</em>, because it is the attribution key -
 *     a durable findings row records the name of the plug-in that produced it, and a console resolves that recorded
 *     string back to a contributor. Renaming a contributor therefore does not rename its history: it orphans it, and
 *     every row it produced renders through {@link Marks#orphaned} from then on. Rename deliberately or not at
 *     all.</li>
 * <li><b>Absence sentinel.</b> "No mark" is {@link Optional#empty()}, which is the default. It is never {@code null},
 *     never an {@link IconResource} wrapping zero bytes, and never a placeholder document an implementation invented
 *     for itself - a contributor that has nothing to draw declares nothing and lets {@link Marks} answer, so
 *     "declares none" stays one fact with one rendering rather than as many as there are implementations.
 *     {@link #name()} likewise never answers {@code null} or a blank string.</li>
 * <li><b>Selection failure.</b> Nothing is selected here and nothing here is discovered: this interface has no
 *     {@code uses} clause, no {@code provides} clause and no resolution primitive of its own. An implementation is
 *     found through <em>its family's</em> clause and switched on and off by that family's
 *     {@code jenreg.<name>} key; a family whose implementation is switched off contributes no mark
 *     because it contributes nothing at all, which is the same degradation as an absent module (&sect;3).</li>
 * <li><b>Tenant scoping (&sect;6).</b> A mark carries no tenant data and no repository data - it is a deployment-static
 *     brand asset, fixed at build time in the contributing module - so it is the one console-facing document that may
 *     be served, cached and shared across tenants without scoping. An implementation must therefore never derive a
 *     mark from anything tenant-specific, because doing so would leak one tenant's state into a document every other
 *     tenant may be handed.</li>
 * <li><b>Error visibility (&sect;9).</b> Neither method may throw: both answer a constant, so there is nothing to
 *     fail. A throw is <em>not</em> contained by {@link Marks}, which is a pure function and deliberately not a
 *     second containment mechanism beside the one collected-report seam ({@code Contributions}) - it propagates to
 *     whichever surface asked, and is contained there or not at all. A console panel's own containment turns that
 *     into one failed card; an icon endpoint turns it into a failed request. Neither is a reason to hide it.</li>
 * <li><b>Read purity (&sect;10).</b> {@link #icon()} performs <b>no I/O of any kind</b>: no file or classpath resource
 *     read, no store access, no fetch, no lazy download and no write. It is called on a render path - once per
 *     rendered row, for as many rows as a page shows - so the document is a constant in the implementation's own
 *     source or a field initialised from one, not something resolved when first asked.</li>
 * <li><b>Lifecycle / ownership.</b> The family owns the lifecycle: instances are created by the family's own
 *     {@link java.util.ServiceLoader} discovery from a public no-arg constructor and held for the life of the
 *     process, and there is no close hook here. A contributor owns no thread, client, connection or cache on account
 *     of this interface, and the caller never retains anything but the resolved {@link Mark}.</li>
 * <li><b>Ordering / concurrency.</b> A contributor's mark must not depend on which other contributors were
 *     discovered, or in what order: two deployments with the same contributor installed render the same mark for it
 *     whatever else is on the module path. Where a surface resolves a mark by something other than the name (a
 *     storage namespace, an ecosystem), two contributors answering to the same key is a packaging error the family's
 *     own resolution refuses - it is never settled by discovery order.</li>
 * <li><b>Bounded work / cancellation.</b> A mark is metadata-sized by construction: one self-contained SVG document
 *     on a uniform square {@code viewBox}, a few kilobytes at most, with no external reference of any kind - no
 *     {@code <image>}, no {@code <use href>} to another document, no font, no {@code <script>} - so it renders inline
 *     with nothing to fetch and nothing to execute. It is a brand mark, never an artifact, so it rides whole rather
 *     than through the streaming store, and an implementation that would need to stream what it is about to declare
 *     has declared the wrong thing.</li>
 * </ol>
 */
public interface IconContributor {

    /**
     * The contributor's stable identifier - {@code maven}, {@code oci}, {@code osv}, {@code secret-scan}. It is the
     * feature toggle key its family already uses, the string a surface attributes a row to, and the sole input to the
     * generated mark, so it is chosen once and not renamed (contract clause 2).
     */
    String name();

    /**
     * This contributor's own mark as a small self-contained SVG {@link IconResource} embedded in its module, or empty
     * when it ships none. A {@code default} of empty, so a family adopting this interface forces no implementation to
     * carry a mark and the core stays mark-agnostic; a contributor with one overrides this to
     * {@code Optional.of(IconResource.svg(...))}, drawing only from permissively-licensed sources with the source and
     * licence recorded next to its module. Resolve it through {@link Marks#of} rather than reading it directly - that
     * is what turns "declares none" into a rendered answer instead of a hole.
     */
    default Optional<IconResource> icon() {
        return Optional.empty();
    }
}
