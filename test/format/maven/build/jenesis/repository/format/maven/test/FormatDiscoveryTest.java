package build.jenesis.repository.format.maven.test;

import module java.base;

import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.Features;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The formats are discovered once, in the SPI's home: {@code declared()} is what the module path carries whatever a
 *  toggle says, {@code installed()} what the configuration switches on, and the two are the same instances. */
class FormatDiscoveryTest {

    @AfterEach
    void reset() {
        Features.reset();
    }

    @Test
    void a_format_configured_off_is_declared_and_not_installed() {
        assertThat(RepositoryFormat.declared()).extracting(RepositoryFormat::name).contains("maven");
        assertThat(RepositoryFormat.installed()).extracting(RepositoryFormat::name).contains("maven");
        assertThat(RepositoryFormat.installed("maven")).isPresent();

        Features.configure(key -> "jenreg.maven".equals(key) ? "false" : null);

        assertThat(RepositoryFormat.declared()).as("the catalogue still lists it").extracting(RepositoryFormat::name).contains("maven");
        assertThat(RepositoryFormat.installed()).as("nothing serves it").extracting(RepositoryFormat::name).doesNotContain("maven");
        assertThat(RepositoryFormat.installed("maven")).isEmpty();
        assertThat(RepositoryFormat.installed(key -> null)).as("a lookup of the caller's own says otherwise")
                .extracting(RepositoryFormat::name).contains("maven");
    }

    @Test
    void every_consumer_shares_the_one_instance_set() {
        RepositoryFormat declared = RepositoryFormat.declared().stream().filter(format -> format.name().equals("maven")).findFirst().orElseThrow();
        RepositoryFormat installed = RepositoryFormat.installed("maven").orElseThrow();
        assertThat(installed).isSameAs(declared);
        assertThat(RepositoryFormat.declared()).as("name-ordered, so no answer depends on the module path's order")
                .isSortedAccordingTo(Comparator.comparing(format -> format.name().toLowerCase(Locale.ROOT)));
    }
}
