package build.jenesis.repository.observation;

import module java.base;

/**
 * The ONE containment mechanism behind every <em>collected report</em> - the surfaces that fold the answers of N
 * discovered, optional contributors into one view (the console's {@code Panel}s, the posture report's
 * {@code SafetyAdvisor}s, this module's own {@link ObservabilitySource}s). It is the report-side counterpart of the
 * store SPI's {@code Providers}: {@code Providers} answers "which implementation does the caller get, and what happens
 * when the answer is ambiguous, missing or duplicated?"; {@code Contributions} answers "one of the contributors just
 * threw - what does the reader see?". It lives here, in the base {@code java.base}-only module every other SPI already
 * requires, because a collected report cannot depend on the store, and a failure that reaches nobody is exactly the
 * observability gap this module exists to close.
 *
 * <p><strong>Why this exists.</strong> Three collected-report seams each rendered their contributors in a bare loop, so
 * one throwing contributor took the <em>whole</em> surface down and hid every other contributor with it - the console
 * 500ed on one bad panel, and one bad advisor took the posture badge (rendered on every console view) and
 * {@code GET /api/posture} with it. That breaks the rule that an optional discovered contributor degrades gracefully:
 * the one thing a plug-in surface must never do is let one plug-in decide whether the surface exists.
 *
 * <p><strong>Containment here is not a swallow.</strong> A contained failure is reported <em>twice</em>, and a caller
 * that cannot do both must not use this class:
 * <ol>
 * <li><b>On the surface itself.</b> {@link #collect} never drops a contributor. A contributor that threw is replaced by
 *     the caller's <em>degraded contribution</em> - a failed panel keeps its navigation entry and renders a failure
 *     notice, a failed advisor becomes an advisory saying its condition is unchecked, a failed observability source
 *     becomes an {@link Health#UNKNOWN} health check. A contributor that failed must never read as a contributor with
 *     nothing to say, because on all three of these surfaces silence means "checked, and clean".</li>
 * <li><b>In the log, once, with the identity.</b> Every contained failure is logged at {@code WARNING} with the
 *     contributor's implementation class and the exception, exactly once per collection pass.</li>
 * </ol>
 *
 * <p><strong>What it deliberately does not contain.</strong> Only {@link Exception} is contained. An {@link Error} - a
 * {@link LinkageError} from a half-installed plugin, an {@link OutOfMemoryError} - propagates: that is a broken module
 * graph or a dying JVM rather than a contributor failing to answer, and reporting it as one degraded row on an
 * otherwise healthy-looking page would misreport it. It is nonetheless <b>attributed on its way out</b>, at
 * {@code ERROR}, with the contributor's class: the earlier ruling is that an {@code Error} is attributed
 * <em>and</em> escalated, and this class used to do the second half only - so an operator whose console 500ed learned
 * that something on the page had given way and nothing about which of N plugins it was. Nor is this class for a
 * <em>verdict-bearing</em> seam: a gate,
 * screen or interceptor that decides whether an artifact is accepted must fail closed and propagate. Containment is for
 * observers and report contributors, never for a gate.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> {@code Contributions} is stateless: every method is a pure function of its arguments, holds
 *     no static mutable state, caches nothing, and may be called concurrently from any thread. It is only as
 *     thread-safe as the contributors and functions handed in.</li>
 * <li><b>Absence sentinel.</b> {@code null} is never accepted and never returned. A {@code contribution} that answers
 *     {@code null} is treated as a <em>failure</em> of that contributor (it is contained and reported like a throw,
 *     because a null contribution would otherwise become an invisible hole in the report); a {@code degraded} function
 *     that answers {@code null} is a bug in the caller and throws, because there is then nothing to put on the surface.
 *     A {@code null} element in {@code contributors} throws - a null in a discovered list is a packaging error with no
 *     identity to attribute a degraded row to.</li>
 * <li><b>Error visibility (&sect;9).</b> Nothing is swallowed: every contained failure both reaches the returned list
 *     as the caller's degraded contribution and is logged once with the contributor's class name and the exception.
 *     The blast radius of a contained failure is exactly one contributor's rows; the surface and every other
 *     contributor stand.</li>
 * <li><b>Ordering / determinism.</b> Contributors are visited in the order the caller supplies and the result carries
 *     one element per contributor in that same order, degraded ones in place - so a failed contributor holds its
 *     position rather than disappearing from the middle of a report. Nothing is sorted here: the collected report
 *     applies its own stable order afterwards.</li>
 * <li><b>Bounded work / cancellation.</b> Work is bounded by the number of contributors: {@code contributors} is
 *     iterated exactly once, and {@code contribution} is called at most once per contributor (and {@code degraded} at
 *     most once, only after a failure). Nothing is retried - a contributor that fails is asked nothing more, so a
 *     failure cannot double the work a render costs. No thread is started and no timeout applies: a contributor that
 *     <em>hangs</em> is not contained here and must be bounded by its own SPI's contract.</li>
 * <li><b>Lifecycle / ownership.</b> This class creates nothing and owns nothing. It never retains a contributor, a
 *     contribution or a failure after it returns; the exception is handed to the caller's {@code degraded} function
 *     and to the log, and is otherwise dropped.</li>
 * </ol>
 */
