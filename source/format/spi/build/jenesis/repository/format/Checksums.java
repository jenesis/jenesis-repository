package build.jenesis.repository.format;

import module java.base;

/**
 * The checksum a format publishes beside an artifact, as the hex every one of them serves it in.
 *
 * <p>Computing one is what a format does constantly - a Maven {@code .sha1} twin, a Debian {@code Packages} entry,
 * an OCI descriptor's digest, a gem's checksum, a Conda index record - and the same six lines had been written out
 * five times, in five modules, character for character apart from the method name and whether the algorithm was a
 * parameter or fixed. Five copies of one function is five chances for one of them to disagree about the encoding,
 * and the encoding is wire-visible: a client verifies what is served against what the document says.
 *
 * <p>Lower case is not a preference here. It is what every one of those five produced and therefore what is
 * already published, and it is what the formats' own specifications call for.
 */
public final class Checksums {

    private Checksums() {
    }

    /**
     * {@code algorithm} over {@code content}, lower-case hex.
     *
     * <p>An algorithm every JVM is required to carry cannot be missing, so its absence is not a condition a caller
     * can do anything about and is not offered as one.
     */
    public static String hex(String algorithm, byte[] content) {
        return HexFormat.of().formatHex(digest(algorithm, content));
    }

    /** {@code algorithm} over {@code content}, as the raw digest - for the few places that publish it in another
     *  encoding (npm's base64 {@code integrity}), so the hex and the bytes it encodes come from one computation. */
    public static byte[] digest(String algorithm, byte[] content) {
        try {
            return MessageDigest.getInstance(algorithm).digest(content);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(algorithm + " is required of every JVM", impossible);
        }
    }

    /** SHA-256 over {@code content}, lower-case hex - the one nearly every caller wants. */
    public static String sha256(byte[] content) {
        return hex("SHA-256", content);
    }

    /**
     * Whether {@code value} is exactly what {@link #sha256} produces: sixty-four lower-case hex characters and nothing
     * else.
     *
     * <p>This is the shape rule behind every content-addressed key - a {@code blobs/<hex>} object, an OCI
     * {@code sha256:<hex>} reference, a pointer body naming a blob - and it is a refusal, not a parse: a value that is
     * not this shape (a tag typo, a {@code ..}-laced reference, a format's small non-hash marker under the same root)
     * must never be spliced into a store key, where it would resolve to a neighbouring key space rather than fail.
     * Four modules used to carry the same eleven lines under two names, one of them citing another as "the rule";
     * the rule lives here so that the citation is a call.
     */
    public static boolean isSha256Hex(String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        for (int index = 0; index < 64; index++) {
            char character = value.charAt(index);
            if ((character < '0' || character > '9') && (character < 'a' || character > 'f')) {
                return false;
            }
        }
        return true;
    }
}
