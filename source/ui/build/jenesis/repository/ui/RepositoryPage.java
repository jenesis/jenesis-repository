package build.jenesis.repository.ui;

import module java.base;

/**
 * One page about a single repository, contributed through {@link ConsoleModuleProvider#repositoryPages()}. The shell
 * lists the installed modules' pages beside the content under each page's {@link Topic}, so a module adds a page to
 * every repository by being installed.
 *
 * @param label    the page's name in the sidebar
 * @param path     the page's path below the repository, starting with a slash ({@code /vulnerabilities}), or the
 *                 empty string for the repository's own overview; the shell resolves it against
 *                 {@code /repositories/<name>}
 * @param access   the minimum role a reader needs to see it
 * @param topic    the heading the shell files it under
 * @param requires the capability the page needs beyond its module being installed, as for
 *                 {@link NavEntry#requires()}, or the empty string
 */
public record RepositoryPage(String label, String path, NavEntry.Access access, Topic topic, String requires) {

    public RepositoryPage {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(requires, "requires");
        if (!path.isEmpty() && !path.startsWith("/")) {
            throw new IllegalArgumentException("A repository page's path starts with a slash or is empty: " + path);
        }
    }

    /** A page any reader of the repository may see, needing nothing beyond its module. */
    public RepositoryPage(String label, String path, Topic topic) {
        this(label, path, NavEntry.Access.USER, topic, "");
    }

    /** A page any reader of the repository may see once {@code requires} is present. */
    public RepositoryPage(String label, String path, Topic topic, String requires) {
        this(label, path, NavEntry.Access.USER, topic, requires);
    }

    /** What a repository page is about, which is the heading the sidebar lists it under, in this order. */
    public enum Topic {

        /** What the repository holds, and the ways to put more in. */
        CONTENTS("Contents"),

        /** The queues waiting for a person's decision: what the gate held or refused, what an analysis proposes. */
        REVIEW("Review"),

        /** What the feeds, the ledgers and the policies say about what it holds. */
        RISK("Risk"),

        /** Where what it holds came from and what depends on it. */
        PROVENANCE("Provenance"),

        /** How long it keeps what it holds, what it brings in from elsewhere, and where it sends it. */
        LIFECYCLE("Lifecycle");

        private final String label;

        Topic(String label) {
            this.label = label;
        }

        /** The heading the sidebar shows. */
        public String label() {
            return label;
        }
    }
}
