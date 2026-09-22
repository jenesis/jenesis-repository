package build.jenesis.repository.gate.contract.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.Freshness;
import build.jenesis.repository.compliance.ExploitProbabilitySource;
import build.jenesis.repository.compliance.HealthSource;
import build.jenesis.repository.compliance.KnownExploitedSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every signal merge propagates a feed that fails, and none of them contains it.
 *
 * <p>The four {@code combined(...)} primitives are one shape four times: query every enabled feed, merge, and
 * <em>do not contain</em> a feed that throws. That last part is the load-bearing half and it is deliberate - a
 * contained feed answers "nothing for this coordinate", which is byte-for-byte the shape of a clean artifact, so
 * containing a failure here turns "I could not check" into "there is nothing against it". The gate then admits on
 * the strength of an answer nobody gave.
 *
 * <p>Only the advisory leg said so. {@code GateHostContractTest} drives it end to end through the gate, and the
 * other three held the property by inheritance and by nobody having changed them - which is exactly the condition
 * under which a well-meaning {@code catch (Exception)} gets added to make the gate "more robust". This states it
 * for all four at the primitive, so the next such edit fails here rather than shipping.
 */
class SignalMergeContainmentTest {

    private static final RuntimeException PLANTED =
            new UncheckedIOException(new IOException("planted: this feed is rate limited"));

    @TestFactory
    Stream<DynamicTest> every_signal_merge_propagates_a_feed_that_fails() {
        Map<String, Runnable> merges = new LinkedHashMap<>();

        merges.put("AdvisorySource", () -> AdvisorySource.combined(
                new AdvisorySource() {
                    @Override public List<AdvisorySource.Advisory> advisories(String eco, String coordinate, String version) {
                        return List.of();
                    }
                    @Override public Freshness freshness() { return Freshness.NEVER; }
                },
                new AdvisorySource() {
                    @Override public List<AdvisorySource.Advisory> advisories(String eco, String coordinate, String version) {
                        throw PLANTED;
                    }
                    @Override public Freshness freshness() { return Freshness.NEVER; }
                }).advisories("maven", "com.example:lib", "1.0"));

        merges.put("KnownExploitedSource", () -> KnownExploitedSource.combined(
                new KnownExploitedSource() {
                    @Override public boolean contains(String cve) { return false; }
                    @Override public Freshness freshness() { return Freshness.NEVER; }
                },
                new KnownExploitedSource() {
                    @Override public boolean contains(String cve) { throw PLANTED; }
                    @Override public Freshness freshness() { return Freshness.NEVER; }
                }).contains("CVE-2021-44228"));

        merges.put("ExploitProbabilitySource", () -> ExploitProbabilitySource.combined(
                new ExploitProbabilitySource() {
                    @Override public Map<String, Double> scores(Collection<String> cves) { return Map.of(); }
                    @Override public Freshness freshness() { return Freshness.NEVER; }
                },
                new ExploitProbabilitySource() {
                    @Override public Map<String, Double> scores(Collection<String> cves) { throw PLANTED; }
                    @Override public Freshness freshness() { return Freshness.NEVER; }
                }).scores(List.of("CVE-2021-44228")));

        merges.put("HealthSource", () -> HealthSource.combined(
                new HealthSource() {
                    @Override public Optional<HealthSource.Health> health(String eco, String coordinate) {
                        return Optional.empty();
                    }
                    @Override public Freshness freshness() { return Freshness.NEVER; }
                },
                new HealthSource() {
                    @Override public Optional<HealthSource.Health> health(String eco, String coordinate) { throw PLANTED; }
                    @Override public Freshness freshness() { return Freshness.NEVER; }
                }).health("maven", "com.example:lib"));

        return merges.entrySet().stream().map(merge -> DynamicTest.dynamicTest(merge.getKey(), () ->
                assertThatThrownBy(() -> merge.getValue().run())
                        .as("%s.combined must let a failing feed through: a contained failure answers 'nothing "
                                + "for this coordinate', which is what a CLEAN artifact answers, so the gate would "
                                + "admit on a check that never ran", merge.getKey())
                        .isSameAs(PLANTED)));
    }

    @Test
    void a_merge_over_healthy_feeds_still_answers() {
        // The vacuity guard: if merging always threw, every row above would pass for the wrong reason.
        KnownExploitedSource healthy = new KnownExploitedSource() {
            @Override public boolean contains(String cve) { return true; }
            @Override public Freshness freshness() { return Freshness.NEVER; }
        };
        assertThat(KnownExploitedSource.combined(healthy, healthy).contains("CVE-2021-44228"))
                .as("a merge over feeds that answer returns their answer rather than throwing")
                .isTrue();
    }
}
