package build.jenesis.repository.observation.test;

import build.jenesis.repository.observation.SpiCatalog;
import org.junit.jupiter.api.Test;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The plug-in surface as a model rather than as markup, and the one walk both consoles read it through.
 *
 * <p>It used to be two walks: a console one that could say only which providers existed, and a settings one that also
 * knew each module's installed and enabled state. They enumerated the same graph with the same product-namespace
 * filter and the same ordering, so what is tested here is the enumeration - which services count as this product's,
 * that both levels come out sorted so a page is stable between reads, and that the decoration seam is what carries
 * everything a deployment knows beyond the graph. The decoration itself is a deployment's business and is tested
 * where one exists.
 */
class SpiCatalogTest {

    @Test
    void it_reports_this_products_services_from_the_running_module_graph() {
        List<SpiCatalog> catalog = SpiCatalog.current();

        assertThat(catalog).as("this test module itself provides several").isNotEmpty();
        assertThat(catalog)
                .as("only this product's contracts; the JDK's and the frameworks' would bury them")
                .allSatisfy(spi -> assertThat(spi.spi()).startsWith("build.jenesis."));
        assertThat(catalog)
                .as("a service is named twice - qualified for identity, simple for the heading")
                .allSatisfy(spi -> assertThat(spi.spi()).endsWith(spi.simpleName()));
    }

    @Test
    void both_levels_are_sorted_so_the_page_does_not_reorder_between_reads() {
        List<SpiCatalog> catalog = SpiCatalog.current();

        assertThat(catalog).extracting(SpiCatalog::spi).isSorted();
        assertThat(catalog).allSatisfy(spi ->
                assertThat(spi.implementations()).extracting(SpiCatalog.Implementation::type).isSorted());
    }

    @Test
    void an_implementation_names_the_module_it_arrived_in() {
        assertThat(SpiCatalog.current())
                .as("which is what makes the catalogue actionable: it says where a capability came from")
                .allSatisfy(spi -> assertThat(spi.implementations())
                        .allSatisfy(implementation -> assertThat(implementation.module()).isNotBlank()));
    }

    @Test
    void an_undecorated_catalogue_reports_every_installed_implementation_as_always_on() {
        // A deployment that reads no stored configuration has nothing that could have switched an implementation
        // off, and "always on" is a different statement from "enabled": the screen says the first where a module
        // carries no gate at all. Reporting these as gated-and-enabled would tell an operator there is a key to
        // find when there is not.
        assertThat(SpiCatalog.current()).allSatisfy(spi -> assertThat(spi.implementations())
                .allSatisfy(implementation -> {
                    assertThat(implementation.installed()).isTrue();
                    assertThat(implementation.enabled()).isTrue();
                    assertThat(implementation.gated()).isFalse();
                    assertThat(implementation.enableKey()).isNull();
                    assertThat(implementation.settings()).isEmpty();
                }));
    }

    @Test
    void a_decoration_carries_everything_the_graph_cannot_say() {
        // The seam that replaced the second walk. A deployment answers per module, and what it answers reaches the
        // row unchanged - which is what lets one screen render both an undecorated console and a settings-reading
        // one without knowing which it is looking at.
        SpiCatalog.Setting setting = new SpiCatalog.Setting("jenreg.example", "Example");
        List<SpiCatalog> catalog = SpiCatalog.of(ModuleLayer.boot(), _ ->
                new SpiCatalog.Capability(true, false, "example", List.of(setting)));

        assertThat(catalog).isNotEmpty();
        assertThat(catalog).allSatisfy(spi -> assertThat(spi.implementations())
                .allSatisfy(implementation -> {
                    assertThat(implementation.enabled()).isFalse();
                    assertThat(implementation.gated()).isTrue();
                    assertThat(implementation.enableKey()).isEqualTo("example");
                    assertThat(implementation.settings()).containsExactly(setting);
                }));
    }

    @Test
    void a_decoration_that_answers_nothing_is_read_as_always_on_rather_than_as_off() {
        // A null answer must not become a row reading "not installed, disabled" for a module that is plainly on the
        // graph: the deployment simply knows nothing about it, which is the always-on case.
        assertThat(SpiCatalog.of(ModuleLayer.boot(), _ -> null)).allSatisfy(spi ->
                assertThat(spi.implementations()).allSatisfy(implementation -> {
                    assertThat(implementation.installed()).isTrue();
                    assertThat(implementation.enabled()).isTrue();
                }));
    }
}
