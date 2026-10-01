package build.jenesis.repository.events;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Providers;

/**
 * A discovered sink for {@link RepositoryEvent}s, {@link ServiceLoader}-loaded so a producer names no delivery
 * mechanism: it calls {@link #emit(ArtifactStore, RepositoryEvent)} and every installed sink observes the event. A sink
 * is fire-and-forget, leaving latency-bearing work such as an HTTP callback to its own background drain, and delivery
 * is best-effort: {@link #emit(ArtifactStore, RepositoryEvent)} contains a sink's failure so it never fails the
 * mutation observed. The event is emitted into the tenant-and-repository scoped {@code store} it occurred in. With no
 * sink installed the fan-out is a no-op.
 *
 * <h2>Contract</h2>
 *
 * <p>The delivery class is <b>{@code DURABLE_AFTER_ENQUEUE}</b>. Not {@code BEST_EFFORT_REPAIRED}, which needs an
 * executable repair, and an event is not re-derivable; not {@code COMMIT_COUPLED_AT_LEAST_ONCE}, since {@link #emit} is
 * called from several mutation choreographies after their own durable writes, and coupling it would need an intent
 * write in each. Clause 12 states the crash window, clause 13 which producers reach the seam.
 *
 * <p><b>Resolution and delivery are different failure classes.</b> Resolving the sinks is a packaging question, and a
 * packaging error fails visibly, taking the observed mutation with it, rather than notifying the wrong set of
 * subscribers. Delivering to a resolved sink is best-effort and never fails the mutation. Everything
 * {@link Providers#all} does precedes the first {@link #accept}, and everything after it is contained.
 *
 * <ol>
 *   <li><b>Thread-safety.</b> {@link #emit} runs on the thread that performed the mutation, and several may run at
 *       once. A sink is constructed per fan-out and used by one thread (clause 9), so it need not be thread-safe, but
 *       it holds no mutable state and its store writes tolerate concurrent siblings. {@link #name()} is a pure
 *       declaration.</li>
 *   <li><b>Idempotency / replay.</b> {@link #accept} converges on the same event twice - a retried producer re-emits,
 *       two nodes may observe one mutation - so a sink keys its note on the event's identity rather than its arrival.
 *       Delivery from the note onward is at-least-once, so a subscriber tolerates duplicates.</li>
 *   <li><b>Absence sentinel.</b> {@link #name()} is never {@code null} or blank, and {@link #installed()} answers an
 *       empty set when no sink is installed. Absence is a no-op and a supported configuration; a sink may decline an
 *       event when its own dial is off. A {@code null} event is a producer bug and fails fast at {@link #emit}.</li>
 *   <li><b>Selection failure.</b> The policy is {@code ALL}: every sink observes every event. {@link #name()} is an
 *       identity, the token {@link #installed()} enumerates and every diagnostic attributes a drop to, and it is
 *       unique: two sinks answering to one name are refused at resolution, naming both classes, through the shared
 *       {@link Providers} primitives ({@link Providers#all}, {@link Providers#installedNames}). Otherwise two sinks
 *       named {@code webhook} would each fire while {@link #installed()} listed one. A packaging error throws (clause
 *       7).</li>
 *   <li><b>Streaming.</b> An event carries no artifact body, only a coordinate, a path and short details; a sink must
 *       not open the artifact, which would put the store's read path inside the mutation path.</li>
 *   <li><b>Tenant scoping.</b> The event carries no tenant or repository; scope arrives with the already-scoped
 *       {@link ArtifactStore}, where a store-backed sink queues its note, and the delivery drain stamps tenant and
 *       repository from its pass context. A sink never derives a tenant from the path or writes outside its store.</li>
 *   <li><b>Error visibility.</b> Three outcomes, by who broke:
 * <ul>
 *   <li><b>A sink's own delivery failure is contained</b>, per sink: everything its {@link #accept} throws that is not
 *       an {@link Error}, so one failing sink neither fails the operation nor starves a later one. The dropped event
 *       and the sink are named at {@code WARN}, with the name captured at resolution, so a throwing {@code name()}
 *       cannot defeat the containment.</li>
 *   <li><b>An {@link Error} propagates</b>, logged at {@code ERROR} and attributed to the sink: an
 *       {@link OutOfMemoryError}, {@link StackOverflowError} or {@link NoClassDefFoundError} says the JVM or the module
 *       graph is broken, and containing it would leave a deployment serving on a broken runtime. It fails the mutation
 *       and starves later sinks, since no useful fan-out is left.</li>
 *   <li><b>A packaging error is refused before any sink is called</b>: a duplicate name (clause 4), a {@code null},
 *       blank or throwing {@link #name()}, or a {@link ServiceConfigurationError}, all raised while
 *       {@link Providers#all} resolves and propagated out of {@link #emit}. It is a build- or deploy-time defect, loud
 *       on purpose, never a condition a healthy deployment enters.</li>
 * </ul></li>
 *   <li><b>Read purity and non-blocking.</b> {@link #accept} performs no outbound I/O: it records a durable note in its
 *       store and returns, the delivery belonging to the sink's background drain, a {@code MaintenanceTaskProvider}.
 *       Inline delivery would put a third party inside the publish path. Nothing structurally prevents it.</li>
 *   <li><b>Lifecycle / ownership.</b> {@link #emit} and {@link #installed()} each look the sinks up and cache nothing,
 *       so a sink is constructed per event: a cheap public no-argument constructor, no threads, clients or connections,
 *       no state across calls. Anything outliving the call belongs in the store.</li>
 *   <li><b>Ordering / concurrency.</b> Fan-out is in name order ({@link Providers} sorts before creating),
 *       deterministic but not a sequencing guarantee: sinks are independent. Nothing orders events across deliveries,
 *       so a subscriber may see a {@code release} before its {@code quarantine}, and one needing order reconciles
 *       against the ledger (clause 12).</li>
 *   <li><b>Bounded work / cancellation.</b> {@link #emit} fans out synchronously on the producer's thread with no
 *       timeout and no cap on sinks, so a sink's time is added to a publish or a release. A sink owes its own bound:
 *       one small store write, sized by the event. Nothing enforces it.</li>
 *   <li><b>Durability / delivery.</b> {@code DURABLE_AFTER_ENQUEUE}: the commit point is the return of {@link #accept},
 *       the note surviving the process before the producer resumes, and delivery from it is at-least-once and
 *       idempotent. <b>The crash window is unrepaired</b>: a crash between a producer's mutation and {@link #accept}
 *       returning loses the event, and nothing can heal it, since the store never records an event as owed. The blast
 *       radius is the push, never the fact: every type has a durable counterpart the emit does not gate -
 *       {@code audit/quarantine} rows, {@code holds/} and {@code overrides/} records, the findings, staging records,
 *       the serving pointers - so a lost event under-notifies and never hides a served artifact or a hold. Completeness
 *       is therefore reconciliation, named per type by {@link EventReconciliation} and rendered on
 *       {@code GET /api/webhook}. The seam is never strengthened by inventing an event: an intent record that cannot
 *       tell an orphan from a publish would announce artifacts that never became visible.</li>
 *   <li><b>Coverage.</b> Every {@link EventType} travels this seam: the declared constants are held against the
 *       {@link #emit} call sites the composition carries, read from compiled classes, so a new constant with no
 *       producer fails. A producer writing into a delivery module's store instead of calling {@link #emit} cannot be
 *       seen that way. The producers are {@code EventPublicationObserver} (publish and unpublish, on the store's
 *       after-commit hook), the gate's quarantine log, the findings store, the staging store's promotion, and both legs
 *       of {@code HoldLifecycle} (release and discard). A producer of a new type calls {@link #emit}.</li>
 * </ol>
 */
