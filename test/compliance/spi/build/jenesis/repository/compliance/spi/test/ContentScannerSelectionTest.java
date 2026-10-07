package build.jenesis.repository.compliance.spi.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.ContentScanner;
import build.jenesis.repository.compliance.ContentScanner.Fidelity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which content scanners screen a repository: every one the deployment configured that takes what is scanned where
 * the repository names none, none where it names {@value ContentScanner#NONE}, and exactly those it names otherwise -
 * a chain of a cataloguer and a matcher as one, joined in a format the matcher reads in full, or lossily where the
 * repository accepts it - and a name no installed scanner answers to, one the deployment has not configured, a
 * matcher alone or a chain nothing joins failing loudly rather than scanning with fewer.
 */
class ContentScannerSelectionTest {

    private static final String SYFT_JSON = "application/vnd.syft+json";

    private static final String CYCLONEDX = "application/vnd.cyclonedx+json";

    private static final ContentScanner CONFIGURED = StandInScanner.combined("adapter", "adapter-url");

    private static final ContentScanner UNCONFIGURED = StandInScanner.combined("tool", "tool-command");

    private static final ContentScanner CATALOGUER = StandInScanner.cataloguer("syft", "syft-command", SYFT_JSON,
            CYCLONEDX);

    private static final ContentScanner MATCHER = StandInScanner.matcher("grype", "grype-command",
            Map.of(SYFT_JSON, Fidelity.FULL, CYCLONEDX, Fidelity.LOSSY));

    private static final ContentScanner LOSSY_MATCHER = StandInScanner.matcher("partial", "grype-command",
            Map.of(CYCLONEDX, Fidelity.LOSSY));

    private static final ContentScanner STRANGER = StandInScanner.matcher("stranger", "grype-command",
            Map.of("application/spdx+json", Fidelity.FULL));

    private static final List<ContentScanner> INSTALLED = List.of(CONFIGURED, UNCONFIGURED, CATALOGUER, MATCHER,
            LOSSY_MATCHER, STRANGER);

    private static final UnaryOperator<String> DEPLOYMENT = Map.of("adapter-url", "http://adapter:8080")::get;

    private static final UnaryOperator<String> EVERYTHING = Map.of("adapter-url", "u", "tool-command", "c",
            "syft-command", "syft", "grype-command", "grype")::get;

    @Test
    void a_repository_naming_no_scanner_is_scanned_by_every_configured_one() {
        assertThat(selected(_ -> null, DEPLOYMENT)).containsExactly(CONFIGURED);
        assertThat(selected(repository(" "), DEPLOYMENT)).containsExactly(CONFIGURED);
    }

    @Test
    void a_repository_naming_no_scanner_is_handed_to_no_matcher_alone() {
        assertThat(selected(_ -> null, EVERYTHING)).as("a matcher is handed nothing an image scan has")
                .containsExactly(CONFIGURED, UNCONFIGURED, CATALOGUER);
    }

    @Test
    void a_repository_naming_none_is_scanned_by_no_scanner() {
        assertThat(selected(repository("None"), DEPLOYMENT)).isEmpty();
    }

    @Test
    void a_repository_names_its_scanners_once_each_in_its_order() {
        UnaryOperator<String> both = Map.of("adapter-url", "u", "tool-command", "c")::get;

        assertThat(selected(repository("tool, adapter,tool"), both)).containsExactly(UNCONFIGURED, CONFIGURED);
    }

    @Test
    void a_name_no_installed_scanner_answers_to_fails_naming_it_and_what_is_installed() {
        assertThatThrownBy(() -> selected(repository("adapter,clair"), DEPLOYMENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'clair'").hasMessageContaining("not installed")
                .hasMessageContaining("adapter").hasMessageContaining("tool");
    }

    @Test
    void a_name_the_deployment_has_not_configured_fails_naming_what_to_set() {
        assertThatThrownBy(() -> selected(repository("tool"), DEPLOYMENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'tool'").hasMessageContaining("not configured")
                .hasMessageContaining("tool-command");
    }

    @Test
    void a_chain_is_one_scanner_joined_in_the_first_format_its_matcher_reads_in_full() {
        assertThat(selected(repository("syft > grype, adapter"), EVERYTHING))
                .extracting(ContentScanner::name).containsExactly("syft>grype", "adapter");
        ContentScanner chain = selected(repository("syft>grype"), EVERYTHING).getFirst();
        assertThat(chain.consumes()).containsExactly(ContentScanner.Input.IMAGE_MANIFEST);
        assertThat(chain.produces()).containsExactlyInAnyOrder(ContentScanner.Output.VULNERABILITIES,
                ContentScanner.Output.BILL_OF_MATERIALS);
        assertThat(chain.missing(_ -> null)).containsExactly("syft-command", "grype-command");
    }

    @Test
    void a_chain_whose_matcher_reads_its_catalogues_only_lossily_fails_unless_the_loss_is_accepted() {
        assertThatThrownBy(() -> selected(repository("syft>partial"), EVERYTHING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'syft>partial'").hasMessageContaining("in full")
                .hasMessageContaining(ContentScanner.LOSSY).hasMessageContaining(CYCLONEDX);

        assertThat(selected(key -> switch (key) {
            case ContentScanner.SETTING -> "syft>partial";
            case ContentScanner.LOSSY -> "true";
            default -> null;
        }, EVERYTHING)).extracting(ContentScanner::name).containsExactly("syft>partial");
    }

    @Test
    void a_chain_no_format_joins_fails_whatever_loss_is_accepted() {
        assertThatThrownBy(() -> selected(key -> switch (key) {
            case ContentScanner.SETTING -> "syft>stranger";
            case ContentScanner.LOSSY -> "true";
            default -> null;
        }, EVERYTHING)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'syft>stranger'").hasMessageContaining("no format at all")
                .hasMessageContaining(SYFT_JSON).hasMessageContaining("application/spdx+json");
    }

    @Test
    void a_matcher_named_alone_fails_saying_to_chain_it() {
        assertThatThrownBy(() -> selected(repository("grype"), EVERYTHING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'grype'").hasMessageContaining("<cataloguer>>grype");
    }

    @Test
    void a_chain_of_the_wrong_roles_or_an_uninstalled_part_fails_naming_it() {
        assertThatThrownBy(() -> selected(repository("grype>syft"), EVERYTHING))
                .hasMessageContaining("'grype>syft'").hasMessageContaining("makes no catalogue");
        assertThatThrownBy(() -> selected(repository("syft>adapter"), EVERYTHING))
                .hasMessageContaining("'syft>adapter'").hasMessageContaining("handed no bill");
        assertThatThrownBy(() -> selected(repository("syft>clair"), EVERYTHING))
                .hasMessageContaining("'syft>clair'").hasMessageContaining("'clair', which is not installed");
        assertThatThrownBy(() -> selected(repository("syft>grype>clair"), EVERYTHING))
                .hasMessageContaining("one cataloguer and one matcher");
    }

    @Test
    void a_chain_the_deployment_has_not_configured_fails_naming_what_to_set() {
        assertThatThrownBy(() -> selected(repository("syft>grype"), Map.of("syft-command", "syft")::get))
                .hasMessageContaining("'syft>grype'").hasMessageContaining("not configured")
                .hasMessageContaining("grype-command");
    }

    private static List<ContentScanner> selected(UnaryOperator<String> repository, UnaryOperator<String> deployment) {
        return ContentScanner.selected(INSTALLED, repository, deployment, ContentScanner.Input.IMAGE_MANIFEST);
    }

    private static UnaryOperator<String> repository(String selection) {
        return key -> ContentScanner.SETTING.equals(key) ? selection : null;
    }
}
