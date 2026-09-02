package build.jenesis.repository.observation.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.observation.Contributions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shared containment every collected report folds its contributors through. The properties asserted here are the
 * ones the three seams (console panels, safety advisors, observability sources) each rely on and none of them may
 * re-decide: a failing contributor is <em>replaced</em>, never dropped and never allowed to end the collection; the
 * surviving contributors keep their order and their place; a {@code null} answer counts as a failure rather than as an
 * empty contribution; an {@link Error} is deliberately not contained; and the substitution can read a declaration off
 * the very contributor that just failed without becoming a second thing that throws.
 */
class ContributionsTest {

    /** A contributor whose only interesting property is whether it answers, and how often it was asked. */
    private record Fixture(String answer, RuntimeException failure, AtomicInteger calls) {

        static Fixture answering(String answer) {
            return new Fixture(answer, null, new AtomicInteger());
        }

        static Fixture failing(RuntimeException failure) {
            return new Fixture(null, failure, new AtomicInteger());
        }

        String contribute() {
            calls.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return answer;
        }
    }

    private static List<String> collect(List<Fixture> fixtures) {
        return Contributions.collect("fixture", fixtures, Fixture::contribute,
                (fixture, failure) -> "failed:" + Contributions.reason(failure));
    }

    @Test
    void a_failing_contributor_is_replaced_in_place_and_never_hides_the_others() {
        Fixture first = Fixture.answering("first");
        Fixture broken = Fixture.failing(new IllegalStateException("the store is unreachable at /secret/path"));
        Fixture last = Fixture.answering("last");

        // Before containment this call threw and the caller rendered nothing at all.
        List<String> collected = collect(List.of(first, broken, last));

        assertThat(collected)
                .as("every contributor is represented, the failed one in its own position - a failed contributor that "
                        + "simply vanished would read exactly like one with nothing to say")
                .containsExactly("first", "failed:IllegalStateException", "last");
        assertThat(broken.calls()).as("a failed contributor is asked exactly once - a contained failure never "
                + "doubles the cost of a render").hasValue(1);
    }

    @Test
    void every_contributor_after_a_failure_still_contributes() {
        List<Fixture> fixtures = List.of(
                Fixture.failing(new IllegalStateException("first")),
                Fixture.failing(new IllegalStateException("second")),
                Fixture.answering("survivor"));

        assertThat(collect(fixtures)).hasSize(3).last().isEqualTo("survivor");
        assertThat(fixtures).allSatisfy(fixture ->
                assertThat(fixture.calls()).as("collection continues past a failure").hasValue(1));
    }

    @Test
    void a_null_contribution_is_a_failure_rather_than_an_invisible_hole() {
        // Dropping it would leave the report one row short with nothing saying so - the silent-degradation case the
        // whole mechanism exists to prevent. It is contained like a throw: reported, not propagated.
        assertThat(collect(List.of(Fixture.answering(null), Fixture.answering("second"))))
                .containsExactly("failed:IllegalStateException", "second");
    }

    @Test
    void a_degraded_substitution_that_answers_null_is_the_callers_bug_and_fails_loudly() {
        assertThatThrownBy(() -> Contributions.collect("fixture", List.of(Fixture.failing(new RuntimeException())),
                Fixture::contribute, (_, _) -> null))
                .as("there would be nothing to put on the surface, and quietly dropping the row is the outcome this "
                        + "class refuses")
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void an_error_is_not_contained() {
        // A LinkageError from a half-installed plugin is a broken module graph rather than a contributor declining to
        // answer; reporting it as one degraded row on an otherwise healthy page would misreport it.
        assertThatThrownBy(() -> Contributions.collect("fixture", List.of(new Object()),
                _ -> {
                    throw new LinkageError("half-installed plugin");
                }, (_, _) -> "degraded"))
                .isInstanceOf(LinkageError.class);
    }

    @Test
    void an_error_is_attributed_on_its_way_out_and_never_replaced_by_its_attribution() {
        // the propagation above was right and the silence around it was not. the earlier ruling is that an Error
        // is attributed AND escalated, and this class did the second half only - so an operator whose console 500ed
        // learned that something on the page had given way and nothing about which of N plugins it was. The log line
        // is what fixes that; what is assertable here is the rule that makes adding it safe, which is that the
        // caller still receives the very failure the contributor raised. An attribution that replaced the Error it
        // attributes - because rendering the message threw on the runtime that had just given way - would be worse
        // than the silence.
        Error planted = new LinkageError("half-installed plugin");

        assertThatThrownBy(() -> Contributions.collect("fixture", List.of(new Object()),
                _ -> {
                    throw planted;
                }, (_, _) -> "degraded"))
                .isSameAs(planted);
    }

    @Test
    void a_null_contributor_has_no_identity_to_attribute_a_failure_to_and_throws() {
        List<Object> contributors = new ArrayList<>();
        contributors.add(null);
        assertThatThrownBy(() -> Contributions.collect("fixture", contributors, Object::toString, (_, _) -> "x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("null");
    }

    @Test
    void a_declaration_read_off_an_already_failed_contributor_falls_back_instead_of_throwing_again() {
        Fixture broken = Fixture.failing(new IllegalStateException("boom"));
        assertThat(Contributions.declared(broken, Fixture::contribute, "fallback")).isEqualTo("fallback");
        assertThat(Contributions.declared(Fixture.answering(null), Fixture::contribute, "fallback"))
                .as("a null declaration is as unusable as a thrown one").isEqualTo("fallback");
        assertThat(Contributions.declared(Fixture.answering("declared"), Fixture::contribute, "fallback"))
                .as("a contributor that can still name itself keeps its own identity").isEqualTo("declared");
    }

    @Test
    void a_failure_row_is_keyed_by_a_grammar_safe_segment_derived_from_the_contributor_class() {
        // The three surfaces file failure rows under jenreg.<feature>.unavailable.<segment>, whose grammar is
        // [a-z][a-z0-9]* - so the segment must survive class names with digits, dollars and mixed case.
        assertThat(Contributions.segment(new ThrowingSource())).isEqualTo("throwingsource");
        assertThat(Contributions.segment(new Object())).isEqualTo("object");
        assertThat(Contributions.segment(Fixture.answering("x"))).isEqualTo("fixture");
        assertThat(Contributions.segment(new Runnable() {
            @Override
            public void run() {
            }
        })).as("an anonymous class has no simple name but still identifies itself").matches("[a-z][a-z0-9]*");
    }

    @Test
    void a_failure_reason_names_the_kind_of_failure_and_never_the_message() {
        // The message is uncontrolled text that can quote a credential the contributor had just read or another
        // tenant's name, and all three surfaces forbid rendering a read value; the log carries the full exception.
        String reason = Contributions.reason(new IllegalStateException("password=hunter2 for tenant acme"));
        assertThat(reason).isEqualTo("IllegalStateException");
        assertThat(reason).doesNotContain("hunter2").doesNotContain("acme");
    }
}
