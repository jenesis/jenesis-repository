package build.jenesis.repository.gate;

import module java.base;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.HeldBy;
import build.jenesis.repository.store.Known;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.ServableNames;

/**
 * The two questions a release or a discard asks of the holds it is <em>not</em> lifting: whether another path of
 * the same version is still held ({@link #othersStillHeld}), and whether another coordinate's live review pointer
 * still needs a content hash a clear would lift the marker for ({@link #withheldByAnotherAlias}). Both read the
 * hold records, the review pointers and the index the holds write; neither replays, links or clears anything.
 *
 * <p>They lived on {@code HoldLifecycle}, the release primitive itself, which put the hold kinds and the clear
 * seam - the vocabulary every module that reacts to a hold reads - on the same class as the replay through the
 * screen and the formats. Every such module then required the review machinery for a query. They are the contract
 * half's now, and the lifecycle in the store half asks them exactly as the hold kinds and the clear seam do.
 */
public final class HeldElsewhere {

    private HeldElsewhere() {
    }

    /**
     * Whether any path of {@code artifact}'s version <em>other than</em> {@code path} still carries a live
     * {@code /quarantine} pointer - the guard a per-version record reaper consults before a discard of one path
     * deletes version-scoped state (a hold record, a findings document): discarding one path of a multi-path hold
     * must not strip the state the remaining held paths are reviewed against. Checks both the discarded path's own
     * directory in the quarantine tree (a publish-time-held sibling never linked into the release layout) and the
     * version's released paths (a cross-published view held under another directory).
     *
     * <p><b>Fail-closed where the version's paths cannot be enumerated at all.</b> The second leg asks the
     * owning format which paths this version serves, and with that format's module off the graph - or with a
     * roots-only layout that resolves no path for the coordinate - it answers nothing. Read as "no other path is
     * held", that silence makes a discard of ONE path reap the whole version's state: the kev/license/reachability
     * records through their {@code onDiscarded}, the metadata document and {@code findings/} sidecar through
     * {@code DiscardedHoldFindingsObserver}, the orphaned records through {@code releaseOrphaned}, the version-wide
     * withhold markers through the release leg, and the served blobs themselves through {@code discardBlobs} - all of
     * it removed while the remaining paths are still under review, because a module is absent. So the enumeration is
     * three-valued ({@link StoreRepositoryInventory#knownPaths}) and an unaskable one answers "still held": the
     * per-version state stays, and the last discard that CAN be judged reaps it.
     */
    public static boolean othersStillHeld(ArtifactStore store, ArtifactDescriptor artifact, String path)
            throws IOException {
        int slash = path.lastIndexOf('/');
        String directory = slash < 0 ? "/" : path.substring(0, slash + 1);
        // "Is any child here other than this one" is answered exactly by the first two names, so it is asked that
        // way rather than by listing the folder: at most one child can be `path` itself, so a second child - or a
        // first that is not `path` - settles it. The folder is a version's quarantine pointers, which is small in
        // every healthy case and is precisely the shape that is not in the pathological one this guards against.
        List<String> children = new ArrayList<>();
        store.page(Publication.quarantineKey(directory), "", 2, children::add);
        for (String child : children) {
            if (!(directory + child).equals(path)) {
                return true;
            }
        }
        Known<List<String>> siblings = new StoreRepositoryInventory(store).knownPaths(
                artifact.ecosystem(), artifact.coordinate(), artifact.version());
        if (siblings instanceof Known.Unknown<List<String>> _) {
            return true;    // nothing installed can enumerate this version's paths - keep every per-version record
        }
        // The Unknown arm above is the whole point of the three-valued read, so determined() cannot raise here; it is
        // the fail-closed narrowing rather than a collapse, and a fourth state would break this line rather than slip
        // through it.
        for (String sibling : siblings.determined().answer().orElse(List.of())) {
            if (!sibling.equals(path) && store.readVersioned(Publication.quarantineKey(sibling)).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /** Whether hash {@code H} is still withheld on account of ANOTHER coordinate: any live {@code /quarantine<path>}
     *  review pointer OUTSIDE {@code excludedPaths} whose hold covers {@code H}. The withhold marker is
     *  content-addressed (one marker withholds the bytes wherever served) and the blobs-namespace serve gate keys
     *  withheld on the MARKER, not the per-path {@code /quarantine} pointer - so clearing {@code H} while a
     *  byte-identical sibling coordinate is still held would un-withhold that sibling. This is {@link #othersStillHeld}
     *  generalised from same-version paths to cross-coordinate content aliases: {@code excludedPaths} is the releasing
     *  coordinate/version's own served paths (the set {@code othersStillHeld} reasons over), so a pointer that maps back
     *  to a DIFFERENT coordinate that still needs {@code H} keeps the marker standing.
     *
     *  <p><b>Answered from the index the holds write</b> ({@link HeldBy}): the served paths recorded under {@code H},
     *  each checked live by one point read of its review pointer. A hold's link records the hash its pointer names,
     *  and a retroactive sweep records every served path of the version under every content hash it serves - the
     *  full-hash form, since a multi-file coordinate's pointer advertises only its first hash (A1-F1) - so the
     *  answer is exact for a sibling whose format has since been uninstalled, which the former descent could only
     *  call unjudgeable. It used to be answered by descending every review pointer in the repository, on every
     *  release and once more to re-verify, and by every reconcile page twice; the descent is now taken once per
     *  repository, as the {@linkplain #backfill backfill} that indexes the holds from before the index.
     *
     *  <p><b>Three-valued, and that is what makes the clear seam safe.</b> A {@code boolean} here fused
     *  <em>"nothing else holds these bytes"</em> with <em>"I could not establish that"</em>, and {@code Withheld.clear}
     *  consumed the fused value as the first. The three states are: {@link Known.Present} with the holding served
     *  path (a live alias needs the hash - the marker stays), {@link Known.Absent} (no live alias claims it - the
     *  ONLY answer that lifts a marker, and the only one {@link Known.Determined}-typed {@code Withheld.clear}
     *  accepts), and {@link Known.Unknown} for a hash more pointers hold than one page reads
     *  ({@link Known.Cause#TRUNCATED}), or - while the backfill is still owed - a pointer nothing installed can
     *  place ({@link Known.Cause#UNINSTALLED}) or one that could not be read at all ({@link Known.Cause#FAILED}). A
     *  genuine store {@link IOException} propagates, so the caller does NOT clear - fail-closed, since leaving a
     *  marker is always safe and clearing wrongly is the disclosure. */
    public static Known<String> withheldByAnotherAlias(ArtifactStore store, String hash, Set<String> excludedPaths)
            throws IOException {
        return withheldByAnotherAlias(store, Set.of(hash), excludedPaths).get(hash);
    }

    /** The most holders one hash is read for; past it the answer is Unknown rather than a partial list. */
    private static final int HOLDERS_PAGE = 1000;

    /**
     * The same question for a whole set of hashes, answered per hash from the {@link HeldBy} index the holds write:
     * one page of point reads per hash - the served paths whose review pointers hold it, each checked live, less
     * the releasing coordinate's own paths - a live one left over is {@link Known.Present} with that path, none is
     * {@link Known.Absent}. An indexed holder whose pointer is not live is no holder, and is left in the index for
     * the reason {@link HeldBy} gives.
     *
     * <p>A repository from before the index has review pointers nothing indexed, and the first question asked of
     * it {@linkplain #backfill backfills} them by the descent this used to make on every release; until that
     * descent has enumerated every pointer and stamped the repository, the answer comes from the descent itself, so
     * no release lifts a marker on an index that is not yet complete. The reconcile backstop asks this for every
     * marker of a page, twice - the judgement and the re-verification after the clear - which is why the page form
     * exists.
     */
    public static Map<String, Known<String>> withheldByAnotherAlias(ArtifactStore store, Set<String> hashes,
                                                                    Set<String> excludedPaths) throws IOException {
        if (!HeldBy.complete(store)) {
            Optional<Map<String, Known<String>>> descended = backfill(store, hashes, excludedPaths);
            if (descended.isPresent()) {
                return descended.get();   // the index could not be completed: the descent's answer stands
            }
        }
        Map<String, Known<String>> answers = new HashMap<>();
        for (String hash : hashes) {
            List<String> holders = HeldBy.holders(store, hash, HOLDERS_PAGE + 1);
            if (holders.size() > HOLDERS_PAGE) {
                answers.put(hash, Known.truncated("more than " + HOLDERS_PAGE + " review pointers hold blobs/" + hash
                        + ", so whether one outside the releasing coordinate is live cannot be established in one page"));
                continue;
            }
            Known<String> answer = Known.absent();
            for (String path : holders) {
                if (excludedPaths.contains(path) || !(answer instanceof Known.Absent<String>)) {
                    continue;
                }
                // An entry whose pointer is not live is no holder and is left standing: it is a hold one write short
                // of its pointer as often as a stale one, and the two cannot be told apart here (HeldBy says why).
                if (store.readVersioned(Publication.quarantineKey(path)).isPresent()) {
                    answer = Known.known(path);
                }
            }
            answers.put(hash, answer);
        }
        return answers;
    }

    /**
     * The descent over the review-pointer subtree that indexes a repository from before the index: every pointer
     * is read once, the hash its body names and every hash its coordinate resolves among its blob hashes are
     * recorded for its served path, and the repository is stamped complete - unless a pointer nobody can judge was
     * met, since an index missing that pointer's hashes would let a release lift a marker it still needs. While
     * descending it also answers the caller's question for {@code hashes} the way the descent always did, and that
     * answer is returned when the stamp could not be written; when it could, the caller reads the index instead.
     * Bounded by the number of currently held paths through the shared {@link HeldPointers} descent, never the
     * whole repository, and taken once per repository.
     */
    private static Optional<Map<String, Known<String>>> backfill(ArtifactStore store, Set<String> hashes,
                                                                 Set<String> excludedPaths) throws IOException {
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        Map<String, Set<String>> found = new HashMap<>();
        for (String hash : hashes) {
            found.put(hash, new TreeSet<>());
        }
        Map<String, Known<String>> unknown = new HashMap<>();
        boolean[] judged = {true};   // false once a pointer nobody can judge was met: the stamp is withheld
        HeldPointers.descend(store, key -> {
            String servedPath = key.substring(HeldPointers.ROOT.length());   // /quarantine stripped == the served path
            Known<Set<String>> held;
            try {
                Optional<String> pointer = store.readVersioned(key)
                        .map(versioned -> ServableNames.hash(versioned.content()));
                if (pointer.isEmpty()) {
                    return true;
                }
                Set<String> claimed = new TreeSet<>();
                claimed.add(pointer.get());
                held = siblingHashes(inventory, servedPath);
                if (held instanceof Known.Present<Set<String>> present) {
                    claimed.addAll(present.value());
                }
                for (String hash : claimed) {
                    HeldBy.record(store, hash, servedPath);
                    if (found.containsKey(hash)) {
                        found.get(hash).add(servedPath);
                    }
                }
            } catch (RuntimeException hostile) {
                held = Known.failed("the review pointer " + key + " could not be judged", hostile);
            }
            if (held instanceof Known.Unknown<Set<String>> failed) {
                judged[0] = false;
                for (String hash : hashes) {
                    unknown.putIfAbsent(hash, Known.unknown(failed.cause(), failed.detail()
                            + " - so whether it holds " + hash + " cannot be established"));
                }
            }
            return true;
        });
        if (judged[0]) {
            HeldBy.completed(store);
            return Optional.empty();
        }
        Map<String, Known<String>> answers = new HashMap<>();
        for (String hash : hashes) {
            if (unknown.containsKey(hash)) {
                answers.put(hash, unknown.get(hash));
                continue;
            }
            Known<String> answer = Known.absent();
            for (String path : found.get(hash)) {
                if (!excludedPaths.contains(path)) {
                    answer = Known.known(path);
                    break;
                }
            }
            answers.put(hash, answer);
        }
        return Optional.of(answers);
    }


    /** The hashes the still-held sibling coordinate behind the review pointer at {@code servedPath} still needs - its
     *  full {@link StoreRepositoryInventory#blobHashes} set, not merely the first hash its pointer body advertises
     *  (A1-F1) - which the backfill records and the reconcile judges a page of markers against. Three-valued, not
     *  two: a path a format DID place and that names no versioned artifact resolves no hash set, and contributes
     *  nothing - that is an answer, {@link Known.Absent}. A path <em>no installed format can place at all</em> is not:
     *  the review pointer is standing there, something is held behind it, and with the owning format's module off
     *  the graph nothing here can say which hashes that coordinate needs. Answering "none" would let a clear lift
     *  {@code withheld/<hash>} out from under a byte-identical sibling whose pointer still stands, so it is
     *  {@link Known.Unknown}, which no clear seam accepts. */
    public static Known<Set<String>> siblingHashes(StoreRepositoryInventory inventory, String servedPath)
            throws IOException {
        Optional<ArtifactDescriptor> described = inventory.describe(servedPath);
        if (described.isEmpty()) {
            // A path an installed format handles but no layout describes belongs to a format without coordinates -
            // a raw asset, an OCI upload, a blobs-namespace publish envelope - and such a pointer keeps no sibling
            // hashes: its own hash was compared before this was asked. Answering Unknown here was measured
            // 2026-09-12 as 3,102 releases in a quarter of an hour leaving every marker they should have lifted
            // standing, behind one CocoaPods pointer at its publish path; only a path no installed format claims at
            // all is a pointer nobody can judge.
            for (RepositoryFormat format : RepositoryFormat.installed()) {
                if (format.handles(servedPath)) {
                    return Known.absent();
                }
            }
            return Known.uninstalled("a live review pointer stands at " + servedPath + " and no installed format "
                    + "claims it, so neither the coordinate it holds nor which hashes that coordinate still needs "
                    + "can be established");
        }
        if (described.get().coordinate() == null || described.get().version() == null) {
            return Known.absent();   // placed, and names no versioned artifact: there is no hash set to resolve
        }
        return Known.known(Set.copyOf(inventory.blobHashes(described.get().ecosystem(),
                described.get().coordinate(), described.get().version())));
    }
}
