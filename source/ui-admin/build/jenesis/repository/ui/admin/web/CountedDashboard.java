package build.jenesis.repository.ui.admin.web;

import module java.base;

import build.jenesis.repository.ui.DashboardContributor;
import build.jenesis.repository.ui.DashboardPanel;

/**
 * A dashboard panel counting what the tenant has - its repositories, its build-cache projects - and offering the way
 * to the first where it has none. One count is one read the screen it opens makes anyway.
 *
 * @param order  where the panel sits among the others
 * @param title  what the panel is about, linking to {@code href}
 * @param href   the screen listing what it counts
 * @param count  how many the tenant has
 * @param noun   what it counts, beside one and beside many
 * @param create where the first one is made, offered while there is none
 */
public record CountedDashboard(int order, String title, String href, Count count, DashboardPanel.Noun noun,
                               DashboardPanel.Link create) implements DashboardContributor {

    /** How many there are, read as the panel is drawn. */
    @FunctionalInterface
    public interface Count {
        long count() throws IOException;
    }

    @Override
    public List<DashboardPanel> panels(Viewer viewer) throws IOException {
        long held = count.count();
        DashboardPanel panel = DashboardPanel.counted(title, href, held, false, noun, DashboardPanel.Tone.NEUTRAL,
                List.of());
        return List.of(held > 0 ? panel : panel.offering(create));
    }
}
