package build.jenesis.repository.icon;

import module java.base;

/**
 * A plug-in that may lend the console a small mark of its own. Not a discovered SPI: it is the interface a family's SPI
 * extends, so every implementation of that family gains the same optional mark and attribution identity.
 *
 * <p>A {@code RepositoryFormat}'s mark appears beside the repositories and browse rows its layout backs; a plug-in that
 * contributes findings (an advisory feed, an inspector, a gate policy, a classifier, a scan marker) has its mark beside
 * the findings it produced. What they share is exactly this: a stable name a row is attributed to and an optional
 * document to draw. The mark defaults to absent; what a surface renders for a contributor that declares none, or that
 * is no longer installed, is {@link Marks}' answer.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> An implementation is the family's discovered singleton, called from every render thread
 *       at once: {@link #name()} and {@link #icon()} are safe to call concurrently and keep no per-call state - in
 *       practice both answer a constant.</li>
 *   <li><b>Idempotency / replay.</b> Both methods are pure declarations, answering the same for the life of the
 *       process. {@link #name()} is also stable across releases: a durable findings row records the producing plug-in's
 *       name and a console resolves it back, so renaming a contributor orphans its history, which then renders through
 *       {@link Marks#orphaned}.</li>
 *   <li><b>Absence sentinel.</b> "No mark" is {@link Optional#empty()}, the default - never {@code null}, an empty
 *       {@link IconResource} or an invented placeholder - so "declares none" has one rendering, {@link Marks}'.
 *       {@link #name()} is never {@code null} or blank.</li>
 *   <li><b>Selection failure.</b> Nothing is selected or discovered here: no {@code uses}, no {@code provides}. An
 *       implementation is found through its family's clause and switched by that family's {@code jenrepo.<name>} key; a
 *       switched-off implementation contributes no mark because it contributes nothing.</li>
 *   <li><b>Tenant scoping.</b> A mark is a deployment-static asset fixed at build time, so it may be served, cached and
 *       shared across tenants unscoped. It is therefore never derived from anything tenant-specific.</li>
 *   <li><b>Error visibility.</b> Neither method throws. A throw is not contained by {@link Marks}, a pure function; it
 *       propagates to the surface that asked (a console panel's containment, an endpoint's error handling).</li>
 *   <li><b>Read purity.</b> {@link #icon()} performs <b>no I/O</b> - no resource read, store access, fetch or write. It
 *       is called once per rendered row, so the document is a constant in the implementation's source, not resolved
 *       when first asked.</li>
 *   <li><b>Lifecycle / ownership.</b> The family owns the lifecycle: instances come from its
 *       {@link java.util.ServiceLoader} discovery via a public no-arg constructor and live for the process, with no
 *       close hook. A contributor owns nothing on account of this interface, and a caller retains only the resolved
 *       {@link Mark}.</li>
 *   <li><b>Ordering / concurrency.</b> A mark never depends on which other contributors were discovered or in what
 *       order. Where a surface resolves a mark by another key (a storage namespace, an ecosystem), two contributors
 *       answering to it is a packaging error the family's resolution refuses, never settled by discovery order.</li>
 *   <li><b>Bounded work / cancellation.</b> A mark is one self-contained SVG document on a uniform square
 *       {@code viewBox}, a few kilobytes at most, with no external reference - no {@code <image>}, {@code <use href>},
 *       font or {@code <script>} - so it renders inline with nothing to fetch or execute, and rides whole rather than
 *       streamed.</li>
 * </ol>
 */
public interface IconContributor {

    /** The contributor's stable identifier - {@code maven}, {@code oci}, {@code osv}, {@code secret-scan}: its family's
     *  toggle key, the string a row is attributed to, and the sole input to the generated mark, so it is never renamed
     *  (clause 2). */
    String name();

    /** This contributor's own mark as a small self-contained SVG embedded in its module, or empty when it ships none. A
     *  contributor with one returns {@code Optional.of(IconResource.svg(...))}, drawn from a permissively licensed
     *  source recorded next to its module. Resolve it through {@link Marks#of}, which turns "declares none" into a
     *  rendered answer. */
    default Optional<IconResource> icon() {
        return Optional.empty();
    }
}
