package build.jenesis.repository.ui;

import java.util.Set;

/**
 * The shared console layout, and the fragments a console built on it may plug into.
 *
 * <p>It exists to make an edge visible that the language could not see. The admin console extends this console
 * entirely through Thymeleaf: all of its templates {@code th:replace="~{base :: ...}"} against {@code base.html}, and
 * {@code requires build.jenesis.repository.ui} is what puts that file on the module path. Nothing in Java crossed the
 * boundary - no import and no type at all - so the dependency was real, load-bearing, and invisible to a
 * module-graph tool and to a reader. Deleting the {@code requires} broke nothing a compiler could report; it broke
 * every page at render time.
 *
 * <p>So the layout declares its extension points here, and a console that builds on it {@linkplain Extension declares
 * which it fills}. The edge becomes a Java one: an extending console references {@link #TEMPLATE} and these fragment
 * names, so removing the module dependency now fails to compile, and a third console joins by providing an
 * {@link Extension} rather than by knowing a filename.
 *
 * <p><b>These are the fragments that are extension points, not every fragment {@code base.html} defines.</b> A
 * fragment used only within the console's own pages is that console's business and may change with it; the set
 * below is the part other consoles may build on, and is therefore the part that may not change silently.
 */
public final class ConsoleLayout {

    /** The layout template's Thymeleaf name - the {@code base} in {@code ~{base :: pageHeader}}. */
    public static final String TEMPLATE = "base";

    /** A page's heading block, taking the title. */
    public static final String PAGE_HEADER = "pageHeader";

    /** A page's heading block with breadcrumbs, taking the title and the crumb list. */
    public static final String PAGE_HEADER_CRUMBS = "pageHeaderCrumbs";

    /** The empty-state block, taking the message shown when a list has no rows. */
    public static final String EMPTY = "empty";

    /** An inline notice, taking the message and its kind. */
    public static final String ALERT = "alert";

    /** The shared list of what every page loads - a console adding its own {@code <head>} extras includes this. */
    public static final String HEAD_CONTENTS = "headContents";

    /** A destructive submit: the danger treatment and its confirmation together, so neither can be forgotten. */
    public static final String DANGER_BUTTON = "dangerButton";

    /** A deletion: the danger treatment, guarded by a dialog in which the reader types {@code delete <name>}, taking the
     *  label, the name and what is lost. */
    public static final String DELETE_BUTTON = "deleteButton";

    /** The console frame every screen renders above its {@code <main>}: the edition's notice strip, the header with
     *  the brand, the groups, the theme switch and the signed-in identity, and the sidebar listing the pages of the
     *  group - or the repository - the reader is in. It reads the published {@link Navigation}, so a page passes
     *  nothing. */
    public static final String SHELL = "shell";

    /** The frame of a page a reader sees before they are anywhere - the sign-in pages and the no-access screen: the
     *  header with the brand and theme switch, and no navigation, since there is nothing yet to navigate. */
    public static final String SIGN_IN_SHELL = "signInShell";

    /** The deployment-wide notices a screen shows above its content (read-only, anonymous access, flash messages). */
    public static final String MESSAGES = "messages";

    /** The scoped-error panel one subsection renders in its own place, leaving the rest of the page intact. */
    public static final String SUBSECTION_ERROR = "subsectionError";

    /** The artifact browse's tree body, taking the rows, whether the level was cut short and what to say when it
     *  was - so a whole-store browse and a repository-scoped one draw one tree. */
    public static final String BROWSE_ROWS = "browseRows";

    /** The browse's up-one-level row, taking where it goes. */
    public static final String BROWSE_UP = "browseUp";

    /** A notice that work is running off the request path, taking what to say about it - and carrying the marker
     *  that makes the page keep itself current until it finishes. A screen never blocks and never waits for the
     *  work; it renders what is known and refreshes itself, and this is the one place that interval lives. */
    public static final String RUNNING = "running";

    /** Every fragment an extending console may build on. */
    public static final Set<String> FRAGMENTS = Set.of(
            PAGE_HEADER, PAGE_HEADER_CRUMBS, EMPTY, ALERT, HEAD_CONTENTS, DANGER_BUTTON, DELETE_BUTTON, SHELL, SIGN_IN_SHELL,
            MESSAGES, SUBSECTION_ERROR, BROWSE_ROWS, BROWSE_UP, RUNNING);

    private ConsoleLayout() {
        throw new UnsupportedOperationException();
    }

    /**
     * A console built on {@link ConsoleLayout}, declaring which fragments it plugs into.
     *
     * <p>Providing one is what turns a template-only dependency into a declared, discoverable edge. It carries no
     * rendering behaviour on purpose - Thymeleaf still resolves the fragments - and exists so that the relationship
     * is stated in the module graph rather than inferred from a resource path.
     */
    public interface Extension {

        /** This console's name, for a diagnostic that has to say which console declared what. */
        String name();

        /**
         * The fragments this console plugs into - each one of {@link #FRAGMENTS}.
         *
         * <p>Declaring a name the layout does not offer is a fail-loud error rather than a page that renders empty at
         * request time, which is the failure mode this whole seam replaces.
         */
        Set<String> fragments();
    }
}
