package build.jenesis.repository.format.nuget;

import module java.base;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.store.ArchiveInflation;

/**
 * The two things a signed {@code .nupkg} needs before the shared PKCS#7 verifier can judge it: the signature the
 * archive carries, and the bytes the signature's hash statement was computed over.
 *
 * <h2>Where NuGet puts a signature, and what it signs</h2>
 *
 * <p>A signed package carries one extra entry, {@value #SIGNATURE_ENTRY}, stored (never deflated) as the archive's
 * last local entry, directly before the central directory, with its record last in the central directory. The entry
 * is a CMS {@code SignedData} whose encapsulated content is a hash statement over <em>the package as it was before
 * that entry was appended</em>: the archive with the signature's local entry cut out, its central-directory record
 * cut out, and the end-of-central-directory record adjusted by one entry, the record's length and the local entry's
 * length. That is NuGet's own definition (the client hashes exactly that reconstruction when it verifies), so
 * {@link #unsigned} rebuilds it byte for byte and the verifier digests it as it would any covered content.
 *
 * <h2>Streamed, twice, and bounded</h2>
 *
 * <p>The layout lives at the end of the archive and a {@link ArtifactSignatures.Signed} opens a fresh stream each time,
 * so the rebuild is two passes and never a copy of the package: the first streams the body to its end keeping only a
 * tail of {@value #TAIL} bytes (the central directory and end record of any package with fewer than tens of
 * thousands of entries fit in it several times over), reads the layout out of the tail and checks NuGet's placement
 * rules; the second streams the body again, copying around the two holes and emitting the adjusted end record. A
 * package whose central directory does not fit the tail, a zip64 archive, or a signature entry with a data
 * descriptor is reported as unreadable rather than guessed at - the verifier then answers "unreadable" for the
 * signature, which is the honest outcome for a shape the client itself refuses to sign.
 */
final class NuGetSignedArchive {

    /** The archive entry a NuGet author or repository signature lives in. */
    static final String SIGNATURE_ENTRY = ".signature.p7s";

    /** How much of the archive's end the first pass keeps: the central directory and end record must fit in it. */
    static final int TAIL = 4 * 1024 * 1024;

    private static final int EOCD_SIGNATURE = 0x06054b50;
    private static final int CD_SIGNATURE = 0x02014b50;
    private static final int EOCD_LENGTH = 22;
    private static final int CD_RECORD_FIXED = 46;

    private NuGetSignedArchive() {
    }

