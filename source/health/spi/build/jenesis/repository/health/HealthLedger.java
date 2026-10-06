package build.jenesis.repository.health;

import module java.base;
import build.jenesis.repository.bounds.InheritedBound;
import build.jenesis.repository.compliance.HealthSource;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Epoch;
import build.jenesis.repository.store.Stamp;

/**
 * The durable maintainer-health ledger over one repository's scoped store: the version-independent record of the
 * Scorecard-style health a source scored for each coordinate's project, persisted so the gate, the read endpoint and
 * the console read a stored answer rather than probing the live source. The health sibling of the {@code Findings}
 * ledger, written by the scheduled sweep and the publish-time persistence and refreshed by an explicit rescan.
 *
 * <p>The ledger <em>is</em> a {@link HealthSource}: {@link #health} is what the gate reads once the durable module is
 * installed. The read is fail-soft, as the live source is - no record, or a failed read, is
 * {@link Optional#empty() absent}, a ranking signal degrading to no finding, never a failed gate. Writes are
 * last-writer-wins upserts.
 *
 * <p>Reclamation rides the artifact lifecycle: eviction of a coordinate's last published version removes its record.
 * With no persistence module installed the SPI resolves to nothing: nothing records, the endpoint answers that the
 * store is absent, and the gate falls back to the live source.
 */
public interface HealthLedger extends HealthSource {

    /** The repository-scope key root of the ledger's own markers; declared in the storage manifest by the persistence
     *  module. */
    String PREFIX = "health";

    /** The eviction epoch marker beside the {@link #scanned health stamp}: an opaque token bumped whenever a
     *  coordinate's health is reclaimed by eviction, which the health rank index folds into its rebuild stamp so an
     *  evicted coordinate drops on the next rank-index pass. It is not the scan stamp: an eviction is not a scan, and
     *  moving the freshness stamp would misreport the panel's as-of instant. */
    String EVICTED = PREFIX + "/evicted";

    /** The key of the {@linkplain #scanned freshness stamp}, inside the prefix. */
    String SCANNED = PREFIX + "/scanned";

    /** Record (upsert) a coordinate's maintainer health, stamped with the instant it was scored, so the sweep, the
     *  publish-time persistence and a rescan converge on the freshest answer. A record scored at or after
     *  {@code scannedAt} stands, so a stale refresh never rolls health backwards. */
    void record(String ecosystem, String coordinate, HealthSource.Health health, Instant scannedAt) throws IOException;

    /** Every coordinate's stored health, from the stored records alone - no live source, no artifact. Empty when
     *  nothing was recorded. */
    List<Located> all() throws IOException;

    /**
     * Stream every coordinate's stored health to {@code visitor} without materialising the set as {@link #all()} does -
     * what a rank-index rebuild folds over.
     *
     * <p><strong>A ledger streams its own walk.</strong> The inherited default buffers {@link #all()} through
     * {@link #streamByListing}, so past {@link ArtifactStore#MAX_INHERITED_CHILDREN} records it throws, naming the
     * inheriting class and the remedy. The store-backed ledger overrides it; an in-memory one calls
     * {@link #streamByListing} by name.
     *
     * @throws IllegalStateException when the inherited fallback holds more than
     *     {@link ArtifactStore#MAX_INHERITED_CHILDREN} records
     */
    default void all(LedgerVisitor visitor) throws IOException {
        streamByListing(this, visitor);
    }

    /**
     * Emit {@code ledger}'s whole {@link #all()} answer record by record - the named form of the inherited fallback,
     * for an in-memory record set. Bounded by {@link InheritedBound}.
     *
     * @throws IllegalStateException when the ledger holds more than {@link ArtifactStore#MAX_INHERITED_CHILDREN}
     *     records
     */
    static void streamByListing(HealthLedger ledger, LedgerVisitor visitor) throws IOException {
        for (Located located : InheritedBound.bounded(ledger, "all(LedgerVisitor)", "all()", ledger.all())) {
            visitor.accept(located);
        }
    }

    /** A sink for {@link #all(LedgerVisitor)}; it may throw to abort the walk. */
    @FunctionalInterface
    interface LedgerVisitor {
        void accept(Located located) throws IOException;
    }

