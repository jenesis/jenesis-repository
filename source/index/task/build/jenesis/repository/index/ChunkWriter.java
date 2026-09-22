package build.jenesis.repository.index;

import module java.base;

/**
 * Streams index records into the store as a sequence of rotated chunks: records accumulate into the current
 * {@link SeekableIndex.Writer}, and when its sealed compressed size reaches the configured maximum the chunk is
 * finished, written content-addressed, and a fresh one started. Only one chunk's frames are ever held in memory, so
 * indexing a large repository never buffers the whole delta. Each finished chunk contributes an
 * {@link IndexDescriptor.Chunk} carrying its id, sizes, record count and the publish-instant range it spans.
 */
final class ChunkWriter {

    private final PublishedIndex index;
    private final long maxChunkBytes;
    private final int frameBudget;
    private final int level;
    private final List<IndexDescriptor.Chunk> written = new ArrayList<>();

    private SeekableIndex.Writer writer;
    private long uncompressed;
    private int records;
    private Instant min;
    private Instant max;

    ChunkWriter(PublishedIndex index, long maxChunkBytes, int frameBudget, int level) {
        this.index = index;
        this.maxChunkBytes = Math.max(1, maxChunkBytes);
        this.frameBudget = frameBudget;
        this.level = level;
        reset();
    }

    private void reset() {
        writer = new SeekableIndex.Writer(frameBudget, level);
        uncompressed = 0;
        records = 0;
        min = null;
        max = null;
    }

    void add(IndexRecord record) throws IOException {
        byte[] line = record.line();
        writer.add(line);
        uncompressed += line.length;
        records++;
        Instant published = record.published();
        if (min == null || published.isBefore(min)) {
            min = published;
        }
        if (max == null || published.isAfter(max)) {
            max = published;
        }
        if (writer.sealedSize() >= maxChunkBytes) {
            seal();
        }
    }

    private void seal() throws IOException {
        if (records == 0) {
            return;
        }
        byte[] bytes = writer.finish();
        String id = index.writeChunk(bytes);
        written.add(new IndexDescriptor.Chunk(id, uncompressed, bytes.length, records,
                min == null ? Instant.EPOCH : min, max == null ? Instant.EPOCH : max));
        reset();
    }

    List<IndexDescriptor.Chunk> finish() throws IOException {
        seal();
        return List.copyOf(written);
    }
}
