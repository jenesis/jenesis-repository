package build.jenesis.repository.gate;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.store.Providers;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.inventory.HeldSubjects;
import build.jenesis.repository.inventory.HoldMarkers;
import build.jenesis.repository.inventory.StoreRepositoryInventory;

/**
 * The single owner of the durable {@code holds/<kind>/<eco>/<coord>/<ver>} key space, and the reason "is this artifact
 * held" no longer depends on which modules happen to be installed.
 *
 * <p><b>The record is the hold; the provider only explains it.</b> A retroactive hold is a statement an enforcement
 * sweep wrote into the store. Whether the module that wrote it is installed right now is a <em>rendering</em>
 * question, not a <em>validity</em> question - the same distinction the console's finding marks draw, where a row
 * whose plug-in is gone is shown as orphaned and never dropped. Before this class,
 * {@link HoldReleaseObserver#anyHolds} and {@link HoldReleaseObserver#heldByAnotherKind} answered by fanning out over
 * the discovered providers alone, so uninstalling (or switching off) a compliance module made both answer
 * {@code false} while that kind's records survived by design - and both callers consume the answer permissively
 * ({@code ComplianceScreen} then treats a {@code /quarantine} pointer as not sweep-owned, so an accepted re-publish
 * clears it; {@link HoldClears} then lifts a withhold marker the absent kind still needs). Uninstalling a compliance
 * module silently released everything it was holding: fail-open, and against this product's standing rule that module
 * absence must never trigger deletion or release.
 *
 * <p>So the durable records are authoritative, and this class is what makes the store able to answer without the
 * provider list. <b>The key space's owner is one module down</b> - {@link HoldMarkers} in the inventory, which
 * composes every key, enumerates the kind index and answers the coordinate-keyed read; this class delegates those
 * three and keeps everything that needs discovery. It had to come down because a second consumer needs the same
 * answer and cannot depend on the gate: the inventory's name-enumeration screen has to know whether a version of an
 * ecosystem NO installed format can place is held, which is exactly when both of its own withholding faces - both
 * layout-resolved - go silent. It moved to the module that already owns every sibling per-version key space, for the
 * same reason {@code OverrideRecords} did. The read is answerable at all because the layout is uniform and always
 * was: {@link HoldReleaseObserver#kind()} is defined
 * as "the {@code <kind>} segment of its {@code holds/<kind>/<eco>/<coord>/<ver>} keys", which makes the second segment
 * an enumerable index of kinds ({@link #kinds}) and the remaining three a constructible probe ({@link #key}). The one
 * change needed to make it answerable was to converge the three kinds' key builders here: KEV and license used to
 * URL-encode only the coordinate while reachability encoded all three segments, so no single construction could find
 * all three. Every kind now writes the fully-encoded spelling reachability already used, whose reason stands on its
 * own - an un-encoded version or ecosystem carrying a {@code /} (or empty) splices extra segments into the key and
 * lets one release's record collide with another's. The sibling {@code overrides/<kind>/} space has the same single
 * owner one module down, {@code OverrideRecords} in the inventory: the claim once made here - that an override is
 * read only by the kind that wrote it, so it needs no kind-neutral construction - was false, because a version's
 * eviction and the reconcile sweep both reap that space kind-neutrally, and while each writer spelled its own key the
 * reaper deleted one nobody wrote and stranded the rest. It lives in the inventory rather than here because
 * the inventory is what reaps it and already owns every sibling per-version key space.
 *
 * <p><b>Fail-closed.</b> Every read here propagates its {@link IOException} rather than answering {@code false}: a
 * caller that cannot prove no hold covers a path must not clear it. The probes go through
 * {@link ArtifactStore#readVersioned} rather than {@link ArtifactStore#exists} for exactly that reason - {@code exists}
 * has no way to report a store failure, so an I/O error would read as "not held".
 *
 * <p><b>Orphaned holds, and what releasing one means.</b> A kind with a record but no discovered provider is
 * {@link #orphanedKinds orphaned}: it still holds (the record is authoritative), it is shown as orphaned wherever
 * holds are shown, and no sweep will ever come back to release it while its module is gone. An operator must not be
 * stuck with that forever, so {@link #releaseOrphaned} reaps such records - but <em>only</em> from
 * {@code HoldLifecycle}'s release and discard legs, i.e. only when a human has explicitly acted on the hold at a
 * review surface. Absence alone reaps nothing, ever. No override is promoted for an orphaned kind: an override's body
 * is that kind's private vocabulary (a CVE set, a licence reason) and inventing one would be fabricating a human
 * decision the human never expressed. If the module is reinstalled its sweep re-evaluates the coordinate from scratch
 * and may hold it again, which is the honest outcome - a stale override would silently suppress that.
 */
public final class HoldRecords {

    private static final Logger LOGGER = LoggerFactory.getLogger(HoldRecords.class);

