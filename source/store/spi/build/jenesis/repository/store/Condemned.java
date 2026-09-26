package build.jenesis.repository.store;

import module java.base;

/**
 * The garbage collector's {@code gc/condemned/<hash>} marker, and the rule both of its writers keep: the marker is
 * the arbiter between a sweep deleting a blob and a publish relying on it, and each side acts on it only by
 * compare-and-set.
 *
 * <p>A content-addressed store keeps a blob it already holds and drops the upload, so a publish of bytes a collector
 * has condemned relies on the condemned blob. The collector deletes such a blob only on a later pass than the one that
 * condemned it, and until now the last guard was a re-read of the marker just before the delete: a publish clearing
 * the marker between that read and the delete was missed, and its pointer named nothing - answered {@code 201} and
 * served {@code 404} from then on. The marker now decides it:
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
 * the other has taken back.
 */
public final class Condemned {

    /** Where the markers live, beside the collector's pass records. */
    public static final String SPACE = "gc/condemned";

    /** How long a claim stands before a publish may take it back from a sweep that did not finish. */
    public static final Duration CLAIM_EXPIRY = Duration.ofMinutes(15);

    private static final String CLAIMED = "claimed=";

    private static final String SPARED = "spared=";

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
     * Spare {@code hash} from any collector, for a publish that relies on it: a condemned marker is written spared,
     * a claimed one refuses with {@link Publication.BlobCollected} unless its claim has expired, and a blob that turns
     * out to be gone although its marker was not yet claimed - a sweep that died after deleting it - refuses the same
     * way. One existence read wherever collection never condemned the blob.
     *
     * @param what the request path or key the publish is writing, which a refusal names
     */
    public static void spare(ArtifactStore store, String hash, String what) throws IOException {
        String key = key(hash);
        if (!store.exists(key)) {
            return;   // the overwhelmingly common case: nothing condemned these bytes
        }
        boolean touched = Retries.decide(store, key, current -> {
            if (current.isEmpty()) {
                return Retries.Verdict.keep(false);
            }
            String body = new String(current.get().content(), StandardCharsets.UTF_8);
            if (body.startsWith(SPARED)) {
                return Retries.Verdict.keep(false);
            }
            Optional<Instant> claimed = claimed(body);
            if (claimed.isPresent() && claimed.get().plus(CLAIM_EXPIRY).isAfter(Instant.now())) {
                throw new Publication.BlobCollected(what, hash);
            }
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
