package build.jenesis.repository.ui;

import java.util.Set;

/**
 * The shared console layout and the fragments a console built on it may plug into. A console extending it through
 * Thymeleaf ({@code th:replace="~{base :: ...}"}) references {@link #TEMPLATE} and these names and provides an
 * {@link Extension}, so its dependency on this module is a Java edge the compiler checks rather than a template path
 * that fails only at render time.
 *
 * <p>These are the extension points, not every fragment {@code base.html} defines; a fragment used only by this
 * console's own pages may change with it.
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

    /** The empty-state block whose way out is an action, taking the words before it, the linked words, the link and
     *  the words after. */
    public static final String EMPTY_ACTION = "emptyAction";

    /** An inline notice, taking the message and its kind. */
    public static final String ALERT = "alert";

    /** The shared list of what every page loads - a console adding its own {@code <head>} extras includes this. */
    public static final String HEAD_CONTENTS = "headContents";

    /** The main action of a page or of a section standing on its own, taking the label: the one filled button. */
    public static final String PRIMARY_BUTTON = "primaryButton";

    /** A neutral action - a filter, a refresh, a row's everyday act - taking the label. */
    public static final String SECONDARY_BUTTON = "secondaryButton";

    /** A consequential submit that can be undone - a release, a promotion, a pass run now: the caution treatment and
     *  its confirmation together, taking the label and the question. */
    public static final String CAUTION_BUTTON = "cautionButton";

    /** An irreversible submit: the danger treatment and its confirmation together, so neither can be forgotten. */
    public static final String DANGER_BUTTON = "dangerButton";

    /** A deletion: the danger treatment, guarded by a dialog in which the reader types {@code delete <name>}, taking the
     *  label, the name and what is lost. */
    public static final String DELETE_BUTTON = "deleteButton";

    /** An act reaching beyond one object - switching features on, reaching outside the deployment: the danger
     *  treatment, guarded by the same dialog with a phrase to type, taking the label, the phrase, the question and
     *  what it warns of. */
    public static final String PHRASE_BUTTON = "phraseButton";

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

    /** A notice that work is running off the request path, taking what to say, and carrying the marker that makes the
     *  page refresh itself until it finishes: the one place that interval lives. */
    public static final String RUNNING = "running";

    /** The heading every page about one repository opens with, taking the title and an optional page between the
     *  repository and this one: the trail back to the repository, ended by the page's title, from the
     *  {@link RepositoryHeader} the console resolves for the request. */
    public static final String REPOSITORY_HEADER = "repositoryHeader";

    /** The repository overview's heading, where the repository is the page. */
    public static final String REPOSITORY_OVERVIEW_HEADER = "repositoryOverviewHeader";

    /** The format a repository holds and the URL a client reaches it at, said once, on the repository's overview. */
    public static final String REPOSITORY_IDENTITY = "repositoryIdentity";

    /** The two views of the installed modules - by module and by the contract each implements - taking the current
     *  one. */
    public static final String MODULE_VIEWS = "moduleViews";

    /** A link into the browse tree, drawn as a small folder beside a heading, taking the address and what it opens. */
    public static final String FOLDER_LINK = "folderLink";

    /** An instant, as every screen shows one: a {@code <time>} element the console's script shows in the reader's
     *  timezone, taking the instant (or its ISO-8601 text). */
    public static final String TIME = "time";

    /** An artifact or a version as every listing of artifacts names it: its coordinate in code, linking to its page
     *  where there is one. */
    public static final String ARTIFACT_LINK = "artifactLink";

    /** A severity as every screen shows one: its band as a badge, taking the severity (or none, shown as unknown). */
    public static final String SEVERITY = "severity";

    /** Every fragment an extending console may build on. */
    public static final Set<String> FRAGMENTS = Set.of(
            PAGE_HEADER, PAGE_HEADER_CRUMBS, EMPTY, EMPTY_ACTION, ALERT, HEAD_CONTENTS, PRIMARY_BUTTON, SECONDARY_BUTTON,
            CAUTION_BUTTON, DANGER_BUTTON, DELETE_BUTTON, PHRASE_BUTTON, SHELL, SIGN_IN_SHELL, MESSAGES, SUBSECTION_ERROR,
            BROWSE_ROWS, BROWSE_UP, RUNNING, REPOSITORY_HEADER, REPOSITORY_OVERVIEW_HEADER, REPOSITORY_IDENTITY,
            MODULE_VIEWS, FOLDER_LINK, TIME, ARTIFACT_LINK, SEVERITY);

    private ConsoleLayout() {
        throw new UnsupportedOperationException();
    }

    /**
     * A console built on {@link ConsoleLayout}, declaring which fragments it plugs into. It carries no rendering
     * behaviour; Thymeleaf resolves the fragments.
     *
     * <h2>Contract</h2>
     * <ol>
     *   <li><b>Absence sentinel.</b> {@link #name()} is never {@code null} or blank, and {@link #fragments()} never
     *       {@code null}; a console plugging into nothing answers an empty set.</li>
     *   <li><b>Selection failure.</b> {@code ALL}: every console on the module path declares itself, and a name not
     *       in {@link #FRAGMENTS} is an error naming the console, never a page that renders empty. The console suites
     *       discover every extension and verify each declared fragment against the templates.</li>
     *   <li><b>Read purity.</b> Both methods are declarations: constant, with no I/O.</li>
     *   <li><b>Lifecycle / ownership.</b> Created by {@code ServiceLoader}, holding nothing and never closed.</li>
     * </ol>
     */
    public interface Extension {

        /** This console's name, for a diagnostic that has to say which console declared what. */
        String name();

        /**
         * The fragments this console plugs into, each one of {@link #FRAGMENTS}; any other name is an error rather than
         * a page that renders empty.
         */
        Set<String> fragments();
    }
}
