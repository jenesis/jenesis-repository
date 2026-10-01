package build.jenesis.repository.format.apk;

import module java.base;
import module org.apache.commons.compress;

import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.store.ArchiveInflation;

/**
 * What an {@code .apk} says about itself: the {@code .PKGINFO} in its control segment, and the checksum an index entry
 * is keyed by.
 *
 * <p>An {@code .apk} is <b>concatenated gzip members</b>, each a tar: a signature, a control segment carrying
 * {@code .PKGINFO}, and the data; an unsigned package, as {@code abuild} produces before signing, has two.
 *
 * <p><b>The index checksum is over the control member's compressed bytes:</b> {@code C:} is
 * {@code "Q1" + base64(SHA-1(...))} of the raw gzip bytes of the control member - not of the signature, nor of the tar
 * it decompresses to - which is what reproduces the {@code C:} of Alpine's published index.
 *
 * <p>Finding the member boundary needs the compressed length, which {@link Inflater#getRemaining()} gives: what it did
 * not consume is the next member. Hence {@link Inflater} directly rather than {@code GzipCompressorInputStream}, which
 * reads ahead.
 */
final class ApkPackage {

    /** The prefix Alpine gives a SHA-1 checksum; the {@code 1} is the hash generation. */
    private static final String Q1 = "Q1";

    /** How much of a package is read before giving up: a control segment is a few kilobytes, so one not found by here
     *  is not there. */
    private static final int CONTROL_SEARCH_LIMIT = 8 * 1024 * 1024;

    private final Map<String, List<String>> fields;
    private final String checksum;
    private final int dataOffset;

    private ApkPackage(Map<String, List<String>> fields, String checksum, int dataOffset) {
        this.fields = fields;
        this.checksum = checksum;
        this.dataOffset = dataOffset;
    }

    /** The package's own {@code .PKGINFO} value for {@code key}, or empty when it declares none. */
    Optional<String> field(String key) {
        List<String> values = fields.get(key);
        return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
    }

    /** Every value for a repeatable key - {@code depend} and {@code provides} appear once per entry. */
    List<String> fields(String key) {
        return fields.getOrDefault(key, List.of());
    }

    /** The {@code C:} an index entry carries for this package. */
    String checksum() {
        return checksum;
    }

    /** Where the data member starts, the byte after the control member, from which to the end the {@code datahash} is
     *  computed. */
    int dataOffset() {
        return dataOffset;
    }

    /** Read a package from the bytes of its front: an array, since the checksum covers a byte range whose end is known
     *  only after inflating past it. The caller passes a bounded prefix. <b>The control is the first member carrying
     *  {@code .PKGINFO}</b>, not one counted from an end: a signed package has three members and an unsigned one two,
     *  and asking each in turn stops by the second without touching the data. */
    static Optional<ApkPackage> of(byte[] bytes) throws IOException {
        int offset = 0;
        while (offset < bytes.length && offset < CONTROL_SEARCH_LIMIT) {
            int consumed = inflateMember(bytes, offset);
            if (consumed <= 0) {
                break;
            }
            Map<String, List<String>> info = pkginfo(bytes, offset, consumed);
            if (!info.isEmpty()) {
                return Optional.of(new ApkPackage(info, checksumOf(bytes, offset, consumed), offset + consumed));
            }
            offset += consumed;
        }
        return Optional.empty();
    }

    /** A package's signature: the member's name and bytes from the first gzip member, and the offset and length of the
     *  control member's raw gzip bytes, which is what {@code apk} signs. */
    record Signature(String member, byte[] bytes, int controlOffset, int controlLength) {
    }

