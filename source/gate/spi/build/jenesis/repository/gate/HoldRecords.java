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
 * The durable {@code holds/<kind>/<eco>/<coord>/<ver>} records, and why "is this artifact held" does not depend on
 * which modules are installed.
 *
 * <p><b>The record is the hold; the provider only explains it.</b> Whether the module that wrote a record is installed
 * is a rendering question, not a validity one. Answered from the providers alone, {@link HoldReleaseObserver#anyHolds}
 * and {@link HoldReleaseObserver#heldByAnotherKind} would release everything an uninstalled module held, since
 * {@code ComplianceScreen} and {@link HoldClears} consume a {@code false} permissively.
 *
 * <p>The keys are composed, the kind index enumerated and the coordinate-keyed read answered by {@link HoldMarkers} in
 * the inventory, which the inventory's own name-enumeration screen also reads; this class adds what needs discovery.
 * {@link HoldKindObserver#kind()} is the second segment of every key, so that segment is an enumerable index of
 * kinds ({@link #kinds}) and the rest a constructible probe ({@link #key}).
 *
 * <p><b>Fail-closed.</b> Every read propagates its {@link IOException}, and probes use
 * {@link ArtifactStore#readVersioned} rather than {@link ArtifactStore#exists}, which cannot report a store failure.
 *
 * <p><b>Orphaned holds.</b> A kind with a record and no provider is {@link #orphanedKinds orphaned}: it still holds and
 * is shown as orphaned, and no sweep will release it. {@link #releaseOrphaned} reaps such records only from
 * {@code HoldLifecycle}'s release and discard legs, where a human acted. No override is promoted for it, since an
 * override's body is that kind's own vocabulary; a reinstalled module's sweep re-evaluates the coordinate from scratch.
 */
public final class HoldRecords {

    private static final Logger LOGGER = LoggerFactory.getLogger(HoldRecords.class);

    /** The one segment under {@code holds/} that is not a hold kind: {@link QuarantineDispatch}'s replay context at
     *  {@code holds/dispatch<path>}. */
    static final String DISPATCH = HoldMarkers.DISPATCH;

    /** The {@code holds/dispatch} root {@link QuarantineDispatch} keys its replay context under. */
    static final String DISPATCH_ROOT = HoldMarkers.DISPATCH_ROOT;

    private HoldRecords() {
    }

    /** The record key for one kind's hold on one coordinate version, every segment URL-encoded so a {@code /} or an
     *  empty value cannot splice segments and make two records collide. */
    public static String key(String kind, String ecosystem, String coordinate, String version) {
        return HoldMarkers.key(kind, ecosystem, coordinate, version);
    }

    /** Every hold kind with a surviving record in this scoped store, installed or not: the second segment of
     *  {@code holds/} minus {@link #DISPATCH}. A truncated enumeration is refused rather than returned, since a dropped
     *  kind would un-hold everything it holds. */
    public static SortedSet<String> kinds(ArtifactStore store) throws IOException {
        return HoldMarkers.kinds(store);
    }

    /** The kinds of the installed hold kinds: what can be explained and released, never what counts as held. The
     *  discovery refuses two hooks of one kind, which a set would silently merge. */
    public static SortedSet<String> installedKinds() {
        SortedSet<String> kinds = new TreeSet<>();
        for (HoldReleaseObserver observer : HoldReleaseObserver.discovered()) {
            if (observer instanceof HoldKindObserver kind) {
                kinds.add(kind.kind());
            }
        }
        return Collections.unmodifiableSortedSet(kinds);
    }

    /** The kinds holding {@code (ecosystem, coordinate, version)}, from the durable records alone, with no
     *  discovery. */
    public static SortedSet<String> heldKinds(ArtifactStore store, String ecosystem, String coordinate, String version)
            throws IOException {
        return HoldMarkers.heldKinds(store, ecosystem, coordinate, version);
    }

    /**
     * The kinds holding the coordinate version {@code path} maps to; empty for a path naming no versioned artifact (a
     * checksum, generated metadata, a raw upload).
     *
     * <p>The path is mapped by the installed formats first, since a live layout is current, and otherwise by the
     * {@link HeldSubjects} record written where the hold was placed, so an uninstalled format does not make a hold read
     * as released. A path neither can place answers empty, which each consumer judges as "nothing could be asked".
     */
    public static SortedSet<String> heldKinds(ArtifactStore store, String path) throws IOException {
        Optional<HeldSubjects.Subject> subject = subject(new StoreRepositoryInventory(store), store, path);
        if (subject.isEmpty()) {
            return Collections.emptySortedSet();
        }
        return heldKinds(store, subject.get().ecosystem(), subject.get().coordinate(), subject.get().version());
    }

    /** The kinds holding each of {@code paths}, with one enumeration of the kind index for the whole page. Every path
     *  gets an entry, empty where nothing holds it or nothing maps it. */
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
     * The coordinate version {@code path} names, from the installed formats or else the {@link HeldSubjects} record;
     * the one resolution every path-keyed read here shares. Only a subject that
     * {@link HeldSubjects.Subject#versioned() names a version} is returned, since only that composes a key.
     */
    private static Optional<HeldSubjects.Subject> subject(StoreRepositoryInventory inventory, ArtifactStore store,
                                                          String path) throws IOException {
        Optional<ArtifactDescriptor> descriptor = inventory.describe(path);
        if (descriptor.isPresent() && descriptor.get().coordinate() != null
                && descriptor.get().version() != null) {
            return Optional.of(new HeldSubjects.Subject(path, descriptor.get().ecosystem(),
                    descriptor.get().coordinate(), descriptor.get().version(), null));
        }
        if (descriptor.isPresent()) {
            return Optional.empty();   // a claiming format placed it and it names no version - an answer, not a gap
        }
        return HeldSubjects.read(store, path).filter(HeldSubjects.Subject::versioned);
    }

    /** Of {@code held}, the kinds no installed provider answers to. An orphaned kind holds exactly as an installed one
     *  does. */
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
     * Deletes the records of every orphaned kind holding {@code path} and logs each, returning the kinds reaped. An
     * operator verb, called only from {@code HoldLifecycle}'s release and discard legs, where a human decided the hold;
     * an installed kind's record is consumed by its own observer instead. Runs inside the pre-commit window under the
     * same contract: delete-if-present, and an {@link IOException} propagates with the hold standing.
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
