package build.jenesis.repository.store;

import module java.base;

/**
 * How a configurable byte ceiling is read, in one place.
 *
 * <p>This product bounds every read it performs, and a bound that is right for one deployment is wrong for the next:
 * a registry whose gems carry a hundred-thousand-file manifest, or whose companion documents are genuinely large,
 * must be able to say so. So a ceiling here is a DEFAULT rather than a law, and the three rules that make that safe
 * are stated once, here, so the next ceiling does not have to restate them:
 *
 * <ul>
 *   <li>The compiled constant is the value a deployment gets when it says nothing - never a minimum, never a maximum,
 *       just the answer for the operator who has no opinion.</li>
 *   <li>The value is read LIVE, never latched into a static field. Two components comparing against "the" ceiling
 *       must agree about it at the moment they compare, and one of them holding a copy from class-initialisation
 *       time is how they come to disagree about what a whole document is.</li>
 *   <li>A key set to something that is not a positive number of bytes RAISES rather than silently falling back. This
 *       is the rule with teeth: an operator who raised a cap and mistyped the value must not be left believing they
 *       raised it, because the symptom - artifacts refused at the old ceiling - looks nothing like a typo.</li>
 * </ul>
 *
 * <p>Keys live in the shared {@code jenreg.} namespace, so every one of them is also settable as an environment
 * variable in a plain {@code docker run -e}: upper-case it and write an underscore for each dot AND each dash, so
 * {@code jenreg.archive.largest-entry} is {@code JENREG_ARCHIVE_LARGEST_ENTRY}. That is the spelling
 * {@link Features#lookup()} resolves with no shell installed (see its default), and Spring's relaxed binding accepts
 * it too - the dash-dropped {@code JENREG_ARCHIVE_LARGESTENTRY} works only under Spring, so it is the wrong one to
 * document.
 */
public final class Limits {

    private Limits() {
        throw new UnsupportedOperationException();
    }

    /**
     * Whether {@code key} has been set to anything at all - for the ceiling whose default is DERIVED from another
     * ceiling rather than a constant, which must be able to tell "unset" from "set to the same number".
     */
    public static boolean isSet(String key) {
        String configured = Features.lookup().apply(key);
        return configured != null && !configured.isBlank();
    }

    /**
     * The configured value of {@code key}, or {@code fallback} when it is unset.
     *
     * @throws IllegalArgumentException when the key is set to something that is not a positive number of bytes
     */
    public static int positive(String key, int fallback) {
        long bytes = positive(key, (long) fallback);
        if (bytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(key + " is read into an int and so cannot exceed "
                    + Integer.MAX_VALUE + " bytes, but is set to " + bytes);
        }
        return (int) bytes;
    }

    /**
     * The configured value of {@code key}, or {@code fallback} when it is unset.
     *
     * @throws IllegalArgumentException when the key is set to something that is not a positive number of bytes
     */
    public static long positive(String key, long fallback) {
        String configured = Features.lookup().apply(key);
        if (configured == null || configured.isBlank()) {
            return fallback;
        }
        long bytes;
        try {
            bytes = Long.parseLong(configured.trim());
        } catch (NumberFormatException cause) {
            throw new IllegalArgumentException(key + " must be a positive number of bytes, not '"
                    + configured + "'", cause);
        }
        if (bytes <= 0) {
            throw new IllegalArgumentException(key + " must be a positive number of bytes, not " + bytes);
        }
        return bytes;
    }
}
