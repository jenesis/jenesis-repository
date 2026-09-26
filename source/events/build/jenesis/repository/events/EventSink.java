package build.jenesis.repository.events;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Providers;

/**
 * A discovered sink for {@link RepositoryEvent}s, {@link ServiceLoader}-loaded so a producer names no delivery
 * mechanism: it calls {@link #emit(ArtifactStore, RepositoryEvent)} and every installed sink observes the event.
 * A sink is fire-and-forget - it must return quickly and never block the request path, leaving the latency-bearing
 * work (an outbound HTTP callback, say) to its own background drain - and delivery is best-effort: {@link
 * #emit(ArtifactStore, RepositoryEvent)} contains a sink's failure so a notification hiccup never fails the
 * publish, unpublish, quarantine, finding or promotion it observes. The event is emitted into the tenant-and-repository
 * scoped {@code store} it occurred in, so a store-backed sink queues its work in that same scope. With no sink
 * installed the fan-out is a no-op, so a deployment without the delivery module emits nothing and pays nothing.
 *
 * <h2>Contract</h2>
 *
 * <p>The delivery class is settled: <b>{@code DURABLE_AFTER_ENQUEUE}</b>, decided by &sect;7 against the one
 * installed sink rather than assumed. It is not {@code BEST_EFFORT_REPAIRED}, because that class requires an
 * <em>executable</em> repair and an event is not re-derivable - nothing in the store records "an event occurred at T
 * that was not announced". It is not {@code COMMIT_COUPLED_AT_LEAST_ONCE}, because {@link #emit} is a static fan-out
 * called from six different mutation choreographies, each after its own durable write, so commit-coupling it would
 * mean six pre-mutation intent writes in six modules - six choke-point extensions rather than one, which design
 * gate 3 refuses on its own. Clause 12 states the crash window that class leaves open, and clause 13 states which
 * producers actually reach this seam.
 *
 * <p><b>Resolution and delivery are two different failure classes, and the split is the spine of clauses 4 and 7.</b>
 * Resolving the sinks is a <em>packaging</em> question - which providers this deployment declares, and whether they
 * are coherent - and a packaging error there fails visibly (&sect;9), taking the observed mutation with it, because
 * the alternative is a deployment whose notifications silently reach the wrong set of subscribers. Delivering to a
 * resolved sink is a <em>best-effort</em> question, and a sink's own failure never fails the observed mutation. The
 * boundary is exact: everything {@link Providers#all} does happens before the first {@link #accept} call, and
 * everything after it is contained.
 *
 * <ol>
 * <li><b>Thread-safety.</b> {@link #emit} is called on whichever thread performed the mutation - a request thread for
 *     a publish, a gate quarantine or a reviewer release, a maintenance thread for a sweep-driven one - and several
 *     may be in {@link #emit} at once. A sink is constructed per fan-out and used by that one thread (clause 9), so
 *     it need not be thread-safe, but it must hold no mutable instance state and its store writes must tolerate
 *     concurrent siblings. {@link #name()} is a pure declaration callable from any thread.</li>
 * <li><b>Idempotency / replay.</b> {@link #accept} must converge when handed the same event twice: a producer whose
 *     own operation is retried re-emits, and two nodes may observe one mutation. A sink therefore keys its note on
 *     the event's identity rather than on arrival - {@code WebhookOutbox} names its object after a digest of
 *     ({@code type}, {@code path}, {@code coordinate}, {@code version}, {@code detail}, epoch-milli), so an exact
 *     duplicate within the same milli dedupes to one delivery instead of piling up, while two genuinely distinct
 *     events never collide. Beyond the sink, delivery is at-least-once from the note onward, so a subscriber must
 *     tolerate duplicates; that is the drain's clause, not this one.</li>
 * <li><b>Absence sentinel.</b> {@code null} is never a legal return: {@link #name()} is never {@code null} and never
 *     blank, and {@link #installed()} answers an empty set rather than {@code null} when no sink is installed.
 *     Absence is a no-op, not an error - with no sink installed {@link #emit} does nothing and costs nothing, so a
 *     deployment without a delivery module is a supported configuration rather than a degraded one. A sink may also
 *     decline an event silently when its own feature dial is off, and that is not a failure. {@code null} is not
 *     accepted either: a {@code null} event is a producer bug, not an absence, and fails fast at {@link #emit}
 *     rather than surfacing as a {@link NullPointerException} from inside a sink or a diagnostic.</li>
 * <li><b>Selection failure (&sect;9).</b> The policy is {@code ALL} and there is no selection key: every discovered
 *     sink observes every event and no <em>choice</em> of provider is a resolution error. {@link #name()} is an
 *     <em>identity</em> - the token {@link #installed()} enumerates, and the one every contained-failure diagnostic
 *     attributes a drop to - rather than a selection key, and no console or API gates a surface on it (see
 *     {@link #installed()}) - but it is still unique, and <b>two sinks answering to one name are refused at
 *     resolution</b>, naming both provider classes, exactly as {@code ForwardTransportProvider} refuses a duplicate
 *     transport name and a gate dimension refuses a duplicate dimension name. The refusal is not a
 *     third hand-rolled check: {@link #emit} and {@link #installed()} both resolve through the shared
 *     {@link Providers} primitives ({@link Providers#all} and {@link Providers#installedNames}), whose clause 5
 *     refuses a duplicate name or a doubly-registered class for the additive policy too. Two sinks named
 *     {@code webhook} used to fire twice each while {@link #installed()} collapsed them to one entry - an
 *     enumeration under-reporting a really-installed sink, and a subscriber base nobody could enumerate. That is a
 *     packaging error, so it throws; the blast radius is stated in clause 7.</li>
 * <li><b>Streaming (&sect;1).</b> An event never carries an artifact body - only the coordinate, the request path and
 *     a handful of short detail strings - so there is nothing to stream and nothing to materialise. A sink must not
 *     open the artifact the event names in order to deliver it: this seam is a metadata hop, and a sink that read a
 *     blob would put the store's read path inside the mutation path.</li>
 * <li><b>Tenant scoping (&sect;6).</b> The event deliberately carries no tenant and no repository. Scope arrives with
 *     the {@link ArtifactStore}, which is already tenant-and-repository scoped by the producer, so a store-backed
 *     sink queues its note in that same scope and the delivery drain stamps tenant and repository from the
 *     authoritative pass context rather than trusting a producer to thread them through. A sink must never derive a
 *     tenant from the event's path and must never write outside the store it was handed.</li>
 * <li><b>Error visibility (&sect;9).</b> Three outcomes, decided by <em>who</em> broke rather than by which
 *     {@code catch} clause happens to match:
 *     <ul>
 *     <li><b>A sink's own delivery failure is contained.</b> {@link #emit} catches everything a sink's
 *         {@link #accept} throws that is not an {@link Error} - {@link IOException}, any {@link RuntimeException},
 *         and any other {@link Throwable} a sink contrives to raise past its {@code throws} clause - <em>per
 *         sink</em>, so one failing sink neither fails the observed operation nor starves a later sink. It names the
 *         dropped event (its type and coordinate/path) and the sink that dropped it at {@code WARN}, because a
 *         fail-soft that emits no diagnostic is a silent loss. The name in that diagnostic is the one captured at
 *         resolution, never a fresh {@link #name()} call, so a sink whose {@code name()} throws can no longer defeat
 *         the containment from inside the handler.</li>
 *     <li><b>An {@link Error} is not the sink's answer, it is the ground giving way, and it propagates.</b> An
 *         {@link OutOfMemoryError}, a {@link StackOverflowError} or a {@link NoClassDefFoundError} from inside
 *         {@link #accept} says the JVM or the module graph is broken, not that a notification could not be queued;
 *         containing it would let a deployment run on serving artifacts while its heap or its module path is gone,
 *         reporting nothing worse than a queued webhook. So it is logged at {@code ERROR}, attributed to the sink
 *         that raised it, and rethrown - the same ruling a contract kit follows, where an {@code Error} is
 *         reported as the harness breaking rather than filed as the subject's answer, and the shape the
 *         interceptor legs already have. It fails the observed mutation and starves the sinks after it, deliberately:
 *         there is no useful fan-out left to continue.</li>
 *     <li><b>A packaging error is refused before any sink is called.</b> A duplicate sink name (clause 4), a
 *         {@code null} or blank {@link #name()}, a {@link #name()} that throws, and a
 *         {@link ServiceConfigurationError} from a provider module that cannot be instantiated all arise while
 *         {@link Providers#all} is resolving, and all propagate out of {@link #emit} uncontained. This is a
 *         deliberate behaviour change: a deployment that ships two sinks under one name, or a sink that
 *         cannot be constructed, now fails the mutation it was observing instead of quietly notifying an
 *         unenumerable set of subscribers. It is loud on purpose - the diagnostic names both provider classes and
 *         the shared name - and it is a build-time or deploy-time defect, never a runtime condition a healthy
 *         deployment can enter.</li>
 *     </ul>
 *     All three legs are asserted by {@code test/events} ({@code EmitSwallowTest}, {@code EmitFanOutTest},
 *     {@code EmitContainmentTest}) and {@code test/events/discovery} ({@code EventSinkDiscoveryTest}).</li>
 * <li><b>Read purity (&sect;10) and non-blocking.</b> {@link #accept} is a write leg, and the rule on it is the
 *     inverse of read purity: it must perform <em>no</em> outbound I/O. It records a durable note in the handed store
 *     and returns; the latency-bearing delivery - an HTTP callback, a queue publish - belongs to the sink's own
 *     background drain, discovered as a {@code MaintenanceTaskProvider}. A sink that delivered inline would put a
 *     third party's availability inside the publish path and would have no delivery class the kit could hold it to,
 *     since an inline effect is neither enqueued nor repaired. Nothing structurally prevents an inline delivery
 *.</li>
 * <li><b>Lifecycle / ownership.</b> {@link #emit} and {@link #installed()} each perform their own
 *     {@link ServiceLoader} lookup and cache nothing, so a sink is constructed afresh on every emitted event. A sink
 *     must therefore have a cheap public no-argument constructor, own no threads, clients or connections, and keep no
 *     state across calls; nothing is ever closed. Everything that must outlive the call belongs in the scoped
 *     store.</li>
 * <li><b>Ordering / concurrency.</b> Fan-out order is <em>name order</em>, not discovery order: both statics resolve
 *     through {@link Providers}, which sorts by name before anything is created, so the fan-out and
 *     {@link #installed()} are deterministic on every module path. Sinks must still be mutually independent - name
 *     order is a determinism guarantee, not a sequencing one, and a sink must never rely on running before or after
 *     another. There is no cross-event ordering guarantee of any kind: events for one coordinate are emitted in
 *     mutation order, but each is delivered independently with its own retry and backoff, so a subscriber may see a
 *     {@code release} before the {@code quarantine} it resolves. A subscriber that needs order must reconcile against
 *     the durable ledger (clause 12), not against arrival order.</li>
 * <li><b>Bounded work / cancellation (&sect;9).</b> {@link #emit} fans out synchronously on the producer's thread,
 *     with no timeout, no interruption protocol and no cap on the number of sinks, so every millisecond a sink spends
 *     in {@link #accept} is a millisecond added to a publish, a gate decision or a reviewer's release. A sink owes an
 *     in-band bound of its own: one small store write, sized by the event rather than by the repository. Nothing
 *     enforces the bound.</li>
 * <li><b>Durability / delivery.</b> {@code DURABLE_AFTER_ENQUEUE}. The commit point is the return of
 *     {@link #accept}: the note must survive the process before the producer resumes, and delivery from the note
 *     onward is at-least-once and idempotent on replay. <b>The crash window is explicitly unrepaired.</b> Every
 *     producer emits <em>after</em> its own durable mutation, so a crash between that mutation and {@link #accept}
 *     returning loses the event permanently - there is no walk, sweep or re-derivation that heals it, and there
 *     cannot be, because an event is a point-in-time observation the store never records as having been owed. The
 *     blast radius is bounded to the <em>push</em>, never the fact: every event type has a durable, queryable
 *     counterpart the emit does not gate ({@code audit/quarantine} rows, {@code holds/} and {@code overrides/}
 *     records, the findings document, staging records, and the serving pointers themselves), so a lost event
 *     under-notifies and never hides a served artifact or a hold. <b>The documented answer to "I cannot miss one" is
 *     therefore reconciliation, not delivery</b>: a subscriber that must be complete polls the corresponding ledger
 *     and treats the webhook as an accelerator (&sect;9 D-6). That pairing is named per event type by
 *     {@link EventReconciliation}, and rendered for an operator on {@code GET /api/webhook} - because this clause
 *     asserted the counterparts existed without naming any of them, which left an integrator with a true statement
 *     and no route. Losing an event must never be made to look like
 *     an event that did not happen, and this seam must never be strengthened by inventing one - an intent record that
 *     could not distinguish an orphan from a real publish would announce artifacts that never became visible, which
 *     is a worse guarantee, not a stronger one.</li>
 * <li><b>Coverage - which producers reach this seam.</b> <b>Every {@link EventType} constant travels it</b>, and that
 *     is an asserted property rather than a claim: {@code EventSeamCoverageTest} confronts the declared constants
 *     with the {@link #emit} call sites the composition really carries, so a new constant with no producer fails
 *     the build. It reads compiled classes rather than source text - a producer never names a type, so the census
 *     learns from {@link RepositoryEvent}'s own code which factory builds which constant, then asks which of those
 *     factories are called by a class that also calls {@link #emit}. The clause's other half - a producer that
 *     writes into a delivery module's own store instead of calling {@link #emit} - is not decidable that way and is
 *     not asserted: nothing can see an event that was never modelled as one. (This sentence named that test for a
 *     long time before it existed, which is why it now says what the test does rather than only that it does it.) The six producer sites are the events module's own {@code EventPublicationObserver}
 *     (publish and unpublish, riding the store's after-commit hook), the gate's quarantine log, the findings
 *     store, the staging store's promotion, and both legs of {@code HoldLifecycle} (release and discard). A sink may
 *     therefore assume it sees every event type; a producer of a new type calls {@link #emit} rather than a delivery
 *     module's own store, and adds itself to that test's inventory in the same change.</li>
 * </ol>
 */
