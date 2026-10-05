package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.Ecosystems;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.Freshness;
import build.jenesis.repository.compliance.ScreeningMode;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.compliance.VulnerabilityPolicy;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.gate.QuarantineLog;
import build.jenesis.repository.gate.store.InspectionSettingsContributor;
import build.jenesis.repository.gateway.ProxyScreen;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shipped screening mode: a repository that sets none holds what a feed could not clear. Every suite driving a mode
 * names the one it needs, so this is the test that reads the default an operator actually gets - from the catalogue,
 * and from what a screen does when asked with nothing set.
 */
class ScreeningModeDefaultTest {

    @TempDir
    Path root;

    @Test
    void the_declared_setting_defaults_to_hold_and_is_a_repositorys_own() {
        Setting declared = new InspectionSettingsContributor().settings().stream()
                .filter(setting -> ScreeningMode.KEY.equals(setting.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(ScreeningMode.KEY + " is not in the catalogue"));
        assertThat(declared.defaultValue())
                .as("the default the settings screens and the generated reference show")
                .isEqualTo(ScreeningMode.HOLD.name());
        assertThat(declared.scope()).as("a repository's dial, over its tenant's and the deployment's")
                .isEqualTo(Setting.Scope.REPOSITORY);
        assertThat(declared.choices())
                .as("the choices are exactly the modes the code honours")
                .containsExactlyElementsOf(Arrays.stream(ScreeningMode.values()).map(Enum::name).toList());
    }

    @Test
    void a_screen_that_names_no_mode_holds_a_copy_a_feed_could_not_clear() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        AdvisorySource unreachable = new AdvisorySource() {
            @Override
            public Set<String> ecosystems() {
                return Ecosystems.canonical();
            }

            @Override
            public List<Advisory> advisories(String ecosystem, String coordinate, String version) {
                throw new UncheckedIOException(new IOException("the feed answered 503"));
            }

            @Override
            public Freshness freshness() {
                return Freshness.NEVER;
            }
        };
        ComplianceGate gate = new ComplianceGate(new VulnerabilityPolicy(Severity.HIGH, Verdict.REJECT), unreachable);
        String path = "/maven/org/acme/lib/1.0/lib-1.0.pom";
        ProxyFormat.Fetcher upstream = (ProxyFormat.Fetcher.Buffered) (url, headers) -> Optional.of(
                new ProxyFormat.Fetched(200, "<project/>".getBytes(StandardCharsets.UTF_8), Map.of()));

        Optional<ProxyFormat.Fetched> fetched = new ProxyScreen(gate, store, 0).wrap(upstream, path)
                .fetch(URI.create("http://up" + path), Map.of());

        assertThat(fetched).as("nothing set anywhere: the copy is held, not served").isEmpty();
        assertThat(new QuarantineLog(store).events()).singleElement().satisfies(event ->
                assertThat(event.reasons()).anySatisfy(reason ->
                        assertThat(reason).contains(ComplianceGate.FEED_FAILED_CLOSED)));
    }
}