public interface EventSink {

    /** The SPI's name in resolution diagnostics; there is no setting, the policy being {@code ALL}. */
    String SPI = "event-sink";

    /** Names the notification a best-effort {@link #emit} contained, so a dropped event is visible. */
    Logger LOGGER = LoggerFactory.getLogger(EventSink.class);

    /** Observe one event that occurred in {@code store}, tenant-and-repository scoped: queue the notification and
     *  return, never deliver inline. */
    void accept(ArtifactStore store, RepositoryEvent event) throws IOException;

    /** Fan an event out to every discovered sink in name order, containing a sink's own failure: the seam a producer
     *  calls, a no-op with no sink installed. A packaging error and an {@link Error} from a sink propagate, being no
     *  notification that failed to queue (clauses 4 and 7). */
    static void emit(ArtifactStore store, RepositoryEvent event) {
        // A null event is a producer bug: it fails here rather than inside a sink or the diagnostic below.
        Objects.requireNonNull(event, "event");
        for (Map.Entry<String, EventSink> sink : resolved()) {
            try {
                sink.getValue().accept(store, event);
            } catch (Error broken) {
                // Not the sink's answer: the JVM or the module graph gave way. Attributed and rethrown.
                try {
                    LOGGER.error("event sink '" + sink.getKey() + "' raised an Error taking a "
                            + event.type().wire() + " notification for " + describe(event)
                            + " - not contained: an Error is the runtime breaking, not a notification failing to"
                            + " queue", broken);
                } catch (Throwable diagnostic) {
                    // The diagnostic may itself fail on a runtime that gave way; it never replaces the Error it
                    // attributes.
                    broken.addSuppressed(diagnostic);
                }
                throw broken;
            } catch (Throwable failure) {
                // Contained, never rethrown, but logged at WARNING with the lost event and the sink's resolved name, so
                // a sink outage swallowing notifications is visible.
                LOGGER.warn(
                        "event sink '" + sink.getKey() + "' dropped a " + event.type().wire() + " notification for "
                              + describe(event) + " - contained to keep the observed operation non-failing", failure);
            }
        }
    }

