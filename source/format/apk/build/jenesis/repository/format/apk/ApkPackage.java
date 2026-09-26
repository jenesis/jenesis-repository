package build.jenesis.repository.format.apk;

import module java.base;
import module org.apache.commons.compress;

import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.store.ArchiveInflation;

/**
 * What an {@code .apk} says about itself: the {@code .PKGINFO} in its control segment, and the checksum an index
 * entry is keyed by.
 *
 * <h2>The file, as measured rather than remembered</h2>
 *
 * <p>An {@code .apk} is not one archive. It is <b>concatenated gzip members</b>, each a tar: a signature, a
 * control segment carrying {@code .PKGINFO}, and the data. Measured against
 * {@code musl-1.2.5-r3.apk} from {@code dl-cdn.alpinelinux.org}: three members of 666, 523 and 407119 compressed
 * bytes. An unsigned package, which is what {@code abuild} produces before signing, has two.
 *
 * <p><b>The index checksum is over the control member's compressed bytes.</b> {@code C:} is
 * {@code "Q1" + base64(SHA-1(...))} of the <em>raw gzip bytes of the control member</em> - not of the signature,
 * and not of the tar the member decompresses to. That was settled by computing all three candidates for musl and
 * comparing them with the {@code C:} Alpine's own published index carries: only the control member's compressed
 * bytes reproduce {@code Q1HKAydGWsHv3gc5d0f9s6k+BlyNY=}. It is the kind of detail that is easy to get plausibly
 * wrong, so the derivation is recorded here beside the code that does it.
 *
 * <p>Finding the member boundary needs the <em>compressed</em> length, which a decompressing stream does not
 * report. {@link Inflater#getRemaining()} does: after a member is inflated, what it has not consumed is the next
 * member, so the boundary is what it did consume. That is why this reads through {@link Inflater} directly rather
 * than through {@code GzipCompressorInputStream}, which buffers ahead and would overshoot.
 */
final class ApkPackage {

    /** The prefix Alpine gives a SHA-1 checksum in an index; the {@code 1} is the hash generation, not a version. */
    private static final String Q1 = "Q1";

    /** How much of a package this will read before giving up. A control segment is a few kilobytes - musl's is 523
     *  compressed - so a package that has not produced one by here is not one this can describe, and reading further
     *  would be reading a payload to find metadata that is not in it. */
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

    /** Where the data member starts: the byte after the control member, from which to the end of the package the
     *  {@code datahash} of {@code .PKGINFO} is computed. */
    int dataOffset() {
        return dataOffset;
    }

    /**
     * Read a package from the bytes of its front.
     *
     * <p>It takes an array rather than a stream because the checksum is over a byte <em>range</em>, which a reader
     * only knows the end of once it has inflated past it. The caller passes a bounded prefix: the control segment is
     * a few kilobytes whatever the payload weighs, so a prefix is all this needs and a package is never held whole.
     *
     * <p><b>The control is the first member carrying {@code .PKGINFO}</b>, not a member counted from either end. A
     * signed package has three members and an unsigned one has two, so counting from the front is wrong for one of
     * them; counting from the back is right for both but has to reach the back, which means inflating the payload to
     * find metadata that is not in it. Asking each member in turn what it contains stops at the second member at the
     * latest and never touches the data segment.
     */
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

    /** The {@code C:} of a control member: SHA-1 over its compressed bytes, base64, behind {@code Q1}. */
    /** A package's signature member and what it covers: the member's name and bytes from the first (signature) gzip
     *  member, and the offset and length of the control member's raw gzip bytes - which is what {@code apk} signs. */
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
