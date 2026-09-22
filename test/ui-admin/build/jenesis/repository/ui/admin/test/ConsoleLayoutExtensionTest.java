package build.jenesis.repository.ui.admin.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.ui.ConsoleLayout;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The declared edge between the two consoles, and the two ways it can rot.
 *
 * <p>The admin console is built entirely on the free console's Thymeleaf layout and imported nothing from it, so the
 * dependency was invisible to Java: a module-graph tool saw an unused {@code requires} and a reader saw one nothing
 * seemed to need, while deleting it broke every page at render time and no compiler said so. {@code ConsoleLayout}
 * makes the relationship a declared one; these legs are what keep the declaration true.
 */
class ConsoleLayoutExtensionTest {

    @Test
    void every_extending_console_plugs_into_fragments_the_layout_offers() {
        List<ConsoleLayout.Extension> extensions = ServiceLoader.load(ConsoleLayout.Extension.class)
                .stream().map(ServiceLoader.Provider::get).toList();

        assertThat(extensions)
                .as("the admin console must declare itself an extension of the shared layout, or the edge is back to "
                        + "being a resource path nothing states")
                .isNotEmpty();
        for (ConsoleLayout.Extension extension : extensions) {
            assertThat(ConsoleLayout.FRAGMENTS)
                    .as("%s declares fragments the layout does not offer: %s. A fragment name that does not exist "
                            + "renders as nothing at request time, on every page that uses it, with no error - which "
                            + "is exactly the failure this seam was introduced to convert into a loud one",
                            extension.name(),
                            extension.fragments().stream().filter(f -> !ConsoleLayout.FRAGMENTS.contains(f)).toList())
                    .containsAll(extension.fragments());
        }
    }

    @Test
    void the_admin_console_declares_the_fragments_its_templates_actually_use() throws IOException {
        ConsoleLayout.Extension admin = ServiceLoader.load(ConsoleLayout.Extension.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(extension -> "admin".equals(extension.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the admin console declares no ConsoleLayout.Extension"));

        assertThat(admin.fragments())
                .as("the declaration and the templates disagree; a declaration that drifts from what is rendered is "
                        + "worth less than no declaration, because it reads as verified")
                .containsExactlyInAnyOrderElementsOf(fragmentsReferencedBy(admin));
    }

    /**
     * Every layout fragment the declaring module's own templates reference, read out of the module that ships them.
     *
     * <p><b>This used to read the source tree</b> - {@code Path.of("enterprise/source/ui/templates")} - behind an
     * {@code assumeTrue} that skipped when the directory was absent. Two things were wrong with that and the second
     * is the dangerous one. It answered a question about the product by reading a tree the build never declared as
     * an input, which is the shape this repository removed ninety-two suites' worth of; and the skip meant that the
     * day those templates moved, the test would go green having asserted nothing, silently and permanently.
     *
     * <p>Reading them through the module is exact instead of approximate: the templates enumerated are by
     * construction the ones shipped beside the declaration being checked, so the test cannot be pointed at the wrong
     * tree and cannot quietly find none. A module with no templates is a failure here, not a skip.
     */
    private static Set<String> fragmentsReferencedBy(ConsoleLayout.Extension extension) throws IOException {
        Module module = extension.getClass().getModule();
        ModuleReference reference = module.getLayer().configuration()
                .findModule(module.getName())
                .orElseThrow(() -> new AssertionError("the extension's module is not in the resolved configuration"))
                .reference();
        Pattern referenced = Pattern.compile("~\\{" + ConsoleLayout.TEMPLATE + " :: ([a-zA-Z0-9_]+)");
        Set<String> used = new TreeSet<>();
        int pages = 0;
        try (ModuleReader reader = reference.open(); Stream<String> resources = reader.list()) {
            for (String page : resources.filter(name -> name.startsWith(TEMPLATE_ROOT) && name.endsWith(".html"))
                    .toList()) {
                pages++;
                try (InputStream body = reader.open(page).orElseThrow()) {
                    Matcher matcher = referenced.matcher(new String(body.readAllBytes(), StandardCharsets.UTF_8));
                    while (matcher.find()) {
                        used.add(matcher.group(1));
                    }
                }
            }
        }
        assertThat(pages).as("%s ships no template under %s, so this leg would assert nothing",
                module.getName(), TEMPLATE_ROOT).isPositive();
        return used;
    }

    /** Where Spring Boot resolves a console's own templates from, and therefore where they sit in its module. */
    private static final String TEMPLATE_ROOT = "templates/";
}
