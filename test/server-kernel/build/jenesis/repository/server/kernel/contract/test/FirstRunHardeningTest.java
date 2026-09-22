package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.server.kernel.FirstRunHardening;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The first-run guided-hardening step (audit P4). Proves the two load-bearing guarantees: it fires only on a genuinely
 * fresh deploy (no persisted runtime configuration) and never re-nags a configured one, and it selects exactly the
 * installed-but-inert per-tenant gate dimensions from the discovered settings catalogue - structurally, so no dial is
 * named by hand and a dimension an operator has already pinned is passed over.
 */
class FirstRunHardeningTest {

    /** A per-tenant, free-form (STRING) compliance rule that ships blank - the shape of version-floor / policy-rules /
     *  private-names / scorecard-floor: an installed dimension that defines no rule until configured. */
    private static Setting tenantRule(String key) {
        return new Setting(key, "Compliance", key, "the " + key + " rule",
                Setting.Kind.STRING, "", true, Setting.Scope.TENANT);
    }

    /** A representative catalogue mixing the inert per-tenant rule dials with the dials that must NOT be recommended:
     *  a paired action (CHOICE, non-blank default), a per-tenant STRING that ships with a value, a deployment-wide rule
     *  (GLOBAL), and a feed toggle (GLOBAL BOOLEAN). */
    private static List<Setting> catalogue() {
        return List.of(
                tenantRule("version-floor"),
                tenantRule("policy-rules"),
                tenantRule("private-names"),
                tenantRule("scorecard-floor"),
                new Setting("version-floor-action", "Compliance", "Version-floor action", "verdict",
                        Setting.Kind.CHOICE, List.of("QUARANTINE", "REJECT"), "REJECT", true, Setting.Scope.TENANT),
                new Setting("scorecard-warn", "Compliance", "Health warn score", "warn",
                        Setting.Kind.STRING, "5", true, Setting.Scope.TENANT),
                new Setting("deny-list", "Compliance", "Deny list", "global deny",
                        Setting.Kind.STRING, "", true),
                new Setting("github", "Compliance", "GitHub advisories", "feed",
                        Setting.Kind.BOOLEAN, "false", false));
    }

    @Test
    void a_fresh_deploy_is_guided_through_exactly_the_inert_per_tenant_gate_dimensions() {
        FirstRunHardening.Advice advice = FirstRunHardening.assess(true, catalogue(), _ -> null);

        assertThat(advice.firstRun()).isTrue();
        assertThat(advice.hasGuidance()).isTrue();
        assertThat(advice.dimensions()).extracting(FirstRunHardening.Step::key)
                .as("only the blank per-tenant rule dials, discovered structurally - no action, feed, threshold or "
                        + "deployment-wide dial")
                .containsExactly("version-floor", "policy-rules", "private-names", "scorecard-floor");
        assertThat(advice.nextSteps()).as("the general pointers - credentialed feeds and the deploy-wide deny list")
                .isNotEmpty();
    }

    @Test
    void a_dimension_already_pinned_or_set_is_not_recommended() {
        // An operator who pinned version-floor from the environment (or stored it) sees it resolved non-blank, so it is
        // not an open dial and is passed over; the still-blank dimensions remain.
        UnaryOperator<String> effective = key ->
                "version-floor".equals(key) ? "org.apache.logging.log4j:log4j-core >= 2.17.0" : null;
        FirstRunHardening.Advice advice = FirstRunHardening.assess(true, catalogue(), effective);

        assertThat(advice.dimensions()).extracting(FirstRunHardening.Step::key)
                .doesNotContain("version-floor")
                .containsExactly("policy-rules", "private-names", "scorecard-floor");
    }

    @Test
    void a_configured_deploy_gets_no_guidance() {
        FirstRunHardening.Advice advice = FirstRunHardening.assess(false, catalogue(), _ -> null);

        assertThat(advice.firstRun()).isFalse();
        assertThat(advice.hasGuidance()).as("an already-configured deploy is never re-nagged").isFalse();
        assertThat(advice.dimensions()).isEmpty();
        assertThat(advice.nextSteps()).isEmpty();
    }

    @Test
    void first_run_is_no_persisted_settings_and_flips_the_first_time_anything_is_stored(@TempDir Path root)
            throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        Settings settings = new Settings(store);

        assertThat(FirstRunHardening.firstRun(settings)).as("a store with no config document is a fresh deploy")
                .isTrue();

        settings.set("deny-list", "com.evil:*");
        assertThat(FirstRunHardening.firstRun(settings))
                .as("the first stored override makes it a configured deploy").isFalse();
        assertThat(FirstRunHardening.assess(FirstRunHardening.firstRun(settings), catalogue(), _ -> null).hasGuidance())
                .as("and the guidance falls silent, so a configured deploy is never re-nagged").isFalse();

        settings.set("deny-list", null);
        assertThat(FirstRunHardening.firstRun(settings))
                .as("clearing the last override returns it to a fresh-deploy posture").isTrue();
    }
}
