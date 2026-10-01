package build.jenesis.repository.ui;

import module java.base;

/**
 * The two navigation levels of one rendered page, resolved for the reader and the request: the groups the header
 * lists, and the pages the sidebar lists for the group the reader is in. The shell renders this and decides nothing,
 * so what a reader may see and where they are is settled once, in the console that publishes it.
 *
 * @param groups  the header's links, one per group holding a page the reader may open, in the shell's order
 * @param sidebar what the sidebar lists: a heading, an optional way back, and its sections
 */
public record Navigation(List<Link> groups, Sidebar sidebar) {

    /** Nothing to navigate: a page rendered outside the console's advice, or for a principal who holds nothing. */
    public static final Navigation NONE = new Navigation(List.of(), Sidebar.NONE);

    public Navigation {
        groups = List.copyOf(groups);
        Objects.requireNonNull(sidebar, "sidebar");
    }

    /** Whether there is anything to render - an empty header bar would read as one that failed to load. */
    public boolean present() {
        return !groups.isEmpty() || sidebar.present();
    }

    /**
     * Whether this page's navigation links to {@code href}, in the header, the sidebar or its way back. A screen asks
     * before linking another, so a link never leads to a page this reader cannot open. The sidebar lists only the
     * current group.
     */
    public boolean offers(String href) {
        return groups.stream().anyMatch(link -> link.href().equals(href))
                || sidebar.back() != null && sidebar.back().href().equals(href)
                || sidebar.sections().stream().flatMap(section -> section.links().stream())
                        .anyMatch(link -> link.href().equals(href));
    }

    /** A link the shell renders, and whether it is where the reader is. */
    public record Link(String label, String href, boolean current) {

        public Link {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(href, "href");
        }
    }

    /** A run of sidebar links under one heading; a section with no heading lists its links bare. */
    public record Section(String heading, List<Link> links) {

        public Section {
            Objects.requireNonNull(heading, "heading");
            links = List.copyOf(links);
        }
    }

    /**
     * The sidebar: its headed sections, and a {@code back} link out of what it lists when there is one.
     */
    public record Sidebar(Link back, List<Section> sections) {

        /** An empty sidebar, which the shell does not render at all. */
        public static final Sidebar NONE = new Sidebar(null, List.of());

        public Sidebar {
            sections = List.copyOf(sections);
        }

        /** Whether the sidebar has a page to offer. */
        public boolean present() {
            return sections.stream().anyMatch(section -> !section.links().isEmpty());
        }
    }
}