public interface EventSink {

    /** The SPI's selection key, the {@code <spi>} every resolution diagnostic points at - there is no
     *  {@code jenreg.event-sink} setting, because the policy is {@code ALL} and nothing is selected, but
     *  a refusal still names the SPI it refused for. */
    String SPI = "event-sink";

    /** Names the notification a best-effort {@link #emit} contained, so a store/sink outage dropping a publish /
     *  unpublish / quarantine / finding / promotion is visible rather than silent (§9). */
    Logger LOGGER = LoggerFactory.getLogger(EventSink.class);

    /** Observe one event that occurred in {@code store} (a tenant-and-repository scoped store). Best-effort and
     *  non-blocking: queue the notification and return, never perform the delivery inline. */
    void accept(ArtifactStore store, RepositoryEvent event) throws IOException;

    /** Fan an event out to every discovered sink, in name order, containing a sink's own failure - the emit seam a
     *  producer calls. Best-effort by contract on the delivery leg: a notification is never allowed to fail the
     *  operation it observes, and with no sink installed this is a no-op. The resolution leg is deliberately not
     *  best-effort: a packaging error (two sinks answering to one name, a sink that cannot be constructed) and an
     *  {@link Error} raised from inside a sink both propagate, because neither is a notification that failed to
     *  queue - see the contract's clauses 4 and 7. The {@link ServiceLoader} call lives here, in the SPI home
     *  ({@code events}) that {@code uses} the service, so a producer reaches the sinks through one static. */
    static void emit(ArtifactStore store, RepositoryEvent event) {
        // A null event is a producer bug, not an absence: fail here rather than inside a sink or - worse - inside the
        // diagnostic below, where a NullPointerException would escape the containment it was written to report.
        Objects.requireNonNull(event, "event");
        for (Map.Entry<String, EventSink> sink : resolved()) {
            try {
                sink.getValue().accept(store, event);
            } catch (Error broken) {
                // NOT the sink's answer: an OutOfMemoryError, a StackOverflowError or a NoClassDefFoundError says the
                // JVM or the module graph gave way, and containing it would leave a deployment serving artifacts on a
                // broken runtime with nothing worse than a queued webhook to show for it. Attribute it and rethrow.
                try {
                    LOGGER.error("event sink '" + sink.getKey() + "' raised an Error taking a "
                            + event.type().wire() + " notification for " + describe(event)
                            + " - not contained: an Error is the runtime breaking, not a notification failing to"
                            + " queue", broken);
                } catch (Throwable diagnostic) {
                    // Rendering the diagnostic can itself fail on the very runtime that just gave way (an
                    // OutOfMemoryError while building the message). The attribution is worth having but never worth
                    // REPLACING the Error it attributes, so the original is what reaches the caller either way.
                    broken.addSuppressed(diagnostic);
                }
                throw broken;
            } catch (Throwable failure) {
                // Best-effort: a notification is never allowed to fail the operation it observes - so the failure is
                // contained here, never rethrown to the caller. But containing it silently would lose a publish /
                // quarantine / finding / promotion notification with zero diagnostic (§9: a fail-soft
                // still emits a diagnostic), so name the lost event - its kind and coordinate/path - and the sink
                // that dropped it at WARNING, so an operator can see a store/sink outage swallowing notifications
                // instead of guessing. The sink's name is the one resolution captured: re-asking a broken sink for
                // its name is how the containment used to be defeated from inside its own handler.
                LOGGER.warn(
                        "event sink '" + sink.getKey() + "' dropped a " + event.type().wire() + " notification for "
                              + describe(event) + " - contained to keep the observed operation non-failing", failure);
            }
        }
    }

