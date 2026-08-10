package build.jenesis.repository.store;

import module java.base;

/**
 * The product's one archive-<em>walk</em> bound, and the screened walk that applies it: how far a read may run
 * <em>through</em> an artifact archive while it looks for the member that carries a declaration. It is the sibling of
 * {@link ArchiveInflation}, which bounds the other dimension - how many decompressed bytes of the member it finally
 * materialises - and the two are deliberately separate numbers, because a walk that never materialises anything can
 * still be driven to spend a decompressor's whole output.
 *
 * <p><strong>What it bounds, and why one number is not enough on its own.</strong> {@link ArchiveInflation} answers
 * "how big may the manifest be"; a hostile archive answers that by putting a one-byte manifest behind a hundred
 * gigabytes of payload. The bound here is the bytes fed <em>into</em> the walk, which is the archive's own footprint:
 * for a zip walked entry by entry that is the stored body, and for a tar read through a decompressor it is the
 * decompressed stream - deliberately the same ceiling in both roles, because it is the "how much of this artifact may
 * one read chew through to find one member" budget rather than a claim about compression ratios. A format whose
 * member legitimately sits behind a large payload derives a body-relative ceiling with
 * {@link #largestWalk(long, long)} and states its ratio at its own call site.
 *
 * <p><strong>Why this is shared rather than per format.</strong> Exactly the argument {@link ArchiveInflation}
 * records, one dimension over: before this existed, every format that cracked an artifact carried its own private
 * byte-counting {@link FilterInputStream} and its own private ceiling - parallel by convention, keyed to nothing an
 * operator can set, and inherited by nothing. A new format then arrived not with a different bound but with
 * <em>none</em>, because there was nothing to inherit it from.
 *
 * <p><strong>Reaching the bound is a fact, not a silence.</strong> A walk answers in the same two words a bounded
 * member read and a bounded store traversal answer in - {@link ArchiveInflation.Outcome#EXHAUSTED} (the archive
 * ended) and {@link ArchiveInflation.Outcome#TRUNCATED} (the bound stopped the walk). That is the whole point:
 * without it, an archive walk that runs out of budget mid-member simply ends, and "this artifact carries no such
 * member" becomes indistinguishable from "we stopped looking before we could tell". The vocabulary is reused rather
 * than reinvented, so "did I see all of it?" reads the same wherever a bound is met in this product.
 *
 * <p><strong>The bound and a corrupt archive never wear each other's clothes.</strong> A walker that fails while the
 * screen still had budget left is walking a genuinely broken archive, and its failure propagates unchanged. A walker
 * that fails <em>after</em> the screen cut it off is failing because of the cut, and that is reported as the
 * truncation it is. Conflating the two is how a bound becomes invisible: every bomb then looks like a corrupt
 * upload, and every corrupt upload like a bomb.
 *
 * <p><strong>A truncated walk yields nothing at all.</strong> A member found before the bound bit may be a decoy a
 * crafted archive placed early, with the real one deliberately past the ceiling - so handing back what was found
 * while the walk was still looking would let the archive choose what a screen sees. The record enforces it: a value
 * exactly when {@link ArchiveInflation.Outcome#EXHAUSTED}, nothing when
 * {@link ArchiveInflation.Outcome#TRUNCATED}, the same equivalence a bounded member read enforces on its bytes.
 *
 * <p><strong>Which side a caller lands on is the read's role.</strong> The two accessors are the decision, and they
 * are the same two {@link ArchiveInflation.Entry} offers:
 * <ul>
 *   <li>{@link Found#orNull()} - an <b>optional declaration</b>, one the request path or the store can supply another
 *       way (a jar's module name, a licence beside a coordinate). A cut-off walk can then only under-declare, so it
 *       degrades to "this artifact declares nothing" and the publish proceeds;</li>
 *   <li>{@link Found#required(String, String)} - the artifact's <b>identity, or a guard's input</b>, which exists
 *       nowhere but inside the archive. Degrading it would admit an artifact that nothing screened, so it fails
 *       closed, and the refusal says which of the two happened: the archive genuinely carries no such member, or the
 *       walk bound stopped before it was reached.</li>
 * </ul>
 * Treating an unreached member as an absent one is the answer neither accessor gives.
 */
