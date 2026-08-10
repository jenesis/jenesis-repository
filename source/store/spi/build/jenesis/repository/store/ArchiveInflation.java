package build.jenesis.repository.store;

import module java.base;

/**
 * The product's one archive-inflation bound, and the screened read that applies it: how many <em>decompressed</em>
 * bytes of a single archive member a format may materialise while it reads a declaration out of a published artifact.
 *
 * <p>It bounds one of the two dimensions an archive read has. Its sibling {@link ArchiveWalk} bounds the other - how
 * far the read may run <em>through</em> the archive to reach that member - because a hostile artifact can satisfy this
 * bound perfectly by putting a one-byte manifest behind a hundred gigabytes of payload. The two are separate numbers
 * and separate operator keys, and they share this class's {@link Outcome} vocabulary so a caller reads the same two
 * words whichever bound it met.
 *
 * <p><strong>Why this is shared rather than per format.</strong> A format that cracks an artifact for its manifest -
 * a jar's {@code MANIFEST.MF} and {@code module-info.class}, a {@code .nuspec}, a gemspec, a {@code control} member, an
 * embedded index - reads it on the publish thread of a shared JVM, and the compression ratio is the attacker's to
 * choose: a kilobyte of stored blob can inflate to gigabytes. Every format that reads one therefore needs the same
 * bound, which is exactly the shape a shared mechanism belongs in (&sect;13 - a guard one format applies to a shared
 * concern is applied by every format with that concern). Before this existed each reading module held its own private
 * constant, so the numbers were parallel by convention and a new format arrived not with a different bound but with
 * <em>none</em>: there was nothing to inherit. The bound now has a name, a home, an operator key and a build guard
 * ({@code ArchiveInflationPrincipleTest}), so a format that ignores it is visibly wrong rather than silently unbounded.
 *
 * <p><strong>Reaching the bound is a fact, not a silence.</strong> {@link Entry} answers in the same two words the
 * bounded store traversals answer in - {@link Outcome#EXHAUSTED} (the member ended) and {@link Outcome#TRUNCATED} (the
 * bound stopped the read) - so "this artifact declares nothing" and "we stopped looking before we could tell" are
 * never the same answer. This is the vocabulary rather than the type, because the traversal vocabulary lives above
 * this module in the walk SPI and a bound on one member's inflation cannot depend on it; the rule it carries is
 * identical.
 *
 * <p><strong>A truncated entry never yields a prefix.</strong> Half a manifest can parse - a truncated
 * {@code MANIFEST.MF} is a valid, shorter {@code MANIFEST.MF}, and a truncated JSON document can be a valid smaller
 * one - so handing back what was read before the ceiling would let a crafted archive choose what a screen sees. The
 * canonical constructor enforces the equivalence: bytes exactly when {@link Outcome#EXHAUSTED}, nothing when
 * {@link Outcome#TRUNCATED}, the same way a bounded traversal result cannot be exhausted and carry a cursor.
 *
 * <p><strong>Which side a caller lands on is the read's role.</strong> The two accessors are the decision, and a caller
 * picks one deliberately:
 * <ul>
 *   <li>{@link Entry#orNull()} - an <b>optional declaration</b>, one the request path or the store can supply another
 *       way (a jar's module name, a licence beside a coordinate). A cut-off read can then only under-declare, so it
 *       degrades to "this artifact declares nothing" and the publish proceeds. This is the outcome the format SPI's
 *       archive-inflation clause mandates by default;</li>
 *   <li>{@link Entry#required(String, String)} - the artifact's <b>identity, or a guard's input</b>, which exists
 *       nowhere but inside the archive. Degrading it would admit an artifact that nothing screened - the fail-open
 *       shape - so it fails closed, and the refusal says which of the two happened.</li>
 * </ul>
 * Treating an unread member as an absent guard is the one answer neither accessor gives.
 */
public final class ArchiveInflation {

    /**
     * The default ceiling on one member's decompressed size: 1 MiB. Every member a format materialises to read a
     * declaration is small metadata - a manifest, a descriptor, a control stanza, a few kilobytes at most - so a
     * member far larger than this is not a declaration but a decompression bomb, and the cost of being wrong is an
     * out-of-memory on the shared JVM on every publish of that artifact.
     */
    public static final int LARGEST_ENTRY = 1 << 20;

    /**
     * The key an operator raises or lowers {@link #largestEntry()} with, in the shared {@code jenesis.} namespace and
     * so also settable as {@code JENESIS_ARCHIVE_LARGEST_ENTRY} in a plain {@code docker run -e}. It is deploy-time
     * configuration rather than a console dial on purpose: it is a per-process heap ceiling sized against the JVM's
     * own heap, read on the publish thread where a store round-trip per archive member would be absurd, and it is
     * deployment-global where a stored setting would be per tenant.
     */
    public static final String LARGEST_ENTRY_KEY = "jenesis.archive.largest-entry";

    private ArchiveInflation() {
        throw new UnsupportedOperationException("ArchiveInflation is a static utility");
    }

