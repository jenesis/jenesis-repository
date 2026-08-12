package build.jenesis.repository.store;

import module java.base;

/**
 * A three-valued answer: <b>the answer is this value</b>, <b>the answer is nothing</b>, or <b>the question could not
 * be answered, and here is why</b>. It exists because the second and the third are not the same fact and a
 * {@code boolean} cannot tell them apart - {@code false} fuses <em>"I asked, and the answer is no"</em> with
 * <em>"I could not ask"</em>, and every caller that then <b>deletes, releases or discloses</b> something reads the
 * fused value as the first.
 *
 * <p><strong>Why a type rather than a discipline.</strong> The same three-state answer had been invented five times
 * independently across this product - a {@code LIVE}/{@code GONE}/{@code UNJUDGEABLE} liveness, a
 * {@code MEMBER}/{@code ABSENT}/{@code UNJUDGEABLE} membership, a root set with a {@code complete()} flag, a
 * three-valued namespace, and an {@code Optional.empty()} standing in for "unenumerable" - each written after a
 * defect had already been shipped, and each unable to help the next site. One spelling, in the module every other
 * one already depends on, is what makes the fix durable rather than a fifth precedent.
 *
 * <p><strong>Construction, not discipline.</strong> {@code Traversal.Result} is the mould:
 * there, "truncated" and "carries a cursor" are the same fact by constructor invariant, so an incomplete traversal
 * cannot be represented as a complete one even by accident. Here the same trick is played with the type hierarchy
 * rather than with an invariant. The hierarchy is sealed and two levels deep:
 *
 * <ul>
 *   <li>{@link Determined} - the question <em>was</em> answered. It permits exactly {@link Present} and
 *       {@link Absent}, and it is the type a seam that <b>destroys, releases or discloses</b> accepts as a
 *       parameter. An {@link Unknown} is not a {@code Determined} and so <em>does not compile</em> there: the
 *       mistake is a compiler error at the call site rather than a review item, and the caller must decide, in the
 *       place that holds the knowledge, what an unanswerable question means for the act it is about to take.</li>
 *   <li>{@link Unknown} - the question could not be answered, carrying a {@link Cause} and a {@code detail}. It is
 *       a peer of {@code Determined}, never a subtype of it, so no widening reaches a destructive seam.</li>
 * </ul>
 *
 * <p><strong>There is deliberately no {@code isPresent()}, no {@code get()} and no {@code orElse()}.</strong>
 * Those are the reflex that produced the defect: a two-valued test over a three-valued answer, whose {@code else}
 * branch silently absorbs the third state. The only ways out of a {@code Known} are an exhaustive
 * {@code switch} - which the sealing makes total, so a new state would break every caller rather than slip past a
 * {@code default} - and the two named narrowings below, each of which says out loud what it is doing:
 * {@link #determined()} (fail closed: raise rather than guess) and {@link #unknownAsAbsent(String)} (collapse, with
 * a written justification). {@link Determined#answer()} hands out an {@link Optional}, because once the question
 * <em>has</em> been answered a two-valued carrier is the truth rather than a fusion.
 *
 * <p><strong>{@code Unknown} is not an error channel.</strong> A probe that failed and <em>can</em> propagate should
 * propagate - an {@link IOException} carries its own stack trace and its own remedy. {@code Unknown} is for the
 * answer a caller must still produce after the failure is caught, or that is structurally unavailable because a
 * module that owns the subject is not installed, or because the enumeration that would have answered stopped at a
 * bound. That is also why {@link Unknown} carries a {@link Cause} and a {@link String} rather than a
 * {@link Throwable}: it stays a value - comparable, assertable and loggable - and the stack trace belongs at the
 * {@code catch} that produced it.
 *
 * @param <T> what an affirmative answer carries. A question whose affirmative answer has no payload beyond "yes"
 *            instantiates at the subject's own identity (the key, the hash, the coordinate), so every state is
 *            loggable without the caller re-deriving what it asked about.
 */
public sealed interface Known<T> permits Known.Determined, Known.Unknown {

    /**
     * Why a question could not be answered. The three constants are the three structural ways this product loses the
     * ability to answer, and they are deliberately closed: each one is a different remedy for an operator and a
     * different thing to look for in a source scan.
     */
    enum Cause {

        /** No installed module can answer: the format, provider or plugin that owns the subject is not on the module
         *  graph, so nothing here can place, enumerate or judge it. The remedy is to install the module again (or to
         *  purge the subject's data explicitly); it is never to act as if the subject were absent, because absence of
         *  the answerer is not absence of the answer. */
        UNINSTALLED,

        /** The enumeration that would have answered stopped at a bound: what was seen is a prefix of the scope, not
         *  the whole of it. A short listing read as a drained container is the same defect in enumeration clothing,
         *  which is why a truncated traversal must become this rather than an empty result. */
        TRUNCATED,

