package build.jenesis.repository.index;

import module java.base;
import com.github.luben.zstd.Zstd;

/**
 * The published index chunk format: a sequence of <strong>independent Zstandard frames</strong> (each a complete,
 * standalone {@code zstd} frame compressing one block of NDJSON records) followed by a <strong>seek table</strong>
 * written as the standard {@code zstd} seekable-format skippable frame. Because every frame is self-contained, a
 * consumer decompresses any one frame in isolation - resuming decompression at a frame boundary, from that frame's
 * known byte offset, rather than re-reading the chunk from the start (and, once the chunk serve honours a byte range,
 * fetching only that suffix; it writes a whole {@code 200} today).
 * The trailing seek table (magic {@code 0x8F92EAB1}) maps each frame's compressed and decompressed sizes, so the
 * byte offset of any frame is known without scanning; the chunks are therefore readable by the reference
 * {@code zstd_seekable} tooling, not just this module.
 *
 * <p>Only the library does the compression ({@link Zstd#compress}/{@link Zstd#decompress}); this class lays out the
 * frames and the seek-table framing, which are a fixed on-disk format, not a hand-rolled algorithm.
 */
public final class SeekableIndex {

    /** {@code zstd} skippable-frame magic (the seek table rides in one). */
    private static final int SKIPPABLE_MAGIC = 0x184D2A5E;

    /** The seekable-format footer magic that terminates a seek table. */
    private static final int SEEKABLE_MAGIC = 0x8F92EAB1;

    /** Compressed + decompressed size of a frame; no per-frame checksum (the descriptor carries the chunk hash). */
    private static final int ENTRY_SIZE = 8;

    /** {@code Number_Of_Frames}(4) + {@code Seek_Table_Descriptor}(1) + {@code Seekable_Magic_Number}(4). */
    private static final int FOOTER_SIZE = 9;

    private SeekableIndex() {
    }

    /** One frame's position in a chunk: its compressed byte length and the decompressed byte length it yields. */
    public record Frame(long compressedSize, long decompressedSize) {
    }

    /**
     * Assembles a streaming chunk one record line at a time, sealing an independent frame whenever the pending
     * uncompressed block reaches the frame budget, and emitting the seek table on {@link #finish}. The budget bounds
     * the memory a single frame holds, so building a chunk never buffers more than one frame of records plus the
     * sealed-frame bytes.
     */
    public static final class Writer {

        private final int frameBudget;
        private final int level;
        private final ByteArrayOutputStream frames = new ByteArrayOutputStream();
        private final List<Frame> table = new ArrayList<>();
        private final ByteArrayOutputStream block = new ByteArrayOutputStream();

        public Writer(int frameBudget, int level) {
            this.frameBudget = Math.max(1, frameBudget);
            this.level = level;
        }

        /** Append one NDJSON record line, sealing a frame once the pending block reaches the budget. */
        public void add(byte[] line) {
            block.writeBytes(line);
            if (block.size() >= frameBudget) {
                sealFrame();
            }
        }

        private void sealFrame() {
            if (block.size() == 0) {
                return;
            }
            byte[] raw = block.toByteArray();
            byte[] compressed = Zstd.compress(raw, level);
            frames.writeBytes(compressed);
            table.add(new Frame(compressed.length, raw.length));
            block.reset();
        }

        /** The compressed size of the frames sealed so far - what a chunk-rotation decision reads. */
        public int sealedSize() {
            return frames.size();
        }

        /** Whether nothing has been added (no sealed frame and no pending record). */
        public boolean isEmpty() {
            return frames.size() == 0 && block.size() == 0;
        }

        /** Seal the last pending frame and append the seek table, returning the finished immutable chunk bytes. */
        public byte[] finish() {
            sealFrame();
            return appendSeekTable(frames.toByteArray(), table);
        }
    }

