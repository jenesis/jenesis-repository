package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.settings.ModuleCapability;
import build.jenesis.repository.settings.ModuleSettings;
import build.jenesis.repository.settings.SettingsContributor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The generic module-capability model behind the modules console and {@code /api/capabilities}: the installed and
 * enabled state of every discovered module, enumerated from the {@code SettingsContributor} contributors rather than a
 * maintained table, plus a not-installed row for any module named only by a leftover stored document. Two real
 * contributors in different JPMS modules on the test path (the retention sweep and the download tracker) exercise
 * attribution and the enabled/live distinction without a fixture.
 */
class ModuleCapabilityTest {

    private static final String CLEANUP = "build.jenesis.repository.cleanup.task";
    private static final String DOWNLOADS = "build.jenesis.repository.downloads";

    @Test
    void the_module_list_reports_installed_and_enabled_per_module() {
        // The retention sweep is switched on; the download tracker is left at its (off) default.
        UnaryOperator<String> effective = key -> "scheduled-cleanup".equals(key) ? "true" : null;
        Map<String, ModuleCapability> byModule = index(ModuleCapability.resolve(effective, Set.of()));

        ModuleCapability cleanup = byModule.get(CLEANUP);
        assertThat(cleanup).as("the retention-sweep module is discovered").isNotNull();
        assertThat(cleanup.installed()).isTrue();
        assertThat(cleanup.enableKey()).isEqualTo("scheduled-cleanup");
        assertThat(cleanup.enabled()).as("its gate is switched on").isTrue();
        assertThat(cleanup.live()).as("a re-resolved maintenance pass toggles live").isTrue();
        assertThat(cleanup.toggleable()).as("a boolean gate renders as a switch").isTrue();

        ModuleCapability downloads = byModule.get(DOWNLOADS);
        assertThat(downloads.installed()).isTrue();
        assertThat(downloads.enableKey()).isEqualTo("track-downloads");
        assertThat(downloads.enabled()).as("its gate is on unless switched off").isTrue();
        assertThat(downloads.live()).as("a tracker owns a thread and stays restart-bound").isFalse();
    }

    @Test
    void attribution_matches_the_declaring_module_for_two_contributors_in_different_modules() {
        Map<String, String> attribution = SettingsContributor.attribution();
        assertThat(attribution.get("scheduled-cleanup")).isEqualTo(CLEANUP);
        assertThat(attribution.get("track-downloads")).isEqualTo(DOWNLOADS);

        // The same attribution drives the modules() grouping and each module's enablement gate.
        Map<String, ModuleSettings> modules = new HashMap<>();
        for (ModuleSettings module : SettingsContributor.modules()) {
            modules.put(module.module(), module);
        }
        assertThat(modules.get(CLEANUP).gateKey()).as("the retention sweep names its own gate")
                .contains("scheduled-cleanup");
        assertThat(modules.get(DOWNLOADS).gateKey()).contains("track-downloads");
        assertThat(modules.get(CLEANUP).module())
                .as("the two contributors are attributed to different modules")
                .isNotEqualTo(modules.get(DOWNLOADS).module());
    }

    @Test
    void a_stored_document_for_an_uninstalled_module_renders_not_installed() {
        String phantom = "build.jenesis.repository.phantom";
        Map<String, ModuleCapability> byModule = index(
                ModuleCapability.resolve(_ -> null, Set.of(phantom, "core")));

        ModuleCapability leftover = byModule.get(phantom);
        assertThat(leftover).as("a module named only by a stored document surfaces").isNotNull();
        assertThat(leftover.installed()).as("but as not-installed - this image was not built with it").isFalse();
        assertThat(leftover.settings()).isEmpty();
        assertThat(leftover.enableKey()).isNull();
        assertThat(byModule).as("the neutral core document is not itself a module row").doesNotContainKey("core");
        assertThat(byModule.get(CLEANUP).installed()).as("a real discovered module stays installed").isTrue();
    }

    private static Map<String, ModuleCapability> index(List<ModuleCapability> capabilities) {
        Map<String, ModuleCapability> byModule = new HashMap<>();
        for (ModuleCapability capability : capabilities) {
            byModule.put(capability.module(), capability);
        }
        return byModule;
    }
}
