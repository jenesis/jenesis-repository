package build.jenesis.repository.dependents.requirements.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.closure.spi.RequirementGrammar;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static build.jenesis.repository.closure.spi.RequirementGrammar.Admission.ADMITS;
import static build.jenesis.repository.closure.spi.RequirementGrammar.Admission.EXCLUDES;
import static build.jenesis.repository.closure.spi.RequirementGrammar.Admission.UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a declared requirement admits a version, per ecosystem, as the installed {@link RequirementGrammar} reads it -
 * the one answer the closure and the dependents marker share. The cases that matter most are the unknowns: a
 * marker that said "admits" for an npm dist-tag or "excludes" for a Maven soft requirement would be a confident
 * answer the ecosystem's own client would not give.
 */
class RequirementGrammarTest {

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
    void maven_reads_ranges_as_its_resolver_does(String requirement, String version, RequirementGrammar.Admission verdict) {
        assertThat(RequirementGrammar.of("Maven").admits(requirement, version)).isEqualTo(verdict);
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
                                                      RequirementGrammar.Admission verdict) {
        assertThat(RequirementGrammar.of("npm").admits(requirement, version)).isEqualTo(verdict);
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
    void cargo_reads_a_bare_version_as_a_caret(String requirement, String version, RequirementGrammar.Admission verdict) {
        assertThat(RequirementGrammar.of("crates.io").admits(requirement, version)).isEqualTo(verdict);
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
                                                                       RequirementGrammar.Admission verdict) {
        assertThat(RequirementGrammar.of("Packagist").admits(requirement, version)).isEqualTo(verdict);
    }

    @ParameterizedTest(name = "Ivy {0} for {1}: {2}")
    @CsvSource(delimiter = '|', value = {
            // Ivy's own notations, on the Maven ecosystem an ivy.xml declares its coordinates in.
            "1.0+               | 1.0.7  | ADMITS",
            "1.0+               | 1.1    | EXCLUDES",
            "latest.integration | 3.2.1  | ADMITS",
            "]1.0,2.0[          | 1.0    | EXCLUDES",
            "]1.0,2.0[          | 1.5    | ADMITS",
            "[1.0,2.0[          | 2.0    | EXCLUDES",
            "[1.0,2.0]          | 2.0    | ADMITS"})
    void ivy_revisions_read_on_the_maven_grammar(String requirement, String version, RequirementGrammar.Admission verdict) {
        assertThat(RequirementGrammar.of("Maven").admits(requirement, version)).isEqualTo(verdict);
    }

    @ParameterizedTest(name = "PyPI {0} for {1}: {2}")
    @CsvSource(delimiter = '|', value = {
            "'>=2.0,<3'      | 2.31.0  | ADMITS",
            "'>=2.0,<3'      | 3.0     | EXCLUDES",
            // PEP 440 pads releases with zeros and sorts a release candidate before its release.
            "==1.0           | 1.0.0   | ADMITS",
            "<1.0            | 1.0rc1  | ADMITS",
            "~=1.4.5         | 1.4.9   | ADMITS",
            "~=1.4.5         | 1.5.0   | EXCLUDES",
            "~=2.2           | 2.9     | ADMITS",
            "~=2.2           | 3.0     | EXCLUDES",
            "==1.2.*         | 1.2.7   | ADMITS",
            "==1.2.*         | 1.20    | EXCLUDES",
            "!=1.2.*         | 1.3     | ADMITS",
            "===1.0+local    | 1.0+local | ADMITS",
            // A compatible release needs two release segments, and a direct reference names no version.
            "~=1             | 1.0     | UNKNOWN",
            "'@ https://example.com/pkg.whl' | 1.0 | UNKNOWN"})
    void pypi_reads_pep_440_specifiers(String requirement, String version, RequirementGrammar.Admission verdict) {
        assertThat(RequirementGrammar.of("PyPI").admits(requirement, version)).isEqualTo(verdict);
    }

    @ParameterizedTest(name = "NuGet {0} for {1}: {2}")
    @CsvSource(delimiter = '|', value = {
            // A bare version is the minimum it names, where Maven's is a recommendation.
            "1.0           | 2.5.0  | ADMITS",
            "1.0           | 0.9    | EXCLUDES",
            "'[1.0, 2.0)'  | 1.9.9  | ADMITS",
            "'[1.0, 2.0)'  | 2.0.0  | EXCLUDES",
            "'(,1.0]'      | 1.0.0  | ADMITS",
            "[1.2.3]       | 1.2.3  | ADMITS",
            "[1.2.3]       | 1.2.4  | EXCLUDES",
            "1.*           | 1.8.0  | ADMITS",
            "1.*           | 2.0.0  | EXCLUDES",
            "(1.0)         | 1.0    | UNKNOWN"})
    void nuget_reads_interval_notation_and_a_bare_minimum(String requirement, String version,
                                                          RequirementGrammar.Admission verdict) {
        assertThat(RequirementGrammar.of("NuGet").admits(requirement, version)).isEqualTo(verdict);
    }

    @ParameterizedTest(name = "RubyGems {0} for {1}: {2}")
    @CsvSource(delimiter = '|', value = {
            "~> 1.2.3         | 1.2.9   | ADMITS",
            "~> 1.2.3         | 1.3.0   | EXCLUDES",
            "~> 1.2           | 1.9     | ADMITS",
            "~> 1.2           | 2.0     | EXCLUDES",
            "'>= 1.0, < 2'    | 1.5     | ADMITS",
            "'>= 1.0, < 2'    | 2.0     | EXCLUDES",
            "'>= 0'           | 0.0.1   | ADMITS",
            "2.0              | 2.0     | ADMITS",
            "!= 1.1           | 1.1     | EXCLUDES",
            // A letter segment is a prerelease, before the release it precedes.
            "< 1.2            | 1.2.a   | ADMITS"})
    void rubygems_reads_the_pessimistic_operator(String requirement, String version, RequirementGrammar.Admission verdict) {
        assertThat(RequirementGrammar.of("RubyGems").admits(requirement, version)).isEqualTo(verdict);
    }

    @ParameterizedTest(name = "Go {0} for {1}: {2}")
    @CsvSource(delimiter = '|', value = {
            // A minimum taken as written: what minimal version selection would raise it to is not known here.
            "v1.2.3  | v1.2.3  | ADMITS",
            "v1.2.3  | v1.10.0 | EXCLUDES"})
    void go_takes_a_minimum_as_written(String requirement, String version, RequirementGrammar.Admission verdict) {
        assertThat(RequirementGrammar.of("Go").admits(requirement, version)).isEqualTo(verdict);
    }

    @ParameterizedTest(name = "Debian {0} for {1}: {2}")
    @CsvSource(delimiter = '|', value = {
            "'>= 2.17'      | 2.36-9   | ADMITS",
            "'<< 2.0'       | 2.0      | EXCLUDES",
            "'<< 2.0'       | 2.0~rc1  | ADMITS",
            "'>> 1:0.9'     | 1.0      | EXCLUDES",
            "'= 1.0-1'      | 1.0-1    | ADMITS",
            // The deprecated single-character operators read as dpkg reads them: inclusive.
            "'> 1.0'        | 1.0      | ADMITS",
            "'~ 1.0'        | 1.0      | UNKNOWN"})
    void debian_reads_relations_in_dpkg_order(String requirement, String version, RequirementGrammar.Admission verdict) {
        assertThat(RequirementGrammar.of("Debian").admits(requirement, version)).isEqualTo(verdict);
    }

    @ParameterizedTest(name = "RPM {0} for {1}: {2}")
    @CsvSource(delimiter = '|', value = {
            "'>= 1.0-1'   | 1.0-2    | ADMITS",
            "'< 1.0-1'    | 1.0-2    | EXCLUDES",
            // A requirement naming no release compares a version without its release, as rpm does.
            "'= 1.0'      | 1.0-3    | ADMITS",
            "'> 1.0'      | 1.0-3    | EXCLUDES",
            "'>= 1:2.0'   | 3.0-1    | EXCLUDES",
            "1.2-1        | 1.2-1    | ADMITS"})
    void rpm_reads_relations_in_rpmvercmp_order(String requirement, String version, RequirementGrammar.Admission verdict) {
        assertThat(RequirementGrammar.of("RPM").admits(requirement, version)).isEqualTo(verdict);
    }

    @ParameterizedTest(name = "Alpine {0} for {1}: {2}")
    @CsvSource(delimiter = '|', value = {
            ">=1.2.3-r0  | 1.2.3-r1 | ADMITS",
            "<1.2.3-r1   | 1.2.3-r1 | EXCLUDES",
            "~1.2        | 1.2.9-r0 | ADMITS",
            "~1.2        | 1.20-r0  | EXCLUDES",
            "=1.0-r2     | 1.0-r2   | ADMITS"})
    void alpine_reads_constraints_in_apk_order(String requirement, String version, RequirementGrammar.Admission verdict) {
        assertThat(RequirementGrammar.of("Alpine").admits(requirement, version)).isEqualTo(verdict);
    }

    @ParameterizedTest(name = "conda {0} for {1}: {2}")
    @CsvSource(delimiter = '|', value = {
            "'>=1.11,<2'        | 1.26.4  | ADMITS",
            "'>=1.11,<2'        | 2.0     | EXCLUDES",
            // A bare version is conda's fuzzy match, and a build string after it is no version constraint.
            "1.11               | 1.11.2  | ADMITS",
            "'3.9 h12345_0'     | 3.9.18  | ADMITS",
            "3.9.*              | 3.10.0  | EXCLUDES",
            "'1.0|>=2.0'        | 2.5     | ADMITS",
            "'1.0|>=2.0'        | 1.5     | EXCLUDES",
            "==1.0              | 1.0.1   | EXCLUDES"})
    void conda_reads_match_specs(String requirement, String version, RequirementGrammar.Admission verdict) {
        assertThat(RequirementGrammar.of("conda").admits(requirement, version)).isEqualTo(verdict);
    }

    @ParameterizedTest(name = "Helm {0} for {1}: {2}")
    @CsvSource(delimiter = '|', value = {
            "~1.2.0              | 1.2.9  | ADMITS",
            "~1.2.0              | 1.3.0  | EXCLUDES",
            "'>= 1.0.0, < 2.0.0' | 1.5.0  | ADMITS",
            "'>=1.0.0 <2.0.0'    | 2.0.0  | EXCLUDES",
            "1.2.x               | 1.2.7  | ADMITS",
            "'^1.0 || ^3.0'      | 3.4.0  | ADMITS",
            "'!= 1.2.0'          | 1.3.0  | UNKNOWN"})
    void helm_reads_masterminds_constraints(String requirement, String version, RequirementGrammar.Admission verdict) {
        assertThat(RequirementGrammar.of("Helm").admits(requirement, version)).isEqualTo(verdict);
    }

    @Test
    void a_range_no_installed_grammar_reads_and_a_version_that_does_not_parse_are_unknown() {
        assertThat(RequirementGrammar.of("CocoaPods").admits("~> 2.0", "2.1")).isEqualTo(UNKNOWN);
        assertThat(RequirementGrammar.of("Maven").admits("[1.0,2.0)", "")).isEqualTo(UNKNOWN);
        assertThat(RequirementGrammar.of("PyPI").admits(">=2.0", "not a version")).isEqualTo(UNKNOWN);
    }

    /** The properties every installed grammar keeps, whatever its ecosystem's spelling. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"Alpine", "Debian", "Go", "Helm", "Maven", "NuGet", "Packagist", "PyPI", "RPM", "RubyGems",
            "conda", "crates.io", "npm"})
    void every_installed_grammar_keeps_the_contract(String ecosystem) {
        RequirementGrammar grammar = RequirementGrammar.of(ecosystem);
        assertThat(grammar).as("installed, and found by its ecosystem").isNotSameAs(RequirementGrammar.FALLBACK);
        assertThat(grammar.ecosystem()).isEqualTo(ecosystem);
        assertThat(grammar.admits("", "1.2.3")).as("a requirement stating nothing admits").isEqualTo(ADMITS);
        assertThat(grammar.admits(null, "1.2.3")).isEqualTo(ADMITS);
        assertThat(grammar.admits("@@ not a requirement @@", "1.2.3")).as("unreadable is unknown, never thrown")
                .isEqualTo(UNKNOWN);
        assertThat(grammar.compare("1.2.3", "1.10.0")).as("the ecosystem's order, not the text's").isNegative();
        assertThat(grammar.compare("1.10.0", "1.2.3")).isPositive();
        assertThat(grammar.compare("1.2.3", "1.2.3")).isZero();
    }

    @Test
    void an_ecosystem_no_grammar_reads_admits_what_names_nothing_or_names_the_version_exactly() {
        RequirementGrammar fallback = RequirementGrammar.of("CocoaPods");
        assertThat(fallback).isSameAs(RequirementGrammar.FALLBACK);
        assertThat(fallback.admits("", "2.1")).isEqualTo(ADMITS);
        assertThat(fallback.admits("2.1", "2.1")).isEqualTo(ADMITS);
        assertThat(fallback.admits("2.0", "2.1")).isEqualTo(EXCLUDES);
    }
}
