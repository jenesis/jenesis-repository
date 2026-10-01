package build.jenesis.repository.compliance.spi.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.License;
import build.jenesis.repository.compliance.LicenseTable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The licences an operator adds under {@value LicenseTable#KEY}: they resolve by identifier, name and URL, they are
 * tried before the built-in rows, the built-in rows still answer beside them, and a value that does not parse is
 * refused naming the row at fault.
 */
class LicenseDefinitionsTest {

    private static final String ACME =
            "Acme-Internal-1.0 | proprietary | Acme Internal Licence | https://acme.example/licence/";

    @Test
    void a_configured_licence_resolves_by_its_identifier_its_names_and_its_urls() {
        LicenseTable table = LicenseTable.of(Map.of(LicenseTable.KEY, ACME)::get);
        License acme = new License("Acme-Internal-1.0", "proprietary");

        assertThat(table.identify("Acme-Internal-1.0", null)).isEqualTo(acme);
        assertThat(table.identify("acme-internal-1.0", null)).as("a bare identifier, whatever its case").isEqualTo(acme);
        assertThat(table.identify("Acme-Internal-1.0+", null)).as("or later, where no row says otherwise")
                .isEqualTo(acme);
        assertThat(table.identify("The ACME Internal License", null))
                .as("a name, matched with licence and license read alike").isEqualTo(acme);
        assertThat(table.identify(null, "http://www.acme.example/licence")).as("a URL, over either scheme")
                .isEqualTo(acme);
        assertThat(table.identify("Acme-Internal-1.0 WITH Acme-exception", null)).isEqualTo(acme);
        assertThat(table.identify("Apache License, Version 2.0", null).spdxId())
                .as("the built-in rows still answer beside the configured ones").isEqualTo("Apache-2.0");
    }

    @Test
    void configured_rows_are_tried_before_the_built_in_ones() {
        LicenseTable table = LicenseTable.of(Map.of(LicenseTable.KEY,
                "MIT | reviewed | mit license\nApache-2.0-Acme | weak-copyleft | acme apache license")::get);

        assertThat(table.identify("MIT", null)).as("a known identifier, recategorised")
                .isEqualTo(new License("MIT", "reviewed"));
        assertThat(table.identify("The MIT License", null).category()).isEqualTo("reviewed");
        assertThat(table.identify("Acme Apache License", null).spdxId())
                .as("a name the built-in 'apache' would otherwise take").isEqualTo("Apache-2.0-Acme");
        assertThat(table.rows().getFirst().license().spdxId()).isEqualTo("MIT");
    }

    @Test
    void rows_may_be_separated_by_lines_or_semicolons_and_blank_ones_are_skipped() {
        LicenseTable table = LicenseTable.configured("One-1.0 | permissive | one licence;\n ; Two-1.0 | Permissive");

        assertThat(table.identify("One-1.0", null).category()).isEqualTo("permissive");
        assertThat(table.identify("Two-1.0", null)).as("a row with no names matches its identifier, category "
                + "lower-cased").isEqualTo(new License("Two-1.0", "permissive"));
        assertThat(table.identify("Two Licence", null)).isEqualTo(License.UNKNOWN);
    }

    @Test
    void an_unset_or_blank_value_is_the_built_in_table() {
        assertThat(LicenseTable.of(key -> null).rows()).isEqualTo(LicenseTable.defaults().rows());
        assertThat(LicenseTable.configured("  \n ").rows()).isEqualTo(LicenseTable.defaults().rows());
    }

    @Test
    void a_malformed_row_is_refused_naming_the_row() {
        Map<String, String> malformed = Map.of(
                "Acme-1.0", "row 1",
                "Ok-1.0 | permissive\nAcme 1.0 | permissive", "row 2",
                "Acme-1.0 | Prop rietary", "is not a category",
                "Acme-1.0 | unknown", "'unknown' is what an unidentified licence counts as",
                "Acme-1.0 | permissive | ab", "shorter than 3 characters",
                "Acme-1.0 | permissive | acme licence |", "an empty name",
                "Acme-1.0 | permissive; acme-1.0 | permissive", "defined by an earlier row",
                "| permissive | acme", "is not an identifier");
        for (Map.Entry<String, String> value : malformed.entrySet()) {
            assertThat(LicenseTable.refusal(value.getKey())).as(value.getKey())
                    .hasValueSatisfying(reason -> assertThat(reason)
                            .contains(LicenseTable.KEY).contains(value.getValue()));
            assertThatThrownBy(() -> LicenseTable.of(Map.of(LicenseTable.KEY, value.getKey())::get))
                    .as("a pass that reads it fails rather than identifying with a table nobody wrote")
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(LicenseTable.refusal(ACME)).isEmpty();
        assertThat(LicenseTable.refusal("")).isEmpty();
    }
}
