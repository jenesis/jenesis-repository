package build.jenesis.repository.gate;

import module java.base;
import build.jenesis.repository.store.Providers;
import build.jenesis.repository.store.ArtifactStore;

/**
 * A hook run when a reviewer releases or discards a quarantined path, discovered with {@link ServiceLoader} so every
 * release surface ({@code GatedRepository.release}, {@code ComplianceReview.releaseQuarantined}) calls
 * {@link #released} once instead of naming each hold kind, and a new retroactive-hold kind joins by adding a provider.
 * An observer promotes its kind's retroactive hold record into an override marker, so a later enforcement sweep never
 * re-holds what a human cleared.
 *
 * <p>A provider explains and releases a hold; it does not make one. The hold is the durable {@code holds/<kind>/}
 * record, and {@link #anyHolds} and {@link #heldByAnotherKind} answer from {@link HoldRecords} first, so uninstalling a
 * module leaves everything it held held.
 *
 * <h2>Contract</h2>
 *
 * <p>{@code HoldReleaseContract} in the store test kit drives the executable clauses through the release fixtures.
 *
 * <ol>
 * <li><b>Role - a pre-commit, fail-closed mutation hook, despite the name.</b> It rides the gate's own
 *     {@code uses HoldReleaseObserver} clause, so it can never be routed into the after-commit observer family whose
 *     failures are swallowed. {@code HoldLifecycle.release} calls {@link #released} first: before the release link,
 *     before {@code HoldClears} lifts the withhold marker, before the {@code /quarantine} pointer is unpublished, the
 *     dispatch descriptor consumed and the release event sent. {@code HoldLifecycle.discard} calls {@link #discarded}
 *     before the dispatch descriptor is dropped, a retroactive hold's release pointer unpublished, a blobs-namespace
 *     version's served blobs evicted and the {@code /quarantine} pointer cleared; only the quarantine log rows are reaped
 *     before it, so a discard hook must not assume its {@code audit/quarantine} row exists. The role must never move
 *     onto a deferred or outbox-backed delivery: a release visible before its override marker is written is exactly
 *     what the enforce sweeps re-hold.</li>
 * <li><b>Thread-safety.</b> A hook is discovered once and shared by every fan-out on every thread, so it holds no
 *     mutable instance state. Fan-outs themselves run concurrently over the same {@code holds/} and
 *     {@code overrides/} keys (two reviewers, or a review racing an enforce sweep), so a hook that reads then writes such
 *     a key uses compare-and-set with bounded retries.</li>
 * <li><b>Idempotency / replay.</b> Every method converges when called again with the same arguments: the fan-out has
 *     no checkpoint, so a failure at hook <i>k</i> leaves hooks <i>1..k-1</i> durable and the retry re-runs all of
 *     them. An override promotion is an upsert, a record deletion a delete-if-present.</li>
 * <li><b>Absence sentinel.</b> {@code null} is never legal. {@link #holds} answers {@code false} for a path no format
 *     maps and for a coordinate this kind never held; {@link #kind()} is never blank. {@link #onReleased} and
 *     {@link #onDiscarded} are no-ops for a path this kind never held and invent no record or override.</li>
 * <li><b>Selection failure.</b> The policy is {@code ALL}: every discovered hook runs and no arrangement is a
 *     resolution error. Removing a provider is not fail-open, because {@link #anyHolds} and {@link #heldByAnotherKind}
 *     union the durable {@link HoldRecords} with the provider fan-out; a kind with a record and no provider is
 *     {@link HoldRecords#orphanedKinds orphaned}, shown in the review queue and reaped only by an operator's explicit
 *     release or discard ({@link HoldRecords#releaseOrphaned}).
 *
 *     <p>Without the format that maps a path to a coordinate, the path-keyed {@link #anyHolds} and
 *     {@link #heldByAnotherKind(ArtifactStore, String, String)} answer {@code false} from both legs where nothing
 *     recorded the subject. That {@code false} means "nothing could be asked", and each consumer judges it:
 *     {@code PublishHolds.sweepHeld} treats the path as sweep-owned, the KEV auto-release refuses. A site that already
 *     has the coordinate uses {@link #heldByAnotherKind(ArtifactStore, String, String, String, Collection, String)},
 *     whose durable leg needs no format.</li>
 * <li><b>Tenant scoping.</b> A hook carries no tenant: the handed {@link ArtifactStore} is tenant-and-repository
 *     scoped, every key is relative to it, and a hook opens no other store, so one tenant's release cannot clear
 *     another's hold.</li>
 * <li><b>Error visibility.</b> Fail-closed: any exception propagates out of {@link #released} and {@link #discarded}
 *     unchanged, so the release or discard fails and nothing is mutated, and a hook never swallows its own store
 *     failure. {@link #anyHolds} and {@link #heldByAnotherKind} propagate too, so a caller that cannot prove a path
 *     unheld does not clear it.</li>
 * <li><b>Read purity and write scope.</b> {@link #holds} and {@link #kind()} read the store and layout
 *     {@code describe} only, with no external I/O and no mutation, since {@link #anyHolds} sits on the accepted-publish
 *     path and inside {@code HoldClears}' guard. {@link #onReleased} and {@link #onDiscarded} write only inside the
 *     {@code StorageNamespace} prefixes their module declares.</li>
 * <li><b>Staleness.</b> Every method re-reads durable state; no hook caches {@code holds/} or {@code overrides/},
 *     which sweeps it never observes write.</li>
 * <li><b>Lifecycle / ownership.</b> {@link #discovered()} answers the one {@link ServiceLoader} discovery, made on
 *     first use and held for the process, since what the module path carries does not change. Each fan-out has an overload taking the hooks, the
 *     substitution seam a suite drives the real choreography through. A provider has a cheap public no-argument
 *     constructor, owns no thread, client or connection, keeps no state across calls and is never closed.</li>
 * <li><b>Ordering / concurrency.</b> Fan-out order is not part of the contract: each hook owns its own
 *     {@code holds/<kind>/} and {@code overrides/<kind>/} keys, reads no other kind's records during the fan-out, and
 *     depends on no other hook. Every hook completes before the release becomes visible.</li>
 * <li><b>Bounded work / cancellation.</b> Both fan-outs run synchronously on the reviewer's request thread with no
 *     timeout or cancellation, so a hook bounds its own work by the size of the review state: a per-coordinate record
 *     read, a per-version guard, a lookup in a queued-work space. It never walks the artifact tree, fetches over the
 *     network or reads an artifact body. Nothing enforces this.</li>
 * <li><b>Durability / delivery.</b> Stronger than at-least-once: the mutation does not happen unless every hook
 *     succeeded. A crash between two hooks leaves the earlier effects durable and the {@code /quarantine} pointer
 *     standing for the retry; a crash after the last hook leaves the artifact held and overridden, which a re-run
 *     converges from and no sweep re-holds. The {@code publish/quarantine<path>} pointer and the
 *     {@code overrides/<kind>/} marker are re-read from the store, so a crash is indistinguishable from a retry.</li>
 * </ol>
 */
