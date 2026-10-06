package build.jenesis.repository.ui.admin.extension;

import build.jenesis.repository.ui.ConsoleLayout;

import java.util.Set;

/**
 * The admin console's declaration that its templates render through the base console's layout
 * ({@link ConsoleLayout.Extension}), listing the fragments they plug into, which are verified against the templates.
 */
public final class AdminConsoleLayout implements ConsoleLayout.Extension {

    @Override
    public String name() {
        return "admin";
    }

    @Override
    public Set<String> fragments() {
        // The shell carries the brand, module links and theme switch.
        return Set.of(ConsoleLayout.HEAD_CONTENTS, ConsoleLayout.SHELL, ConsoleLayout.PAGE_HEADER,
                ConsoleLayout.PAGE_HEADER_CRUMBS,
                ConsoleLayout.MESSAGES, ConsoleLayout.SUBSECTION_ERROR, ConsoleLayout.ALERT,
                ConsoleLayout.EMPTY, ConsoleLayout.EMPTY_ACTION,
                ConsoleLayout.PRIMARY_BUTTON, ConsoleLayout.SECONDARY_BUTTON, ConsoleLayout.CAUTION_BUTTON,
                ConsoleLayout.DANGER_BUTTON, ConsoleLayout.DELETE_BUTTON, ConsoleLayout.PHRASE_BUTTON,
                ConsoleLayout.BROWSE_ROWS, ConsoleLayout.BROWSE_UP, ConsoleLayout.FOLDER_LINK,
                ConsoleLayout.RUNNING,
                ConsoleLayout.REPOSITORY_HEADER, ConsoleLayout.REPOSITORY_OVERVIEW_HEADER, ConsoleLayout.MODULE_VIEWS,
                ConsoleLayout.TIME, ConsoleLayout.ARTIFACT_LINK, ConsoleLayout.SEVERITY);
    }
}
