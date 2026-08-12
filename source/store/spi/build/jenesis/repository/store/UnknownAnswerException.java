package build.jenesis.repository.store;

/**
 * Raised by {@link Known#determined()} when a question that had to be answered was not: the fail-closed exit from a
 * three-valued answer. Unchecked so a refusal propagates out of a screen, a sweep or a release without widening every
 * signature between here and the caller that reports it - the same reason {@link ReadOnlyException} and
 * {@link QuotaExceededException} are unchecked - and it carries the {@link Known.Unknown} itself, so the surface that
 * catches it reports the {@link Known.Cause} and the detail the probe recorded rather than a flattened message.
 *
 * <p>Reaching this exception is never a bug in the answerer: it is the answerer telling the truth. It is a bug only
 * where a caller narrowed with {@link Known#determined()} on a path that had a safe third-state behaviour available
 * and should have written the {@link Known.Unknown} arm instead.
 */
public final class UnknownAnswerException extends RuntimeException {

    private final Known.Unknown<?> unknown;

    public UnknownAnswerException(Known.Unknown<?> unknown) {
        super(unknown.toString());
        this.unknown = unknown;
    }

    /** The unanswered answer, with its {@link Known.Cause} and detail - what an operator-facing surface reports. */
    public Known.Unknown<?> unknown() {
        return unknown;
    }
}
