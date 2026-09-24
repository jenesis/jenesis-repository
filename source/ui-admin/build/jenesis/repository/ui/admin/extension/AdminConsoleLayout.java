package build.jenesis.repository.ui.admin.extension;

import build.jenesis.repository.ui.ConsoleLayout;

import java.util.Set;

/**
 * The admin console's declaration that it is built on the base console's layout.
 *
 * <p>This is the whole of the D-281 fix, and it is deliberately small. The dependency it declares was already real -
 * every one of this console's templates renders through {@code base.html} - but it existed only as a Thymeleaf
 * fragment reference and a {@code requires} clause with no Java behind it. A module-graph tool saw an unused edge; a
 * reader saw a {@code requires} nothing seemed to need. Referencing {@link ConsoleLayout} here means removing that
 * dependency now fails to compile instead of failing at render time on every page.
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
        // What these templates reach for directly. The brand, the module links and the theme switch are no
        // longer among them: they moved inside the shared shell, so this console asks for the shell and gets them.
        // Nor is the alert: the sign-in page was the last screen here to render one itself, and that page is now
        // the shared one. This console still shows alerts - through `messages`, which renders them - but it no
        // longer reaches for the fragment, and the census is what noticed.
        return Set.of(ConsoleLayout.HEAD_CONTENTS, ConsoleLayout.SHELL, ConsoleLayout.FOOTER, ConsoleLayout.PAGE_HEADER,
                ConsoleLayout.MESSAGES, ConsoleLayout.SUBSECTION_ERROR,
                ConsoleLayout.EMPTY, ConsoleLayout.DANGER_BUTTON,
                ConsoleLayout.BROWSE_ROWS, ConsoleLayout.BROWSE_UP,
                // Every screen that starts work off the request path renders this instead of telling the reader to
                // reload: the rescans, the blast radius, the project count, both cleanup notices and the migration.
                ConsoleLayout.RUNNING);
    }
}