public final class ArchiveWalk {

    /**
     * The default ceiling on how many bytes one walk may draw from an archive: 64 MiB. A genuine package reaches the
     * member that declares it far inside this - a manifest, a control stanza, an index entry, all of them near the
     * archive's front by every ecosystem's own convention - while a decompression bomb is cut off at it instead of
     * pinning the publishing or screening thread for as long as its author chose.
     */
    public static final long LARGEST_WALK = 64L * 1024 * 1024;

    /**
     * The key an operator raises or lowers {@link #largestWalk()} with, in the shared {@code jenesis.} namespace and
     * so also settable as {@code JENESIS_ARCHIVE_LARGEST_WALK} in a plain {@code docker run -e}. Deploy-time
     * configuration for the same reasons its sibling {@link ArchiveInflation#LARGEST_ENTRY_KEY} is: it is a per-process
     * budget on work done on the publish thread, read where a store round-trip per archive would be absurd, and
     * deployment-global where a stored setting would be per tenant.
     */
    public static final String LARGEST_WALK_KEY = "jenesis.archive.largest-walk";

    private ArchiveWalk() {
        throw new UnsupportedOperationException("ArchiveWalk is a static utility");
    }

    /**
     * The configured ceiling on one walk - {@link #LARGEST_WALK} unless an operator set {@link #LARGEST_WALK_KEY}.
     * Read live rather than latched, so a deployment's value is whatever its configuration says at the moment of the
     * read and a test can move it.
     *
     * @throws IllegalArgumentException when the key is set to something that is not a positive number of bytes - an
     *         operator who raised a cap and got the spelling wrong must not be left believing they raised it (&sect;9)
     */
    public static long largestWalk() {
        // The key is spelled out here as well as in LARGEST_WALK_KEY on purpose: ConfigPrincipleTest enumerates config
        // reads by matching a literal key at its read site, and a key reached only through a constant would escape
        // that scan - a stranded key is exactly what it exists to catch. The two spellings are pinned equal by
        // ArchiveWalkTest, so they cannot drift.
        String configured = Features.lookup().apply("jenesis.archive.largest-walk");
        if (configured == null || configured.isBlank()) {
            return LARGEST_WALK;
        }
        long bytes;
        try {
            bytes = Long.parseLong(configured.trim());
        } catch (NumberFormatException cause) {
            throw new IllegalArgumentException(LARGEST_WALK_KEY + " must be a positive number of bytes, not '"
                    + configured + "'", cause);
        }
        if (bytes <= 0) {
            throw new IllegalArgumentException(LARGEST_WALK_KEY + " must be a positive number of bytes, not " + bytes);
        }
        return bytes;
    }

    /**
     * A body-relative ceiling for the archive shapes whose declaring member may legitimately sit <em>after</em> a
     * large payload, where the flat {@link #largestWalk()} tier would refuse valid large packages: at most
     * {@code ratio} times the artifact's own stored length, and never below the shared tier. A genuine package
     * decompresses at an ordinary ratio well inside this, while a bomb - tiny stored, vast inflated - is stopped at
     * {@code storedLength * ratio}, so the work one publish can be made to do stays proportional to what it uploaded.
     *
     * <p>The ratio is the format's own judgement about its own container and stays explicit at its call site; only
     * the floor is shared. A stored length that is unknown or not yet counted ({@code <= 0}) falls back to the flat
     * tier rather than to no bound at all.
     *
     * @throws IllegalArgumentException when {@code ratio} is not positive
     */
    public static long largestWalk(long storedLength, long ratio) {
        if (ratio <= 0) {
            throw new IllegalArgumentException("An inflation ratio must be positive: " + ratio);
        }
        long floor = largestWalk();
        if (storedLength <= 0) {
            return floor;
        }
        long scaled = storedLength > Long.MAX_VALUE / ratio ? Long.MAX_VALUE : storedLength * ratio;
        return Math.max(floor, scaled);
    }

