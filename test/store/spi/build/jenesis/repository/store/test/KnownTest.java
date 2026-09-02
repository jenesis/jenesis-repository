package build.jenesis.repository.store.test;

import module java.base;
import module org.junit.jupiter.api;

import build.jenesis.repository.store.Known;
import build.jenesis.repository.store.UnknownAnswerException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The three-valued answer (layer 1): "the answer is this", "the answer is nothing", and "I could not ask".
 *
 * <p>Most of what this type is for is proven by the <em>compiler</em> - a seam typed on {@link Known.Determined}
 * cannot be handed a {@link Known.Unknown} - and a test that would not compile is not a test. So the shape checks
 * here assert the same facts reflectively: that {@code Unknown} is not assignable to {@code Determined}, that the
 * hierarchy is sealed to exactly three states so a {@code switch} over it is total, and that the two-valued reflex
 * ({@code isPresent} / {@code get} / {@code orElse}) has no method to be spelled with. The behavioural checks then
 * pin the two narrowings and the constructor invariants.
 */
class KnownTest {

    @Test
    void a_present_answer_carries_its_value_and_an_absent_one_carries_nothing() {
        Known.Determined<String> present = Known.known("blobs/abc");
        Known.Determined<String> absent = Known.absent();

        assertThat(present).isEqualTo(new Known.Present<>("blobs/abc"));
        assertThat(present.answer()).contains("blobs/abc");
        assertThat(absent).isEqualTo(new Known.Absent<String>());
        assertThat(absent.answer()).isEmpty();
    }

    @Test
    void a_present_answer_cannot_be_built_around_nothing() {
        assertThatThrownBy(() -> new Known.Present<>(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("value");
    }

    @Test
    void an_unanswerable_question_must_say_why_it_could_not_be_answered() {
        // The reason is the whole reason this state exists: an operator has to be told what to install, resume or
        // fix, and every downstream site that refuses today logs exactly this.
        assertThatThrownBy(() -> new Known.Unknown<String>(Known.Cause.UNINSTALLED, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("could not be answered");
        assertThatThrownBy(() -> new Known.Unknown<String>(Known.Cause.FAILED, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Known.Unknown<String>(null, "detail"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void the_three_causes_are_the_three_ways_an_answer_is_lost() {
        assertThat(Known.uninstalled("no format places ecosystem npm"))
                .isEqualTo(new Known.Unknown<String>(Known.Cause.UNINSTALLED, "no format places ecosystem npm"));
        assertThat(Known.truncated("published/ did not enumerate whole"))
                .isEqualTo(new Known.Unknown<String>(Known.Cause.TRUNCATED, "published/ did not enumerate whole"));

        Known<String> failed = Known.failed("blobs/abc", new IOException("connection reset"));

        assertThat(failed).isInstanceOf(Known.Unknown.class);
        assertThat(((Known.Unknown<String>) failed).cause()).isEqualTo(Known.Cause.FAILED);
        assertThat(((Known.Unknown<String>) failed).detail()).contains("blobs/abc").contains("connection reset");
    }

    @Test
    void an_unknown_reads_as_an_operator_line_rather_than_a_record_dump() {
        assertThat(Known.uninstalled("no installed format places ecosystem npm"))
                .hasToString("unknown (UNINSTALLED): no installed format places ecosystem npm");
    }

    @Test
    void narrowing_to_a_determined_answer_passes_the_answered_states_through() {
        Known<String> present = Known.known("hash");
        Known<String> absent = Known.absent();

        assertThat(present.determined()).isSameAs(present);
        assertThat(absent.determined()).isSameAs(absent);
    }

    @Test
    void narrowing_an_unanswerable_question_refuses_and_carries_the_reason_with_it() {
        Known<String> unknown = Known.uninstalled("the oci format module is not installed");

        assertThatThrownBy(unknown::determined)
                .isInstanceOf(UnknownAnswerException.class)
                .hasMessageContaining("the oci format module is not installed")
                .extracting(thrown -> ((UnknownAnswerException) thrown).unknown())
                .isEqualTo(unknown);
    }

    @Test
    void collapsing_unknown_into_absent_is_possible_only_by_writing_down_why() {
        Known<String> unknown = Known.truncated("the listing stopped at its cap");

        assertThatThrownBy(() -> unknown.unknownAsAbsent(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("justification");
        assertThat(unknown.unknownAsAbsent("a browse may omit a row it cannot judge")).isEmpty();
        assertThat(Known.known("x").unknownAsAbsent("a browse may omit a row it cannot judge")).contains("x");
        assertThat(Known.absent().unknownAsAbsent("a browse may omit a row it cannot judge")).isEmpty();
    }

    // ---- shape: what the compiler enforces, asserted where a non-compiling test could not ----

    @Test
    void an_unanswerable_question_does_not_fit_a_seam_that_demands_an_answered_one() {
        // The mechanism of layer 2: a destructive/releasing/disclosing seam types its parameter on Determined,
        // and Unknown is a peer of Determined rather than a subtype, so handing one over is a compile error at the
        // call site instead of a review item. If this ever became true, every such seam would silently re-open.
        assertThat(Known.Determined.class.isAssignableFrom(Known.Unknown.class)).isFalse();
        assertThat(Known.class.isAssignableFrom(Known.Unknown.class)).isTrue();
        assertThat(Known.Determined.class.isAssignableFrom(Known.Present.class)).isTrue();
        assertThat(Known.Determined.class.isAssignableFrom(Known.Absent.class)).isTrue();
    }

    @Test
    void the_hierarchy_is_sealed_to_exactly_three_states_so_a_switch_over_it_is_total() {
        assertThat(Known.class.isSealed()).isTrue();
        assertThat(Known.class.getPermittedSubclasses())
                .containsExactlyInAnyOrder(Known.Determined.class, Known.Unknown.class);
        assertThat(Known.Determined.class.isSealed()).isTrue();
        assertThat(Known.Determined.class.getPermittedSubclasses())
                .containsExactlyInAnyOrder(Known.Present.class, Known.Absent.class);
    }

    @Test
    void the_two_valued_reflex_has_no_method_to_be_spelled_with() throws NoSuchMethodException {
        // isPresent()/get()/orElse() over a three-valued answer is the defect itself: the else-branch absorbs the
        // third state without saying so. There is deliberately nothing here to call, so the only ways out are an
        // exhaustive switch and the two named narrowings.
        List<String> spellings = List.of("isPresent", "isEmpty", "get", "orElse", "orElseGet", "orElseThrow",
                "ifPresent", "ifPresentOrElse", "filter", "stream", "or");

        assertThat(Arrays.stream(Known.class.getMethods()).map(Method::getName))
                .doesNotContainAnyElementsOf(spellings);
        assertThat(Arrays.stream(Known.class.getMethods()).map(Method::getName))
                .contains("determined", "unknownAsAbsent");
        // The Optional carrier is legal exactly once the question HAS been answered, and nowhere else.
        assertThat(Known.Determined.class.getMethod("answer").getReturnType()).isEqualTo(Optional.class);
    }
}
