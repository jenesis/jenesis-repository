package build.jenesis.repository.format.testkit;

import module java.base;

/**
 * An artifact-sized body that never exists as an array.
 *
 * <p>The streaming clause cannot be proven with a small fixture blob: a format that buffers a 40-byte body and one
 * that streams it are indistinguishable from the outside. So the kit's streaming leg feeds a body that is
 * <em>generated</em> as it is read - deterministic, so its SHA-256 is known without materialising it - and counts the
 * bytes it has handed out. That counter is the tripwire: {@link WitnessStore} reads {@link #produced()} at the moment
 * the store is handed the stream, and a format that had already read the body into memory shows the whole length there
 * instead of zero. No timing, no heap sampling, no size threshold to tune - just "had you already read it?".
 *
 * <p>Deterministic by construction: byte {@code i} is a function of {@code i} alone, so every stream this hands out
 * carries identical content and a re-read verifies the stored blob byte for byte. The pattern varies per index (rather
 * than being a repeated constant) so a truncated, doubled or mis-offset copy changes the digest.
 */
public final class GeneratedBody {

    private final long length;
    private final AtomicLong produced = new AtomicLong();
    private final Map<String, String> digests = new ConcurrentHashMap<>();

    private GeneratedBody(long length) {
        this.length = length;
    }

    /** A generated body of exactly {@code length} bytes. */
    public static GeneratedBody of(long length) {
        if (length < 0) {
            throw new IllegalArgumentException("A generated body cannot be shorter than nothing: " + length);
        }
        return new GeneratedBody(length);
    }

    /** The body's length in bytes. */
    public long length() {
        return length;
    }

    /** How many bytes every stream handed out by {@link #open()} has delivered so far - the streaming tripwire. */
    public long produced() {
        return produced.get();
    }

    /** Forget what has been produced, so one body can drive several legs (an honest fetch, then a tampered one). */
    public void rewind() {
        produced.set(0L);
    }

    /**
     * A fresh stream over the body, counting into {@link #produced()}. Nothing is allocated beyond the caller's own
     * buffer, so this is a legitimate stand-in for a multi-gigabyte upstream download.
     */
    public InputStream open() {
        return new InputStream() {

            private long position;

            @Override
            public int read() {
                if (position >= length) {
                    return -1;
                }
                produced.incrementAndGet();
                return at(position++) & 0xFF;
            }

            @Override
            public int read(byte[] buffer, int offset, int count) {
                Objects.checkFromIndexSize(offset, count, buffer.length);
                if (position >= length) {
                    return -1;
                }
                int written = (int) Math.min(count, length - position);
                for (int index = 0; index < written; index++) {
                    buffer[offset + index] = at(position + index);
                }
                position += written;
                produced.addAndGet(written);
                return written;
            }
        };
    }

    /** The body's SHA-256, in lower-case hex - the content address the store keys it by. */
    public String sha256() {
        return digest("SHA-256");
    }

    /**
     * The body's digest under {@code algorithm}, in lower-case hex - computed by streaming the generator, never by
     * materialising it, and deliberately not counted into {@link #produced()} so the tripwire measures the format
     * alone. An ecosystem advertises whichever algorithm its protocol chose (Maven a SHA-1 sibling, OCI a SHA-256
     * reference), so a proxy fixture asks for the one its upstream would publish.
     */
    public String digest(String algorithm) {
        return digests.computeIfAbsent(algorithm, name -> {
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance(name);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalArgumentException("no such digest algorithm: " + name, e);
            }
            byte[] buffer = new byte[8192];
            for (long position = 0; position < length; ) {
                int written = (int) Math.min(buffer.length, length - position);
                for (int index = 0; index < written; index++) {
                    buffer[index] = at(position + index);
                }
                digest.update(buffer, 0, written);
                position += written;
            }
            return HexFormat.of().formatHex(digest.digest());
        });
    }

    /** The body's byte at {@code index} - a pure function of the index, so the content is reproducible anywhere. */
    private static byte at(long index) {
        return (byte) (index * 31 + (index >>> 8) * 7 + 11);
    }
}