    /**
     * The signature entry's bytes, read under the seam's signature bound, or empty for an unsigned package. The walk is
     * the archive's own order, so the signature - last by NuGet's rule - is found after every other entry's header has
     * been skipped, never inflated.
     */
    static Optional<byte[]> signature(InputStream nupkg) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(nupkg)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (entry.getName().equals(SIGNATURE_ENTRY)) {
                    return Optional.of(ArchiveInflation.entry(zip, ArtifactSignatures.Material.LARGEST_SIGNATURE)
                            .required("NuGet package", SIGNATURE_ENTRY));
                }
            }
        }
        return Optional.empty();
    }

    /** The package as it was before signing, rebuilt on each open as the class comment describes. */
    static ArtifactSignatures.Signed unsigned(ArtifactSignatures.Signed signed) {
        return () -> {
            Layout layout = layout(signed);
            return new Rebuilt(signed.open(), layout);
        };
    }

    /** Where the signature entry and its record sit, and what the end record must become without them. */
    record Layout(long localOffset, long localLength, long cdOffset, long cdLength, long recordOffset, int recordLength,
                  long eocdOffset, byte[] eocd) {
    }

    static Layout layout(ArtifactSignatures.Signed signed) throws IOException {
        byte[] tail = new byte[TAIL];
        int held = 0;
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream body = signed.open()) {
            for (int read = body.read(buffer); read != -1; read = body.read(buffer)) {
                total += read;
                if (read >= tail.length) {
                    System.arraycopy(buffer, read - tail.length, tail, 0, tail.length);
                    held = tail.length;
                } else if (held + read <= tail.length) {
                    System.arraycopy(buffer, 0, tail, held, read);
                    held += read;
                } else {
                    int keep = tail.length - read;
                    System.arraycopy(tail, held - keep, tail, 0, keep);
                    System.arraycopy(buffer, 0, tail, keep, read);
                    held = tail.length;
                }
            }
        }
        long tailStart = total - held;
        int eocd = -1;
        for (int at = held - EOCD_LENGTH; at >= 0; at--) {
            if (int32(tail, at) == EOCD_SIGNATURE) {
                eocd = at;
                break;
            }
        }
        if (eocd < 0) {
            throw new IOException("The NuGet package has no end-of-central-directory record in its last " + held
                    + " bytes");
        }
        int entries = int16(tail, eocd + 10);
        long cdLength = int32(tail, eocd + 12) & 0xFFFFFFFFL;
        long cdOffset = int32(tail, eocd + 16) & 0xFFFFFFFFL;
        if (entries == 0xFFFF || cdLength == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
            throw new IOException("The NuGet package is a zip64 archive, which this reader does not rebuild");
        }
        if (cdOffset < tailStart) {
            throw new IOException("The NuGet package's central directory (" + cdLength + " bytes at " + cdOffset
                    + ") does not fit the " + TAIL + "-byte tail this reader keeps");
        }
        if (cdOffset + cdLength != tailStart + eocd) {
            throw new IOException("The NuGet package's central directory does not end where its end record begins");
        }
        int cd = (int) (cdOffset - tailStart);
        int at = cd;
        long lastLocal = -1;
        int signatureRecord = -1;
        int signatureRecordLength = 0;
        long signatureLocal = -1;
        int signatureFlags = 0;
        for (int index = 0; index < entries; index++) {
            if (at + CD_RECORD_FIXED > eocd || int32(tail, at) != CD_SIGNATURE) {
                throw new IOException("The NuGet package's central directory record " + index + " is not readable");
            }
            int nameLength = int16(tail, at + 28);
            int extraLength = int16(tail, at + 30);
            int commentLength = int16(tail, at + 32);
            long localOffset = int32(tail, at + 42) & 0xFFFFFFFFL;
            int recordLength = CD_RECORD_FIXED + nameLength + extraLength + commentLength;
            String name = new String(tail, at + CD_RECORD_FIXED, nameLength, StandardCharsets.UTF_8);
            if (name.equals(SIGNATURE_ENTRY)) {
                signatureRecord = at;
                signatureRecordLength = recordLength;
                signatureLocal = localOffset;
                signatureFlags = int16(tail, at + 8);
                if (index != entries - 1) {
                    throw new IOException("The NuGet package's " + SIGNATURE_ENTRY
                            + " is not the last central-directory record, which a signed package requires");
                }
            } else {
                lastLocal = Math.max(lastLocal, localOffset);
            }
            at += recordLength;
        }
        if (signatureRecord < 0) {
            throw new IOException("The NuGet package carries no " + SIGNATURE_ENTRY + " entry");
        }
        if ((signatureFlags & 0x8) != 0) {
            throw new IOException("The NuGet package's " + SIGNATURE_ENTRY
                    + " carries a data descriptor, which a signed package must not");
        }
        if (signatureLocal <= lastLocal || signatureLocal >= cdOffset) {
            throw new IOException("The NuGet package's " + SIGNATURE_ENTRY
                    + " is not the last local entry before the central directory, which a signed package requires");
        }
        long localLength = cdOffset - signatureLocal;
        byte[] end = Arrays.copyOfRange(tail, eocd, held);
        put16(end, 8, entries - 1);
        put16(end, 10, entries - 1);
        put32(end, 12, cdLength - signatureRecordLength);
        put32(end, 16, cdOffset - localLength);
        return new Layout(signatureLocal, localLength, cdOffset, cdLength,
                tailStart + signatureRecord, signatureRecordLength, tailStart + eocd, end);
    }

    /** The second pass: the body with the signature's local entry and record cut out, then the adjusted end record. */
    private static final class Rebuilt extends InputStream {

        private final InputStream body;
        private final Layout layout;
        private long position;
        private int endAt;

        private Rebuilt(InputStream body, Layout layout) {
            this.body = body;
            this.layout = layout;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read == -1 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] into, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            long localEnd = layout.localOffset() + layout.localLength();
            long recordEnd = layout.recordOffset() + layout.recordLength();
            while (position < layout.eocdOffset()) {
                long copyUntil;
                if (position < layout.localOffset()) {
                    copyUntil = layout.localOffset();
                } else if (position < localEnd) {
                    skipFully(localEnd - position);
                    position = localEnd;
                    continue;
                } else if (position < layout.recordOffset()) {
                    copyUntil = layout.recordOffset();
                } else if (position < recordEnd) {
                    skipFully(recordEnd - position);
                    position = recordEnd;
                    continue;
                } else {
                    copyUntil = layout.eocdOffset();
                }
                int want = (int) Math.min(length, copyUntil - position);
                int read = body.read(into, offset, want);
                if (read == -1) {
                    throw new EOFException("The NuGet package ended at " + position + " on the second pass, before "
                            + "the " + layout.eocdOffset() + " bytes the first pass read");
                }
                position += read;
                return read;
            }
            if (endAt >= layout.eocd().length) {
                return -1;
            }
            int count = Math.min(length, layout.eocd().length - endAt);
            System.arraycopy(layout.eocd(), endAt, into, offset, count);
            endAt += count;
            return count;
        }

        private void skipFully(long count) throws IOException {
            long left = count;
            while (left > 0) {
                long skipped = body.skip(left);
                if (skipped <= 0) {
                    if (body.read() == -1) {
                        throw new EOFException("The NuGet package ended inside its signature entry on the second pass");
                    }
                    skipped = 1;
                }
                left -= skipped;
            }
        }

        @Override
        public void close() throws IOException {
            body.close();
        }
    }

    private static int int16(byte[] bytes, int at) {
        return (bytes[at] & 0xFF) | (bytes[at + 1] & 0xFF) << 8;
    }

    private static int int32(byte[] bytes, int at) {
        return (bytes[at] & 0xFF) | (bytes[at + 1] & 0xFF) << 8 | (bytes[at + 2] & 0xFF) << 16 | (bytes[at + 3] & 0xFF) << 24;
    }

    private static void put16(byte[] bytes, int at, int value) {
        bytes[at] = (byte) value;
        bytes[at + 1] = (byte) (value >>> 8);
    }

    private static void put32(byte[] bytes, int at, long value) {
        bytes[at] = (byte) value;
        bytes[at + 1] = (byte) (value >>> 8);
        bytes[at + 2] = (byte) (value >>> 16);
        bytes[at + 3] = (byte) (value >>> 24);
    }
}
