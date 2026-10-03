package build.jenesis.repository.gate.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.MaliciousPackagePolicy;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.compliance.VulnerabilityPolicy;
import build.jenesis.repository.gate.store.ComplianceScreen;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.store.QuotaArtifactStore;
import build.jenesis.repository.store.ReadMemo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two deployments in one process, each publishing through the store it bound, are each judged by their own gate -
 * through the one {@link ComplianceScreen} the process discovered, which is the instance both share. One quarantines a
 * malicious coordinate and the other admits it, so a publish judged by the wrong deployment's gate shows as the wrong
 * disposition. A store neither deployment bound is refused while either is open, and is the inert screen once neither
 * is.
 */
class DeploymentBindingTest {

    private static final String MALICIOUS_PATH = "/gatetest/malicious/stealer-1.0.jar";

    /** Holds the coordinate the test inspector derives from {@link #MALICIOUS_PATH}. The malicious dial is named
     *  rather than inherited: the scenario needs a hold, and the shipped default refuses instead. */
    private static final ComplianceGate HOLDING = new ComplianceGate(
            new VulnerabilityPolicy(Severity.HIGH, Verdict.REJECT),
            AdvisorySource.of(Map.of("com.mal:stealer",
                    List.of(new AdvisorySource.Advisory("MAL-2026-0001", Severity.NONE, true)))))
            .malicious(new MaliciousPackagePolicy().action(Verdict.QUARANTINE));

    /** Knows of no advisory at all, so it admits the same coordinate. */
    private static final ComplianceGate ADMITTING = new ComplianceGate(
            new VulnerabilityPolicy(Severity.HIGH, Verdict.REJECT),
            AdvisorySource.none());

    @TempDir
    Path first;

    @TempDir
    Path second;

    @TempDir
    Path third;

    @Test
    void each_deployment_judges_the_publishes_through_its_own_store() throws IOException {
        List<String> firstVerdicts = new ArrayList<>();
        List<String> secondVerdicts = new ArrayList<>();
        try (ComplianceScreen.Binding holding = ComplianceScreen.binding().gate(() -> HOLDING)
                .verdicts((_, verdict) -> firstVerdicts.add(verdict)).open();
             ComplianceScreen.Binding admitting = ComplianceScreen.binding().gate(() -> ADMITTING)
                     .verdicts((_, verdict) -> secondVerdicts.add(verdict)).open()) {
            // Each deployment's root, bound, and the repository view a publish actually runs over: two scopes down
            // and behind the decorators a composition layers, all of which must forward the binding.
            ArtifactStore held = repository(holding.bind(filesystem(first)));
            ArtifactStore admitted = repository(admitting.bind(filesystem(second)));

            assertThat(publish(held)).as("the holding deployment holds the malicious coordinate")
                    .isEqualTo(PublishInterceptor.Disposition.QUARANTINE);
            assertThat(publish(admitted)).as("the admitting deployment, in the same process, admits it")
                    .isEqualTo(PublishInterceptor.Disposition.ACCEPT);
            assertThat(publish(held)).as("and the holding one still holds, whichever bound last")
                    .isEqualTo(PublishInterceptor.Disposition.QUARANTINE);
            assertThat(firstVerdicts).as("each deployment's meter counts its own publishes")
                    .containsExactly("QUARANTINE", "QUARANTINE");
            assertThat(secondVerdicts).containsExactly("ACCEPT");
        }
    }

    @Test
    void a_store_no_deployment_bound_is_refused_while_one_is_and_inert_once_none_is() throws IOException {
        ArtifactStore unbound = repository(filesystem(third));
        try (ComplianceScreen.Binding _ = ComplianceScreen.binding().gate(() -> HOLDING).open()) {
            assertThatThrownBy(() -> publish(unbound))
                    .as("a publish through a store that lost the deployment's binding is refused, not admitted")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("carries no deployment binding");
        }
        assertThat(publish(unbound)).as("with no deployment bound, the screen over an unbound store is inert")
                .isEqualTo(PublishInterceptor.Disposition.ACCEPT);
    }

    @Test
    void an_explicit_screen_judges_by_its_own_gate_whatever_store_it_is_handed() throws IOException {
        try (ComplianceScreen.Binding admitting = ComplianceScreen.binding().gate(() -> ADMITTING).open()) {
            ArtifactStore store = repository(admitting.bind(filesystem(first)));
            Publication explicit = new Publication(store, List.of(new ComplianceScreen(() -> HOLDING)));
            assertThat(explicit.screen(ArtifactDescriptor.at("test", MALICIOUS_PATH), bytes()).disposition())
                    .isEqualTo(PublishInterceptor.Disposition.QUARANTINE);
        }
    }

    /** A publish of the malicious coordinate through the discovered screen, as a format's own publish runs it. */
    private static PublishInterceptor.Disposition publish(ArtifactStore store) throws IOException {
        return new Publication(store).screen(ArtifactDescriptor.at("test", MALICIOUS_PATH), bytes()).disposition();
    }

    /** A repository's view of {@code root}, as the server derives it: the decorators a composition layers above the
     *  binding, then the tenant and the repository scopes. */
    private static ArtifactStore repository(ArtifactStore root) {
        return ReadMemo.over(new QuotaArtifactStore(root, Long.MAX_VALUE)).scope("default").scope("releases");
    }

    private static ArtifactStore filesystem(Path root) {
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
    }

    private static ByteArrayInputStream bytes() {
        return new ByteArrayInputStream("stealer bytes".getBytes(StandardCharsets.UTF_8));
    }
}