    /** The one segment under the {@code holds/} root that is NOT a hold kind: {@link QuarantineDispatch} keys its
     *  stored replay context at {@code holds/dispatch<path>}. Named from {@link HoldMarkers#DISPATCH} - the key space's
     *  owner - so {@link QuarantineDispatch}'s root and the kind enumeration that must skip it can never drift apart. */
    static final String DISPATCH = HoldMarkers.DISPATCH;

    /** The whole {@code holds/dispatch} root {@link QuarantineDispatch} keys its replay context under, named from
     *  {@link HoldMarkers#DISPATCH_ROOT} - the key space's owner - so neither segment of it is spelled twice. */
    static final String DISPATCH_ROOT = HoldMarkers.DISPATCH_ROOT;

    private HoldRecords() {
    }

    /** The durable record key for one kind's hold on one coordinate version. Every segment is URL-encoded: an
     *  un-encoded version or ecosystem carrying a {@code /} (or empty) would splice extra segments into the key and
     *  let one release's record collide with another's - and a kind-neutral reader could not construct it. Composed by
     *  {@link HoldMarkers}, the key space's one owner, so this construction and the inventory's own
     *  coordinate-keyed read cannot diverge the way the four {@code overrides/} spellings did. */
    public static String key(String kind, String ecosystem, String coordinate, String version) {
        return HoldMarkers.key(kind, ecosystem, coordinate, version);
    }

    /** Every hold kind that has ever written a record in this scoped store - the second segment of {@code holds/},
     *  minus the {@link #DISPATCH} squatter. Note what this is NOT: it is not the installed providers, and a kind
     *  appears here for as long as one of its records survives, whether or not its module is still on the graph.
     *
     *  <p>A truncated enumeration is refused rather than returned: dropping a kind from this index would silently
     *  un-hold every coordinate that kind holds, so "I could not enumerate the kinds" and "nothing holds this" must
     *  never be the same answer. Enumerated by {@link HoldMarkers#kinds}, with its bounds and its refusal. */
    public static SortedSet<String> kinds(ArtifactStore store) throws IOException {
        return HoldMarkers.kinds(store);
    }

    /** The {@link HoldReleaseObserver#kind()} tokens of the providers this deployment has installed right now - what
     *  can be <em>explained</em> and <em>released</em>, never what counts as held. */
    public static SortedSet<String> installedKinds() {
        // Through the primitive, not a TreeSet: a set MERGES two observers answering to one kind, so the capability
        // answer reported the pair as a single installed kind and looked right doing it. The shared validation
        // refuses the clash instead.
        return Providers.installedNames("hold-release", ServiceLoader.load(HoldReleaseObserver.class),
                HoldReleaseObserver::kind, _ -> true);
    }

    /** The kinds holding {@code (ecosystem, coordinate, version)} right now, read from the durable records alone -
     *  through {@link HoldMarkers#heldKinds}, the key space's owner, which the inventory's name-enumeration screen
     *  reads the same answer from. No format, no provider list, no discovery of any sort. */
    public static SortedSet<String> heldKinds(ArtifactStore store, String ecosystem, String coordinate, String version)
            throws IOException {
        return HoldMarkers.heldKinds(store, ecosystem, coordinate, version);
    }

    /**
     * The kinds holding the coordinate version {@code path} maps to. Empty for a path that maps to no coordinate at
     * all - a checksum, generated metadata, a raw upload - which is a real answer: with no coordinate there is no
     * record key to look under, because there is no versioned artifact to hold.
     *
     * <p><b>This used to be the last dependence on an installed format left standing, and it is
     * closed.</b> The route from a request path to a coordinate is the owning format's layout reverse mapping, so
     * uninstalling a <em>format</em> module made this answer empty while the coordinate-keyed records it should have
     * found sat there intact - a hold that reads as released because a module is absent. Nothing already persisted
     * could stand in ({@code publish/<path>} is a bare content hash by design; {@code holds/dispatch<path>} carries a
     * format name and a body hash but no coordinate, and only the screen-time legs write it; the quarantine index
     * fuses coordinate and version into one string with no ecosystem and is best-effort and age-pruned; every other
     * per-version space is keyed <em>by</em> the triple with no reverse index), so the record was invented:
     * {@link HeldSubjects}, written where a hold is placed - screen-time and retroactive alike - and reclaimed with
     * the hold. It is affordable precisely because every consumer is a hold path, so it is bounded by the review
     * queue rather than by the repository.
     *
     * <p>So the lookup is: ask the installed formats first, because a live layout is the current truth and a
     * re-published path may have moved; fall back to the durable record, which was written when that format was still
     * installed and answers with no discovery of any sort. A path neither can place answers empty, and every consumer
     * of that emptiness judges it as "nothing could be asked" at its own site, exactly as before.
     */
    public static SortedSet<String> heldKinds(ArtifactStore store, String path) throws IOException {
        Optional<HeldSubjects.Subject> subject = subject(new StoreRepositoryInventory(store), store, path);
        if (subject.isEmpty()) {
            return Collections.emptySortedSet();
        }
        return heldKinds(store, subject.get().ecosystem(), subject.get().coordinate(), subject.get().version());
    }