    /** Walk {@code archive} under the configured {@link #largestWalk()} ceiling - the call a format makes unless it
     *  has a stated reason of its own for a different bound. */
    public static <T> Found<T> walk(InputStream archive, Walker<T> walker) throws IOException {
        return walk(archive, largestWalk(), walker);
    }

    /**
     * Walk {@code archive} under an explicit ceiling, for the containers whose declaring member is legitimately
     * further in than the shared default reaches (see {@link #largestWalk(long, long)}). The number stays visible at
     * the call site that chose it; what is <em>not</em> negotiable is that there is a ceiling and that reaching it is
     * reported.
     *
     * <p>{@code walker} is handed a screened view of the archive and opens whatever container it speaks - a
     * {@link java.util.zip.ZipInputStream}, a tar reader over a decompressor, an {@code ar} reader - over that view
     * rather than over the archive, so every byte the container draws, including the ones it skips over, counts
     * against the same budget. It answers the declaration it found, or {@code null} when the archive carries none.
     *
     * <p>The archive is neither drained nor closed here; whether the walker closes what it wrapped is the walker's
     * own business, and closing the screened view closes the archive exactly as closing the archive directly would.
     *
     * @throws IllegalArgumentException when {@code limit} is not positive
     * @throws IOException              from a walker that failed while the bound still had budget left - a genuinely
     *                                  unreadable archive, which is a different fact from a bounded one and is never
     *                                  reported as a truncation
     */
    public static <T> Found<T> walk(InputStream archive, long limit, Walker<T> walker) throws IOException {
        if (limit <= 0) {
            throw new IllegalArgumentException("An archive-walk bound must be positive: " + limit);
        }
        Objects.requireNonNull(walker, "walker");
        Screen screen = new Screen(Objects.requireNonNull(archive, "archive"), limit);
        T value;
        try {
            value = walker.walk(screen);
        } catch (IOException | RuntimeException cause) {
            if (!screen.stopped) {
                // The archive itself is broken - a corrupt entry, a body that is not the container it claims to be.
                // Reporting that as a bound would tell an operator to raise a ceiling that was never reached.
                throw cause;
            }
            // The container ran off the end of a stream this bound cut short. That is the bound, said in the
            // container's own words, and it is the one failure a walk is allowed to convert into an outcome.
            return new Found<>(null, ArchiveInflation.Outcome.TRUNCATED, screen.consumed);
        }
        return screen.stopped
                ? new Found<>(null, ArchiveInflation.Outcome.TRUNCATED, screen.consumed)
                : new Found<>(value, ArchiveInflation.Outcome.EXHAUSTED, screen.consumed);
    }

    /** Reads an archive that has already been bounded, and answers the declaration it carries - or {@code null} when
     *  it carries none. The container is the walker's to choose; the budget is not. */
    @FunctionalInterface
    public interface Walker<T> {

        /**
         * Walk {@code screened} and answer what it declares.
         *
         * @param screened the archive, reporting end-of-stream once the walk bound is spent - so a container reading
         *                 it sees a stream that ends early rather than one that never ends. Whatever this method
         *                 returns is dropped if that happened (see the class javadoc), so it need not - and cannot
         *                 usefully - check for itself.
         */
        T walk(InputStream screened) throws IOException;
    }

    /**
     * What one bounded walk found: the declaration when the walk saw the archive out, the
     * {@link ArchiveInflation.Outcome}, and how many bytes the walk drew ({@code consumed} equals the ceiling on a
     * truncated walk - the point at which it stopped, not the archive's real size, which is precisely what the bound
     * refuses to find out).
     *
     * <p>A value is carried exactly when the walk was not cut off. An exhausted walk with no value is the honest
     * "this archive carries no such member"; a truncated one is "we stopped looking", and it never carries the
     * member it happened to pass on the way.
     */
    public record Found<T>(T value, ArchiveInflation.Outcome outcome, long consumed) {

