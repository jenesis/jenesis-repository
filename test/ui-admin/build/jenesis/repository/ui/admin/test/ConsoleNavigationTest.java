package build.jenesis.repository.ui.admin.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.ui.NavEntry;
import build.jenesis.repository.ui.NavEntry.Group;
import build.jenesis.repository.ui.Navigation;
import build.jenesis.repository.ui.RepositoryPage;
import build.jenesis.repository.ui.RepositoryPage.Topic;
import build.jenesis.repository.ui.admin.web.ConsoleNavigation;

import static org.assertj.core.api.Assertions.assertThat;

/** Where a reader is, decided from the path alone: the page, its group, and a repository's own sidebar. */
class ConsoleNavigationTest {

    private static final List<NavEntry> ENTRIES = List.of(
            new NavEntry("All repositories", "/ui/repositories", Group.REPOSITORIES),
            new NavEntry("Limits", "/ui/limits", Group.REPOSITORIES),
            new NavEntry("Credentials", "/ui/credentials", Group.ACCESS),
            new NavEntry("Settings", "/ui/settings", Group.SETTINGS),
            new NavEntry("Modules", "/ui/settings/modules", Group.SETTINGS));

    private static final List<RepositoryPage> PAGES = List.of(
            new RepositoryPage("Overview", "", Topic.CONTENTS),
            new RepositoryPage("Pins", "/pins", Topic.LIFECYCLE),
            new RepositoryPage("Quarantine", "/quarantine", Topic.REVIEW));

    @Test
    void the_longest_entry_path_the_request_lies_below_is_the_page_the_reader_is_on() {
        Navigation navigation = resolve("/ui/settings/modules/contracts");

        assertThat(navigation.groups()).filteredOn(Navigation.Link::current)
                .extracting(Navigation.Link::label).containsExactly("Settings");
        assertThat(links(navigation)).filteredOn(Navigation.Link::current)
                .extracting(Navigation.Link::label).as("Modules, not Settings, though both are ancestors")
                .containsExactly("Modules");
    }

    @Test
    void a_path_below_a_repository_lists_its_pages_under_their_topics() {
        Navigation navigation = resolve("/ui/repositories/releases/pins");

        assertThat(navigation.sidebar().title()).isEqualTo("releases");
        assertThat(navigation.sidebar().back().href()).isEqualTo("/ui/repositories");
        assertThat(navigation.sidebar().sections()).extracting(Navigation.Section::heading)
                .as("the repository's name heads the first topic").containsExactly("", "Review", "Lifecycle");
        assertThat(links(navigation)).filteredOn(Navigation.Link::current)
                .extracting(Navigation.Link::href).containsExactly("/ui/repositories/releases/pins");
    }

    @Test
    void the_repositories_group_lists_its_pages_and_leaves_the_repositories_to_the_screen() {
        Navigation navigation = resolve("/ui/limits");

        assertThat(links(navigation)).extracting(Navigation.Link::label)
                .as("the repositories are the screen's table, which filters them")
                .containsExactly("All repositories", "Limits");
    }

    @Test
    void a_repository_named_like_a_page_of_the_collection_is_a_repository() {
        assertThat(ConsoleNavigation.repository("/ui/repositories/quota")).isEqualTo("quota");
        assertThat(ConsoleNavigation.repository("/ui/repositories")).isNull();
        assertThat(ConsoleNavigation.repository("/ui/repositories/")).isNull();
    }

    @Test
    void a_path_no_entry_holds_has_no_sidebar() {
        Navigation navigation = resolve("/ui/elsewhere");

        assertThat(navigation.sidebar().present()).isFalse();
        assertThat(navigation.groups()).noneMatch(Navigation.Link::current);
    }

    private static Navigation resolve(String path) {
        return ConsoleNavigation.resolve(ENTRIES, PAGES, path);
    }

    private static List<Navigation.Link> links(Navigation navigation) {
        return navigation.sidebar().sections().stream().flatMap(section -> section.links().stream()).toList();
    }
}
