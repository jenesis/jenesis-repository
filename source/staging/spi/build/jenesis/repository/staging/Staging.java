package build.jenesis.repository.staging;

import module java.base;

/**
 * The per-repository staging operations: deploys land under a staging id, are listed for review, and the id is promoted
 * into the release layout or dropped. Persistence is a discovered {@link StagingProvider}'s; without one staging is
 * absent and the endpoints and screens say so.
 */
public interface Staging {


    StagingState state(String id) throws IOException;

    /** Deploy content into staging id {@code id} at the release path it will occupy; held, not resolvable. The content
     *  streams into the store, never buffered whole. */
    void stage(String id, String releasePath, InputStream content) throws IOException;

    /** Deploy a small in-memory artifact (a test fixture); the server uses the streaming overload. */
    default void stage(String id, String releasePath, byte[] content) throws IOException {
        stage(id, releasePath, new ByteArrayInputStream(content));
    }

    /** The release paths staged under {@code id} - the complete set: promotion is all-or-nothing, so a listing that
     *  lost paths would drop staged artifacts. A store failure surfaces rather than degrading to a short listing. */
    List<String> staged(String id) throws IOException;

    /** A bounded window of staging ids - the first {@code limit} in store order, those with a state marker first, then
     *  any held tree whose marker is missing, so no staged tree is invisible for lack of a marker - and whether more
     *  exist. The implementation answers it from a bounded read; there is no whole listing beside it. */
    Window ids(int limit) throws IOException;

    /** How many paths are staged under {@code id}, counted up to {@code cap}, so a screen shows "1000+" rather than
     *  walking a large tree. The default counts the complete listing. */
    default int stagedAtMost(String id, int cap) throws IOException {
        return Math.min(staged(id).size(), cap);
    }

    /** A bounded window of ids, and whether the store holds more than were asked for. */
    record Window(List<String> ids, boolean more) {
    }

    /** Promote every staged artifact into the release layout and seal the id. */
    void promote(String id) throws IOException;

    /** Drop every staged artifact under {@code id} without releasing it, and seal the id. */
    void drop(String id) throws IOException;
}
