package build.jenesis.repository.store;

import module java.base;

/**
 * A token under one store key that changes every time something happened, without saying what or when: the signal a
 * derived index folds into its rebuild stamp so that an event its own freshness stamp does not see - an eviction that
 * removed rows while no scan ran - still makes it rebuild on its next pass rather than no-op on an unmoved stamp.
 *
 * <p>{@link #bump} writes a fresh unique token with a plain put, last writer wins: any bump moves the epoch, and since a
 * rebuild always re-derives from durable truth, a lost race between two bumps costs at most one redundant rebuild and
 * never a missed change. {@link #current} answers the empty string until the first bump, so a rebuild stamp composed
 * from it is stable over a repository where nothing was ever evicted. A plain put rather than a compare-and-set, so a
 * lifecycle owner can mark the change whether or not the module that consumes it is installed.
 *
 * <p>This used to be written twice, as {@code markEvicted}/{@code evictionEpoch} on the findings ledger and again on
 * the health ledger, identical bar the key; a ledger now hands out its epoch through a factory naming the key
 * ({@code Findings.evictions(store)}). See {@link Stamp} for the instant-valued sibling.
 */
public final class Epoch {

    private final ArtifactStore store;

    private final String key;

    public Epoch(ArtifactStore store, String key) {
        this.store = Objects.requireNonNull(store, "store");
        this.key = Objects.requireNonNull(key, "key");
    }

    /** The store key the epoch lives under, so a storage manifest can name it. */
    public String key() {
        return key;
    }

    /** Move the epoch: a fresh token nobody has seen, so every stamp that folded the previous one is stale. */
    public void bump() throws IOException {
        store.write(key, new ByteArrayInputStream(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)));
    }

    /** The current token, or {@code ""} when the epoch was never bumped. */
    public String current() throws IOException {
        return store.readVersioned(key)
                .map(versioned -> new String(versioned.content(), StandardCharsets.UTF_8))
                .orElse("");
    }
}
