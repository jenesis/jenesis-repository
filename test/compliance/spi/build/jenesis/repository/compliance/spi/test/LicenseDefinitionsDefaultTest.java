package build.jenesis.repository.compliance.spi.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.License;
import build.jenesis.repository.compliance.LicenseTable;
import build.jenesis.repository.compliance.LicenseTableSettings;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@value LicenseTable#KEY} has one default, {@link LicenseTable#DEFAULT}: what the catalogue declares is that value,
 * and a deployment that sets nothing identifies with the built-in rows and nothing else. The catalogue is also where a
 * malformed value is refused, on every surface that writes settings, so that leg is asked through the same static
 * every write path asks.
 */
class LicenseDefinitionsDefaultTest {

    private static Setting declared() {
        return new LicenseTableSettings().settings().stream()
                .filter(setting -> LicenseTable.KEY.equals(setting.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(LicenseTable.KEY + " is not in the catalogue"));
    }

    @Test
    void the_catalogue_declares_the_default_the_table_applies() {
        Setting setting = declared();
        assertThat(setting.defaultValue()).isEqualTo(LicenseTable.DEFAULT).isEmpty();
        assertThat(setting.scope()).as("deployment-wide, like the licence policy it feeds")
                .isEqualTo(Setting.Scope.GLOBAL);
        assertThat(setting.tier()).isEqualTo(Setting.Tier.ADVANCED);
        assertThat(setting.live()).isTrue();
    }

    @Test
    void a_deployment_that_sets_nothing_identifies_with_the_built_in_rows_alone() {
        LicenseTable unset = LicenseTable.of(key -> null);
        assertThat(unset.rows()).isEqualTo(LicenseTable.defaults().rows());
        assertThat(unset.identify("Acme Internal Licence", null)).isEqualTo(License.UNKNOWN);
        assertThat(unset.identify("Apache License, Version 2.0", null).spdxId()).isEqualTo("Apache-2.0");
        assertThat(LicenseTable.configured(declared().defaultValue()).rows())
                .as("the declared default, applied, is the same table").isEqualTo(LicenseTable.defaults().rows());
    }

    @Test
    void every_write_path_refuses_a_malformed_value_naming_the_row() {
        assertThat(SettingsContributor.refusal(LicenseTable.KEY, "Acme-1.0 | permissive\nAcme 2 | permissive",
                Setting.Scope.GLOBAL, key -> null))
                .hasValueSatisfying(reason -> assertThat(reason).contains("row 2 of " + LicenseTable.KEY));
        assertThat(SettingsContributor.refusal(LicenseTable.KEY, "Acme-1.0 | proprietary | acme licence",
                Setting.Scope.GLOBAL, key -> null)).isEmpty();
        assertThat(SettingsContributor.refusal(LicenseTable.KEY, "Acme-1.0 | permissive", Setting.Scope.TENANT,
                key -> null)).as("one table for the deployment, not one per tenant").isPresent();
    }
}
