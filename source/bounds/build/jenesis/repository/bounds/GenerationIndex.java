package build.jenesis.repository.bounds;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.LineDocument;

/**
 * A store-backed index published one whole generation at a time: a rebuild writes a fresh generation beside the live
 * one and flips one small marker to it, so a reader sees the old generation until the flip and the new one after, never
 * a half-built set. Two generations ({@code g0}/{@code g1}) alternate; the superseded one and any orphan of a crashed
 * rebuild are reclaimed at the start of the next rebuild, so a page read in flight across a flip finds its generation
 * intact.
 *
 * <p>The findings filter, the health rank and the vulnerability rank share this; what differs is the bucket - the entry
 * body, the read, and the filter's facet nesting. A caller supplies a {@link Writer} that fills a fresh generation and
 * a {@link Reclaimer} that empties one; {@link #reclaimFlat} serves a generation whose entries are its immediate
 * children.
 *
 * <p>The marker is a {@link LineDocument} (a magic, a version, a field per line), keeping this module {@code java.base}
 * light. A marker that does not parse - a torn write, another shape - reads as unbuilt, as an absent one does: the next
 * rebuild writes a fresh generation and readers fall back until it lands.
 */
public final class GenerationIndex {

    /** How many flat children one reclaim step deletes, so a large generation is torn down in pages, not listed
     *  whole. */
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

    /** Fills a fresh generation under {@code generationPrefix} and answers how many entries it wrote - the marker's
     *  count. */
    @FunctionalInterface
    public interface Writer {
        long write(String generationPrefix) throws IOException;
    }

    /** Empties one generation under {@code generationPrefix} - the bucket's shape-aware delete ({@link #reclaimFlat}
     *  for a flat one). */
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

    /** Whether a live generation's stamp already matches {@code stamp}, so a caller skips the whole fold in the steady
     *  state. */
    public boolean upToDate(String stamp) throws IOException {
        Optional<Marker> marker = marker();
        return marker.isPresent() && marker.get().stamp().equals(stamp);
    }

    /**
     * Rebuild if the inputs moved since the last build, else do nothing: reclaim the superseded generation and any
     * crashed orphan, hand {@code writer} the fresh prefix, then publish it with one marker write. A reader that read
     * the old marker still pages the old, still-standing generation; later readers see this one.
     *
     * <p>The marker write is a compare-and-set against the marker this rebuild started from. Two rebuilders meet only
     * when one's lease lapsed and the other took it; the one whose marker moved loses the flip, and its generation is
     * an orphan the next rebuild reclaims rather than a marker pointing readers at a generation about to be reclaimed.
     * The lost flip is reported, so the pass counts as failed.
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

    /** Reclaim every generation directory but the live one - the one the last flip superseded and any crashed
     *  orphan. */
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

    /** Delete every entry directly under one prefix, in bounded pages - the flat reclaimer, which a nested one also
     *  uses for each leaf bucket. */
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
