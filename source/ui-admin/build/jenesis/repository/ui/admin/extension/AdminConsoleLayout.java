package build.jenesis.repository.ui.admin.extension;

import build.jenesis.repository.ui.ConsoleLayout;

import java.util.Set;

/**
 * The admin console's declaration that it is built on the base console's layout.
 *
 * <p>Deliberately small. The dependency it declares is real - every one of this console's templates renders through
 * {@code base.html} - but without it that would exist only as a Thymeleaf fragment reference and a {@code requires}
 * clause with no Java behind it: a module-graph tool would see an unused edge, and a reader a {@code requires}
 * nothing seemed to need. Referencing {@link ConsoleLayout} here means removing that dependency fails to compile
 * instead of failing at render time on every page.
 *
 * <p>The fragment set is the ones this console's templates actually plug into, verified against the templates rather
 * than declared aspirationally - so a fragment dropped from the layout is a failure with a name attached.
 */
public final class AdminConsoleLayout implements ConsoleLayout.Extension {

    @Override
    public String name() {
        return "admin";
    }

    @Override
    public Set<String> fragments() {
        // What these templates reach for directly. The brand, the module links and the theme switch are inside
        // the shared shell, so this console asks for the shell and gets them. The alert is: the repository overview warns inline when garbage collection refuses the repository.
        // A page under another screen - a group under Members - carries the trail back to it.
        return Set.of(ConsoleLayout.HEAD_CONTENTS, ConsoleLayout.SHELL, ConsoleLayout.PAGE_HEADER,
                ConsoleLayout.PAGE_HEADER_CRUMBS,
                ConsoleLayout.MESSAGES, ConsoleLayout.SUBSECTION_ERROR, ConsoleLayout.ALERT,
                ConsoleLayout.EMPTY,
                // Every action carries its weight: the main one, a neutral one, a recoverable consequence that asks
                // first (a release, a reclaim, a cache clear, a cleanup run), and an irreversible one - a restore
                // that asks, or a deletion guarded by typing its name.
                ConsoleLayout.PRIMARY_BUTTON, ConsoleLayout.SECONDARY_BUTTON, ConsoleLayout.CAUTION_BUTTON,
                ConsoleLayout.DANGER_BUTTON, ConsoleLayout.DELETE_BUTTON,
                ConsoleLayout.BROWSE_ROWS, ConsoleLayout.BROWSE_UP,
                // Every screen that starts work off the request path renders this instead of telling the reader to
                // reload: the rescans, the blast radius, the project count, both cleanup notices and the migration.
                ConsoleLayout.RUNNING,
                // Every page about one repository opens with its trail and identity; the modules have two views.
                ConsoleLayout.REPOSITORY_HEADER, ConsoleLayout.REPOSITORY_OVERVIEW_HEADER,
                ConsoleLayout.REPOSITORY_IDENTITY, ConsoleLayout.MODULE_VIEWS);
    }
}
