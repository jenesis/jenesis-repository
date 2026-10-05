package build.jenesis.repository.dependents.requirements.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.dependents.requirements.Requirements;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static build.jenesis.repository.dependents.requirements.Requirements.Verdict.ADMITS;
import static build.jenesis.repository.dependents.requirements.Requirements.Verdict.EXCLUDES;
import static build.jenesis.repository.dependents.requirements.Requirements.Verdict.UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a declared requirement admits a version, per ecosystem. The cases that matter most are the unknowns: a
 * marker that said "admits" for an npm dist-tag or "excludes" for a Maven soft requirement would be a confident
 * answer the ecosystem's own client would not give.
 */
class RequirementsTest {

    @ParameterizedTest(name = "Maven {0} for {1}: {2}")
    @CsvSource(delimiter = '|', value = {
            "[1.0,2.0)       | 1.5    | ADMITS",
            "[1.0,2.0)       | 2.0    | EXCLUDES",
            "[2.14.0,)       | 2.17.1 | ADMITS",
            "'(,1.0],[1.2,)' | 1.1    | EXCLUDES",
            "'(,1.0],[1.2,)' | 1.3    | ADMITS",
            // A bare version is a recommendation mediation may override: it admits itself and decides nothing else.
            "2.14.1          | 2.14.1 | ADMITS",
            "2.14.1          | 2.17.1 | UNKNOWN",
            "[1.0            | 1.0    | UNKNOWN"})
    void maven_reads_ranges_as_its_resolver_does(String requirement, String version, Requirements.Verdict verdict) {
        assertThat(Requirements.admits("Maven", requirement, version)).isEqualTo(verdict);
    }

    @ParameterizedTest(name = "npm {0} for {1}: {2}")
    @CsvSource(delimiter = '|', value = {
            "^4.17.0            | 4.17.21 | ADMITS",
            "^4.17.0            | 5.0.0   | EXCLUDES",
            "~1.3.0             | 1.3.9   | ADMITS",
            "~1.3.0             | 1.4.0   | EXCLUDES",
            "1.x                | 1.9.0   | ADMITS",
            "1.2.3 - 2.3.4      | 2.0.0   | ADMITS",
            "'>=1 <2 || 3.x'    | 3.1.0   | ADMITS",
            "'>= 1.2.3'         | 1.2.3   | ADMITS",
            "*                  | 0.0.1   | ADMITS",
            // Not ranges: what they name is decided by the registry or a source, never by a version comparison.
            "latest             | 1.0.0   | UNKNOWN",
            "'~1.2 || beta'     | 1.2.0   | UNKNOWN",
            "github:acme/lib    | 1.0.0   | UNKNOWN",
            "file:../lib        | 1.0.0   | UNKNOWN",
            "npm:other@^1       | 1.0.0   | UNKNOWN",
            "workspace:*        | 1.0.0   | UNKNOWN",
            "^1.0.0             | 1.0     | UNKNOWN"})
    void npm_reads_ranges_and_refuses_what_is_not_one(String requirement, String version,
                                                      Requirements.Verdict verdict) {
        assertThat(Requirements.admits("npm", requirement, version)).isEqualTo(verdict);
    }

    @ParameterizedTest(name = "Cargo {0} for {1}: {2}")
    @CsvSource(delimiter = '|', value = {
            // A bare version in a Cargo.toml is a caret requirement - where npm would read it as exact.
            "1.2.3          | 1.9.0 | ADMITS",
            "1.2.3          | 2.0.0 | EXCLUDES",
            "0.2.3          | 0.2.9 | ADMITS",
            "0.2.3          | 0.3.0 | EXCLUDES",
            "'>=1.2, <1.5'  | 1.4.9 | ADMITS",
            "'>=1.2, <1.5'  | 1.5.0 | EXCLUDES",
            "~1.2           | 1.3.0 | EXCLUDES",
            "=1.2.3         | 1.2.4 | EXCLUDES",
            "1.*            | 1.8.0 | ADMITS",
            "*              | 3.0.0 | ADMITS",
            // No union in Cargo's grammar: whatever wrote this, it was not Cargo.
            "'1.0 || 2.0'   | 2.0.0 | UNKNOWN"})
    void cargo_reads_a_bare_version_as_a_caret(String requirement, String version, Requirements.Verdict verdict) {
        assertThat(Requirements.admits("crates.io", requirement, version)).isEqualTo(verdict);
    }

    @ParameterizedTest(name = "Composer {0} for {1}: {2}")
    @CsvSource(delimiter = '|', value = {
            // A two-part tilde runs to the next major in Composer, where npm's stops at the next minor.
            "~1.2                    | 1.9.0  | ADMITS",
            "~1.2                    | 2.0.0  | EXCLUDES",
            "~1.2.3                  | 1.3.0  | EXCLUDES",
            // A bare version is exact however short, where npm reads 1.2 as every 1.2.x.
            "1.2                     | 1.2.0  | ADMITS",
            "1.2                     | 1.2.1  | EXCLUDES",
            "'^2.0 | ^3.0'           | 3.1.0  | ADMITS",
            "'>=1.0,<1.1 || >=1.2'   | 1.0.5  | ADMITS",
            "'>=1.0 <1.1'            | 1.1.0  | EXCLUDES",
            "1.0.*                   | 1.0.9  | ADMITS",
            "1.0 - 2.0               | 2.0.5  | ADMITS",
            "^1.0                    | v1.2.0 | ADMITS",
            "'>= 1.0'                | 1.0.0  | ADMITS",
            // No node-semver meaning: a stability flag, a branch, a not-equal.
            "^1.0@dev                | 1.0.0  | UNKNOWN",
            "dev-main                | 1.0.0  | UNKNOWN",
            "!=1.0                   | 2.0.0  | UNKNOWN"})
    void composer_reads_its_tilde_and_its_bare_version_as_composer_does(String requirement, String version,
                                                                       Requirements.Verdict verdict) {
        assertThat(Requirements.admits("Packagist", requirement, version)).isEqualTo(verdict);
    }

    @Test
    void an_ecosystem_with_no_grammar_here_and_a_requirement_stating_nothing_are_unknown() {
        assertThat(Requirements.admits("PyPI", ">=2.0", "2.1")).isEqualTo(UNKNOWN);
        assertThat(Requirements.admits("npm", "", "1.0.0")).isEqualTo(UNKNOWN);
        assertThat(Requirements.admits("Maven", "[1.0,2.0)", "")).isEqualTo(UNKNOWN);
        assertThat(Requirements.ecosystems()).containsExactly("Maven", "Packagist", "crates.io", "npm");
    }

    @Test
    void the_verdicts_are_the_three_a_surface_renders() {
        assertThat(List.of(Requirements.Verdict.values())).containsExactly(ADMITS, EXCLUDES, UNKNOWN);
    }
}
