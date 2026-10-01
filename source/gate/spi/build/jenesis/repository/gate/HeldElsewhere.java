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
 * The two questions a release or a discard asks of the holds it is not lifting: whether another path of the same
 * version is still held ({@link #othersStillHeld}), and whether another coordinate's live review pointer still needs a
 * content hash a clear would lift the marker for ({@link #withheldByAnotherAlias}). Both only read.
 */
public final class HeldElsewhere {

    private HeldElsewhere() {
    }

    /**
     * Whether any path of {@code artifact}'s version other than {@code path} still carries a live {@code /quarantine}
     * pointer: the guard before a discard of one path deletes version-scoped state (hold records, findings, markers,
     * served blobs) the remaining held paths are reviewed against. Checks the discarded path's own directory in the
     * quarantine tree and the version's released paths.
     *
     * <p>Fail-closed: when no installed format can enumerate the version's paths
     * ({@link StoreRepositoryInventory#knownPaths} is unknown), the answer is "still held", and the last discard that
     * can be judged reaps the state.
     */
    public static boolean othersStillHeld(ArtifactStore store, ArtifactDescriptor artifact, String path)
            throws IOException {
        int slash = path.lastIndexOf('/');
        String directory = slash < 0 ? "/" : path.substring(0, slash + 1);
        // At most one child is `path` itself, so the first two names settle whether another exists.
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
        for (String sibling : siblings.determined().answer().orElse(List.of())) {
            if (!sibling.equals(path) && store.readVersioned(Publication.quarantineKey(sibling)).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code hash} is still withheld on account of another coordinate: a live {@code /quarantine<path>}
     *  pointer outside {@code excludedPaths} (the releasing version's own served paths) whose hold covers it. The
     *  marker is content-addressed and the blobs-namespace serve gate keys on it, so clearing it while a byte-identical
     *  sibling is held would un-withhold that sibling.
     *
     *  <p>Answered from the {@link HeldBy} index the holds write, each recorded path checked live by one point read; a
     *  retroactive sweep records every served path under every hash the version serves, so the answer is exact even for
     *  a sibling whose format is uninstalled.
     *
     *  <p>{@link Known.Present} names the holding path; {@link Known.Absent} is the only answer that lifts a marker;
     *  {@link Known.Unknown} covers more holders than one page reads ({@link Known.Cause#TRUNCATED}) and, while the
     *  {@linkplain #backfill backfill} is owed, a pointer nothing installed can place ({@link Known.Cause#UNINSTALLED})
     *  or read ({@link Known.Cause#FAILED}). A store {@link IOException} propagates, so the caller does not clear. */
    public static Known<String> withheldByAnotherAlias(ArtifactStore store, String hash, Set<String> excludedPaths)
            throws IOException {
        return withheldByAnotherAlias(store, Set.of(hash), excludedPaths).get(hash);
    }

    /** The most holders one hash is read for; past it the answer is Unknown rather than a partial list. */
    private static final int HOLDERS_PAGE = 1000;

    /**
     * The same question for a set of hashes, one page of point reads per hash in the {@link HeldBy} index. An indexed
     * holder whose pointer is not live is no holder, and stays in the index.
     *
     * <p>Until the repository is stamped complete, the first question {@linkplain #backfill backfills} the index, and
     * while that cannot complete the answer comes from the descent itself, so no release lifts a marker on an
     * incomplete index.
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
                // Left standing: a hold one write short of its pointer looks the same as a stale entry.
                if (store.readVersioned(Publication.quarantineKey(path)).isPresent()) {
                    answer = Known.known(path);
                }
            }
            answers.put(hash, answer);
        }
        return answers;
    }

    /**
     * Indexes a repository's review pointers: each pointer is read once, the hash it names and every blob hash of its
     * coordinate are recorded for its served path, and the repository is stamped complete unless a pointer nobody can
     * judge was met. The caller's answer for {@code hashes} is returned only when the stamp was withheld; otherwise the
     * caller reads the index. Bounded by the held paths ({@link HeldPointers}), and taken once per repository.
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


    /** The full {@link StoreRepositoryInventory#blobHashes} set the coordinate behind the review pointer at
     *  {@code servedPath} needs, not only the first hash its pointer names. {@link Known.Absent} for a path a format
     *  handles that names no versioned artifact; {@link Known.Unknown}, which no clear seam accepts, for a path no
     *  installed format claims. */
    public static Known<Set<String>> siblingHashes(StoreRepositoryInventory inventory, String servedPath)
            throws IOException {
        Optional<ArtifactDescriptor> described = inventory.describe(servedPath);
        if (described.isEmpty()) {
            // A handled path no layout describes (a raw asset, an OCI upload) keeps no sibling hashes; its own hash
            // was already compared. Only a path no installed format claims cannot be judged.
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