    /** The discovered sinks, each paired with the name resolution read, through the shared {@code ALL}-policy
     *  primitive, so the containment never re-enters a sink to ask its name. A duplicate name or a doubly-registered
     *  sink throws here, before any sink is called (clause 4). */
    private static List<Map.Entry<String, EventSink>> resolved() {
        return Providers.all(SPI,
                ServiceLoader.load(EventSink.class),
                EventSink::name,
                _ -> true,
                sink -> Optional.of(Map.entry(sink.name(), sink)));
    }

    /** The lost event named for a diagnostic: its coordinate and path, or its salient detail (a promotion carries a
     *  staging id). */
    private static String describe(RepositoryEvent event) {
        StringBuilder at = new StringBuilder();
        if (event.coordinate() != null) {
            if (event.ecosystem() != null) {
                at.append(event.ecosystem()).append(':');
            }
            at.append(event.coordinate());
            if (event.version() != null) {
                at.append('@').append(event.version());
            }
        }
        if (event.path() != null) {
            at.append(at.isEmpty() ? "" : " ").append("path=").append(event.path());
        }
        if (at.isEmpty() && !event.detail().isEmpty()) {
            at.append(event.detail());
        }
        return at.isEmpty() ? "(no coordinate)" : at.toString();
    }

    /** The installed sink names, name-sorted and duplicate-refusing through the same primitive {@link #emit} resolves
     *  with, so an enumeration never collapses two sinks into one. No production surface reads it; tests do, which
     *  keeps clause 4's duplicate-name refusal asserted. */
    static Set<String> installed() {
        return Providers.installedNames(SPI,
                ServiceLoader.load(EventSink.class),
                EventSink::name,
                _ -> true);
    }

    /** This sink's name, e.g. {@code webhook}: the identity {@link #installed()} enumerates and diagnostics attribute a
     *  drop to, unique across the deployment (clause 4). */
    String name();
}
