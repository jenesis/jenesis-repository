package build.jenesis.repository.compliance;

import module java.base;

import build.jenesis.repository.store.ArchiveInflation;
import build.jenesis.repository.store.ArchiveWalk;

/**
 * The zip-entry walk a {@link QualityInspector} cracks an artifact with: open the archive, refuse a body that is not
 * the archive it claims to be, and pick the best-ranked manifest entry - the shape the NuGet, Composer, CocoaPods and
 * Conda inspectors each hand-rolled before it lived here once.
 *
 * <h2>The bound is not this class's</h2>
 * It used to be. This file carried its own 64 MiB {@code SCAN_LIMIT}, its own body-relative {@code scanLimit(len,
 * ratio)} and its own byte-counting {@code Capped} stream, in parallel with the formats' private copies of the same
 * three things one repository over. All of that is now {@link ArchiveWalk} - one number an operator can
 * move at {@link ArchiveWalk#LARGEST_WALK_KEY}, one screened walk that applies it, one vocabulary for reaching it - and
 * this class is the zip walker that rides it. What is left here is the ranking and the entry reading, which are the
 * gate's own and have no counterpart on the shared walk.
 *
 * <h2>What that changed, deliberately</h2>
 * Two behaviours the hand-rolled cap got wrong, and the screen gets right:
 * <ul>
 *   <li><b>A truncated walk yields nothing at all.</b> The old walk handed back the best entry it had found <em>with
 *       the truncation flag set</em>, so an archive could place a decoy manifest early and the real one past the
 *       ceiling and choose what a screen saw. {@link ArchiveWalk.Found} makes that unrepresentable - a value exactly
 *       when the walk was not cut off - so the {@code orNull()} callers (Composer, CocoaPods, Conda, Maven licences)
 *       now read "this artifact declares nothing" where they used to read the early find.</li>
 *   <li><b>A budget exactly spent is not a truncation.</b> The old stream reported capped the moment the ceiling was
 *       reached, even when the archive ended on that very byte, so an artifact whose footprint was exactly the bound
 *       cried wolf. The screen looks one byte ahead before it decides.</li>
 * </ul>
 * Nothing else about the walk moves. In particular a walk that runs out of <em>body</em> part-way through still ends
 * as a walk that did not complete rather than as a corrupt archive: the {@code byte[]} an inspector is handed is
 * itself bounded, at {@link QualityInspector#PREFIX_INSPECTION_LIMIT}, so an archive ending mid-entry is the ordinary
 * shape of a large artifact seen through that window and not a verdict about the artifact. That tier is the gate's to
 * know about, which is why the mapping lives here and not in the walk.
 *
 * <h2>Which side a caller lands on is the read's ROLE, never the format</h2>
 * A cut-off walk is degraded by one inspector and refused by another, and the criterion is what the entry being looked
 * for carries, exactly as {@link QualityInspector}'s error-visibility clause states it:
 * <ul>
 *   <li>an <b>optional declaration</b> - a licence beside a coordinate the request path already yields (Composer,
 *       CocoaPods, Conda, Maven) - takes {@link ArchiveWalk.Found#orNull()} and degrades to "declares nothing", because
 *       a cut-off scan can then only under-declare a licence, never hide a coordinate; and</li>
 *   <li>the artifact's <b>identity</b> - a coordinate that exists nowhere but inside the archive (NuGet's
 *       {@code .nuspec}, RubyGems' gemspec) - fails closed, saying which of the two happened.</li>
 * </ul>
 * The gate's refusal type is its own ({@link MalformedArtifactException}, which the screens hold fail-closed and record
 * as an inspection failure), so an identity-bearing caller re-words the seam's refusal rather than inheriting it.
 */
public final class BoundedArchive {

    private BoundedArchive() {
        throw new UnsupportedOperationException("BoundedArchive is a static utility");
    }

    /** Reads one archive entry an inspector claimed, or empty when the entry does not carry a usable declaration. */
    @FunctionalInterface
    public interface EntryReader<T> {

        /**
         * The declaration read from the entry {@code name}, or {@link Optional#empty()} when this entry turned out
         * not to carry one (it exceeded the manifest tier, or did not parse). {@code entry} is bounded to this
         * entry by the archive reader and must not be closed; the walk moves on to the next entry either way.
         */
        Optional<T> read(String name, InputStream entry) throws IOException;
    }

