package build.jenesis.repository.staging;

import module java.base;

/**
 * The per-repository staging operations the server and console program against: deploys land under a staging id,
 * are listed for review, and the id is then promoted into the release layout or dropped. How the lifecycle is
 * persisted (the artifact-store-backed implementation, an in-memory model in a test) is the implementation's part,
 * supplied by a {@link StagingProvider} discovered with {@link ServiceLoader} - with no provider installed, staging
 * is simply absent and the endpoints and screens say so.
 */
public interface Staging {


    StagingState state(String id) throws IOException;

    /** Deploy content into staging repo {@code id} at the release path it will occupy; held, not resolvable. The
     *  content streams into the content-addressed store, so a large deploy is never buffered whole in heap. */
    void stage(String id, String releasePath, InputStream content) throws IOException;

    /** Deploy a small in-memory artifact (a test fixture) into staging; the streaming {@link #stage(String, String,
     *  InputStream)} overload is what the server uses for an upload. */
    default void stage(String id, String releasePath, byte[] content) throws IOException {
        stage(id, releasePath, new ByteArrayInputStream(content));
    }

    /** The release paths currently staged under {@code id} - the complete set, never a prefix of it: promotion is
     *  all-or-nothing, so a listing that silently lost paths would drop artifacts a deploy staged. A store failure
     *  therefore surfaces rather than degrading to an empty (or short) listing. */
    List<String> staged(String id) throws IOException;

    /**
     * A bounded window of staging repository ids: the first {@code limit} in the store's order - those with a
     * recorded state marker first, then any live held tree whose marker is absent (a pre-marker deploy, or a
     * marker lost out of band), so a staged tree is never invisible purely because its lifecycle marker is
     * missing - and whether more exist. This is the listing face, and it is the implementation's to answer from
     * a bounded read: there is no whole listing beside it any more. It used to default to paging a complete
     * {@code ids()} - a million open stagings in heap to show the first two hundred, and the API's list took the
     * whole listing outright and walked every staging's tree for its count; the staging-reap canary measured that
     * read at a million stagings and it did not answer.
     */
    Window ids(int limit) throws IOException;

    /**
     * How many paths are staged under {@code id}, counted up to {@code cap}: the number when it is below the cap,
     * the cap otherwise - a screen shows "1000+" rather than walking a large staged tree whole to count it. The
     * default counts the complete listing.
     */
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
