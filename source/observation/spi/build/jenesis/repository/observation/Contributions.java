package build.jenesis.repository.observation;

import module java.base;

/**
 * The one containment mechanism behind every <em>collected report</em> - a surface folding the answers of discovered,
 * optional contributors into one view: the console's {@code Panel}s, the posture report's {@code SafetyAdvisor}s, this
 * module's {@link ObservabilitySource}s. Where the store SPI's {@code Providers} decides which implementation a caller
 * gets, this decides what the reader sees when one contributor throws: the surface and every other contributor stand,
 * since one plug-in must never decide whether the surface exists. It lives in this {@code java.base}-only module, which
 * every report-owning SPI already requires.
 *
 * <p><strong>Containment is not a swallow.</strong> A contained failure is reported twice, and a caller that cannot do
 * both must not use this class:
 * <ol>
 *   <li><b>On the surface.</b> {@link #collect} never drops a contributor: one that threw is replaced by the caller's
 *       degraded contribution - a failure notice on a panel, an advisory that its condition is unchecked, an
 *       {@link Health#UNKNOWN} health check - since on these surfaces silence means "checked, and clean".</li>
 *   <li><b>In the log, once,</b> at {@code WARNING}, with the contributor's class and the exception.</li>
 * </ol>
 *
 * <p><strong>What it does not contain.</strong> Only {@link Exception}. An {@link Error} - a {@link LinkageError} from
 * a half-installed plugin, an {@link OutOfMemoryError} - is a broken graph or a dying JVM, not a contributor failing to
 * answer, so it propagates, attributed at {@code ERROR} with the contributor's class on its way out. Nor is this for a
 * verdict-bearing seam: a gate, screen or interceptor must fail closed and propagate.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> Stateless: every method is a pure function of its arguments, callable concurrently, as
 *       thread-safe as the contributors and functions handed in.</li>
 *   <li><b>Absence sentinel.</b> {@code null} is never accepted or returned. A {@code null} contribution is a failure
 *       of that contributor, contained like a throw; a {@code null} from {@code degraded} is a caller bug and throws; a
 *       {@code null} contributor throws, being a packaging error with no identity to attribute.</li>
 *   <li><b>Error visibility.</b> Every contained failure reaches the returned list as the degraded contribution and the
 *       log once. The blast radius is one contributor's rows.</li>
 *   <li><b>Ordering / determinism.</b> One result per contributor in the caller's order, degraded ones in place.
 *       {@link #collect} sorts nothing; a seam whose contributors declare an order puts them in it with
 *       {@link #ordered}, so two of one order are asked the same way on every node.</li>
 *   <li><b>Bounded work / cancellation.</b> {@code contributors} is iterated once, {@code contribution} called at most
 *       once per contributor and {@code degraded} at most once after a failure; nothing is retried. No thread or
 *       timeout: a contributor that hangs must be bounded by its own SPI.</li>
 *   <li><b>Lifecycle / ownership.</b> Nothing is created, owned or retained; the exception goes to {@code degraded} and
 *       the log and is dropped.</li>
 * </ol>
 */
public final class Contributions {

    private static final System.Logger LOGGER = System.getLogger(Contributions.class.getName());

    /** A key derived from a class name stays short enough to read in a signal name or an advisory id. */
    private static final int SEGMENT_LIMIT = 40;

    private Contributions() {
    }

    /**
     * What one contributor answers with: a function that may throw a checked exception, as {@code Panel.render} does,
     * since containing it is the point.
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
     * contribution.
     *
     * @param surface what the contributors contribute to, as a log line names it ({@code "console panel"})
     * @param contributors the discovered contributors, in render order
     * @param contribution what one contributor answers; a throw or a {@code null} is contained
     * @param degraded the contribution a failed contributor is shown as: naming the failure, cheap, never throwing;
     *     {@link #declared} reads a declaration off the failed contributor safely
     * @return one contribution per contributor, in order; never {@code null}, unmodifiable
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
                    // Contained like a throw: a missing contribution would read as "checked, nothing to say".
                    throw new IllegalStateException("The " + surface + " " + contributor.getClass().getName()
                            + " answered null; null is never a legal contribution.");
                }
            } catch (Error broken) {
                // Not contained (see the class comment) but attributed on its way out, so an operator learns which of N
                // plugins gave way; the escalation is unchanged.
                try {
                    LOGGER.log(System.Logger.Level.ERROR, "The " + surface + " " + contributor.getClass().getName()
                            + " raised an Error; it is NOT contained - an Error is the runtime or the module graph "
                            + "giving way rather than a contributor failing to answer, so it reaches the caller "
                            + "instead of becoming one degraded row on a page that would then look healthy.", broken);
                } catch (Throwable diagnostic) {
                    // The diagnostic may itself fail on a runtime that just gave way; it never replaces the Error it
                    // attributes.
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

    /** {@code contributors} in their declared {@code order}, lower first, then by class name. */
    public static <C> List<C> ordered(Stream<C> contributors, ToIntFunction<? super C> order) {
        return contributors.sorted(Comparator.<C>comparingInt(order)
                .thenComparing(contributor -> contributor.getClass().getName())).toList();
    }

    /** A declaration read off a contributor that already failed, or {@code fallback} when reading it fails or yields
     *  {@code null}, so a degraded contribution keeps the contributor's identity without a second throw. Only inside a
     *  {@link #collect} degraded function, where a second failure adds a log line but must not escape. */
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

    /** The stable {@code [a-z][a-z0-9]*} key a failure row is filed under
     *  ({@code jenrepo.observation.unavailable.<segment>}, {@code jenrepo.posture.unavailable.<segment>}, a failed
     *  panel's anchor), derived from the contributor's class, since these additive SPIs carry no {@code name()}. */
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

    /** The one-line reason a failure row carries: the failure's type, never its message, which is uncontrolled text
     *  that could quote a credential, a path or another tenant's name. The log carries the full exception. */
    public static String reason(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        String simple = failure.getClass().getSimpleName();
        return simple.isEmpty() ? failure.getClass().getName() : simple;
    }
}
