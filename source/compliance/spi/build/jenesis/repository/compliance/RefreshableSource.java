package build.jenesis.repository.compliance;

import module java.base;

/**
 * The role a {@link SignalSource} takes when it <em>mirrors</em> its vendor rather than querying it per lookup: its
 * query path renders what is durably stored, and the fetch is this separate, explicit, write-role entry point
 * (&sect;10). Detected by {@code instanceof} on an already-created source, the way {@code ProxyFormat} and
 * {@code ArtifactLayout} are detected on a {@code RepositoryFormat} - a provider does not declare it in
 * {@link SignalSourceProvider#signals()}, because it is not a kind of signal a consumer resolves for; it is how one
 * signal keeps its read path pure.
 *
 * <p>A source that does <em>not</em> implement this fetches from inside its own query, which is the position eleven of
 * the twelve shipped signals still hold: for them there is no refresh to drive, and the scheduled sweep that
 * drives this role simply passes them by.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> {@link #refresh()} may be called concurrently with the query methods of the contracts the
 *     same object answers - a scheduled sweep refreshes while the publish gate reads - so the refreshed data must
 *     become visible as one whole value and a reader must never see a half-adopted catalogue.</li>
 * <li><b>Idempotency / replay.</b> Refreshing twice must not draw the vendor twice: an implementation that is still
 *     inside its own refresh window renders and returns rather than fetching, so a sweep that runs every few minutes
 *     over a feed republished daily costs one fetch a day. Re-running the pass after a crash is therefore free and
 *     safe, which is what lets the scheduler treat it as an ordinary convergent sweep.</li>
 * <li><b>Absence sentinel.</b> The returned {@link Freshness} is never {@code null}; a refresh that could not draw
 *     anything answers the source's unchanged reading rather than a fabricated one.</li>
 * <li><b>Error visibility (&sect;9).</b> A vendor that cannot be reached is <em>fail-soft and visible</em>: nothing is
 *     committed, the prior-good data keeps serving, and the returned {@link Freshness} says the attempt did not land,
 *     which is what the driving sweep counts as a failed pass. A failure of the <em>store</em> - the durable side the
 *     mirror commits into - is an {@link IOException}, because that is a wiring or infrastructure fault rather than a
 *     vendor outage and must not read as "the feed is down".</li>
 * <li><b>Read purity (&sect;10).</b> This is the method that is <em>allowed</em> to fetch, and the only one. Its
 *     existence is what lets the source's query methods promise they render.</li>
 * <li><b>Durability / delivery (&sect;13).</b> The commit point is the snapshot space's own compare-and-set pointer
 *     move. An incomplete refresh commits nothing and leaves the prior-good data serving with its true age; a crash
 *     between writing a body and moving the pointer leaves unreferenced bytes rather than a pointer naming data that
 *     is not there.</li>
 * <li><b>Lifecycle / ownership.</b> Nothing here owns a thread. The caller decides when a refresh happens - the
 *     scheduled signal-refresh pass, or an operator with the write role - which is precisely what makes the fetch an
 *     explicit action rather than a side effect of somebody's read.</li>
 * </ol>
 */
public interface RefreshableSource extends SignalSource {

    /**
     * Draw the vendor's data and commit it, unless the source is still inside its own refresh window - in which case
     * this renders and returns without spending a request. Answers the source's freshness after the attempt, so the
     * caller can tell a refresh that landed from one that did not without reading a second accessor.
     *
     * @throws IOException the durable side could not be read or written - a wiring or infrastructure fault, never a
     *                     vendor outage, which is fail-soft and shows up in the returned {@link Freshness} instead
     */
    Freshness refresh() throws IOException;

    /**
     * The identity of the data this source currently serves - a content digest of its committed snapshot - so a
     * caller can tell whether a {@link #refresh} changed what the source answers: the passes that judge every held
     * version against this source read only what was published since their last full pass, and a catalogue that
     * changed is what sends them over everything again. Empty for a source with no durable snapshot, whose caller
     * has nothing to compare and asks for nothing.
     */
    default Optional<String> snapshot() throws IOException {
        return Optional.empty();
    }
}
