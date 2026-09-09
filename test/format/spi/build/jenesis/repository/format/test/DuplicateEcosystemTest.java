package build.jenesis.repository.format.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.RepositoryFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A composition that would serve one ecosystem through two layouts refuses to start.
 *
 * <p>The harm it is refusing is silent: an ecosystem is what neutral code resolves a layout by, and every such
 * lookup takes the first match, so two claimants make the owner of a coordinate a property of the module path's
 * ordering - including for the lookup that computes the request paths an eviction <em>deletes</em> under, which can
 * then differ between two nodes over one store. Both answers look like a layout doing its job, so nothing
 * downstream reports it; the only place it can be caught is where the set is assembled.
 *
 * <p>Driven through {@link java.util.ServiceLoader} rather than over a hand-built list, because "what this
 * composition discovered" is precisely the claim: {@link StubTwinAlphaFormat} and {@link StubTwinBetaFormat} are
 * two distinct formats, with distinct names, both declaring {@code Twin}.
 */
class DuplicateEcosystemTest {

    private static final String ALPHA = "twin-alpha";
    private static final String BETA = "twin-beta";

    @Test
    void two_installed_formats_declaring_one_ecosystem_refuse_to_start() {
        assertThatThrownBy(() -> RepositoryFormat.installed(_ -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ALPHA)
                .hasMessageContaining(BETA)
                .hasMessageContaining(StubTwinAlphaFormat.ECOSYSTEM)
                // The operator is told what to do about it, not only that it happened.
                .hasMessageContaining("jenreg.");
    }

    @Test
    void a_deployment_that_carries_both_and_switches_one_off_starts() {
        // The refusal is judged over the ACTIVE set. Carrying two such formats on the module path is a packaging
        // choice; only serving through both is the error, and the one that is off owns nothing.
        assertThat(RepositoryFormat.installed(off(BETA))).extracting(RepositoryFormat::name).containsExactly(ALPHA);
        assertThat(RepositoryFormat.installed(off(ALPHA))).extracting(RepositoryFormat::name).containsExactly(BETA);
    }

    @Test
    void switching_both_off_leaves_nothing_to_collide() {
        assertThat(RepositoryFormat.installed(key -> ALPHA.equals(key) || BETA.equals(key) ? "false" : null))
                .isEmpty();
    }

    @Test
    void every_declared_format_is_still_discovered_whatever_its_toggle_says() {
        // declared() is the catalogue the settings surface reads, so it must NOT apply the refusal: a deployment
        // has to be able to see the format it just switched off.
        assertThat(RepositoryFormat.declared()).extracting(RepositoryFormat::name).contains(ALPHA, BETA);
    }

    private static UnaryOperator<String> off(String format) {
        return key -> format.equals(key) ? "false" : null;
    }
}
