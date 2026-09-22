package build.jenesis.repository.compliance;

/**
 * The common base of every compliance security-signal a {@link SignalSourceProvider} creates: one named,
 * config-gated contribution of external security data the compliance engine consults. The specialised contracts are
 * sub-interfaces of this marker - {@link AdvisorySource} (known vulnerabilities per coordinate),
 * {@link KnownExploitedSource} (exploited-in-the-wild membership per CVE), {@link ExploitProbabilitySource} (EPSS
 * probability per CVE), {@link HealthSource} (maintainer-health per coordinate) and {@link AdvisorySignal} (a report
 * column over advisories) - and a created source opts into one or several of them by simply implementing them, the
 * way a {@code RepositoryFormat} opts into {@code ProxyFormat} or {@code ArtifactLayout}. Each consumer filters the
 * created sources by {@code instanceof} its contract and applies that contract's own conservative merge, so "a
 * vendor module answering advisories and a KEV catalogue from one client" is one provider creating one object, not
 * two SPIs. A source implementing only this marker participates in discovery and simply appears in no specialised
 * view - the graceful base case.
 *
 * <p>It is not a bare marker: it carries the one thing every signal owes its consumers regardless of which
 * specialised contract it answers - {@link #freshness()}, the answer to "is this a real answer, and how old is it".
 * It lives here rather than on the five contracts because it is the same question with the same answer shape for all
 * of them, and because a consumer holding a source through this base type (a refresh sweep, a console listing every
 * installed signal) must be able to ask it without knowing which contract it came for.
 *
 * <p>One further sub-interface is not a kind of signal at all but a statement about where a source keeps its data:
 * {@link RefreshableSource} says this source <em>mirrors</em> its vendor, so its query paths render and its fetch is
 * a separate write-role entry point (&sect;10). A provider does not declare it in
 * {@link SignalSourceProvider#signals()} - nobody resolves <em>for</em> it - and it is detected by
 * {@code instanceof} on the created object, exactly as the five contracts above are.
 */
public interface SignalSource {

    /**
     * Whether this source currently answers from real data, and when that data was last fetched - the accessor that
     * makes an outage, a stale snapshot and a genuinely clean answer three different values (&sect;9, &sect;10).
     *
     * <p>It is abstract on purpose. A default would have to guess, and both guesses are wrong in the direction that
     * matters: a default of "authoritative" makes every source that forgot to answer claim its outage is a clean
     * answer - the exact defect this accessor exists to remove, and one this family has already met once, where a
     * created source wrapping a catalogue inherited {@code available() == true} and would have had a loosening
     * consumer release every hold during a vendor outage - while a default of "never fetched" makes every fixed
     * in-memory source read as an outage and any consumer gating on it refuse to act. A source that genuinely does
     * not fetch says so with {@link Freshness#FIXED}; there is nothing left for a default to save.
     *
     * <p>The value is a fresh reading, not a stamp taken at construction: a source refreshed after its last query
     * reports the new instant on the next call. Answering <em>renders</em> - it reads state the source already holds
     * or has durably stored, and never fetches from the vendor, refreshes or commits anything - so a console panel
     * may render it beside an empty result set without spending a request or moving a stamp.
     *
     * <p><strong>Reading it changes nothing</strong>. Asking twice with no lookup in between must give the
     * same answer: this is an accessor, and a reading that is consumed by being read turns a console panel into a
     * thief of the sweep it shares a thread with. A source whose vendor is probed <em>per key</em> rather than
     * mirrored as a whole still owes a single source-wide reading - it derives it from the lookups it actually made,
     * so that a probe of one key which reached nothing is not laundered by a sibling probe of another key answering
     * cleanly, on this thread or any other. {@link FreshnessTracker} <em>is</em> that derivation, for the whole
     * family rather than for half of it: a source holds one and records each lookup's key against it, and a
     * {@link FeedCache}-backed source gets it through the cache that made the lookups.
     */
    Freshness freshness();
}
