package build.jenesis.repository.ui.admin.web;

import module java.base;
import build.jenesis.repository.ui.NavEntry;
import build.jenesis.repository.ui.Navigation;
import build.jenesis.repository.ui.RepositoryPage;

/**
 * The two navigation levels of one request, from the pages a reader may open and the path they asked for.
 *
 * <p>Where a reader is follows from the path alone: the page whose path is the longest prefix of the request path,
 * on a segment boundary, is the page they are on, and its group is the group they are in. A path below
 * {@code /repositories/<name>} puts them inside that repository, and the sidebar then lists the repository's pages
 * rather than the group's. Nothing here reads the store or asks a module anything; the caller has already decided
 * which pages this reader may see.
 */
final class ConsoleNavigation {

    /** The collection the repository pages live below. */
    static final String REPOSITORIES = "/repositories";

    /** The names below {@link #REPOSITORIES} that are not repositories but actions on the collection. */
    private static final Set<String> COLLECTION_ACTIONS = Set.of("quota", "rate-limit");

    private ConsoleNavigation() {
    }

    /**
     * The navigation for {@code path}, given the top-level {@code entries} and the repository {@code pages} the reader
     * may open, each in the order it is to be listed.
     */
    static Navigation resolve(List<NavEntry> entries, List<RepositoryPage> pages, String path) {
        NavEntry current = current(entries, path);
        String repository = repository(path);
        NavEntry.Group group = repository != null ? NavEntry.Group.REPOSITORIES
                : current == null ? null : current.group();
        List<Navigation.Link> groups = new ArrayList<>();
        for (NavEntry.Group candidate : NavEntry.Group.values()) {
            entries.stream().filter(entry -> entry.group() == candidate).findFirst().ifPresent(first ->
                    groups.add(new Navigation.Link(candidate.label(), first.path(), candidate == group)));
        }
        if (repository != null) {
            return new Navigation(groups, repositorySidebar(pages, repository, path));
        }
        if (group == null) {
            return new Navigation(groups, Navigation.Sidebar.NONE);
        }
        List<Navigation.Link> links = entries.stream()
                .filter(entry -> entry.group() == group)
                .map(entry -> new Navigation.Link(entry.label(), entry.path(), entry == current))
                .toList();
        return new Navigation(groups, new Navigation.Sidebar(group.label(), null,
                List.of(new Navigation.Section("", links))));
    }

    /** The sidebar inside one repository: its pages under their topics, and the way back to the collection. */
    private static Navigation.Sidebar repositorySidebar(List<RepositoryPage> pages, String repository, String path) {
        String base = REPOSITORIES + "/" + repository;
        String relative = path.substring(base.length());
        RepositoryPage here = null;
        for (RepositoryPage page : pages) {
            boolean matches = page.path().isEmpty() ? relative.isEmpty() || relative.equals("/")
                    : within(relative, page.path());
            if (matches && (here == null || page.path().length() > here.path().length())) {
                here = page;
            }
        }
        List<Navigation.Section> sections = new ArrayList<>();
        for (RepositoryPage.Topic topic : RepositoryPage.Topic.values()) {
            List<Navigation.Link> links = new ArrayList<>();
            for (RepositoryPage page : pages) {
                if (page.topic() == topic) {
                    links.add(new Navigation.Link(page.label(), base + page.path(), page == here));
                }
            }
            if (!links.isEmpty()) {
                sections.add(new Navigation.Section(topic.label(), links));
            }
        }
        return new Navigation.Sidebar(repository, new Navigation.Link("All repositories", REPOSITORIES, false),
                sections);
    }

    /** The entry the reader is on: the longest entry path that is {@code path} or one of its ancestors. */
    private static NavEntry current(List<NavEntry> entries, String path) {
        NavEntry found = null;
        for (NavEntry entry : entries) {
            if (within(path, entry.path()) && (found == null || entry.path().length() > found.path().length())) {
                found = entry;
            }
        }
        return found;
    }

    /** The repository {@code path} is inside, or {@code null} when it is not inside one. */
    static String repository(String path) {
        if (!path.startsWith(REPOSITORIES + "/")) {
            return null;
        }
        String rest = path.substring(REPOSITORIES.length() + 1);
        int slash = rest.indexOf('/');
        String name = slash < 0 ? rest : rest.substring(0, slash);
        return name.isEmpty() || COLLECTION_ACTIONS.contains(name) ? null : name;
    }

    /** Whether {@code path} is {@code prefix} or lies below it, on a segment boundary. */
    private static boolean within(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix.endsWith("/") ? prefix : prefix + "/");
    }
}