public interface HoldReleaseObserver {

    /** React to a reviewer releasing {@code path}: a no-op unless this observer's hold kind held the path. */
    void onReleased(ArtifactStore store, String path) throws IOException;

    /** React to a reviewer discarding {@code path}: drop this observer's hold record for it, since a discarded version
     *  has no {@code published} record any sweep would reach. No override is promoted. A no-op by default. */
    default void onDiscarded(ArtifactStore store, String path) throws IOException {
    }

    /** Whether this observer's kind holds a retroactive record for {@code path}'s coordinate version, which the
     *  accepted-re-publish guard asks of every kind. {@code false} by default. */
    default boolean holds(ArtifactStore store, String path) throws IOException {
        return false;
    }

    /** The kind token of this observer's hold records, the {@code <kind>} segment of its
     *  {@code holds/<kind>/<eco>/<coord>/<ver>} keys, so an automated release of one kind can ask whether another
     *  still holds. The default, the class name, matches no hold key. */
    default String kind() {
        return getClass().getName();
    }

    /** Whether any hold kind holds a retroactive record for {@code path}, which decides whether a pointer at it is
     *  sweep-owned. The durable {@link HoldRecords} answer first, so an uninstalled kind still holds; the provider
     *  fan-out only widens the answer. */
    static boolean anyHolds(ArtifactStore store, String path) throws IOException {
        return anyHolds(store, path, discovered());
    }

