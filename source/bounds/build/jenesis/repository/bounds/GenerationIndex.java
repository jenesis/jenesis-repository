package build.jenesis.repository.bounds;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.LineDocument;

/**
 * A store-backed index published one whole generation at a time: a rebuild writes a fresh generation beside the live
 * one, then flips a single small marker to point at it, so a reader sees the old generation until the flip and the new
 * one after, never a half-built set. Two generations ({@code g0}/{@code g1}) alternate; the superseded one and any
 * half-built orphan a crashed rebuild left are reclaimed at the <em>start</em> of the next rebuild, so a page read in
 * flight across a flip always finds its generation intact.
 *
 * <p>Three indexes carried this machinery each, each saying it was "identical to" the other two - the findings filter,
 * the maintainer-health rank and the vulnerability rank. What differs between them is the <em>bucket</em>: the entry
 * body they write, the read they serve, and (for the filter) the facet nesting under a generation. What was the same
 * is here: the {@link Marker} codec, the {@link #upToDate stamp check}, the g0/g1 choice, the atomic flip and the
 * reclaim-every-generation-but-the-live-one orchestration. A caller supplies a {@link Writer} that fills a fresh
 * generation and a {@link Reclaimer} that empties one - {@link #reclaimFlat} is the shared reclaimer for a generation
 * whose entries are its immediate children, which the two rank indexes use; the filter passes its own for its nested
 * facet buckets.
 *
 * <p>The marker is a {@link LineDocument} - a magic and a version, then a named field per line - so this primitive
 * stays in a {@code java.base}-light module beside {@link InheritedBound} rather than pulling a JSON library in for
 * three fields, and does not carry its own codec for them: it used to, as three positional lines under the magic,
 * which was the shape {@code LineDocument} was written to absorb. A marker that does not parse - a torn write, an
 * older positional or JSON marker from before this shape - reads as <em>unbuilt</em>, exactly as an absent one does:
 * the next rebuild writes a fresh generation and the read falls back until it lands, which is the same self-heal a
 * torn generation already had.
 */
public final class GenerationIndex {

    /** How many flat children one reclaim step deletes at a time, so a very large generation is torn down in pages
     *  rather than listed whole into heap. */
    private static final int GC_BATCH = 500;

    private static final String MAGIC = "jenesis-generation";
    private static final int VERSION = 1;

    private final ArtifactStore store;
    private final String prefix;
    private final String built;

    public GenerationIndex(ArtifactStore store, String prefix) {
        this.store = Objects.requireNonNull(store, "store");
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        this.built = prefix + "/built";
    }

    /** The live generation, its entry count and the freshness stamp it was built at. */
    public record Marker(int generation, long count, String stamp) {

        /** The live generation's key prefix, {@code <index>/g<generation>}. */
        public String generationPrefix(String indexPrefix) {
            return indexPrefix + "/g" + generation;
        }
    }

    /** Fills a fresh generation under {@code generationPrefix} and answers how many entries it wrote - the count the
     *  marker records. */
    @FunctionalInterface
    public interface Writer {
        long write(String generationPrefix) throws IOException;
    }

    /** Empties one generation under {@code generationPrefix} - the shape-aware delete a bucket definition owns
     *  ({@link #reclaimFlat} for a flat one). */
    @FunctionalInterface
    public interface Reclaimer {
        void reclaim(String generationPrefix) throws IOException;
    }

    /** The live marker, or empty when no generation has ever been committed or the marker does not parse. */
    public Optional<Marker> marker() throws IOException {
        return marker(store.readVersioned(built));
    }

    private static Optional<Marker> marker(Optional<ArtifactStore.Versioned> stored) {
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        Optional<LineDocument> document = LineDocument.parse(stored.get().content(), MAGIC)
                .filter(parsed -> parsed.version() == VERSION);
        if (document.isEmpty()) {
            return Optional.empty();                            // torn, or an older marker: reads as unbuilt, rebuild
        }
        try {
            LineDocument marker = document.get();
            return Optional.of(new Marker(Integer.parseInt(marker.field("generation").orElseThrow()),
                    Long.parseLong(marker.field("count").orElseThrow()), marker.field("stamp").orElseThrow()));
        } catch (NumberFormatException | NoSuchElementException malformed) {
            return Optional.empty();
        }
    }

