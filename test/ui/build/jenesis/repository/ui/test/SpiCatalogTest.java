package build.jenesis.repository.ui.test;

import build.jenesis.repository.ui.SpiCatalog;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The plug-in surface as a model rather than as markup.
 *
 * <p>It used to be a panel that built its own HTML with a {@code StringBuilder} and called an escape helper on every
 * value it interpolated - safe only for as long as nobody forgot one. The screen now renders a record through a
 * template, so escaping is the engine's job, and what is left to test here is the reading: which services count as
 * this product's, and that both levels come out sorted so the page is stable between reads.
 */
class SpiCatalogTest {

    @Test
    void it_reports_this_products_services_from_the_running_module_graph() {
        SpiCatalog catalog = SpiCatalog.current();

        assertThat(catalog.services()).as("this test module itself provides several").isNotEmpty();
        assertThat(catalog.services())
                .as("only this product's contracts; the JDK's and the frameworks' would bury them")
                .allSatisfy(service -> assertThat(service.name()).startsWith("build.jenesis."));
        assertThat(catalog.services())
                .as("a service is named twice - qualified for identity, simple for the heading")
                .allSatisfy(service -> assertThat(service.name()).endsWith(service.simpleName()));
    }

    @Test
    void both_levels_are_sorted_so_the_page_does_not_reorder_between_reads() {
        SpiCatalog catalog = SpiCatalog.current();

        assertThat(catalog.services()).extracting(SpiCatalog.Service::name).isSorted();
        assertThat(catalog.services()).allSatisfy(service ->
                assertThat(service.implementations()).extracting(SpiCatalog.Implementation::name).isSorted());
    }

    @Test
    void an_implementation_names_the_module_it_arrived_in() {
        assertThat(SpiCatalog.current().services())
                .as("which is what makes the catalogue actionable: it says where a capability came from")
                .allSatisfy(service -> assertThat(service.implementations())
                        .allSatisfy(implementation -> assertThat(implementation.module()).isNotBlank()));
    }
}
