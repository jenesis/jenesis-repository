package build.jenesis.repository.findings.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.findings.FindingMarks;
import build.jenesis.repository.icon.IconContributor;
import build.jenesis.repository.icon.IconResource;
import build.jenesis.repository.icon.Mark;
import build.jenesis.repository.icon.Marks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The join the findings console makes between a stored finding's recorded {@code source} and the plug-ins installed
 * right now. {@code MarksTest} owns what each of the three answers <em>looks</em> like; what is asserted here
 * is which of the three a given recorded name gets, and in particular that the two that are easiest to confuse -
 * "installed but declares no mark" and "nothing answers to this name any more" - never collapse into one.
 *
 * <p>Built directly over stub contributors and bare names (no {@code ServiceLoader}, no Spring), so the mapping is
 * what is under test rather than discovery.
 */
public class FindingMarksTest {

    private static final String OSV_MARK = "<svg data-mark=\"osv\">stroke=currentColor</svg>";

    /** A feed that ships a mark of its own - the shape a branded advisory source has. */
    private final IconContributor osv = new StubContributor("osv", OSV_MARK);
    /** A feed that ships none - the ordinary case, and not a gap: it still gets a figure of its own. */
    private final IconContributor github = new StubContributor("github", null);

    /** The console's composition: two mark-bearing contributors plus the writers that are named but cannot declare
     *  a mark (a maintenance task provider, the publish screen's two stage names). */
    private final FindingMarks marks = new FindingMarks(List.of(osv, github),
            List.of("reachability", "gate", "inspection"));

    @Test
    void a_feed_that_ships_a_mark_is_drawn_with_its_own() {
        Mark mark = marks.of("osv");
        assertThat(mark.kind()).isEqualTo(Mark.Kind.DECLARED);
        assertThat(mark.svg()).isEqualTo(OSV_MARK);
        assertThat(mark.installed()).isTrue();
        // The attribution the row carries is the recorded source, so a title read aloud names the plug-in.
        assertThat(mark.title()).isEqualTo("osv");
    }

    @Test
    void a_feed_that_ships_none_gets_its_own_generated_figure_not_a_shared_box() {
        Mark mark = marks.of("github");
        assertThat(mark.kind()).isEqualTo(Mark.Kind.GENERATED);
        assertThat(mark.installed()).isTrue();
        assertThat(mark.title()).isEqualTo("github");
        // Two different installed contributors that both declare nothing must still be told apart: a page of
        // identical neutral boxes attributes nothing, which is the whole reason the generated scheme exists.
        assertThat(mark.svg()).isNotEqualTo(marks.of("reachability").svg()).isNotEqualTo(Marks.neutral());
    }

    @Test
    void a_writer_that_is_named_but_cannot_declare_a_mark_is_still_installed() {
        // A maintenance task provider names itself and writes findings under that name, but is a scheduler entry
        // rather than a console-facing plug-in family - it has no mark to ship, and that is not absence.
        for (String named : List.of("reachability", "gate", "inspection")) {
            assertThat(marks.of(named).kind()).as(named).isEqualTo(Mark.Kind.GENERATED);
            assertThat(marks.of(named).installed()).as(named).isTrue();
            assertThat(marks.of(named).title()).as(named).doesNotContain("not installed");
        }
    }

    @Test
    void a_source_no_installed_writer_claims_is_an_orphan_and_keeps_its_recorded_identity() {
        // The state this whole seam exists for: the Snyk module left the deployment and every row it ever wrote
        // still says "snyk". The row is shown, never deleted or rewritten - removed-module cleanup is an explicit,
        // dry-run-guarded operator action and a module's absence triggers nothing.
        Mark orphan = marks.of("snyk");
        assertThat(orphan.kind()).isEqualTo(Mark.Kind.ORPHANED);
        assertThat(orphan.installed()).isFalse();
        assertThat(orphan.title()).isEqualTo("snyk (not installed)");
        // Recognisably still that plug-in: the same figure it would have generated, in a dashed tile - so the state
        // is carried by the DRAWING and by the title, never by colour alone.
        assertThat(orphan.svg()).contains("stroke-dasharray");
        assertThat(strip(orphan.svg())).isEqualTo(strip(Marks.generated("snyk").svg()));
    }

    @Test
    void the_three_states_are_three_and_never_two() {
        // The failure this guards is a resolver that folds "declares no mark" and "is gone" onto one rendering,
        // which would make an uninstalled plug-in's findings indistinguishable from a live plug-in's.
        assertThat(List.of(marks.of("osv"), marks.of("github"), marks.of("snyk")))
                .extracting(Mark::kind)
                .containsExactly(Mark.Kind.DECLARED, Mark.Kind.GENERATED, Mark.Kind.ORPHANED);
        assertThat(marks.of("github").svg()).isNotEqualTo(marks.of("snyk").svg());
    }

    @Test
    void a_contributor_wins_over_a_bare_name_of_the_same_plug_in() {
        // A name that arrives on both inputs is one plug-in described twice; the contributor can say strictly more
        // about itself, so a mark it ships is not lost to the coarser input.
        FindingMarks both = new FindingMarks(List.of(osv), List.of("osv"));
        assertThat(both.of("osv").kind()).isEqualTo(Mark.Kind.DECLARED);
    }

    @Test
    void a_sources_mark_is_resolved_once_and_then_memoized_not_per_row() {
        // The findings table renders one mark per row and a repository's rows come from a handful of sources, so
        // the same name is resolved over and over. Each source's document must be materialised once, not per row.
        AtomicInteger resolutions = new AtomicInteger();
        IconContributor counting = new StubContributor("osv", OSV_MARK) {
            @Override
            public Optional<IconResource> icon() {
                resolutions.incrementAndGet();
                return super.icon();
            }
        };
        FindingMarks memoized = new FindingMarks(List.of(counting), List.of());

        Mark first = memoized.of("osv");
        for (int row = 0; row < 200; row++) {
            assertThat(memoized.of("osv")).isSameAs(first);
        }
        assertThat(resolutions.get()).isEqualTo(1);
    }

    @Test
    void an_unnamed_source_is_refused_rather_than_sharing_one_unknown_figure() {
        // A finding is identified by (source, id), so a blank source could not have been written. Resolving it to a
        // shared "unknown" mark would attribute two unrelated rows to one thing, which is the mis-attribution this
        // seam exists to prevent - so it throws instead.
        assertThatThrownBy(() -> marks.of(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> marks.of(null)).isInstanceOf(NullPointerException.class);
    }

    /** The dashed tile is the only intended difference between a generated figure and its orphan twin. */
    private static String strip(String svg) {
        return svg.replace(" stroke-dasharray=\"3 2.5\"", "");
    }

    private static class StubContributor implements IconContributor {

        private final String name;
        private final String mark;

        StubContributor(String name, String mark) {
            this.name = name;
            this.mark = mark;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Optional<IconResource> icon() {
            return mark == null ? Optional.empty() : Optional.of(IconResource.svg(mark));
        }
    }
}
