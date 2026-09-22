package build.jenesis.repository.format.rpm;

import module java.base;

/**
 * A minimal, read-only reader for the front of an RPM package: the 96-byte lead, the signature header and the main
 * header, from which the {@code repodata} the {@link RpmFormat} generates needs the name, version, release, epoch,
 * architecture and a few descriptive fields, plus the byte range the main header occupies (so {@code dnf} can fetch
 * just the header with a ranged request). Only the header region is materialised - a small, bounded, front-of-file
 * metadata parse, exactly the index parse the streaming principle allows - while the (arbitrarily large) cpio payload
 * streams past into the content-addressed store, never buffered.
 *
 * <p>The RPM header is a simple, stable, well-documented binary structure (an 8-byte magic, a count and a data-store
 * size, then fixed 16-byte index entries into a tagged data store). It is hand-read here rather than through a library
 * because no maintained RPM library fits the constraints the rest of this system is built on - a Java-module-path
 * artifact, native-image-friendly, permissively licensed - and a read-only header walk is small enough to keep
 * correct and pin to real output (the {@code rpmbuild}-gated case in {@code RpmFormatTest}), the same reasoning that
 * lets the RubyGems format hand-write its {@code Marshal} stream against real Ruby.
 */
final class RpmHeader {

    /** The RPM lead is a fixed-length legacy preamble; the two header structures follow it. */
    private static final int LEAD_LENGTH = 96;
    private static final byte[] LEAD_MAGIC = {(byte) 0xED, (byte) 0xAB, (byte) 0xEE, (byte) 0xDB};
    /** Header-structure magic: {@code 8E AD E8} + a version byte {@code 01}. */
    private static final byte[] HEADER_MAGIC = {(byte) 0x8E, (byte) 0xAD, (byte) 0xE8, 0x01};
    private static final int INTRO_LENGTH = 16;
    private static final int INDEX_ENTRY_LENGTH = 16;

    /** Sanity bounds on the declared header sizes, so a crafted upload can never make the bounded parse allocate wildly. */
    private static final int MAX_INDEX_ENTRIES = 100_000;
    private static final int MAX_STORE_BYTES = 64 * 1024 * 1024;

    // Main-header tag numbers this reader consults - enough to describe a package in repodata.
    private static final int TAG_NAME = 1000;
    private static final int TAG_VERSION = 1001;
    private static final int TAG_RELEASE = 1002;
    private static final int TAG_EPOCH = 1003;
    private static final int TAG_SUMMARY = 1004;
    private static final int TAG_DESCRIPTION = 1005;
    private static final int TAG_BUILDTIME = 1006;
    private static final int TAG_SIZE = 1009;
    private static final int TAG_LICENSE = 1014;
    private static final int TAG_GROUP = 1016;
    private static final int TAG_ARCH = 1022;
    private static final int TAG_SOURCERPM = 1044;

    private static final int TYPE_STRING = 6;
    private static final int TYPE_BIN = 7;
    private static final int TYPE_I18NSTRING = 9;

    // Signature-header tags carrying an OpenPGP signature, and what each one covers. rpmsign writes the header-only
    // pair by default and the header-and-payload pair for the older tools that check that; all four are read.
    /** A DSA signature over the main header alone. */
    static final int SIGTAG_DSA = 267;
    /** An RSA signature over the main header alone - what a current {@code rpmsign} writes. */
    static final int SIGTAG_RSA = 268;
    /** An RSA signature over the main header and the payload together. */
    static final int SIGTAG_PGP = 1002;
    /** A DSA signature over the main header and the payload together. */
    static final int SIGTAG_GPG = 1005;

    private RpmHeader() {
    }

    /**
     * A package's metadata plus the {@code [start, end)} byte offsets of its main header within the file, so the
     * format can emit {@code <rpm:header-range>}.
     */
    record Package(String name,
                   String epoch,
                   String version,
                   String release,
                   String arch,
                   String summary,
                   String description,
                   String license,
                   String group,
                   String sourceRpm,
                   long buildTime,
                   long installedSize,
                   long headerStart,
                   long headerEnd) {
    }

    /**
     * One OpenPGP signature the signature header carries: the tag that says what it covers, and the signature's own
     * bytes as {@code rpmsign} wrote them (a binary OpenPGP packet, never armoured).
     */
    record Signature(int tag, byte[] bytes) {

        /** Whether the signature is over the main header and the payload together, rather than the header alone. */
        boolean coversPayload() {
            return tag == SIGTAG_PGP || tag == SIGTAG_GPG;
        }

