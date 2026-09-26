package build.jenesis.repository.health;

import module java.base;
import build.jenesis.repository.bounds.InheritedBound;
import build.jenesis.repository.compliance.HealthSource;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Epoch;
import build.jenesis.repository.store.Stamp;

/**
 * The durable maintainer-health ledger over one repository's scoped store: the version-independent, per-coordinate
 * record of the OpenSSF Scorecard-style health a source scored for a coordinate's project, persisted so every surface
 * - the compliance gate, the read endpoint, the console panel - reads a stored answer rather than re-probing the live
 * source on the read path (Principle 10: reads render what is durably there, writes refresh it). It is the health
 * sibling of the advisory {@code Findings} ledger: where that keeps a coordinate
 * <em>version's</em> vulnerability rows, this keeps one coordinate's health (health is a property of the project, not
 * a release, so the key carries no version), written by the scheduled health sweep and the publish-time persistence
 * and refreshed by an explicit rescan.
 *
 * <p>The ledger <em>is</em> a {@link HealthSource}: {@link #health} reads the stored record for a coordinate and is the
 * one seam the compliance gate reads through once the durable module is installed, so the gate scores off the persisted
 * answer instead of the live deps.dev probe. The read is fail-soft, exactly as the live source is - a coordinate with
 * no stored record, or a store read that fails, is {@link Optional#empty() absent} (a ranking signal degrades to no
 * finding, never a hard-failed gate), so a never-swept coordinate resolves to the same safe default the live probe
 * produces for a coordinate it cannot resolve. Writes are last-writer-wins idempotent upserts: a re-scan renews the
 * facts and the freshness instant, never duplicating a coordinate's record.
 *
 * <p>Reclamation rides the artifact lifecycle: the inventory's {@code evict} removes a coordinate's health record when
 * its <em>last</em> published version goes (the version-independent counterpart of the findings document going with each
 * evicted version), so the record never outlives the coordinate it describes. The key-space is declared in the storage
 * manifest by the persistence module. With no persistence module installed the SPI resolves to nothing and every writer
 * and surface degrades: nothing records, the endpoint answers the store is absent, and the gate falls back to the live
 * health source.
 */
public interface HealthLedger extends HealthSource {

    /** The repository-scope key root the ledger owns; declared in the storage manifest by the persistence module. */
    String PREFIX = "health";

    /**
     * The repository-level eviction-epoch marker: a one-segment in-tree sentinel beside the ledger's ecosystem subtrees
     * (the same shape as {@code health/scanned}, {@link #scanned health stamp}), declared in the storage manifest by the
     * persistence module. It carries an opaque token bumped every time a coordinate's health is reclaimed by
     * <em>eviction</em> (its last published version going) - the "dirty" signal the health rank index folds into its
     * rebuild stamp so an evicted coordinate drops on the next rank-index pass rather than lingering until the next
     * <em>scan</em> moves {@link #scanned health stamp}. It is deliberately NOT the scan stamp: an eviction is not a scan, so
     * bumping the freshness stamp would both misreport the panel's as-of instant (Principle 10) and make the
     * eventually-consistent read fall back - the two failure modes this separate epoch avoids.
     */
    String EVICTED = PREFIX + "/evicted";

    /** The key of the {@linkplain #scanned freshness stamp}, inside the prefix so it lives and dies with the
     *  ledger's key-space. */
    String SCANNED = PREFIX + "/scanned";

    /**
     * Record (upsert) a coordinate's maintainer-health, stamped with the instant it was scored - a last-writer-wins
     * idempotent write, so the scheduled sweep, the publish-time persistence and an explicit rescan writing the same
     * coordinate converge on the freshest answer instead of duplicating it. A record already carrying a newer or equal
     * {@code scannedAt} is left standing (a stale refresh never rolls a coordinate's health backwards).
     */
    void record(String ecosystem, String coordinate, HealthSource.Health health, Instant scannedAt) throws IOException;

    /** Every coordinate's stored health, walked from the ledger's own key tree - no live source is consulted and no
     *  artifact is read. Empty when nothing was ever recorded. */
    List<Located> all() throws IOException;