    /**
     * The configured ceiling on one member's decompressed size - {@link #LARGEST_ENTRY} unless an operator set
     * {@link #LARGEST_ENTRY_KEY}. Read live rather than latched, so a deployment's value is whatever its configuration
     * says at the moment of the read and a test can move it.
     *
     * @throws IllegalArgumentException when the key is set to something that is not a positive number of bytes -
     *         an operator who raised a cap and got the spelling wrong must not be left believing they raised it
     *         (&sect;9)
     */
    public static int largestEntry() {
        // The key is spelled out here as well as in LARGEST_ENTRY_KEY on purpose: ConfigPrincipleTest enumerates
        // config reads by matching a literal key at its read site, and a key reached only through a constant would
        // escape that scan - a stranded key is exactly what it exists to catch. The two spellings are pinned equal by
        // ArchiveInflationTest, so they cannot drift.
        String configured = Features.lookup().apply("jenesis.archive.largest-entry");
        if (configured == null || configured.isBlank()) {
            return LARGEST_ENTRY;
        }
        int bytes;
        try {
            bytes = Integer.parseInt(configured.trim());
        } catch (NumberFormatException cause) {
            throw new IllegalArgumentException(LARGEST_ENTRY_KEY + " must be a positive number of bytes, not '"
                    + configured + "'", cause);
        }
        if (bytes <= 0) {
            throw new IllegalArgumentException(LARGEST_ENTRY_KEY + " must be a positive number of bytes, not "
                    + bytes);
        }
        return bytes;
    }

    /** Read one archive member whole under the configured {@link #largestEntry()} ceiling - the call a format makes
     *  unless it has a stated reason of its own for a different bound. */
    public static Entry entry(InputStream member) throws IOException {
        return entry(member, largestEntry());
    }

    /**
     * Read one archive member whole under an explicit ceiling, for the reads whose own document is legitimately larger
     * than the shared default (a repository index record rather than a manifest). The number stays visible at the call
     * site that chose it, exactly as the shared default is visible here; what is <em>not</em> negotiable is that there
     * is a ceiling and that reaching it is reported.
     *
     * <p>{@code member} is read but never closed - an archive walk owns the underlying stream and moves on to the next
     * member either way - and the read stops at the ceiling rather than draining the rest, so a bomb costs the
     * ceiling, not the archive.
     *
     * @throws IllegalArgumentException when {@code limit} is not positive
     */
    public static Entry entry(InputStream member, int limit) throws IOException {
        if (limit <= 0) {
            throw new IllegalArgumentException("An archive-entry inflation bound must be positive: " + limit);
        }
        ByteArrayOutputStream inflated = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        for (int read; (read = member.read(buffer)) != -1; ) {
            total += read;
            if (total > limit) {
                // Past the ceiling: hand back nothing at all. What was inflated so far is a prefix an attacker chose
                // the length of, and a prefix of a declaration can parse.
                return new Entry(null, Outcome.TRUNCATED, limit);
            }
            inflated.write(buffer, 0, read);
        }
        return new Entry(inflated.toByteArray(), Outcome.EXHAUSTED, total);
    }

    /** Whether a bounded member read saw the member whole, or stopped at the ceiling - deliberately the vocabulary the
     *  bounded store traversals answer in, so "did I see all of it?" reads the same wherever a bound is met. */
    public enum Outcome {
        /** The member ended: the bytes are the whole member. */
        EXHAUSTED,
        /** The inflation ceiling stopped the read: no bytes are handed back, and nothing is known about the member. */
        TRUNCATED
    }

    /**
     * One bounded member read: the decompressed bytes when the member ended within the ceiling, the {@link Outcome},
     * and how many bytes were inflated ({@code inflated} equals the ceiling on a truncated read - the point at which
     * the read stopped, not the member's real size, which is precisely what the bound refuses to find out).
     */
    public record Entry(byte[] value, Outcome outcome, long inflated) {

        public Entry {
            Objects.requireNonNull(outcome, "outcome");
            if ((value != null) != (outcome == Outcome.EXHAUSTED)) {
                throw new IllegalArgumentException(
                        "An exhausted member read carries its bytes and a truncated one carries none: " + outcome);
            }
            if (inflated < 0) {
                throw new IllegalArgumentException("Negative inflated byte count: " + inflated);
            }
        }

        /** Whether the member was read whole, so {@link #value()} is the entire member. */
        public boolean exhausted() {
            return outcome == Outcome.EXHAUSTED;
        }

        /** Whether the inflation ceiling stopped the read, so nothing is known about what the member declared. */
        public boolean truncated() {
            return outcome == Outcome.TRUNCATED;
        }

        /**
         * The member's bytes, or {@code null} when the ceiling stopped the read - the <b>optional-declaration</b>
         * outcome. A caller that reaches for this is saying a missing declaration is a survivable answer: the artifact
         * publishes and simply declares nothing. Choose it only when losing the declaration can under-declare and
         * never under-screen.
         */
        public byte[] orNull() {
            return value;
        }

        /**
         * The member's bytes, or an {@link IOException} when the ceiling stopped the read - the <b>identity or
         * guard</b> outcome, for a member that carries something no other source can supply. The refusal names the
         * artifact and the member so an operator sees which of the two happened: the archive carries no such member,
         * or the inflation bound stopped the read before it could be seen.
         *
         * @param artifact how to name this artifact in the refusal ("Maven index record")
         * @param member   how to name the member that could not be read ("compressed field 'd'")
         */
        public byte[] required(String artifact, String member) throws IOException {
            if (value != null) {
                return value;
            }
            throw new IOException(artifact + " does not reach the end of its " + member + " within the "
                    + inflated + "-byte archive-inflation bound (" + LARGEST_ENTRY_KEY + ")");
        }
    }
}
