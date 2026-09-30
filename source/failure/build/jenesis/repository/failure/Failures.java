package build.jenesis.repository.failure;

import module java.base;
import module org.slf4j;

/**
 * Records a failure the product did not mean - an exception no surface turned into a refusal of its own - and answers
 * the reference a person quotes to find it.
 *
 * <p>The whole failure is logged once, at {@code ERROR}, with its stack trace and the reference, and nothing of it goes
 * back to the caller but {@link #MESSAGE} and the reference: an exception's message, its class, a store key or a file
 * path can name internals, and a response is read by whoever sent the request. The reference is random and says
 * nothing about the request, so it can be shown to anyone; one search of the log for it finds the trace.
 */
public final class Failures {

    private static final Logger LOGGER = LoggerFactory.getLogger(Failures.class);

    /** What every surface says of a failure it did not mean. */
    public static final String MESSAGE = "Something went wrong on the server.";

    /** The alphabet a reference is spelled in: Crockford's base 32, which has no letters a person mistakes for others. */
    private static final char[] ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    private Failures() {
    }

    /**
     * Log {@code failure} with a new reference and answer the reference. {@code what} says, in a few words, what was
     * being done - a request line, a pass - and goes to the log only. A {@code null} failure still mints a reference
     * and logs the line, for a surface that knows a request failed without holding the exception.
     */
    public static String record(String what, Throwable failure) {
        String reference = reference();
        LOGGER.error("Unexpected failure {} while {}", reference, what, failure);
        return reference;
    }

    /** The sentence and the reference, as one line a person can read and quote. */
    public static String sentence(String reference) {
        return MESSAGE + " Reference: " + reference;
    }

    private static String reference() {
        char[] reference = new char[12];
        for (int index = 0; index < reference.length; index++) {
            reference[index] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(reference);
    }
}
