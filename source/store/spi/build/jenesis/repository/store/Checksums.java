package build.jenesis.repository.store;

import module java.base;

/**
 * A digest as the lower-case hex every format serves it in, and as the key a store names a record by.
 *
 * <p>Computing one is what a format does constantly - a Maven {@code .sha1} twin, a Debian {@code Packages} entry,
 * an OCI descriptor's digest, a gem's checksum, a Conda index record - and the encoding is wire-visible: a client
 * verifies what is served against what the document says. A record keyed by a path or a name hashes it here too, so
 * the key never carries the characters of what it names.
 *
 * <p>Lower case is what the formats' own specifications call for, and what is already published.
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

    /** The raw bytes of a hex digest of exactly {@code bytes} bytes, or {@code null} when it is absent, of another
     *  length or not hex - a digest a document declares, which a caller that finds none falls back from. */
    public static byte[] parse(String hex, int bytes) {
        if (hex == null || hex.length() != bytes * 2) {
            return null;
        }
        try {
            return HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException notHex) {
            return null;
        }
    }

    /** SHA-256 over {@code text}'s UTF-8 bytes, lower-case hex. */
    public static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }
}