    /**
     * Stream every coordinate's stored health to {@code visitor}, walked from the ledger's own key tree without
     * materialising the whole set in heap the way {@link #all()} does - the primitive a rank-index rebuild folds over
     * so a repository with millions of scored coordinates never buffers them all.
     *
     * <p><strong>A ledger streams its own walk; the inherited body is a small-ledger fallback and says so out loud.</strong>
     * The {@code default} delegates to {@link #streamByListing}, which buffers the whole {@link #all()} answer and
     * emits it row by row - precisely what this signature exists to avoid, and what makes the rank-index rebuild's
     * "never buffers them all" false for anything that inherits it. So it refuses rather than pretending: past
     * {@link ArtifactStore#MAX_INHERITED_CHILDREN} records it throws an {@link IllegalStateException} naming the
     * inheriting class and the remedy. The store-backed ledger overrides it with a real streaming walk; an
     * implementation whose record set genuinely <em>is</em> in memory calls {@link #streamByListing} by name.
     *
     * @throws IllegalStateException when the inherited fallback holds more than
     *                               {@link ArtifactStore#MAX_INHERITED_CHILDREN} records
     */
    default void all(LedgerVisitor visitor) throws IOException {
        streamByListing(this, visitor);
    }

    /**
     * Emit {@code ledger}'s whole {@link #all()} answer to {@code visitor} record by record - the explicit, named form
     * of the fallback {@link #all(LedgerVisitor)} inherits, for an implementation whose record set is already in
     * memory. It is bounded, and the bound throws: see {@link InheritedBound}, which holds the ceiling and the
     * refusal for every SPI that ships this shape.
     *
     * @throws IllegalStateException when the ledger holds more than {@link ArtifactStore#MAX_INHERITED_CHILDREN}
     *                               records
     */
    static void streamByListing(HealthLedger ledger, LedgerVisitor visitor) throws IOException {
        for (Located located : InheritedBound.bounded(ledger, "all(LedgerVisitor)", "all()", ledger.all())) {
            visitor.accept(located);
        }
    }

    /** A sink for {@link #all(LedgerVisitor)}: receives each stored record in turn, may throw to abort the walk. */
    @FunctionalInterface
    interface LedgerVisitor {
        void accept(Located located) throws IOException;
    }

    /**
     * The repository's weakest-first health ranking: one bounded page of it, ordered by ascending overall score (a
     * reviewer meets the least-maintained projects first), or the explicit {@link Ranking.NotBuilt} state when no
     * ranking pass has committed one for this repository yet.
     *
     * <h2>What this promises</h2>
     * <ol>
     * <li><b>A ranking is served only from a committed one.</b> There is no recompute-on-read: the page a caller gets
     *     back is a page of the ranking a {@code Lease}-guarded pass built and published, never one derived on the
     *     request thread. A ledger that keeps no ranking of its own inherits this default and so reports
     *     {@link Ranking.NotBuilt}; it never fabricates a ranking by buffering {@link #all()} and sorting it.</li>
     * <li><b>"Built" means a pass left a stamp</b> - the same discipline {@code DependentsQuery.built()} holds to. It
     *     is read from the ranking pass's own completion marker, never inferred from the records: an empty ranking and
     *     an unbuilt one are different facts, and the records cannot tell them apart (a repository with no scored
     *     coordinate reads identically to one whose pass has never run).</li>
     * <li><b>The two states are separate types, so a caller must tell them apart.</b> {@link Ranking} is
     *     {@code sealed}: only {@link Ranking.Ranked} carries entries at all, so the not-built state cannot be handed
     *     to a renderer as an empty ranking - the mistake this signature exists to make impossible. A surface that
     *     showed the not-built state as an empty weakest-first list would tell an operator that nothing worse exists,
     *     which is wrong precisely in the window before the first pass (&sect;9's silent fallback, one layer up).</li>
     * <li><b>Both states carry their as-of instant</b> ({@link Ranking#scannedAt()}), so an empty panel is never
     *     ambiguous between "clean" and "never scanned" (&sect;10). A ranked page reports the ledger scan freshness the
     *     ranking was <em>built at</em> - never the live one, which the records may have moved past since - and the
     *     not-built state reports the ledger's own last sweep, so the panel can say "swept at X, not yet ranked".</li>
     * <li><b>Read purity (&sect;10).</b> Neither state writes anything, refreshes anything or probes a live health
     *     source; both render durable state alone.</li>
     * </ol>
     *
     * @param cursor the {@link Ranking.Ranked#nextCursor() next-cursor} of the previous page, or {@code null}/empty for
     *               the first
     * @param limit  the maximum number of records to return in this page (a non-positive limit yields an empty page)
     */
    default Ranking worstFirst(String cursor, int limit) throws IOException {
        // No ranking of its own, so no ranking to serve - the conservative half, and the only honest one: the
        // alternative (buffer all() and sort it here) is the whole-ledger read this signature exists to avoid, and it
        // would wear a "worst first" label while being a sample of whatever the ledger happened to answer.
        return new Ranking.NotBuilt(freshness().refreshed());
    }

