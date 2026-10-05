package build.jenesis.repository.dependents.requirements.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.closure.RequirementGrammar;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each installed grammar is the one a closure asks for its ecosystem, orders versions as that ecosystem's tooling
 * does, and admits any version to a requirement that states none.
 */
class GrammarOrderTest {

    @Test
    void each_ecosystem_is_ordered_by_its_own_scheme() {
        assertThat(RequirementGrammar.of("Maven").compare("1.0-SNAPSHOT", "1.0")).as("a snapshot precedes its release")
                .isNegative();
        assertThat(RequirementGrammar.of("Maven").compare("1.10", "1.9")).isPositive();
        assertThat(RequirementGrammar.of("npm").compare("1.0.0-beta.2", "1.0.0")).isNegative();
        assertThat(RequirementGrammar.of("crates.io").compare("0.10.0", "0.9.9")).isPositive();
        assertThat(RequirementGrammar.of("Packagist").compare("v2.1", "2.0.9")).isPositive();
        assertThat(RequirementGrammar.of("PyPI").compare("1.0rc1", "1.0")).isNegative();
        assertThat(RequirementGrammar.of("PyPI").compare("1.0", "1.0.0")).isZero();
        assertThat(RequirementGrammar.of("NuGet").compare("1.10.0", "1.9.0")).isPositive();
        assertThat(RequirementGrammar.of("RubyGems").compare("1.2.a", "1.2")).isNegative();
        assertThat(RequirementGrammar.of("Go").compare("v1.10.0", "v1.2.3")).isPositive();
        assertThat(RequirementGrammar.of("Debian").compare("1:1.0", "2.0")).as("the epoch first").isPositive();
        assertThat(RequirementGrammar.of("Debian").compare("1.0~rc1", "1.0")).isNegative();
        assertThat(RequirementGrammar.of("RPM").compare("1.0-10", "1.0-9")).isPositive();
        assertThat(RequirementGrammar.of("Alpine").compare("1.2.3-r1", "1.2.3-r0")).isPositive();
        assertThat(RequirementGrammar.of("conda").compare("1.11", "1.9")).isPositive();
        assertThat(RequirementGrammar.of("Helm").compare("1.10.0", "1.9.0")).isPositive();
    }

    @Test
    void the_grammars_are_installed_for_their_ecosystems_and_admit_an_unstated_requirement() {
        for (String ecosystem : List.of("Maven", "npm", "crates.io", "Packagist", "PyPI", "NuGet", "RubyGems", "Go",
                "Debian", "RPM", "Alpine", "conda", "Helm")) {
            RequirementGrammar grammar = RequirementGrammar.of(ecosystem);
            assertThat(grammar).as(ecosystem).isNotSameAs(RequirementGrammar.FALLBACK);
            assertThat(grammar.admits("", "1.0.0")).isEqualTo(RequirementGrammar.Admission.ADMITS);
        }
        assertThat(RequirementGrammar.of("Maven").admits("[1.0,2.0)", "1.5"))
                .isEqualTo(RequirementGrammar.Admission.ADMITS);
        assertThat(RequirementGrammar.of("npm").admits("^1.2.0", "2.0.0"))
                .isEqualTo(RequirementGrammar.Admission.EXCLUDES);
    }
}