    /**
     * The repository's weakest-first health ranking: one page, ascending overall score, or {@link Ranking.NotBuilt}
     * when no ranking pass has committed one.
     *
     * <ol>
     *   <li><b>Served only from a committed ranking</b> a lease-guarded pass built - never derived on the request
     *       thread. A ledger that keeps no ranking inherits this default and reports {@link Ranking.NotBuilt}, never a
     *       sort of {@link #all()}.</li>
     *   <li><b>"Built" means a pass left a stamp</b>, as {@code DependentsQuery.declarationsBuiltAt()} does: it is read from the
     *       pass's marker, never inferred from the records, since an empty ranking and an unbuilt one are different
     *       facts.</li>
     *   <li><b>The states are separate types.</b> {@link Ranking} is sealed and only {@link Ranking.Ranked} carries
     *       entries, so the not-built state cannot be rendered as an empty list that says nothing worse exists.</li>
     *   <li><b>Both carry their as-of instant</b> ({@link Ranking#scannedAt()}): a ranked page the freshness it was
     *       built at, the not-built state the ledger's last sweep, so a panel can say "swept at X, not yet
     *       ranked".</li>
     *   <li><b>Read purity.</b> Neither writes, refreshes or probes a live source.</li>
     * </ol>
     *
     * @param cursor the {@link Ranking.Ranked#nextCursor() next-cursor} of the previous page, or {@code null}/empty for
     *     the first
     * @param limit the maximum number of records in this page (a non-positive limit yields an empty page)
     */
    default Ranking worstFirst(String cursor, int limit) throws IOException {
        // No ranking of its own, so none to serve: buffering all() and sorting it would be the whole-ledger read this
        // signature exists to avoid, wearing a "worst first" label.
        return new Ranking.NotBuilt(freshness().refreshed());
    }

    /** Rebuild any ranking this ledger maintains, so {@link #worstFirst} serves a committed one; a no-op for a ledger
     *  keeping none. Called by the rank-index pass under its lease, never on a request thread, since it mutates shared
     *  state. */
    default void reindex() throws IOException {
    }

    /** A coordinate's stored health and the instant it was last scored, for the repository-wide view. */
    record Located(String ecosystem, String coordinate, HealthSource.Health health, Instant scannedAt) {
    }

    /** The answer {@link #worstFirst} gives: a page of a built ranking, or the fact that none was built. Sealed, so a
     *  caller must say which it renders before it can reach a row; both carry {@link #scannedAt()}, so an empty panel
     *  is never ambiguous between "clean" and "never scanned". */
    sealed interface Ranking {

        /** The scan instant this answer stands on, or empty when the repository was never scanned. For {@link Ranked},
         *  the freshness the ranking was built at, since the ranked read serves the last committed ranking; for
         *  {@link NotBuilt}, the ledger's last sweep. */
        Optional<Instant> scannedAt();

        /** One weakest-first page of a committed ranking: the records in ascending-overall order, the opaque
         *  {@code nextCursor} ({@code null} on the last page), the {@code total} the ranking holds (rendered "N of M",
         *  never a completeness claim), and the {@linkplain #scannedAt() build-time freshness}. */
        record Ranked(List<Located> entries, String nextCursor, int total, Optional<Instant> scannedAt)
                implements Ranking {
            public Ranked {
                entries = List.copyOf(entries);
                Objects.requireNonNull(scannedAt, "scannedAt");
            }
        }

        /** No ranking pass has committed a ranking for this repository yet - every deployment's state until its first
         *  pass, and a non-ranking ledger's for good. It carries no entries or cursor: an empty list would claim no
         *  project is worse, which cannot be said before a ranking exists. {@link #scannedAt()} is the ledger's last
         *  sweep. */
        record NotBuilt(Optional<Instant> scannedAt) implements Ranking {
            public NotBuilt {
                Objects.requireNonNull(scannedAt, "scannedAt");
            }
        }
    }

    /** When the repository's maintainer health was last refreshed against the live source - by the sweep or an explicit
     *  rescan - so every view shows how fresh it is. Last-writer-wins. Absent means never scanned, which a view renders
     *  as such, not as healthy. */
    static Stamp scanned(ArtifactStore store) {
        return new Stamp(store, SCANNED);
    }

    /** The {@link #EVICTED eviction epoch}: bumped by the inventory's eviction of a coordinate's last version, after
     *  its record is removed, so a rebuild that observes the new epoch also observes the removal - without the
     *  lifecycle owner reaching into the health module. */
    static Epoch evictions(ArtifactStore store) {
        return new Epoch(store, EVICTED);
    }
}
