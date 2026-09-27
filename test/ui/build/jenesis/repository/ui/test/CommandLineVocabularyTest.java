package build.jenesis.repository.ui.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cli.Commands;
import build.jenesis.repository.ui.NavEntry;
import build.jenesis.repository.ui.RepositoryPage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The command line's help is organised the way the console's navigation is, so that a reader who knows where a page
 * sits on one surface finds its command under the same heading on the other.
 *
 * <p>The console is repository-first: its groups are Repositories, Build cache, Access, Operations and Settings, and
 * inside a repository its pages are filed under Contents, Review, Risk, Provenance and Lifecycle. The command line
 * stays noun-first with the repository as an argument, which is how a command line is used, so its headings are the
 * repository's topics where the console has its Repositories group, then the other groups - after Session, which is
 * the command line's alone. Both sides are read from their declarations here rather than written down, so renaming
 * or reordering a group or a topic on either side fails until the other follows.
 *
 * <p>It lives beside the console's tests rather than the command line's because the client is a thin HTTP client
 * shipped as its own launcher jar: it must not require the console, and a test module can see both.
 */
class CommandLineVocabularyTest {

    @Test
    void the_help_sections_are_the_console_topics_and_groups_in_the_console_order() {
        List<String> console = new ArrayList<>();
        console.add("Session");
        for (NavEntry.Group group : NavEntry.Group.values()) {
            if (group == NavEntry.Group.REPOSITORIES) {
                for (RepositoryPage.Topic topic : RepositoryPage.Topic.values()) {
                    console.add(topic.label());
                }
            } else {
                console.add(group.label());
            }
        }

        assertThat(Commands.SECTIONS.stream().map(Commands.Section::title).toList())
                .as("the help's headings, in order, against the console's topics and groups")
                .containsExactlyElementsOf(console);
        assertThat(Commands.SECTIONS)
                .as("and every heading files at least one command, or it is a heading for nothing")
                .allSatisfy(section -> assertThat(section.nouns()).isNotEmpty());
    }
}