        /** The probe itself failed - a backend outage, a throttle, an authorization refusal, an unparseable body -
         *  and the caller caught it rather than propagating. Prefer propagating: this constant is for the caller that
         *  must still produce an answer. */
        FAILED
    }

    /**
     * A question that <em>was</em> answered: {@link Present} or {@link Absent}, and nothing else. This is the type a
     * seam that deletes, releases or discloses takes, so that an {@link Unknown} cannot be handed to it at all.
     */
    sealed interface Determined<T> extends Known<T> permits Present, Absent {

        /** The answered value, or empty when the answer is that there is none. An {@link Optional} is honest here and
         *  only here: the question was asked, so two values are all there are. */
        Optional<T> answer();
    }

    /** The question was asked and the answer is this value. */
    record Present<T>(T value) implements Determined<T> {

        public Present {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public Optional<T> answer() {
            return Optional.of(value);
        }
    }

    /** The question was asked and there is no such thing - a genuine, observed "no". */
    record Absent<T>() implements Determined<T> {

        @Override
        public Optional<T> answer() {
            return Optional.empty();
        }
    }

    /**
     * The question could not be answered. Never a "no": a caller that treats it as one has re-created the defect this
     * type exists to end.
     *
     * @param cause  which of the three structural ways the answer was lost.
     * @param detail what was being asked and what stopped it, in a form an operator can act on - it is the log line.
     */
    record Unknown<T>(Cause cause, String detail) implements Known<T> {

        public Unknown {
            Objects.requireNonNull(cause, "cause");
            if (detail == null || detail.isBlank()) {
                throw new IllegalArgumentException(
                        "An unanswerable question must say why it could not be answered: " + cause);
            }
        }

        @Override
        public String toString() {
            return "unknown (" + cause + "): " + detail;
        }
    }

    /** The question was asked and the answer is {@code value}. */
    static <T> Determined<T> known(T value) {
        return new Present<>(value);
    }

    /** The question was asked and there is no such thing. */
    static <T> Determined<T> absent() {
        return new Absent<>();
    }

    /** The question could not be answered, for this reason. */
    static <T> Known<T> unknown(Cause cause, String detail) {
        return new Unknown<>(cause, detail);
    }

    /** {@link Cause#UNINSTALLED}: no installed module can answer for {@code detail}. */
    static <T> Known<T> uninstalled(String detail) {
        return new Unknown<>(Cause.UNINSTALLED, detail);
    }

    /** {@link Cause#TRUNCATED}: the enumeration that would have answered for {@code detail} stopped at a bound. */
    static <T> Known<T> truncated(String detail) {
        return new Unknown<>(Cause.TRUNCATED, detail);
    }

    /** {@link Cause#FAILED}: the probe of {@code subject} threw, and the caller caught it rather than propagating.
     *  The throwable's own text becomes the detail - the stack trace belongs in the log at the {@code catch}. */
    static <T> Known<T> failed(String subject, Throwable thrown) {
        return new Unknown<>(Cause.FAILED, subject + ": " + Objects.requireNonNull(thrown, "thrown"));
    }

    /**
     * Narrow to the answered form, or refuse: the <b>fail-closed</b> exit. A caller that has no safe conduct for an
     * unanswerable question - a sweep about to delete, a release about to un-retract - uses this and raises
     * {@link UnknownAnswerException} rather than proceeding on a guess. It is the only narrowing that never invents
     * an answer, and it is what lets a {@code Known} reach a {@link Determined}-typed seam at all.
     */
    default Determined<T> determined() {
        return switch (this) {
            case Determined<T> determined -> determined;
            case Unknown<T> unknown -> throw new UnknownAnswerException(unknown);
        };
    }

    /**
     * Collapse "could not ask" into "no" and hand back the resulting two-valued answer - the <b>only</b> legitimate
     * way to lose the third state, and deliberately the ugliest thing in this API.
     *
     * <p>It is legitimate on an <em>additive read</em>: a browse or a diagnostic that shows what it can and whose
     * worst outcome is an item missing from a screen. It is never legitimate where the answer decides a deletion, a
     * release or a disclosure - there, an unanswerable question means refuse, which is {@link #determined()} or an
     * explicit {@code Unknown} arm. That ruling is not a style preference: refusal is right for an eviction and wrong
     * for a browse, and only the call site knows which it is.
     *
     * @param justification why this particular caller may fuse the two, in words. It is required and must be
     *                      non-blank, so the collapse costs a sentence at the site that takes it and shows up in a
     *                      diff, in a review and in a grep - which is the entire point of it being a named call
     *                      rather than a default.
     */
    default Optional<T> unknownAsAbsent(String justification) {
        if (justification == null || justification.isBlank()) {
            throw new IllegalArgumentException("Fusing 'I could not ask' with 'the answer is no' needs a written "
                    + "justification naming why this caller may lose the distinction");
        }
        return switch (this) {
            case Determined<T> determined -> determined.answer();
            case Unknown<T> _ -> Optional.empty();
        };
    }
}
