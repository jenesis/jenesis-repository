package build.jenesis.repository.ui.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.ui.Navigation;

import static org.assertj.core.api.Assertions.assertThat;

class NavigationTest {

    @Test
    void a_page_is_offered_when_a_rendered_link_leads_to_it() {
        Navigation navigation = new Navigation(
                List.of(new Navigation.Link("Operations", "/ui/observability", true)),
                new Navigation.Sidebar("Operations", new Navigation.Link("Back", "/ui/", false), List.of(
                        new Navigation.Section("", List.of(
                                new Navigation.Link("Metrics", "/ui/observability", true),
                                new Navigation.Link("Walks", "/ui/walks", false))))));

        assertThat(navigation.offers("/ui/walks")).as("a sidebar link").isTrue();
        assertThat(navigation.offers("/ui/observability")).as("a header link").isTrue();
        assertThat(navigation.offers("/ui/")).as("the sidebar's way back").isTrue();
    }

    @Test
    void a_page_no_link_leads_to_is_not_offered() {
        Navigation navigation = new Navigation(
                List.of(new Navigation.Link("Operations", "/ui/observability", true)),
                new Navigation.Sidebar("Operations", null, List.of(new Navigation.Section("", List.of(
                        new Navigation.Link("Metrics", "/ui/observability", true))))));

        assertThat(navigation.offers("/ui/walks")).as("a module this composition does not carry").isFalse();
        assertThat(navigation.offers("/ui/walks/")).as("matched exactly, not by prefix").isFalse();
        assertThat(Navigation.NONE.offers("/ui/walks")).as("and a page with no navigation offers nothing").isFalse();
    }
}
