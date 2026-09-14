package build.jenesis.repository.store;

import module java.base;

/**
 * The review pointers by the content they hold: {@code quarantine/by-hash/<sha256>/<sha256 of the served path>},
 * one entry per {@code /quarantine<path>} review pointer whose hold covers the bytes with that hash, its body the
 * served path itself - named by the path's digest because a served path runs longer than a filesystem allows one
 * name segment to be and as long as an object key may be at all. A hold is one marker for
 * the bytes wherever they are served, so a release has to answer "does another live hold still need these bytes"
 * before it lifts the marker - and the answer used to be found by descending every review pointer in the
 * repository, on every release, and once more to re-verify. This index answers it by one page of point reads.
 *
 * <p>It is written where holds are placed, never where artifacts are published: the pointer link writes the entry
 * for the hash the pointer names, and a retroactive sweep that marks every content hash a version serves records
 * the version's served paths under each of those hashes as well - the full-hash form, since a multi-file version's
 * review pointer advertises one hash while the version keeps several. The publish path pays nothing for it.
 *
 * <p>An entry is written before its pointer and forgotten after the pointer is removed, and a reader treats an
 * entry whose pointer is not live as no holder <em>and leaves it standing</em>. The two orders are one decision: an
 * entry with no pointer is either a hold in flight, one write short of its pointer, or a stale one left by a crash
 * between an unpublish's two writes - and a reader cannot tell which, so it prunes neither. Pruning the first would
 * strand a live hold outside the index, where a later release lifts the marker it needs; keeping the second costs
 * one point read per question about that hash, bounded by crashes in a two-write window. Every review pointer is
 * linked and unlinked through {@link Publication}, which is what makes the index complete once backfilled.
 *
 * <p>A repository from before the index has review pointers nothing indexed; the first reader to ask backfills
 * every pointer by the descent it used to make, then {@linkplain #completed stamps} the repository, and the descent
 * is never taken again there. Until the stamp stands a reader answers by the descent, as before, so no release
 * lifts a marker on an index that is not yet complete.
 */
public final class HeldBy {

    /** The index root, {@code quarantine/by-hash/<sha256>/}. */
    public static final String ROOT = "quarantine/by-hash/";

    /** The stamp a completed backfill leaves, so readers know the index describes every review pointer. */
    static final String COMPLETE = "quarantine/by-hash-complete";

    private HeldBy() {
    }

    /** Record that the review pointer at {@code servedPath} holds the bytes with this hash. Idempotent. */
    public static void record(ArtifactStore store, String hash, String servedPath) throws IOException {
        String key = key(hash, servedPath);
        if (!store.exists(key)) {
            // A lost create is the same entry written by a peer: the name is the path's digest, the body the path.
            store.writeVersioned(key, servedPath.getBytes(StandardCharsets.UTF_8), null);
        }
    }

    /** {@link #record} for every served path of one held version - the full-hash form a sweep writes for each
     *  content hash the version serves. */
    public static void record(ArtifactStore store, String hash, Collection<String> servedPaths) throws IOException {
        for (String path : servedPaths) {
            record(store, hash, path);
        }
    }

    /** Drop the entry for one review pointer whose hold was lifted. A missing entry is left missing. */
    public static void forget(ArtifactStore store, String hash, String servedPath) throws IOException {
        String key = key(hash, servedPath);
        if (store.exists(key)) {
            store.delete(key);
        }
    }

    /** The served paths whose review pointers the index says hold this hash, up to {@code limit} - one page of
     *  entries, each read for the path it names, with no claim about liveness: a reader checks each pointer it
     *  cares about and ignores one not live. An entry forgotten between the page and its read is simply not there. */
    public static List<String> holders(ArtifactStore store, String hash, int limit) throws IOException {
        List<String> names = new ArrayList<>();
        store.page(ROOT + hash + "/", "", limit, names::add);
        List<String> paths = new ArrayList<>(names.size());
        for (String name : names) {
            store.readVersioned(ROOT + hash + "/" + name)
                    .ifPresent(entry -> paths.add(new String(entry.content(), StandardCharsets.UTF_8)));
        }
        return paths;
    }

    /** Whether the index describes every review pointer of this repository - the backfill's stamp. */
    public static boolean complete(ArtifactStore store) throws IOException {
        return store.exists(COMPLETE);
    }

    /** Stamp the repository as indexed whole, after a backfill enumerated every review pointer. */
    public static void completed(ArtifactStore store) throws IOException {
        if (!store.exists(COMPLETE)) {
            store.writeVersioned(COMPLETE, new byte[0], null);
        }
    }

    private static String key(String hash, String servedPath) {
        return ROOT + hash + "/" + name(servedPath);
    }

    /** A served path as one key segment: its SHA-256, so its own separators never shape the key space and its
     *  length never meets a name bound. The path itself is the entry's body. */
    static String name(String servedPath) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(servedPath.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
