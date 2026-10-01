package build.jenesis.repository.gateway.contract.test;

import module org.junit.jupiter.api;
import module java.base;
import build.jenesis.repository.format.signing.UnsealedSigningKeysAdvisor;
import build.jenesis.repository.posture.Configuration;
import build.jenesis.repository.posture.PostureReport;
import build.jenesis.repository.posture.Scope;
import build.jenesis.repository.posture.SecurityAdvisory;
import build.jenesis.repository.posture.Severity;
import build.jenesis.repository.settings.SecretCipher;
import static org.assertj.core.api.Assertions.assertThat;

/** A deployment that signs repositories with no master key is told on the posture screen that its keys are stored in
 *  the clear, and one with a master key is told nothing. */
class UnsealedSigningKeysAdvisorTest {

    @Test
    void no_master_key_is_reported_as_unsealed_signing_keys() {
        List<SecurityAdvisory> advised = new UnsealedSigningKeysAdvisor(() -> SecretCipher.of(null))
                .advise(Configuration.ofMap(Map.of()));
        assertThat(advised).singleElement().satisfies(advisory -> {
            assertThat(advisory.id()).isEqualTo("jenrepo.signing.unsealed");
            assertThat(advisory.severity()).isEqualTo(Severity.WARN);
            assertThat(advisory.fix()).contains(SecretCipher.ENV);
        });
    }

    @Test
    void a_master_key_reports_nothing() {
        String key = "k1:" + Base64.getEncoder().encodeToString(new byte[32]);
        assertThat(new UnsealedSigningKeysAdvisor(() -> SecretCipher.of(key)).advise(Configuration.ofMap(Map.of())))
                .isEmpty();
    }

    @Test
    void the_posture_report_asks_it_wherever_the_signing_module_is() {
        // Whatever this JVM's environment holds, the discovered report answers as the advisor does over it.
        boolean keyed = SecretCipher.fromEnvironment().configured();
        assertThat(PostureReport.discover(Configuration.ofMap(Map.of())).scoped(Scope.DEPLOYMENT))
                .extracting(SecurityAdvisory::id)
                .as("a master key is %s", keyed ? "set" : "not set")
                .matches(ids -> ids.contains("jenrepo.signing.unsealed") != keyed);
    }
}