    /**
     * The signature an {@code .apk} carries, from a bounded prefix of it, or empty when its first member carries no
     * {@code .SIGN.RSA*.} entry or no control member follows. The signature member is read entry by entry under the
     * seam's signature bound; the control member is located as {@link #of} locates it, never inflated here.
     */
    static Optional<Signature> signature(byte[] bytes) throws IOException {
        int offset = 0;
        String member = null;
        byte[] signature = null;
        while (offset < bytes.length && offset < CONTROL_SEARCH_LIMIT) {
            int consumed = inflateMember(bytes, offset);
            if (consumed <= 0) {
                return Optional.empty();
            }
            if (member == null) {
                try (InputStream raw = new ByteArrayInputStream(bytes, offset, consumed);
                     GzipCompressorInputStream gzip = new GzipCompressorInputStream(raw);
                     TarArchiveInputStream tar = new TarArchiveInputStream(gzip, "UTF-8")) {
                    for (ArchiveEntry entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry()) {
                        if (entry.getName().startsWith(".SIGN.RSA")) {
                            member = entry.getName();
                            signature = ArchiveInflation.entry(tar, ArtifactSignatures.Material.LARGEST_SIGNATURE)
                                    .required("apk package", entry.getName());
                            break;
                        }
                    }
                }
                if (member == null) {
                    return Optional.empty();   // the first member is not a signature: an unsigned package
                }
            } else if (!pkginfo(bytes, offset, consumed).isEmpty()) {
                return Optional.of(new Signature(member, signature, offset, consumed));
            }
            offset += consumed;
        }
        return Optional.empty();
    }

    /** The {@code C:} of a control member: SHA-1 over its compressed bytes, base64, behind {@code Q1}. */
    private static String checksumOf(byte[] bytes, int offset, int length) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(bytes, offset, length);
            return Q1 + Base64.getEncoder().encodeToString(sha1.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-1 is required of every JDK", impossible);
        }
    }

    /** How many bytes the member starting at {@code offset} occupies, or {@code -1} when it is not one. */
    private static int inflateMember(byte[] bytes, int offset) {
        Inflater inflater = new Inflater(true);
        try {
            int header = gzipHeaderLength(bytes, offset);
            if (header < 0) {
                return -1;
            }
            inflater.setInput(bytes, offset + header, bytes.length - offset - header);
            byte[] scratch = new byte[8192];
            while (!inflater.finished()) {
                if (inflater.inflate(scratch) == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    return -1;
                }
            }
            // The deflate stream, its 8-byte gzip trailer (CRC and size), and the header before it.
            long consumed = (long) header + (inflater.getBytesRead() + 8);
            return consumed > 0 && offset + consumed <= bytes.length ? (int) consumed : -1;
        } catch (DataFormatException notAMember) {
            return -1;
        } finally {
            inflater.end();
        }
    }

    /** The length of the gzip header at {@code offset}, honouring the optional name, comment and extra fields. */
    private static int gzipHeaderLength(byte[] bytes, int offset) {
        if (bytes.length - offset < 18 || (bytes[offset] & 0xFF) != 0x1F || (bytes[offset + 1] & 0xFF) != 0x8B
                || (bytes[offset + 2] & 0xFF) != 8) {
            return -1;
        }
        int flags = bytes[offset + 3] & 0xFF;
        int at = offset + 10;
        if ((flags & 4) != 0) {
            if (bytes.length - at < 2) {
                return -1;
            }
            at += 2 + ((bytes[at] & 0xFF) | ((bytes[at + 1] & 0xFF) << 8));
        }
        for (int flag : new int[]{8, 16}) {
            if ((flags & flag) != 0) {
                while (at < bytes.length && bytes[at] != 0) {
                    at++;
                }
                at++;
            }
        }
        if ((flags & 2) != 0) {
            at += 2;
        }
        return at <= bytes.length ? at - offset : -1;
    }

    /** {@code .PKGINFO}, parsed from the control member's tar. Repeatable keys keep every value, in order. */
    private static Map<String, List<String>> pkginfo(byte[] bytes, int offset, int length) throws IOException {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        try (InputStream raw = new ByteArrayInputStream(bytes, offset, length);
             GzipCompressorInputStream gzip = new GzipCompressorInputStream(raw);
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip, "UTF-8")) {
            ArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (!".PKGINFO".equals(entry.getName())) {
                    continue;   // a signature member carries .SIGN.RSA.*: not the control segment
                }
                byte[] value = ArchiveInflation.entry(tar).orNull();
                if (value == null) {
                    return Map.of();
                }
                for (String line : new String(value, StandardCharsets.UTF_8).split("\n")) {
                    int equals = line.indexOf('=');
                    if (line.startsWith("#") || equals < 0) {
                        continue;
                    }
                    fields.computeIfAbsent(line.substring(0, equals).strip(), _ -> new ArrayList<>())
                            .add(line.substring(equals + 1).strip());
                }
                return fields;
            }
        }
        return Map.of();
    }
}
