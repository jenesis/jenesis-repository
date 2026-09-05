package build.jenesis.repository.store;

import module java.base;

/**
 * How many incremental passes have run since the last full one, under one store key, so a consumer of a
 * {@link DirtyIndexFeed} can heal feed-missed drift on a cadence: every {@code n}th pass rebuilds from truth and
 * {@link #reset resets} the count, every other pass applies the feed and {@link #bump bumps} it. The count is a plain
 * last-writer-wins object - a lost race between two nodes costs at most one pass of cadence either way, never a wrong
 * index - and an unreadable count reads as zero, the honest restart. The search and dependents passes each carried
 * these thirty-five lines beside their feed, identical bar the key and the name of the dial that sets the cadence;
 * the dial stays with the pass, the counter lives here.
 */
public final class PassCounter {

    private final ArtifactStore store;

    private final String key;

    public PassCounter(ArtifactStore store, String key) {
        this.store = Objects.requireNonNull(store, "store");
        this.key = Objects.requireNonNull(key, "key");
    }

    /** The store key the counter lives under, so a storage manifest can name it. */
    public String key() {
        return key;
    }

    /** Incremental passes since the last full one; zero when never counted or unreadable. */
    public long passes() throws IOException {
        Optional<ArtifactStore.Versioned> stored = store.readVersioned(key);
        if (stored.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(new String(stored.get().content(), StandardCharsets.UTF_8).trim());
        } catch (NumberFormatException _) {
            return 0;
        }
    }

    /** Whether the pass about to run is the {@code every}th since the last full one - the one that reconciles. A
     *  cadence of one makes every pass a reconcile. */
    public boolean due(int every) throws IOException {
        return passes() + 1 >= every;
    }

    /** One more incremental pass ran. */
    public void bump() throws IOException {
        store.write(key, new ByteArrayInputStream(Long.toString(passes() + 1).getBytes(StandardCharsets.UTF_8)));
    }

    /** A full pass ran: the count starts over. */
    public void reset() throws IOException {
        store.write(key, new ByteArrayInputStream("0".getBytes(StandardCharsets.UTF_8)));
    }
}