    /** The live generation's key prefix, or empty when none is built - the prefix a reader pages. */
    public Optional<String> live() throws IOException {
        return marker().map(marker -> marker.generationPrefix(prefix));
    }

    /** Whether a live generation stands whose stamp already matches {@code stamp} - so a caller can skip the whole
     *  fold in the steady state rather than computing it only for {@link #rebuild} to no-op. */
    public boolean upToDate(String stamp) throws IOException {
        Optional<Marker> marker = marker();
        return marker.isPresent() && marker.get().stamp().equals(stamp);
    }

    /**
     * Rebuild if the inputs moved since the last build, else do nothing: reclaim the superseded generation and any
     * crashed orphan, hand {@code writer} the fresh generation's prefix, then publish it with one marker write.
     * A reader that read the old marker a moment ago still pages the old (still-standing) generation; every later
     * reader sees this one.
     *
     * <p>The marker write is a compare-and-set against the marker this rebuild started from. Two rebuilders on two
     * nodes can only meet here when one's single-writer lease lapsed and the other took it; the one whose marker
     * moved under it then loses the flip and the generation it built is an orphan the next rebuild reclaims, instead
     * of its marker overwriting the newer rebuild's and pointing readers at a generation that rebuild is about to
     * reclaim. The lost flip is reported, so the pass is counted as failed the way a lost lease renewal is.
     */
    public void rebuild(String stamp, Writer writer, Reclaimer reclaimer) throws IOException {
        Optional<ArtifactStore.Versioned> stored = store.readVersioned(built);
        Optional<Marker> marker = marker(stored);
        if (marker.isPresent() && marker.get().stamp().equals(stamp)) {
            return;                                             // inputs unchanged since the last build: nothing to do
        }
        int liveGeneration = marker.map(Marker::generation).orElse(-1);
        reclaimGenerationsExcept(liveGeneration, reclaimer);
        int generation = liveGeneration == 0 ? 1 : 0;          // double-buffered: build the other half
        long count = writer.write(prefix + "/g" + generation);
        if (!store.writeVersioned(built, framed(generation, count, stamp),
                stored.map(ArtifactStore.Versioned::token).orElse(null))) {
            throw new IOException("the generation index at " + prefix + " was rebuilt by another node meanwhile; this "
                    + "rebuild's generation is left for the next rebuild to reclaim");
        }
    }

    /** Reclaim every generation directory except the live one - the previous generation superseded by the last flip
     *  and any half-built generation a crashed rebuild orphaned - so at a rebuild's start only the live generation
     *  stands. */
    private void reclaimGenerationsExcept(int live, Reclaimer reclaimer) throws IOException {
        for (String child : store.list(prefix)) {
            if (!child.startsWith("g") || child.equals("g" + live)) {
                continue;                                       // the marker leaf, or the live generation: leave it
            }
            if (child.length() == 1 || !child.chars().skip(1).allMatch(Character::isDigit)) {
                continue;                                       // not a generation directory (g<digits>)
            }
            reclaimer.reclaim(prefix + "/" + child);
        }
    }

    /** Delete every entry directly under one prefix, in bounded pages so a very large level never lists whole into
     *  heap - the reclaimer for an index whose entries are the generation's immediate children, and the one a nested
     *  reclaimer tears each of its leaf buckets down with, rather than with a copy of this loop. */
    public void reclaimFlat(String generationPrefix) throws IOException {
        List<String> page = new ArrayList<>();
        do {
            page.clear();
            store.page(generationPrefix, "", GC_BATCH, page::add);
            for (String name : page) {
                store.delete(generationPrefix + "/" + name);
            }
        } while (page.size() == GC_BATCH);                      // deletes remove the earliest names; re-page from start
    }

    private static byte[] framed(int generation, long count, String stamp) {
        return LineDocument.of(MAGIC, VERSION)
                .field("generation", generation)
                .field("count", count)
                .field("stamp", stamp)
                .bytes();
    }
}
