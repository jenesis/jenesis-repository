package build.jenesis.repository.compliance.spi.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.ContentScanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which content scanners screen a repository: every one the deployment configured where the repository names none,
 * none where it names {@value ContentScanner#NONE}, and exactly those it names otherwise - a name no installed scanner
 * answers to, or one the deployment has not configured, failing loudly rather than scanning with fewer.
 */
class ContentScannerSelectionTest {

    private static final ContentScanner CONFIGURED = scanner("adapter", "adapter-url");

    private static final ContentScanner UNCONFIGURED = scanner("tool", "tool-command");

    private static final List<ContentScanner> INSTALLED = List.of(CONFIGURED, UNCONFIGURED);

    private static final UnaryOperator<String> DEPLOYMENT = Map.of("adapter-url", "http://adapter:8080")::get;

    @Test
    void a_repository_naming_no_scanner_is_scanned_by_every_configured_one() {
        assertThat(ContentScanner.selected(INSTALLED, _ -> null, DEPLOYMENT)).containsExactly(CONFIGURED);
        assertThat(ContentScanner.selected(INSTALLED, repository(" "), DEPLOYMENT)).containsExactly(CONFIGURED);
    }

    @Test
    void a_repository_naming_none_is_scanned_by_no_scanner() {
        assertThat(ContentScanner.selected(INSTALLED, repository("None"), DEPLOYMENT)).isEmpty();
    }

    @Test
    void a_repository_names_its_scanners_once_each_in_its_order() {
        UnaryOperator<String> both = Map.of("adapter-url", "u", "tool-command", "c")::get;

        assertThat(ContentScanner.selected(INSTALLED, repository("tool, adapter,tool"), both))
                .containsExactly(UNCONFIGURED, CONFIGURED);
    }

    @Test
    void a_name_no_installed_scanner_answers_to_fails_naming_it_and_what_is_installed() {
        assertThatThrownBy(() -> ContentScanner.selected(INSTALLED, repository("adapter,grype"), DEPLOYMENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'grype'").hasMessageContaining("not installed")
                .hasMessageContaining("adapter").hasMessageContaining("tool");
    }

    @Test
    void a_name_the_deployment_has_not_configured_fails_naming_what_to_set() {
        assertThatThrownBy(() -> ContentScanner.selected(INSTALLED, repository("tool"), DEPLOYMENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'tool'").hasMessageContaining("not configured")
                .hasMessageContaining("tool-command");
    }

    private static UnaryOperator<String> repository(String selection) {
        return key -> ContentScanner.SETTING.equals(key) ? selection : null;
    }

    /** A scanner named {@code name}, configured where {@code key} is set. */
    private static ContentScanner scanner(String name, String key) {
        return new ContentScanner() {

            @Override
            public String name() {
                return name;
            }

            @Override
            public Set<Input> consumes() {
                return Set.of(Input.IMAGE_MANIFEST);
            }

            @Override
            public Set<Output> produces() {
                return Set.of(Output.VULNERABILITIES);
            }

            @Override
            public List<String> missing(UnaryOperator<String> config) {
                String value = config.apply(key);
                return value == null || value.isBlank() ? List.of(key) : List.of();
            }

            @Override
            public Session open(UnaryOperator<String> config) {
                throw new UnsupportedOperationException("selection opens no session");
            }

            @Override
            public String toString() {
                return name;
            }
        };
    }
}