        public Found {
            Objects.requireNonNull(outcome, "outcome");
            if (value != null && outcome != ArchiveInflation.Outcome.EXHAUSTED) {
                throw new IllegalArgumentException(
                        "A walk the bound stopped hands back nothing at all, not what it passed on the way: "
                                + outcome);
            }
            if (consumed < 0) {
                throw new IllegalArgumentException("Negative consumed byte count: " + consumed);
            }
        }

        /** Whether the walk saw the archive out, so an absent value means the archive genuinely carries no such
         *  member. */
        public boolean exhausted() {
            return outcome == ArchiveInflation.Outcome.EXHAUSTED;
        }

        /** Whether the walk bound stopped the read, so nothing is known about what the archive declares past it. */
        public boolean truncated() {
            return outcome == ArchiveInflation.Outcome.TRUNCATED;
        }

        /**
         * The declaration, or {@code null} when the archive carried none or the bound stopped the walk - the
         * <b>optional-declaration</b> outcome. A caller that reaches for this is saying a missing declaration is a
         * survivable answer: the artifact publishes and simply declares nothing. Choose it only where losing the
         * declaration can under-declare and never under-screen; {@link #truncated()} still tells the two apart for a
         * caller that wants to log the difference.
         */
        public T orNull() {
            return value;
        }

        /**
         * The declaration, or an {@link IOException} when there is none - the <b>identity or guard</b> outcome, for a
         * member that carries something no other source can supply. The refusal names which of the two happened, so
         * an operator is never told an archive "carries no manifest" when the walk never reached one.
         *
         * @param artifact how to name this artifact in the refusal ("Debian .deb")
         * @param member   how to name the member that was not reached ("control member")
         */
        public T required(String artifact, String member) throws IOException {
            if (value != null) {
                return value;
            }
            throw new IOException(truncated()
                    ? artifact + " does not reach its " + member + " within the " + consumed
                            + "-byte archive-walk bound (" + LARGEST_WALK_KEY + ")"
                    : artifact + " carries no " + member);
        }
    }

    /**
     * The archive as the walk sees it: a view that reports end-of-stream once the budget is spent, and remembers
     * whether it did that because the budget ran out or because the archive simply ended there.
     *
     * <p>Skipped bytes count against the same budget as read ones - a container that jumps over a payload entry has
     * still made the decompressor produce it, so a bound counting only {@code read} would not bound the work. And at
     * the budget the screen looks exactly one byte ahead before it decides: an archive that ends precisely on the
     * ceiling is exhausted, not truncated, because "the last byte I was allowed to read was the last byte there was"
     * is a complete walk. Without that look-ahead every archive at exactly the bound would report as cut off, and a
     * bound that cries wolf is a bound callers learn to ignore.
     */
    private static final class Screen extends FilterInputStream {

        private final long limit;

        private long consumed;
        private boolean stopped;
        private boolean ended;

        private Screen(InputStream archive, long limit) {
            super(archive);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            if (consumed >= limit) {
                return refuse();
            }
            int read = super.read();
            if (read < 0) {
                ended = true;
            } else {
                consumed++;
            }
            return read;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (consumed >= limit) {
                return refuse();
            }
            int read = super.read(bytes, offset, (int) Math.min(length, limit - consumed));
            if (read < 0) {
                ended = true;
            } else {
                consumed += read;
            }
            return read;
        }

        @Override
        public long skip(long count) throws IOException {
            if (consumed >= limit) {
                refuse();
                return 0;
            }
            long skipped = super.skip(Math.min(count, limit - consumed));
            if (skipped > 0) {
                consumed += skipped;
            }
            return skipped;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(super.available(), Math.max(0L, limit - consumed));
        }

        @Override
        public boolean markSupported() {
            // A reset would rewind the archive without rewinding the budget, which is a bound a caller can spend
            // twice. No container this walk serves needs one.
            return false;
        }

        /** At the budget: one byte of look-ahead decides whether the archive ended exactly here (exhausted) or had
         *  more to give (truncated). The look-ahead byte is discarded - the walk is over either way. */
        private int refuse() throws IOException {
            if (!stopped && !ended) {
                if (super.read() < 0) {
                    ended = true;
                } else {
                    stopped = true;
                }
            }
            return -1;
        }
    }
}