public final class Contributions {

    private static final System.Logger LOGGER = System.getLogger(Contributions.class.getName());

    /** A key derived from a class name stays short enough to read in a signal name or an advisory id. */
    private static final int SEGMENT_LIMIT = 40;

    private Contributions() {
    }

    /**
     * What one contributor answers with. Distinct from {@link java.util.function.Function} because a contributor may
     * declare a checked exception ({@code Panel.render} throws {@link java.io.IOException}), and containing it is the
     * whole point.
     *
     * @param <C> the contributor type
     * @param <T> the contribution type
     */
    @FunctionalInterface
    public interface Contribution<C, T> {

        /** The contribution of {@code contributor}, or a thrown failure this class contains. */
        T from(C contributor) throws Exception;
    }

    /**
     * Collect one contribution per contributor, replacing a contributor that fails with the caller's degraded
     * contribution rather than letting it take the surface down.
     *
     * @param surface      what the contributors contribute to, named as it reads in a log line ({@code "console
     *                     panel"}, {@code "safety advisor"}); used verbatim in the failure log.
     * @param contributors the discovered contributors, in the order the report renders them.
     * @param contribution what one contributor answers; a throw or a {@code null} answer is contained.
     * @param degraded     the contribution a failed contributor is represented by - it must name the failure on the
     *                     surface, must be cheap, and must not throw (it is answering <em>for</em> something that just
     *                     did). {@link #declared} is how it safely reads a declaration off the failed contributor.
     * @return one contribution per contributor, in contributor order; never {@code null}, never modifiable.
     */
    public static <C, T> List<T> collect(String surface,
                                         Iterable<? extends C> contributors,
                                         Contribution<? super C, ? extends T> contribution,
                                         BiFunction<? super C, ? super Exception, ? extends T> degraded) {
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(contributors, "contributors");
        Objects.requireNonNull(contribution, "contribution");
        Objects.requireNonNull(degraded, "degraded");
        List<T> collected = new ArrayList<>();
        for (C contributor : contributors) {
            if (contributor == null) {
                throw new IllegalStateException("A discovered " + surface + " is null; there is no contributor to"
                        + " attribute a degraded contribution to.");
            }
            T contributed;
            try {
                contributed = contribution.from(contributor);
                if (contributed == null) {
                    // Contained like a throw: a null contribution is a contributor that answered nothing at all, and
                    // dropping it would leave a hole in the report that reads exactly like "checked, nothing to say".
                    throw new IllegalStateException("The " + surface + " " + contributor.getClass().getName()
                            + " answered null; null is never a legal contribution.");
                }
            } catch (Error broken) {
                // NOT contained - see the class note - but named on its way out. The propagation is right: a
                // LinkageError from a half-installed plugin is a broken module graph rather than a contributor
                // declining to answer, and one degraded row on an otherwise healthy-looking page would misreport it.
                // What was missing is the earlier other half. An Error left here with no log line at all, so an operator
                // whose console 500ed learned that something on the page raised a NoClassDefFoundError and nothing
                // about which of N plugins it was. Attributed and rethrown, exactly as EventSink.emit does: the
                // escalation is unchanged, the diagnosis is not.
                try {
                    LOGGER.log(System.Logger.Level.ERROR, "The " + surface + " " + contributor.getClass().getName()
                            + " raised an Error; it is NOT contained - an Error is the runtime or the module graph "
                            + "giving way rather than a contributor failing to answer, so it reaches the caller "
                            + "instead of becoming one degraded row on a page that would then look healthy.", broken);
                } catch (Throwable diagnostic) {
                    // Rendering the diagnostic can itself fail on the very runtime that just gave way. The
                    // attribution is worth having but never worth REPLACING the Error it attributes.
                    broken.addSuppressed(diagnostic);
                }
                throw broken;
            } catch (Exception exception) {
                LOGGER.log(System.Logger.Level.WARNING, "The " + surface + " " + contributor.getClass().getName()
                        + " failed; it is reported as failed on the surface and every other " + surface
                        + " still contributes.", exception);
                contributed = Objects.requireNonNull(degraded.apply(contributor, exception),
                        "the degraded contribution of a failed " + surface);
            }
            collected.add(contributed);
        }
        return List.copyOf(collected);
    }