    /** Append the seekable-format seek table (skippable frame) to the concatenated compressed {@code frames}. */
    static byte[] appendSeekTable(byte[] frames, List<Frame> table) {
        int content = table.size() * ENTRY_SIZE + FOOTER_SIZE;
        ByteArrayOutputStream out = new ByteArrayOutputStream(frames.length + 8 + content);
        out.writeBytes(frames);
        writeLe32(out, SKIPPABLE_MAGIC);
        writeLe32(out, content);
        for (Frame frame : table) {
            writeLe32(out, (int) frame.compressedSize());
            writeLe32(out, (int) frame.decompressedSize());
        }
        writeLe32(out, table.size());
        out.write(0);                                       // Seek_Table_Descriptor: no per-frame checksum
        writeLe32(out, SEEKABLE_MAGIC);
        return out.toByteArray();
    }

    /** Parse a chunk's seek table (from its trailing footer), yielding one {@link Frame} per compressed frame. */
    public static List<Frame> seekTable(byte[] chunk) {
        if (chunk.length < FOOTER_SIZE + 8) {
            throw new IllegalArgumentException("chunk too small to hold a seek table");
        }
        int length = chunk.length;
        if (readLe32(chunk, length - 4) != SEEKABLE_MAGIC) {
            throw new IllegalArgumentException("not a seekable-format chunk");
        }
        int descriptor = chunk[length - 5] & 0xFF;
        int entrySize = (descriptor & 0x80) != 0 ? ENTRY_SIZE + 4 : ENTRY_SIZE;
        int frameCount = readLe32(chunk, length - FOOTER_SIZE);
        if (frameCount < 0 || frameCount > (length - FOOTER_SIZE - 8) / entrySize) {
            // A garbled count would otherwise overflow entriesStart past the array instead of failing cleanly.
            throw new IllegalArgumentException("malformed seek table");
        }
        int entriesStart = length - FOOTER_SIZE - frameCount * entrySize;
        if (entriesStart < 8 || readLe32(chunk, entriesStart - 8) != SKIPPABLE_MAGIC) {
            throw new IllegalArgumentException("malformed seek table");
        }
        List<Frame> frames = new ArrayList<>(frameCount);
        int position = entriesStart;
        for (int index = 0; index < frameCount; index++) {
            frames.add(new Frame(readLe32u(chunk, position), readLe32u(chunk, position + 4)));
            position += entrySize;
        }
        return frames;
    }

    /**
     * Decompress a single frame in isolation, seeking to its byte offset computed from {@code table} - the proof that
     * a consumer resumes decompression at a frame boundary without touching earlier frames.
     */
    public static byte[] frame(byte[] chunk, List<Frame> table, int index) {
        long offset = 0;
        for (int earlier = 0; earlier < index; earlier++) {
            offset += table.get(earlier).compressedSize();
        }
        Frame frame = table.get(index);
        byte[] compressed = Arrays.copyOfRange(chunk, (int) offset, (int) (offset + frame.compressedSize()));
        return Zstd.decompress(compressed, (int) frame.decompressedSize());
    }

    /** Every NDJSON record line the whole chunk holds, decoded frame by frame - a full-chain replay for a consumer. */
    public static List<byte[]> records(byte[] chunk) {
        List<Frame> table = seekTable(chunk);
        List<byte[]> lines = new ArrayList<>();
        for (int index = 0; index < table.size(); index++) {
            split(frame(chunk, table, index), lines);
        }
        return lines;
    }

    private static void split(byte[] block, List<byte[]> lines) {
        int start = 0;
        for (int index = 0; index < block.length; index++) {
            if (block[index] == '\n') {
                lines.add(Arrays.copyOfRange(block, start, index));
                start = index + 1;
            }
        }
        if (start < block.length) {
            lines.add(Arrays.copyOfRange(block, start, block.length));
        }
    }

    private static void writeLe32(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    private static int readLe32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | (bytes[offset + 1] & 0xFF) << 8
                | (bytes[offset + 2] & 0xFF) << 16
                | (bytes[offset + 3] & 0xFF) << 24;
    }

    private static long readLe32u(byte[] bytes, int offset) {
        return readLe32(bytes, offset) & 0xFFFFFFFFL;
    }
}