    /**
     * The discovered sinks, each paired with the name resolution read from it - the one resolution both statics
     * share, through the shared {@code ALL}-policy primitive rather than a third hand-rolled loop (design gate 3).
     * Pairing the name here is what makes the containment above unbreakable: the handler never re-enters a sink to
     * ask what it is called. Two sinks answering to one name, or one sink registered twice, throw out of here before
     * any sink is called (clause 4).
     */
    private static List<Map.Entry<String, EventSink>> resolved() {
        return Providers.all(SPI,
                ServiceLoader.load(EventSink.class),
                EventSink::name,
                _ -> true,
                sink -> Optional.of(Map.entry(sink.name(), sink)));
    }

    /** The lost event named for a diagnostic: its coordinate and path where it has them, or its salient detail (a
     *  promotion carries a staging id, not a coordinate), so a contained-notification log line points at what was
     *  dropped rather than a bare event type. */
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

    /** The sink names installed on this deployment - name-sorted and duplicate-refusing, through the same shared
     *  primitive {@link #emit} resolves with, so an enumeration can never under-report a really-installed sink by
     *  collapsing two of them into one entry.
     *
     *  <p><b>No production surface reads this</b>, and this javadoc asserted that a console and an API gated their
     *  surfaces on it for as long as neither did. There is no console screen for sinks, and the console is a
     *  separate node carrying no plugin runtime, so it could not resolve the events module if there were one;
     *  {@code /api/capabilities} reports the {@code webhook} <em>web module</em>, which is a different thing. Wiring
     *  this into the capabilities body would move the dead leg to the wire rather than close it, because a JSON field
     *  no client reads is the same defect one layer out. Its real readers are
     *  {@code test/events} and {@code test/webhook}, which is what keeps the duplicate-name refusal of clause 4
     *  asserted: {@link #emit} would fan out to two sinks named {@code webhook} where this reported one, and that
     *  divergence is what the duplicate-name refusal closed. This note and the call graph are held to
     *  each other. */
    static Set<String> installed() {
        return Providers.installedNames(SPI,
                ServiceLoader.load(EventSink.class),
                EventSink::name,
                _ -> true);
    }

    /** This sink's name, e.g. {@code webhook} - the identity {@link #installed()} enumerates and every diagnostic
     *  attributes a contained drop to. Unique across the
     *  deployment: two sinks answering to one name are a packaging error refused at resolution (clause 4). */
    String name();
}
