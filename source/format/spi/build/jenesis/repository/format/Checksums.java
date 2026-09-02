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
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(algorithm + " is required of every JVM", impossible);
        }
    }

    /** SHA-256 over {@code content}, lower-case hex - the one nearly every caller wants. */
    public static String sha256(byte[] content) {
        return hex("SHA-256", content);
    }
}