    /** Rebuild any derived ranking this ledger maintains from the current records, so a subsequent
     *  {@link #worstFirst} serves a committed ranking rather than the not-built state - a no-op for a ledger that keeps
     *  no ranking. Called by the scheduled rank-index pass under its single-writer lease, never on a request thread:
     *  it mutates shared durable state, so an explicit rescan persists records and leaves the ranking to that pass. */
    default void reindex() throws IOException {
    }

    /** A coordinate's stored health located at its key, for the repository-wide health view - the record and the
     *  instant it was last scored. */
    record Located(String ecosystem, String coordinate, HealthSource.Health health, Instant scannedAt) {
    }

    /**
     * The answer {@link #worstFirst} gives: either a page of a ranking that was built, or the fact that none was. It is
     * {@code sealed} on purpose - the two states are not one state with an empty list, and a caller has to say which it
     * is rendering before it can reach a row.
     *
     * <p>Both carry {@link #scannedAt()}, the ledger scan instant the answer stands on, so &sect;10's "every derived
     * view shows its last fetch instant" holds in both states and an empty panel is never ambiguous between "clean" and
     * "never scanned".
     */
    sealed interface Ranking {

        /**
         * The ledger scan instant this answer stands on, or empty when the repository's health was never scanned.
         * For {@link Ranked} it is the scan freshness the ranking was <em>built at</em> rather than the live stamp,
         * because the ranked read is eventually consistent (it serves the last committed ranking regardless of records
         * moving since), so a surface rendering this can never show the ranking fresher than it is. For
         * {@link NotBuilt} it is the ledger's own last sweep - the half of the answer that separates "swept, not yet
         * ranked" from "nothing has ever run here".
         */
        Optional<Instant> scannedAt();

        /**
         * One weakest-first page of a committed ranking: the records in ascending-overall order, the opaque
         * {@code nextCursor} to resume after (or {@code null} when this is the last page), the {@code total} scored
         * coordinates the ranking holds (a diagnostic the panel renders as "N of M", never a completeness claim) and
         * the {@linkplain #scannedAt() build-time scan freshness}.
         */
        record Ranked(List<Located> entries, String nextCursor, int total, Optional<Instant> scannedAt)
                implements Ranking {
            public Ranked {
                entries = List.copyOf(entries);
                Objects.requireNonNull(scannedAt, "scannedAt");
            }
        }

        /**
         * No ranking pass has committed a weakest-first ranking for this repository yet - the state every deployment
         * is in until its first pass runs, and the one a ledger that keeps no ranking reports for good. It carries no
         * entries and no cursor <em>at all</em>: there is nothing to page, and an empty list here would read as "no
         * project is worse than these", which is exactly the claim that cannot be made before a ranking exists.
         *
         * <p>{@link #scannedAt()} is the ledger's last sweep, so the surface can distinguish the two shapes an
         * operator cares about - "health was swept at X but not ranked yet" from "nothing has ever run here" - and say
         * so instead of showing a blank list.
         */
        record NotBuilt(Optional<Instant> scannedAt) implements Ranking {
            public NotBuilt {
                Objects.requireNonNull(scannedAt, "scannedAt");
            }
        }
    }

    /**
     * The instant the repository's maintainer health was last refreshed against the live health source - by the
     * scheduled health sweep or an operator's explicit rescan - so every view can show how fresh its rendered ledger is.
     * Last-writer-wins: the newest completed refresh is the panel's honest freshness, whoever drove it. Absent means the
     * repository's health was never scanned, which a view must render as "never scanned", not as "healthy".
     */
    static Stamp scanned(ArtifactStore store) {
        return new Stamp(store, SCANNED);
    }

    /**
     * The {@link #EVICTED eviction epoch}: bumped when a coordinate's health was reclaimed by eviction, so the health
     * rank index rebuilds on its next pass instead of no-opping on an unmoved {@link #scanned} stamp. Bumped by the
     * artifact-lifecycle owner (the inventory's {@code evict}, on the last-version eviction that reclaims the
     * coordinate's health) <em>after</em> the record is removed, so a rebuild that observes the new epoch also observes
     * the removal. The lifecycle owner marks it through this key without reaching into the health module.
     */
    static Epoch evictions(ArtifactStore store) {
        return new Epoch(store, EVICTED);
    }
}
