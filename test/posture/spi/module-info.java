/**
 * Focused unit tests for the security-posture SPI - the self-describing configuration-warning contract, exercised
 * without the server or any network: the {@link build.jenesis.repository.posture.Advisories} id grammar, the {@link
 * build.jenesis.repository.posture.SecurityAdvisory} construction-time validation (id grammar, scope/tenant
 * consistency), the {@link build.jenesis.repository.posture.Configuration} typed helpers, the {@link
 * build.jenesis.repository.posture.PostureReport} critical-first aggregation both from an explicit set of advisors and
 * through the {@link java.util.ServiceLoader} discovery a {@code provides}-declared test advisor proves, and the core
 * {@link build.jenesis.repository.posture.SecurityPosture} seeder's real-key conditions (each seed fires on its actual
 * {@code jenreg.*} key and stays silent on the secure default).
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.posture
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.posture.test {
    requires build.jenesis.repository.posture;
    requires org.junit.jupiter;
    requires org.assertj.core;
    provides build.jenesis.repository.posture.SafetyAdvisor
            with build.jenesis.repository.posture.test.SampleSafetyAdvisor;
}
