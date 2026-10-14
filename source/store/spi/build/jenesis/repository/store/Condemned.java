package build.jenesis.repository.store;

import module java.base;

/**
 * The garbage collector's {@code gc/condemned/<hash>} marker, and the rule both of its writers keep: the marker is
 * the arbiter between a sweep deleting a blob and a publish relying on it, and each side acts on it only by
 * compare-and-set.
 *
 * <p>A content-addressed store keeps a blob it already holds and drops the upload, so a publish of bytes a collector
 * has condemned relies on the condemned blob. The collector deletes such a blob only on a later pass than the one that
 * condemned it, and a re-read of the marker just before the delete is not enough: a publish clearing the marker
 * between that read and the delete would be missed, and its pointer would name nothing - answered {@code 201} and
 * served {@code 404} from then on. So the marker decides it:
 *
 * <ul>
 *   <li>A sweep {@linkplain #claim claims} the marker, writing it over the token it judged the blob by, and deletes
 *       the blob only once that write has landed. A marker a publish touched since does not take the claim, and the
 *       blob is spared.</li>
 *   <li>A publish {@linkplain #spare spares} the blob by writing the marker over the token it read - the store has no
 *       versioned delete, so the marker stays, saying it was spared, until the next sweep finds the blob referenced
 *       and removes it. A claimed marker does not take the write: the blob is going, and the publish fails with
 *       {@link Publication.BlobCollected}, a retryable answer, so the client sends the bytes again once they are gone
 *       and they are stored afresh.</li>
 * </ul>
 *
 * <p>A claim is followed at once by the delete, so one that has stood for {@link #CLAIM_EXPIRY} belongs to a sweep
 * that died between the two. A publish takes such a claim back, since nothing else would until the next confirming
 * pass, and a sweep abandons a delete its own claim has outlived half of that - so neither side can act on a claim
 * the other has taken back. Each side measures the time on its own monotonic clock and never on the other's: the
 * instant written into a claim is the sweeping node's wall clock, and a publishing node whose clock ran ahead of it by
 * more than the window would read a claim made a moment ago as long expired. So a publish takes a claim back only once
 * its own node has {@linkplain ClaimWatch watched} that claim stand for the whole window.
 */
public final class Condemned {

    /** Where the markers live, beside the collector's pass records. */
    public static final String SPACE = "gc/condemned";

    /** How long a claim stands before a publish may take it back from a sweep that did not finish. */
    public static final Duration CLAIM_EXPIRY = Duration.ofMinutes(15);

    private static final String CLAIMED = "claimed=";

    private static final String SPARED = "spared=";

    private static final String COLLECTED = "collected=";

    private Condemned() {
    }

    /** The marker key for a blob. */
    public static String key(String hash) {
        return SPACE + "/" + hash;
    }

    /** The body a sweep condemns a blob with: the pass whose judgement it is, and when. */
    public static byte[] condemnation(long pass, Instant since) {
        return ("pass=" + pass + "\nsince=" + since).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Claim the blob for deletion by writing {@code current} - the marker as the sweep judged it - over its own token,
     * answering whether the claim landed. A sweep deletes only on {@code true}.
     */
    public static boolean claim(ArtifactStore store, String hash, ArtifactStore.Versioned current, Instant at)
            throws IOException {
        // A marker a sweep that died had claimed carries that claim; this one replaces it rather than following it.
        String condemnation = new String(current.content(), StandardCharsets.UTF_8).lines()
                .filter(line -> !line.startsWith(CLAIMED))
                .collect(Collectors.joining("\n"));
        byte[] claimed = (condemnation + "\n" + CLAIMED + at).getBytes(StandardCharsets.UTF_8);
        return store.writeVersioned(key(hash), claimed, current.token());
    }

    /**
     * Record that the sweep holding the claim on {@code hash} has deleted the blob: the claim is rewritten
     * {@code collected}, which a publish reads as "these bytes went at this instant" rather than "a sweep is about to
     * take them". A claim a publish took back since (an expired one it spared) is left as the publish wrote it.
     */
    public static void collected(ArtifactStore store, String hash, Instant at) throws IOException {
        Retries.decide(store, key(hash), current -> current.isPresent()
                && claimed(new String(current.get().content(), StandardCharsets.UTF_8)).isPresent()
                ? Retries.Verdict.write((COLLECTED + at).getBytes(StandardCharsets.UTF_8), null)
                : Retries.Verdict.keep(null));
    }

    /** When the blob a marker names was collected, or empty for a marker that is condemned, claimed or spared. */
    public static Optional<Instant> collectedAt(String body) {
        if (!body.startsWith(COLLECTED)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(body.substring(COLLECTED.length()).strip()));
        } catch (DateTimeParseException _) {
            return Optional.empty();
        }
    }