    /**
     * Walk the zip entries of {@code body} under {@code walkLimit} and return the best-ranked entry {@code reader}
     * could read.
     *
     * <p><b>Ranking.</b> {@code rank} scores an entry name: a negative score ignores the entry, {@code 0} is the
     * preferred location (a manifest at the archive root) and higher scores are fallbacks (the same manifest one
     * directory deep, as a VCS export files it). A rank-0 hit ends the walk immediately; a fallback is remembered
     * and only ever bettered, so the walk stays single-pass and the archive is read once.
     *
     * <p><b>Unreadable versus declaring nothing.</b> The first entry is read eagerly, so a body that is not the
     * archive it claims to be is refused up front with a {@link MalformedArtifactException} naming
     * {@code artifact} - a could-not-parse of the claimed artifact, which the screens fail closed on. Once the
     * archive HAS opened, a walk that cannot finish - the walk bound cutting off a bomb, or the bounded body simply
     * ending mid-entry - is a walk that did not complete, not a verdict, and is reported as
     * {@link ArchiveWalk.Found#truncated()}.
     *
     * <p>Whether a cut-off walk is fatal is the caller's to say, and it says it by which accessor it takes:
     * {@link ArchiveWalk.Found#orNull()} for an optional declaration, a refusal for the artifact's identity (see the
     * class javadoc). A truncated walk carries no value at all, so an early decoy is never what the caller reads.
     *
     * <p>A {@code reader} that itself refuses the entry it read - an over-tier manifest whose value carries the
     * coordinate - throws {@link MalformedArtifactException} and that refusal propagates unchanged, because it is a
     * verdict about the entry the walk did reach, not a walk that fell short.
     *
     * @param artifact  how to name this artifact in a refusal ("Composer package", "conda .conda package")
     * @param body      the archive bytes; consumed, not closed
     * @param walkLimit the walk ceiling - {@link ArchiveWalk#largestWalk()}, or a body-relative one from
     *                  {@link ArchiveWalk#largestWalk(long, long)}
     * @param rank      the entry-name ranking: negative ignores, 0 preferred, higher is a fallback
     * @param reader    reads a ranked entry into the declaration, or empty when it does not carry one
     */
    public static <T> ArchiveWalk.Found<T> zipEntry(String artifact, InputStream body, long walkLimit,
                                                    ToIntFunction<String> rank, EntryReader<T> reader)
            throws MalformedArtifactException {
        boolean[] cutShort = {false};
        ArchiveWalk.Found<T> found;
        try {
            found = ArchiveWalk.walk(body, walkLimit, screened -> {
                ZipInputStream zip = new ZipInputStream(screened);
                ZipEntry first;
                try {
                    first = zip.getNextEntry();
                } catch (IOException | RuntimeException cause) {
                    throw new MalformedArtifactException(artifact + " is not a readable zip archive", cause);
                }
                if (first == null) {
                    throw new MalformedArtifactException(artifact + " is not a readable zip archive");
                }
                T best = null;
                int bestRank = Integer.MAX_VALUE;
                try {
                    for (ZipEntry entry = first; entry != null; entry = zip.getNextEntry()) {
                        if (entry.isDirectory()) {
                            continue;
                        }
                        int rankedAt = rank.applyAsInt(entry.getName());
                        if (rankedAt < 0 || rankedAt >= bestRank) {
                            continue;
                        }
                        Optional<T> read = reader.read(entry.getName(), zip);
                        if (read.isPresent()) {
                            if (rankedAt == 0) {
                                return read.get();
                            }
                            best = read.get();
                            bestRank = rankedAt;
                        }
                    }
                } catch (MalformedArtifactException malformed) {
                    throw malformed;            // a reader's verdict about the entry it DID reach keeps its signal
                } catch (IOException | RuntimeException _) {
                    // The archive opened and the walk could not finish: the bounded body ended mid-entry, or a
                    // trailing entry will not inflate. Either way nothing more can be learnt, and what was already
                    // passed is dropped rather than handed back (see the class javadoc) - the outcome below says the
                    // walk stopped early, and the caller's accessor decides whether that is fatal.
                    cutShort[0] = true;
                    return null;
                }
                return best;
            });
        } catch (MalformedArtifactException malformed) {
            throw malformed;                    // the gate's own refusal keeps its distinct signal
        } catch (IOException | RuntimeException cause) {
            throw new MalformedArtifactException(artifact + " is not a readable zip archive", cause);
        }
        // A body that ran out is a bound the GATE owns (the prefix tier), so the walk says it stopped early even
        // though the screen still had budget - the walk cannot know the byte[] it was fed is itself a
        // window. The consumed count is the real one, so a refusal still names how far the read got.
        return cutShort[0] && !found.truncated()
                ? new ArchiveWalk.Found<>(null, ArchiveInflation.Outcome.TRUNCATED, found.consumed())
                : found;
    }

    /**
     * The identity-bearing outcome, in the gate's own refusal type: the declaration, or a
     * {@link MalformedArtifactException} saying WHICH of the two happened - the archive genuinely carries no such
     * member, or the walk bound stopped before it was reached. It re-words {@link ArchiveWalk.Found#required} rather
     * than restating its rule, because the compliance screens hold on {@code MalformedArtifactException} specifically
     * (they record an inspection-failed finding on the coordinate) and a plain {@code IOException} would read to them
     * as a transport problem.
     *
     * @param artifact how to name this artifact in the refusal ("NuGet .nupkg")
     * @param manifest how to name the member that was not found (".nuspec manifest")
     */
    public static <T> T required(ArchiveWalk.Found<T> found, String artifact, String manifest)
            throws MalformedArtifactException {
        try {
            return found.required(artifact, manifest);
        } catch (IOException refused) {
            throw new MalformedArtifactException(refused.getMessage(), refused);
        }
    }
}
