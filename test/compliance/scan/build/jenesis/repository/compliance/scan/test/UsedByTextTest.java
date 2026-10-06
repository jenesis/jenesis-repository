package build.jenesis.repository.compliance.scan.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.closure.spi.Reliance;
import build.jenesis.repository.compliance.scan.VulnerabilityReports;

import static org.assertj.core.api.Assertions.assertThat;

/** How a vulnerable coordinate's dependents are said, the one wording the console and the CLI show. */
class UsedByTextTest {

    @Test
    void a_count_is_said_as_it_is_at_the_cap_as_at_least_and_none_as_nothing() {
        assertThat(artifact(3).usedByText()).isEqualTo("Used by 3");
        assertThat(artifact(Reliance.USED_BY_CAP).usedByText()).as("the count stops at its cap, so it says at least")
                .isEqualTo("Used by " + Reliance.USED_BY_CAP + "+");
        assertThat(artifact(0).usedByText()).as("no dependent known is said as nothing, never as 'used by 0'")
                .isEmpty();
    }

    private static VulnerabilityReports.VulnerableArtifact artifact(int usedBy) {
        return new VulnerabilityReports.VulnerableArtifact("org.acme:lib:1.0", "Maven", "org.acme:lib", usedBy,
                List.of());
    }
}