    /**
     * Spare {@code hash} from any collector, for a publish that relies on it: a condemned marker is written spared,
     * a claimed one refuses with {@link Publication.BlobCollected} unless its claim has expired, and a blob that turns
     * out to be gone although its marker was not yet claimed - a sweep that died after deleting it - refuses the same
     * way. One existence read wherever collection never condemned the blob.
     *
     * <p>A marker saying the blob was {@linkplain #collected collected} settles the case a bare absence cannot: a
     * publish that stored these bytes while the blob still stood had its upload dropped as a duplicate, and meets the
     * marker after the sweep deleted the blob. It is refused, so the client sends the bytes again; an absent marker
     * would let it link a pointer at nothing. A publish that stored the bytes after the collection finds the blob
     * there again and spares it. A marker already spared is taken at its word only while the blob is there: the
     * publish that spared it may have found the blob gone and been refused.
     *
     * @param what the request path or key the publish is writing, which a refusal names
     */
    public static void spare(ArtifactStore store, String hash, String what) throws IOException {
        spare(store, hash, what, ClaimWatch.NODE);
    }

    /** {@link #spare(ArtifactStore, String, String)}, judging how long a claim has stood by {@code watch}. */
    public static void spare(ArtifactStore store, String hash, String what, ClaimWatch watch) throws IOException {
        String key = key(hash);
        if (!store.exists(key)) {
            return;   // the overwhelmingly common case: nothing condemned these bytes
        }
        boolean touched = Retries.decide(store, key, current -> {
            if (current.isEmpty()) {
                return Retries.Verdict.keep(false);
            }
            String body = new String(current.get().content(), StandardCharsets.UTF_8);
            Optional<Instant> claimed = claimed(body);
            if (claimed.isEmpty()) {
                watch.forget(key);
            }
            if (body.startsWith(SPARED)) {
                // Spared, but not necessarily standing: a publish that took back an expired claim over a blob the
                // sweep had already deleted wrote this marker and was then refused, and a duplicate upload dropped
                // while the blob still stood would otherwise link to nothing here.
                if (store.size("blobs/" + hash) < 0) {
                    throw new Publication.BlobCollected(what, hash);
                }
                return Retries.Verdict.keep(false);
            }
            if (collectedAt(body).isPresent() && store.size("blobs/" + hash) < 0) {
                throw new Publication.BlobCollected(what, hash);
            }
            if (claimed.isPresent() && !watch.outlasted(key, claimed.get())) {
                throw new Publication.BlobCollected(what, hash);
            }
            watch.forget(key);
            return Retries.Verdict.write((SPARED + Instant.now()).getBytes(StandardCharsets.UTF_8), true);
        });
        if (touched && store.size("blobs/" + hash) < 0) {
            throw new Publication.BlobCollected(what, hash);
        }
    }

    /** Whether a collector's condemnation of {@code hash} stands: a marker is there and no publish has spared it. */
    public static boolean standing(ArtifactStore store, String hash) throws IOException {
        return store.readVersioned(key(hash))
                .map(marker -> !new String(marker.content(), StandardCharsets.UTF_8).startsWith(SPARED))
                .orElse(false);
    }

    /**
     * The claims a node has met while sparing, each with the moment it first met it on the node's own monotonic clock.
     * A claim counts as outlasted once the node has watched it stand for {@link #CLAIM_EXPIRY}; a new claim on the
     * same marker starts the watch again. Forgetting a claim is always safe, since a claim it has not watched makes a
     * node wait a whole window from its next meeting, which delays a publish and never deletes anything - so a node
     * that restarted starts empty, and one reaching {@link #CAPACITY} claims forgets them all. The node's watch is
     * {@link #NODE}; a claim leaves it once it is taken back or the marker is no longer claimed.
     */
    public static final class ClaimWatch {

        /** This node's watch, on its monotonic clock. */
        public static final ClaimWatch NODE = new ClaimWatch(System::nanoTime);

        /** The most claims watched at once: one per blob a sweep died holding, so far above any real count. */
        static final int CAPACITY = 10_000;

        private final LongSupplier nanos;

        private final ConcurrentHashMap<String, Met> met = new ConcurrentHashMap<>();

        /** A watch on {@code nanos}, a monotonic clock in nanoseconds. */
        public ClaimWatch(LongSupplier nanos) {
            this.nanos = nanos;
        }

        /** Whether this node has watched {@code claim} stand on the marker {@code key} for the whole window. */
        boolean outlasted(String key, Instant claim) {
            long now = nanos.getAsLong();
            if (met.size() >= CAPACITY) {
                met.clear();
            }
            Met seen = met.compute(key, (_, prior) -> prior != null && prior.claim().equals(claim)
                    ? prior : new Met(claim, now));
            return now - seen.since() >= CLAIM_EXPIRY.toNanos();
        }

        /** Stop watching the marker {@code key}. */
        void forget(String key) {
            met.remove(key);
        }

        private record Met(Instant claim, long since) {
        }
    }

    /** When a marker was claimed, or empty for one that is condemned, spared or unreadable. */
    public static Optional<Instant> claimed(String body) {
        for (String line : body.split("\n")) {
            if (line.startsWith(CLAIMED)) {
                try {
                    return Optional.of(Instant.parse(line.substring(CLAIMED.length()).strip()));
                } catch (DateTimeParseException _) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }
}
