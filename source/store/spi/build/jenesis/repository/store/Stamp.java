package build.jenesis.repository.store;

import module java.base;

/**
 * A last-writer-wins instant under one store key: when something last completed - a feed refresh, a scan, a sweep -
 * so every view can show how fresh what it renders is (§10: a read renders what is durably there, and its staleness is
 * visible). Absent means <em>never</em>, which a view must render as such and not as "clean"; a stamp that does not
 * parse reads as absent, the honest direction.
 *
 * <p>{@link #mark} is a single compare-and-set attempt against the token it read, deliberately not retried: two
 * completions racing to the same key settle on whichever wrote last, and a lost write leaves the <em>older</em> stamp
 * standing - never a wrong one - which the next completion moves. That is the whole of the mechanism, and it used to be
 * written twice, as the advisory findings ledger's {@code ScanStamp} and the maintainer-health ledger's
 * {@code HealthStamp}, each javadoc calling itself "the exact shape" of the other; the two moved apart only in their
 * key. A ledger now hands out its stamp through a factory naming the key ({@code Findings.scanned(store)}), so the
 * mechanism has one home and one test.
 *
 * <p>Not every small marker is a stamp. A <em>built</em> marker that a consumer gates its behaviour on carries a
 * format version ahead of the instant and is that consumer's; an {@link Epoch} is a token that changes without
 * ordering. This class is for the instant something last finished, read back as an instant.
 */
public final class Stamp {

    private final ArtifactStore store;

    private final String key;

    public Stamp(ArtifactStore store, String key) {
        this.store = Objects.requireNonNull(store, "store");
        this.key = Objects.requireNonNull(key, "key");
    }

    /** The store key the stamp lives under, so a storage manifest can name it. */
    public String key() {
        return key;
    }

    /** Record that the thing completed at {@code now}: one compare-and-set against the current token, last writer
     *  wins, a lost race left to the next completion. */
    public void mark(Instant now) throws IOException {
        Object token = store.readVersioned(key).map(ArtifactStore.Versioned::token).orElse(null);
        store.writeVersioned(key, now.toString().getBytes(StandardCharsets.UTF_8), token);
    }

    /** The last completion, or empty when it never completed - or when the stamp does not parse, which degrades to
     *  "never" rather than to a wrong instant. */
    public Optional<Instant> read() throws IOException {
        Optional<ArtifactStore.Versioned> stored = store.readVersioned(key);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(new String(stored.get().content(), StandardCharsets.UTF_8).trim()));
        } catch (DateTimeParseException _) {
            return Optional.empty();
        }
    }
}
