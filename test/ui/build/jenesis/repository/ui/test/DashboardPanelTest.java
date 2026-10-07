package build.jenesis.repository.ui.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.ui.DashboardPanel;

import static org.assertj.core.api.Assertions.assertThat;

/** A panel's phrase agrees with its count wherever a panel says one, a counted panel offers where what it counts
 *  begins only while there is none, and a panel whose first count is still running is drawn, saying so. */
class DashboardPanelTest {

    private static final DashboardPanel.Noun WAITING =
            new DashboardPanel.Noun("version waits for a decision", "versions wait for a decision");

    @Test
    void the_phrase_agrees_with_its_count() {
        assertThat(WAITING.of(1, false)).isEqualTo("version waits for a decision");
        assertThat(WAITING.of(2, false)).isEqualTo("versions wait for a decision");
        assertThat(WAITING.of(0, false)).isEqualTo("versions wait for a decision");
        assertThat(WAITING.of(1, true)).as("a count that stopped short is many").isEqualTo("versions wait for a decision");
        assertThat(new DashboardPanel.Noun("version", "versions").counted(12_000, true)).isEqualTo("12,000+ versions");
    }

    @Test
    void a_counted_panel_shows_its_figure_and_offers_an_action_only_where_asked() {
        DashboardPanel panel = DashboardPanel.counted("Repositories", "/ui/repositories", 0, false,
                new DashboardPanel.Noun("repository", "repositories"), DashboardPanel.Tone.NEUTRAL, List.of());
        assertThat(panel.figure()).isEqualTo("0");
        assertThat(panel.caption()).isEqualTo("repositories");
        assertThat(panel.action()).isEmpty();
        DashboardPanel offering = panel.offering(new DashboardPanel.Link("New repository", "/ui/new/repository"));
        assertThat(offering.action()).contains(new DashboardPanel.Link("New repository", "/ui/new/repository"));
        assertThat(offering.says()).isTrue();
    }

    @Test
    void a_panel_whose_first_count_runs_is_drawn_and_one_with_nothing_to_say_is_not() {
        DashboardPanel counting = new DashboardPanel("Held for review", "/ui/repositories", "", "",
                DashboardPanel.Tone.NEUTRAL, List.of(), Optional.empty(), "Counting…", Optional.empty(), true);
        assertThat(counting.says()).as("the page polls for it, so it says why").isTrue();
        DashboardPanel silent = new DashboardPanel("Held for review", "/ui/repositories", "", "",
                DashboardPanel.Tone.NEUTRAL, List.of(), Optional.empty(), "", Optional.empty(), false);
        assertThat(silent.says()).isFalse();
    }
}