    /** The kinds holding each of {@code paths}, for a whole review-queue page, with ONE enumeration of the kind index
     *  for the page rather than one per row - the shape the console's hold queue reads. Every path gets an entry, empty
     *  where nothing holds it (or where neither an installed format nor a durable {@link HeldSubjects} record maps it
     *  to a coordinate), so a caller never has to distinguish "not held" from "not asked". */
    public static Map<String, SortedSet<String>> heldKinds(ArtifactStore store, List<String> paths)
            throws IOException {
        Map<String, SortedSet<String>> held = new LinkedHashMap<>();
        if (paths.isEmpty()) {
            return held;
        }
        SortedSet<String> kinds = kinds(store);
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        for (String path : paths) {
            SortedSet<String> holding = new TreeSet<>();
            Optional<HeldSubjects.Subject> subject = subject(inventory, store, path);
            if (subject.isPresent()) {
                for (String kind : kinds) {
                    if (store.readVersioned(key(kind, subject.get().ecosystem(), subject.get().coordinate(),
                            subject.get().version())).isPresent()) {
                        holding.add(kind);
                    }
                }
            }
            held.put(path, holding);
        }
        return held;
    }

    /**
     * The coordinate version {@code path} names, from the installed formats if any can place it and from the durable
     * {@link HeldSubjects} record if none can - the one resolution every path-keyed read here shares, so the fallback
     * cannot be wired into one of them and forgotten in another.
     *
     * <p>Empty means "this path names no versioned artifact": either an installed format placed it and it carries no
     * coordinate, or the hold recorded that same answer when it was placed, or nothing has ever been able to say. Only
     * a subject that {@link HeldSubjects.Subject#versioned() names a version} is returned, because that is the only
     * shape a {@code holds/} key can be composed from.
     */
    private static Optional<HeldSubjects.Subject> subject(StoreRepositoryInventory inventory, ArtifactStore store,
                                                          String path) throws IOException {
        Optional<ArtifactDescriptor> descriptor = inventory.describe(path);
        if (descriptor.isPresent() && descriptor.get().coordinate() != null
                && descriptor.get().version() != null) {
            return Optional.of(new HeldSubjects.Subject(path, descriptor.get().ecosystem(),
                    descriptor.get().coordinate(), descriptor.get().version()));
        }
        if (descriptor.isPresent()) {
            return Optional.empty();   // a claiming format placed it and it names no version - an answer, not a gap
        }
        return HeldSubjects.read(store, path).filter(HeldSubjects.Subject::versioned);
    }

    /** Of {@code held}, the kinds no installed provider answers to - the holds whose module is gone. Presentation and
     *  operator-verb input only: an orphaned kind holds exactly as hard as an installed one. */
    public static SortedSet<String> orphanedKinds(Collection<String> held) {
        SortedSet<String> installed = installedKinds();
        SortedSet<String> orphaned = new TreeSet<>(held);
        orphaned.removeAll(installed);
        return orphaned;
    }

    /** The kinds holding {@code path} that no installed provider answers to. */
    public static SortedSet<String> orphanedKinds(ArtifactStore store, String path) throws IOException {
        return orphanedKinds(heldKinds(store, path));
    }

    /**
     * Reap the records of every kind holding {@code path} that no installed provider answers to, returning the kinds
     * reaped. <b>This is an operator verb, not a sweep.</b> It is called only from {@code HoldLifecycle}'s release and
     * discard legs - i.e. only where a human has decided the fate of this hold at a review surface - and it is what
     * keeps an orphaned hold releasable rather than permanent. Module absence on its own never reaches here, so
     * absence still releases nothing.
     *
     * <p>An installed kind's record is consumed by that kind's own {@link HoldReleaseObserver#onReleased}/
     * {@link HoldReleaseObserver#onDiscarded}, which also promotes the override that stops its sweep re-holding a
     * human's release. An orphaned kind has neither, so this deletes the record and says so in the log, naming the
     * kind - see the class note for why no override is invented in its place.
     *
     * <p>Called from inside the pre-commit window and holding to the same contract: delete-if-present (so a retry
     * converges), and any {@link IOException} propagates so the release or discard fails with the hold still standing.
     */
    public static SortedSet<String> releaseOrphaned(ArtifactStore store, String path) throws IOException {
        SortedSet<String> orphaned = orphanedKinds(store, path);
        if (orphaned.isEmpty()) {
            return orphaned;
        }
        Optional<HeldSubjects.Subject> subject = subject(new StoreRepositoryInventory(store), store, path);
        if (subject.isEmpty()) {
            return Collections.emptySortedSet();   // unreachable: orphanedKinds is empty for such a path
        }
        for (String kind : orphaned) {
            String key = key(kind, subject.get().ecosystem(), subject.get().coordinate(),
                    subject.get().version());
            if (store.readVersioned(key).isPresent()) {
                store.delete(key);
            }
            LOGGER.warn("hold-records: released the '{}' hold on {} - no installed module answers to that kind, so no "
                    + "override was recorded; reinstalling it lets its sweep re-evaluate the coordinate", kind, path);
        }
        return orphaned;
    }

}
