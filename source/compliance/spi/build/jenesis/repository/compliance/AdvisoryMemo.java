package build.jenesis.repository.compliance;

import module java.base;

/**
 * What the feeds one decision asks may share: the gate asks every feed about a subject in turn, and two feeds reading
 * one upstream - OSV's vulnerability and malicious-package feeds both query OSV - would otherwise ask it the same
 * question twice. A feed remembers an answer here only {@link #during} a decision, so a memo holds nothing past the
 * decision it was opened for and no answer outlives the call that drew it; outside one, every query reaches its
 * feed, as each feed declares.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> A memo is bound to the thread that opened it and to the threads that thread forks
 *       within the scope; its slots are concurrent maps.</li>
 *   <li><b>Lifecycle.</b> {@link #during} opens a fresh memo and drops it when the decision returns; a nested
 *       decision shares its enclosing one.</li>
 *   <li><b>Error visibility.</b> A feed puts only an answer it drew in a slot, never a failure, so a failure is the
 *       asker's to report and the next ask reaches the feed.</li>
 * </ol>
 */
public final class AdvisoryMemo {

    private static final ScopedValue<AdvisoryMemo> CURRENT = ScopedValue.newInstance();

    private final Map<String, Map<String, Object>> slots = new ConcurrentHashMap<>();

    private AdvisoryMemo() {
    }

    /** Run {@code decision} with a memo its feeds share, or within the enclosing decision's where one is open. */
    public static <T, X extends Throwable> T during(ScopedValue.CallableOp<T, X> decision) throws X {
        if (CURRENT.isBound()) {
            return decision.call();
        }
        return ScopedValue.where(CURRENT, new AdvisoryMemo()).call(decision);
    }

    /** The memo of the decision in progress, or empty outside one. */
    public static Optional<AdvisoryMemo> current() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }

    /** The answers remembered under {@code name}, a slot the feeds sharing one upstream agree on. */
    @SuppressWarnings("unchecked")
    public <V> Map<String, V> slot(String name) {
        return (Map<String, V>) (Map<String, ?>) slots.computeIfAbsent(name, _ -> new ConcurrentHashMap<>());
    }
}