    /**
     * A declaration read off a contributor that has already failed, or {@code fallback} when reading it fails or
     * yields {@code null} too - so a degraded contribution can keep the failed contributor's own identity (a panel
     * keeps its navigation id and title) without becoming a second thing that throws.
     *
     * <p>Legal <em>only</em> inside a {@link #collect} degraded function: the contributor's primary failure has been
     * logged and is about to be rendered, so a second failure while naming it adds a log line but must not escape.
     */
    public static <C, T> T declared(C contributor, Contribution<? super C, ? extends T> declaration, T fallback) {
        Objects.requireNonNull(contributor, "contributor");
        Objects.requireNonNull(declaration, "declaration");
        Objects.requireNonNull(fallback, "fallback");
        try {
            T declared = declaration.from(contributor);
            return declared == null ? fallback : declared;
        } catch (Exception exception) {
            LOGGER.log(System.Logger.Level.WARNING, "The already-failed contributor "
                    + contributor.getClass().getName() + " cannot even declare itself; the surface names it by its"
                    + " implementation class instead.", exception);
            return fallback;
        }
    }

    /**
     * The stable {@code [a-z][a-z0-9]*} key a failure row is filed under, derived from the contributor's own
     * implementation class: {@code jenreg.observation.unavailable.<segment>},
     * {@code jenreg.posture.unavailable.<segment>}, a failed panel's anchor. It is derived from the class rather than
     * declared by the contributor because these additive SPIs carry no {@code name()} - and it is stable for as long as
     * the class name is, which is what a row key and a docs anchor need.
     */
    public static String segment(Object contributor) {
        Objects.requireNonNull(contributor, "contributor");
        String name = contributor.getClass().getSimpleName();
        if (name.isEmpty()) {
            // An anonymous or hidden class has no simple name; its binary name still identifies it.
            name = contributor.getClass().getName();
        }
        StringBuilder segment = new StringBuilder(name.length());
        for (int index = 0; index < name.length() && segment.length() < SEGMENT_LIMIT; index++) {
            char character = Character.toLowerCase(name.charAt(index));
            if ((character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9' && !segment.isEmpty())) {
                segment.append(character);
            }
        }
        return segment.isEmpty() ? "unnamed" : segment.toString();
    }

    /**
     * The one-line, operator-facing reason a failure row carries: the failure's <em>type</em>, never its message.
     *
     * <p>A contributor's exception message is uncontrolled text - it can quote a configured credential the contributor
     * had just read, an artifact path, or another tenant's name - and all three of these surfaces forbid rendering a
     * read value ({@code SafetyAdvisory} names the risk, never the secret; a {@link HealthCheck} detail carries no
     * credential and no tenant content). So the surface names the contributor and the kind of failure, and the log -
     * where the full exception and its stack trace go - carries the rest.
     */
    public static String reason(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        String simple = failure.getClass().getSimpleName();
        return simple.isEmpty() ? failure.getClass().getName() : simple;
    }
}
