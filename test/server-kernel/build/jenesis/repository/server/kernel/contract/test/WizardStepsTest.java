package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;
import build.jenesis.repository.settings.Wizard;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What each wizard asks is derived from the catalogue: every essential setting of its scopes that its level holds, one
 * step per settings group in catalogue order, and nothing a standard or advanced setting, or another level's, would
 * add. Over a stand-in contributor, so the derivation is shown on declarations made for it rather than on whatever the
 * shipped modules happen to declare today.
 */
class WizardStepsTest {

    /** A contributor declaring one setting of each tier, level and kind of reach the derivation has to tell apart. */
    private static final SettingsContributor PROBE = () -> List.of(
            new Setting("probe-asked", "Probe", "Asked", "An essential repository setting.", Setting.Kind.STRING, "",
                    true, Setting.Scope.REPOSITORY).essential(),
            new Setting("probe-local", "Probe", "Local", "An essential setting only a repository holds.",
                    Setting.Kind.STRING, "", true, Setting.Scope.REPOSITORY).local().essential(),
            new Setting("probe-standard", "Probe", "Standard", "A standard repository setting.", Setting.Kind.STRING,
                    "", true, Setting.Scope.REPOSITORY).standard(),
            new Setting("probe-advanced", "Probe", "Advanced", "An advanced repository setting.", Setting.Kind.STRING,
                    "", true, Setting.Scope.REPOSITORY).advanced(),
            new Setting("probe-project", "Probe", "Project", "An essential project setting.", Setting.Kind.LONG, "0",
                    true, Setting.Scope.PROJECT).essential(),
            new Setting("probe-tenant", "Probe limits", "Tenant", "An essential tenant setting.", Setting.Kind.LONG,
                    "0", true, Setting.Scope.TENANT).essential(),
            new Setting("probe-global", "Probe alpha", "Global", "An essential deployment setting.",
                    Setting.Kind.STRING, "", true).essential(),
            new Setting("probe-global-standard", "Probe alpha", "Global standard", "A standard deployment setting.",
                    Setting.Kind.STRING, "", true).standard());

    private static final List<Setting> CATALOGUE = SettingsContributor.all(List.of(PROBE));

    private static List<String> asked(Wizard wizard) {
        return wizard.steps(CATALOGUE).stream().flatMap(step -> step.settings().stream()).map(Setting::key).toList();
    }

    @Test
    void a_wizard_asks_the_essential_settings_of_its_own_level_and_nothing_else() {
        assertThat(asked(Wizard.REPOSITORY)).containsExactly("probe-asked", "probe-local");
        assertThat(asked(Wizard.PROJECT)).containsExactly("probe-project");
        assertThat(asked(Wizard.SETUP)).as("the deployment's own, and a tenant's and a repository's as the value "
                        + "every tenant and repository inherits - but not one only a repository holds")
                .containsExactly("probe-asked", "probe-global", "probe-tenant");
    }

    @Test
    void a_step_is_a_settings_group_in_catalogue_order() {
        assertThat(Wizard.SETUP.steps(CATALOGUE)).extracting(Wizard.Step::group)
                .containsExactly("Probe", "Probe alpha", "Probe limits");
        assertThat(Wizard.REPOSITORY.steps(CATALOGUE)).singleElement()
                .satisfies(step -> assertThat(step.group()).isEqualTo("Probe"));
    }

    @Test
    void only_the_first_boot_opens_with_the_starter_credential() {
        assertThat(Wizard.SETUP.information()).containsExactly(Wizard.STARTER_CREDENTIAL);
        assertThat(Wizard.REPOSITORY.information()).isEmpty();
        assertThat(Wizard.PROJECT.information()).isEmpty();
    }
}