        /** The tag's name as {@code rpm -Kv} prints it, for an operator reading which signature was judged. */
        String name() {
            return switch (tag) {
                case SIGTAG_DSA -> "DSA";
                case SIGTAG_RSA -> "RSA";
                case SIGTAG_PGP -> "PGP";
                case SIGTAG_GPG -> "GPG";
                default -> Integer.toString(tag);
            };
        }
    }

    /**
     * The OpenPGP signatures the signature header of {@code region} carries, in index order: the four tags above,
     * each a {@code BIN} entry of at most {@code largest} bytes. An entry past that bound is a hostile package's
     * rather than a usable signature and is left out, so a verifier is never handed it; a malformed index throws,
     * as the rest of this reader does for a package that is not what it claims.
     */
    static List<Signature> signatures(byte[] region, int largest) throws IOException {
        int count = int32(region, LEAD_LENGTH + 8);
        int store = int32(region, LEAD_LENGTH + 12);
        int indexStart = LEAD_LENGTH + INTRO_LENGTH;
        int dataStart = indexStart + count * INDEX_ENTRY_LENGTH;
        int end = dataStart + store;
        if (count < 0 || count > MAX_INDEX_ENTRIES || store < 0 || store > MAX_STORE_BYTES || end > region.length) {
            throw new IOException("truncated RPM signature header");
        }
        List<Signature> found = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int entry = indexStart + i * INDEX_ENTRY_LENGTH;
            int tag = int32(region, entry);
            int type = int32(region, entry + 4);
            int offset = int32(region, entry + 8);
            int length = int32(region, entry + 12);   // for a BIN entry the count is the byte length
            if (type != TYPE_BIN
                    || tag != SIGTAG_DSA && tag != SIGTAG_RSA && tag != SIGTAG_PGP && tag != SIGTAG_GPG) {
                continue;
            }
            int at = dataStart + offset;
            if (offset < 0 || length <= 0 || length > largest || at + length > end) {
                continue;
            }
            found.add(new Signature(tag, Arrays.copyOfRange(region, at, at + length)));
        }
        return List.copyOf(found);
    }

    /**
     * Read exactly the lead + signature header + main header off the front of {@code in}, leaving it positioned at the
     * first payload byte, and return the raw bytes read so the caller can restream them ahead of the untouched payload
     * into storage. Throws when the bytes are not a well-formed RPM front.
     */
    static byte[] readHeaderRegion(InputStream in) throws IOException {
        ByteArrayOutputStream region = new ByteArrayOutputStream();
        readInto(in, region, LEAD_LENGTH);
        if (!matches(region.toByteArray(), 0, LEAD_MAGIC)) {
            throw new IOException("not an RPM: bad lead magic");
        }
        int[] signature = readIntro(in, region);
        readInto(in, region, signature[0] * INDEX_ENTRY_LENGTH + signature[1]);
        readInto(in, region, (8 - (signature[1] & 7)) & 7); // pad the signature header to an 8-byte boundary
        int[] main = readIntro(in, region);
        readInto(in, region, main[0] * INDEX_ENTRY_LENGTH + main[1]);
        return region.toByteArray();
    }

    /** Parse the metadata and header range out of a region produced by {@link #readHeaderRegion}. */
    static Package parse(byte[] region) throws IOException {
        int signatureCount = int32(region, LEAD_LENGTH + 8);
        int signatureStore = int32(region, LEAD_LENGTH + 12);
        int mainStart = LEAD_LENGTH + INTRO_LENGTH + signatureCount * INDEX_ENTRY_LENGTH + signatureStore;
        mainStart += (8 - (signatureStore & 7)) & 7;
        if (!matches(region, mainStart, HEADER_MAGIC)) {
            throw new IOException("not an RPM: bad main-header magic");
        }
        int count = int32(region, mainStart + 8);
        int store = int32(region, mainStart + 12);
        if (count < 0 || count > MAX_INDEX_ENTRIES || store < 0 || store > MAX_STORE_BYTES) {
            throw new IOException("not an RPM: main header out of bounds");
        }
        int indexStart = mainStart + INTRO_LENGTH;
        int dataStart = indexStart + count * INDEX_ENTRY_LENGTH;
        int headerEnd = dataStart + store;
        if (headerEnd > region.length) {
            throw new IOException("truncated RPM header");
        }
        String name = null, version = null, release = null, epoch = "0", arch = "noarch";
        String summary = "", description = "", license = "", group = "", sourceRpm = null;
        long buildTime = 0, installedSize = 0;
        for (int i = 0; i < count; i++) {
            int entry = indexStart + i * INDEX_ENTRY_LENGTH;
            int tag = int32(region, entry);
            int type = int32(region, entry + 4);
            int at = dataStart + int32(region, entry + 8);
            if (at < dataStart || at >= headerEnd) {
                continue;
            }
            switch (tag) {
                case TAG_NAME -> name = string(region, at, type, headerEnd);
                case TAG_VERSION -> version = string(region, at, type, headerEnd);
                case TAG_RELEASE -> release = string(region, at, type, headerEnd);
                case TAG_EPOCH -> epoch = int32Fits(at, headerEnd) ? Integer.toString(int32(region, at)) : epoch;
                case TAG_ARCH -> arch = string(region, at, type, headerEnd);
                case TAG_SUMMARY -> summary = string(region, at, type, headerEnd);
                case TAG_DESCRIPTION -> description = string(region, at, type, headerEnd);
                case TAG_LICENSE -> license = string(region, at, type, headerEnd);
                case TAG_GROUP -> group = string(region, at, type, headerEnd);
                case TAG_SOURCERPM -> sourceRpm = string(region, at, type, headerEnd);
                case TAG_BUILDTIME -> buildTime = int32Fits(at, headerEnd)
                        ? Integer.toUnsignedLong(int32(region, at)) : buildTime;
                case TAG_SIZE -> installedSize = int32Fits(at, headerEnd)
                        ? Integer.toUnsignedLong(int32(region, at)) : installedSize;
                default -> {
                }
            }
        }
        if (name == null || version == null || release == null) {
            throw new IOException("RPM main header lacks name/version/release");
        }
        return new Package(name, epoch, version, release, arch, summary, description, license, group, sourceRpm,
                buildTime, installedSize, mainStart, headerEnd);
    }

    /** Read a header intro (magic, count, store size) into {@code region} and return {@code {count, store}}. */
    private static int[] readIntro(InputStream in, ByteArrayOutputStream region) throws IOException {
        int offset = region.size();
        readInto(in, region, INTRO_LENGTH);
        byte[] bytes = region.toByteArray();
        if (!matches(bytes, offset, HEADER_MAGIC)) {
            throw new IOException("not an RPM: bad header magic");
        }
        int count = int32(bytes, offset + 8);
        int store = int32(bytes, offset + 12);
        if (count < 0 || count > MAX_INDEX_ENTRIES || store < 0 || store > MAX_STORE_BYTES) {
            throw new IOException("RPM header out of bounds");
        }
        return new int[]{count, store};
    }

    /** Copy exactly {@code length} bytes from {@code in} into {@code region}, or throw on a short read. */
    private static void readInto(InputStream in, ByteArrayOutputStream region, int length) throws IOException {
        if (length == 0) {
            return;
        }
        byte[] chunk = in.readNBytes(length);
        if (chunk.length != length) {
            throw new IOException("truncated RPM header");
        }
        region.write(chunk, 0, chunk.length);
    }

    /** A NUL-terminated string in the data store; only {@code STRING}/{@code I18NSTRING} carry the fields we read. */
    private static String string(byte[] region, int at, int type, int limit) {
        if (type != TYPE_STRING && type != TYPE_I18NSTRING) {
            return "";
        }
        int end = at;
        while (end < limit && region[end] != 0) {
            end++;
        }
        return new String(region, at, end - at, StandardCharsets.UTF_8);
    }

    private static int int32(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24) | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
    }

    /** Whether a 4-byte {@code int32} at a data-store offset stays within the header. A tag's data offset is checked
     *  {@code [dataStart, headerEnd)} for the string reads, but a numeric read consumes four bytes, so an offset within
     *  three bytes of {@code headerEnd} would run off the end - an {@link ArrayIndexOutOfBoundsException} (a 500) on a
     *  crafted header rather than a clean rejection. A numeric tag that does not fit is treated as absent. */
    private static boolean int32Fits(int at, int headerEnd) {
        return at >= 0 && at + 4 <= headerEnd;
    }

    private static boolean matches(byte[] bytes, int offset, byte[] magic) {
        if (offset < 0 || offset + magic.length > bytes.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (bytes[offset + i] != magic[i]) {
                return false;
            }
        }
        return true;
    }
}
