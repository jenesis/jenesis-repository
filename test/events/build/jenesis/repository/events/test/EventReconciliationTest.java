package build.jenesis.repository.events.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.events.EventReconciliation;
import build.jenesis.repository.events.EventType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The static half of the earlier route: every {@link EventType} names the durable read a subscriber reconciles it
 * against, and the pairing is complete by construction rather than by maintenance.
 *
 * <p>{@code EventSink}'s clause 12 already claimed that "every event type has a durable, queryable counterpart" and
 * named none of them, which is a true statement an integrator cannot act on - the defect records. The claim is
 * now {@link EventReconciliation#of(EventType)}, a total switch with no {@code default}, so a new event kind does not
 * compile until its counterpart is answered. That is the mechanism; this suite is what stops the mechanism being
 * satisfied vacuously - by a blank string, a placeholder, or a route that promises more than the ledger behind it can
 * tell.
 *
 * <p>The runtime half - that an operator actually reaches this on a surface they read - is asserted separately, in
 * {@code test/webhook-web} against {@code GET /api/webhook}. A route nothing renders is the dead leg removed.
 */
class EventReconciliationTest {

    @Test
    void every_event_type_names_a_durable_ledger_and_says_what_it_cannot_tell_you() {
        for (EventType type : EventType.values()) {
            EventReconciliation route = EventReconciliation.of(type);

            assertThat(route.ledger()).as("%s names the durable state that is authoritative for it", type)
                    .isNotBlank();
            assertThat(route.caveat()).as("%s says what its durable read cannot tell you - a route stated without "
                    + "its limits is the over-promise this whole ticket is about", type).isNotBlank();
        }
    }

    @Test
    void every_route_points_at_a_read_and_never_at_a_write() {
        for (EventType type : EventType.values()) {
            String route = EventReconciliation.of(type).route();

            assertThat(route).as("%s names the read an integrator polls", type).isNotBlank();
            assertThat(route).as("%s reconciles by reading; a route that told an integrator to POST would be "
                    + "prescribing a mutation to answer a question", type).startsWith("GET /api/");
        }
    }

    @Test
    void the_map_is_complete_and_ordered_by_the_enum() {
        Map<EventType, EventReconciliation> all = EventReconciliation.all();

        assertThat(all).as("no event type is missing from the rendered table").hasSize(EventType.values().length);
        assertThat(all.keySet()).as("in declaration order, so the rendered surface is deterministic")
                .containsExactly(EventType.values());
        assertThat(all.values()).as("and every entry is distinct - a copy-pasted row would mean one event type's "
                        + "route is silently another's").doesNotHaveDuplicates();
    }

    @Test
    void the_preamble_states_both_halves_of_the_gap_the_route_exists_for() {
        String preamble = EventReconciliation.preamble();

        // The honesty test. A route rendered without the two gaps reads like an optional extra rather than like the
        // answer to "I cannot miss one" - and the losing half is the one a reader will not guess, since a retrying
        // outbox looks reliable from outside.
        assertThat(preamble).as("the duplicate half - delivery out of the outbox is at-least-once")
                .containsIgnoringCase("at-least-once");
        assertThat(preamble).as("and the losing half - delivery into the outbox is not repaired")
                .containsIgnoringCase("lossy");
        assertThat(preamble).as("naming the crash window rather than leaving 'lossy' abstract")
                .containsIgnoringCase("crash");
    }

    @Test
    void a_new_event_type_cannot_be_added_without_a_route() {
        // Not a runtime assertion - it is a compile-time property, recorded here so the reason the switch has no
        // default is not lost to a later "simplification". EventReconciliation.of switches over EventType totally,
        // so adding a constant reds the build in that file until its counterpart is named. Verified by falsification
        // during implementation: adding a constant to EventType failed compilation of EventReconciliation.of.
        assertThat(EventType.values()).as("the enum the switch is total over").isNotEmpty();
        assertThat(EventReconciliation.all().keySet())
                .as("and every constant of it is answered, which is what totality buys")
                .containsExactlyInAnyOrder(EventType.values());
    }
}