    /** {@link #anyHolds} over an explicit set of hooks. */
    static boolean anyHolds(ArtifactStore store, String path, Iterable<HoldReleaseObserver> observers)
            throws IOException {
        if (!HoldRecords.heldKinds(store, path).isEmpty()) {
            return true;
        }
        for (HoldReleaseObserver observer : observers) {
            if (observer.holds(store, path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether any kind other than {@code releasingKind} still holds a retroactive record for {@code path}: the guard
     * every automated release consults before un-retracting a pointer or marker. Answered as {@link #anyHolds} is,
     * durable records first; {@link HoldRecords#heldKinds(ArtifactStore, String)} falls back to the recorded subject
     * when no installed format places the path. A {@code false} for a path nothing can place means "nothing could be
     * asked" (see the contract's selection clause); a site that has the coordinate uses
     * {@link #heldByAnotherKind(ArtifactStore, String, String, String, Collection, String)}.
     */
    static boolean heldByAnotherKind(ArtifactStore store, String path, String releasingKind) throws IOException {
        return heldByAnotherKind(store, path, releasingKind, discovered());
    }

    /** {@link #heldByAnotherKind(ArtifactStore, String, String)} over an explicit set of hooks. */
    static boolean heldByAnotherKind(ArtifactStore store, String path, String releasingKind,
                                     Iterable<HoldReleaseObserver> observers) throws IOException {
        for (String kind : HoldRecords.heldKinds(store, path)) {
            if (!kind.equals(releasingKind)) {
                return true;
            }
        }
        for (HoldReleaseObserver observer : observers) {
            if (!observer.kind().equals(releasingKind) && observer.holds(store, path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The kind-neutral guard asked of the coordinate. The durable leg,
     * {@link HoldRecords#heldKinds(ArtifactStore, String, String, String)}, needs no installed format; the provider
     * fan-out runs over {@code servedPaths} and only widens the answer. An empty list is legitimate for a version no
     * format enumerates a path for, but never stands in for "could not enumerate": the caller keeps the hold then.
     */
    static boolean heldByAnotherKind(ArtifactStore store, String ecosystem, String coordinate, String version,
                                     Collection<String> servedPaths, String releasingKind) throws IOException {
        return heldByAnotherKind(store, ecosystem, coordinate, version, servedPaths, releasingKind, discovered());
    }

    /** {@link #heldByAnotherKind(ArtifactStore, String, String, String, Collection, String)} over an explicit set
     *  of hooks. */
    static boolean heldByAnotherKind(ArtifactStore store, String ecosystem, String coordinate, String version,
                                     Collection<String> servedPaths, String releasingKind,
                                     Iterable<HoldReleaseObserver> observers) throws IOException {
        for (String kind : HoldRecords.heldKinds(store, ecosystem, coordinate, version)) {
            if (!kind.equals(releasingKind)) {
                return true;
            }
        }
        for (HoldReleaseObserver observer : observers) {
            if (observer.kind().equals(releasingKind)) {
                continue;
            }
            for (String path : servedPaths) {
                if (observer.holds(store, path)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Fans a release out to every observer before the release surface mutates anything; nothing is caught, so a
     *  throwing hook fails the release with the hold standing. */
    static void released(ArtifactStore store, String path) throws IOException {
        released(store, path, discovered());
    }

    /** {@link #released} over an explicit set of hooks. */
    static void released(ArtifactStore store, String path, Iterable<HoldReleaseObserver> observers)
            throws IOException {
        for (HoldReleaseObserver observer : observers) {
            observer.onReleased(store, path);
        }
    }

    /** Fans a discard out to every observer before anything is destroyed, so a hook can record a durable intent about
     *  the destruction and a throwing hook leaves the discard undone. */
    static void discarded(ArtifactStore store, String path) throws IOException {
        discarded(store, path, discovered());
    }

    /** {@link #discarded} over an explicit set of hooks. */
    static void discarded(ArtifactStore store, String path, Iterable<HoldReleaseObserver> observers)
            throws IOException {
        for (HoldReleaseObserver observer : observers) {
            observer.onDiscarded(store, path);
        }
    }

    /** The installed hooks: the one {@code ServiceLoader} call for this SPI, which every static above delegates to. */
    static Iterable<HoldReleaseObserver> discovered() {
        // The primitive refuses two observers of one kind, whose shared key space would let one's release clear the
        // other's hold, and sorts by kind, so the fan-out order is the same on every node.
        return Providers.all("hold-release", HoldReleaseObservers.DISCOVERED, HoldReleaseObserver::kind, _ -> true,
                Optional::of);
    }
}
