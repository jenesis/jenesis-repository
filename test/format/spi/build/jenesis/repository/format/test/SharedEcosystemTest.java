package build.jenesis.repository.format.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.EcosystemLayout;
import build.jenesis.repository.format.FormatMarks;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.icon.Mark;
import build.jenesis.repository.icon.Marks;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two installed formats may declare one ecosystem, and every seam that maps an ecosystem back to a layout answers
 * over both of them rather than over whichever discovery yielded first.
 *
 * <p>The composition is the point: an ecosystem names a coordinate space rather than a layout, so one space can be
 * served through two layouts - an Ivy repository and a Maven one are both {@code Maven} to OSV. What must not
 * happen is that such a deployment resolves to one arbitrary claimant, because the seam a coordinate's paths are
 * computed from is the one an eviction deletes under, and a discovery-order answer there means two nodes can sweep
 * one store differently.
 *
 * <p>Driven through {@link java.util.ServiceLoader} rather than over a hand-built list, because "what this
 * composition discovered" is precisely the claim: {@link StubTwinAlphaFormat} and {@link StubTwinBetaFormat} are two
 * distinct formats, with distinct names, both declaring {@code Twin}.
 */
class SharedEcosystemTest {

    private static final String ALPHA = "twin-alpha";
    private static final String BETA = "twin-beta";

    @Test
    void two_installed_formats_may_declare_one_ecosystem() {
        // The composition resolves at all: this used to throw. A deployment offering one coordinate space through
        // two layouts is a legitimate one and must start.
        assertThat(RepositoryFormat.installed(_ -> null))
                .extracting(RepositoryFormat::name)
                .contains(ALPHA, BETA);
    }

    @Test
    void every_claimant_is_discoverable_and_not_just_the_first() {
        // The shape every ecosystem-to-layout consumer takes: filter, do not findFirst. A seam written the other way
        // passes this module's other tests and still loses one of the two layouts.
        assertThat(claimants(RepositoryFormat.installed(_ -> null)))
                .as("both formats declaring 'Twin' are reachable from the installed set")
                .hasSize(2);
    }

    @Test
    void a_shared_ecosystem_draws_the_ecosystems_own_figure_rather_than_one_claimants_mark() {
        // The one lookup that cannot fan out - a row draws a single icon. Taking a claimant would make the icon a
        // property of discovery order, and would change an ecosystem's appearance the day a second format lands.
        assertThat(new FormatMarks(RepositoryFormat.installed(_ -> null)).forEcosystem(StubTwinAlphaFormat.ECOSYSTEM))
                .hasValueSatisfying(mark -> {
                    assertThat(mark.kind()).isEqualTo(Mark.Kind.GENERATED);
                    assertThat(mark.svg()).isEqualTo(Marks.generated(StubTwinAlphaFormat.ECOSYSTEM).svg());
                });
    }

    @Test
    void one_claimant_still_resolves_to_that_formats_own_mark() {
        // The ordinary case is untouched: the fan-out must not cost a lone format its declared figure.
        FormatMarks marks = new FormatMarks(RepositoryFormat.installed(off(BETA)));
        assertThat(marks.forEcosystem(StubTwinAlphaFormat.ECOSYSTEM))
                .hasValueSatisfying(mark -> assertThat(mark.svg()).isEqualTo(Marks.of(alpha()).svg()));
    }

    @Test
    void an_ecosystem_no_installed_format_declares_stays_empty() {
        assertThat(new FormatMarks(RepositoryFormat.installed(_ -> null)).forEcosystem("Nothing")).isEmpty();
    }

    private static List<RepositoryFormat> claimants(List<RepositoryFormat> installed) {
        return installed.stream()
                .filter(format -> format instanceof EcosystemLayout layout
                        && StubTwinAlphaFormat.ECOSYSTEM.equals(layout.ecosystem()))
                .toList();
    }

    private static RepositoryFormat alpha() {
        return RepositoryFormat.installed(off(BETA)).stream()
                .filter(format -> ALPHA.equals(format.name()))
                .findFirst()
                .orElseThrow();
    }

    private static UnaryOperator<String> off(String format) {
        return key -> format.equals(key) ? "false" : null;
    }
}
