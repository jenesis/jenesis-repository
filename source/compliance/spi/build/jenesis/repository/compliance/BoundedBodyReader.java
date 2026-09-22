package build.jenesis.repository.compliance;

import module java.base;

import build.jenesis.repository.store.ArchiveInflation;

/**
 * The bounded heap reads a {@link QualityInspector} makes while it screens an artifact, and the shared inspection
 * tiers those reads are bounded by.
 *
 * <h2>The manifest tier moved out</h2>
 * This class used to own the manifest tier too - a private {@code MANIFEST_LIMIT} of 4 MiB and a {@code readComplete}
 * that applied it. That was a second copy of a bound that has since been given one home, one operator key
 * and one build guard: {@link ArchiveInflation}. Two shared per-member readers with the same never-a-prefix doctrine
 * and two different numbers is the shape removed, so the manifest tier is now
 * {@link ArchiveInflation#largestEntry()}, settable at {@link ArchiveInflation#LARGEST_ENTRY_KEY}, and an inspector
 * that reads one archive member calls {@link ArchiveInflation#entry(InputStream)} directly. What is left here is the
 * two <em>prefix-tier</em> helpers, which are about how much of the ARTIFACT an inspector sees rather than how far one
 * of its members may inflate.
 *
 * <h2>The tiers</h2>
 * These bounds apply to every inspector and are therefore declared once:
 * <ul>
 *   <li>the <b>manifest tier</b> - {@link ArchiveInflation#largestEntry()}, the most of one embedded declaration
 *       inside an artifact that is materialised while the coordinate/licence is read off it (shared with every
 *       format that cracks an archive);</li>
 *   <li>the <b>prefix tier</b> - {@link QualityInspector#PREFIX_INSPECTION_LIMIT}, the most of an artifact body that
 *       is ever materialised into a heap {@code byte[]} for the {@code byte[]} inspection tier; and</li>
 *   <li>the <b>full-body tier</b> - {@link QualityInspector#FULL_BODY_INSPECTION_LIMIT}, the most of a fully-spooled
 *       body an inspector that overrides the spooled leg may stream, which {@link #readPrefix} is the bridge down
 *       <em>from</em>.</li>
 * </ul>
 * They are ordered, and the order is load-bearing rather than incidental - a declaration cannot outgrow the prefix
 * that carries it, and a whole-artifact read that stopped short of the bounded-prefix read would buy no reach at all.
 * {@code BoundedInspectionTest} asserts the order, now with the manifest tier read live off {@link ArchiveInflation}
 * so an operator who raises the archive key past the prefix tier is a visible failure rather than a silent inversion.
 *
 * <p>A limit that is genuinely format-specific (a gemspec that is allowed to be larger than a manifest, a binary
 * header's index/store sanity bounds) stays an explicit constant at its own call site and is passed to the
 * {@linkplain ArchiveInflation#entry(InputStream, int) two-argument} read - the shared tiers exist so the
 * <em>shared</em> concern is stated once, not so every format's arithmetic is hidden.
 *
 * <h2>A bound is an outcome, never a shorter value</h2>
 * {@link ArchiveInflation.Entry} never carries a truncated prefix: a caller that gets bytes got all of them, and a
 * member that exceeded the ceiling carries none - a distinct outcome the caller maps to its own disposition with
 * {@link ArchiveInflation.Entry#orNull()} (declare no licence, screen the coordinate from the path) or
 * {@link ArchiveInflation.Entry#required(String, String)} (refuse the artifact). A "plausible but incomplete"
 * manifest is exactly what must never reach a parser, because a truncated declaration that happens to still parse
 * would be screened as if it were the whole one.
 */
public final class BoundedBodyReader {

    private BoundedBodyReader() {
        throw new UnsupportedOperationException("BoundedBodyReader is a static utility");
    }

    /**
     * A body an inspector may open, and open again.
     *
     * <p>The two inspection legs hold an artifact differently - the bounded one has a {@code byte[]} that is at most
     * a front prefix, the streamed one a re-openable handle on the stored blob - and an inspector that reads the same
     * thing on both needs one way to say "open it" that does not care which. Two inspectors wrote that interface
     * privately within a day of each other (Debian's licence walk and Maven's jar rungs), which is the shape &sect;2
     * exists to stop: it is stated here once, beside the tiers it is read under.
     *
     * <p>Deliberately NOT {@link QualityInspector.Content}, which is the streamed leg's own handle: that contract
     * promises {@link QualityInspector.Content#size()} is the artifact's full length, and a bounded leg holding a
     * prefix cannot honour it. A source promises less - only that it opens - and is therefore the thing both legs
     * can be.
     */
    @FunctionalInterface
    public interface Source {

        /** A fresh stream over the body from its first byte; the caller closes it. */
        InputStream open() throws IOException;

        /** A source over bytes already in hand - the bounded leg's form. */
        static Source of(byte[] body) {
            return () -> new ByteArrayInputStream(body);
        }

        /** A source over a spooled artifact - the streamed leg's form. */
        static Source of(QualityInspector.Content body) {
            return body::open;
        }
    }

    /**
     * Whether the {@code byte[]} an inspector was handed on the {@code byte[]} tier is the artifact <em>whole</em>,
     * rather than a {@link QualityInspector#PREFIX_INSPECTION_LIMIT} front prefix of a larger body. An inspector
     * that derives a whole-artifact fact - a digest for an attestation's statement-subject binding, a length - must
     * ask this first: computing that fact over a prefix and reporting it as if it covered the artifact would assert
     * something the inspector never read. The answer is "no" at exactly the bound, because a body of precisely the
     * limit is indistinguishable from a larger one that was cut off there.
     */
    public static boolean completeArtifact(byte[] artifact) {
        return artifact != null && artifact.length < QualityInspector.prefixInspectionLimit();
    }

    /**
     * The {@link QualityInspector#PREFIX_INSPECTION_LIMIT} front prefix of a fully-spooled body - the bridge from
     * the full-body inspection tier down to the {@code byte[]} prefix tier, so the tier arithmetic exists once
     * rather than in every inspector that overrides the spooled leg. The body is streamed, never materialised past
     * the bound, and the stream is closed.
     */
    public static byte[] readPrefix(QualityInspector.Content body) throws IOException {
        try (InputStream in = body.open()) {
            return in.readNBytes(QualityInspector.prefixInspectionLimit());
        }
    }
}
