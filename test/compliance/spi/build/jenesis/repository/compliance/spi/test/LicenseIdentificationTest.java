package build.jenesis.repository.compliance.spi.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.License;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * License identification now reads its rules from an ordered classpath table ({@code spdx-licenses.tsv}) rather than a
 * fourteen-branch inline substring chain in the record. This pins that the table loads at all (a missing or malformed
 * resource fails fast at class initialisation), that every row resolves, and - crucially - that the resource's order
 * is honoured: the most-specific rule wins, so a name that matches two rules resolves to the earlier one exactly as the
 * old ordered {@code if} chain did.
 */
class LicenseIdentificationTest {

    @Test
    void every_rule_in_the_table_resolves_to_its_spdx_id_and_category() {
        assertThat(License.identify("GNU Affero General Public License v3.0", null))
                .isEqualTo(new License("AGPL-3.0", "network-copyleft"));
        assertThat(License.identify("GNU Lesser General Public License", null))
                .isEqualTo(new License("LGPL", "weak-copyleft"));
        assertThat(License.identify("GNU General Public License, version 2", null))
                .isEqualTo(new License("GPL", "strong-copyleft"));
        assertThat(License.identify("The Apache Software License, Version 2.0", null))
                .isEqualTo(new License("Apache-2.0", "permissive"));
        assertThat(License.identify("Eclipse Distribution License v1.0", null))
                .isEqualTo(new License("BSD-3-Clause", "permissive"));
        assertThat(License.identify("Eclipse Public License 2.0", null))
                .isEqualTo(new License("EPL-2.0", "weak-copyleft"));
        assertThat(License.identify("Mozilla Public License 2.0", null))
                .isEqualTo(new License("MPL-2.0", "weak-copyleft"));
        assertThat(License.identify("Common Development and Distribution License", null))
                .isEqualTo(new License("CDDL-1.1", "weak-copyleft"));
        assertThat(License.identify("The 2-Clause BSD License", null))
                .isEqualTo(new License("BSD", "permissive"));
        assertThat(License.identify("MIT License", null))
                .isEqualTo(new License("MIT", "permissive"));
        assertThat(License.identify("Boost Software License 1.0", null))
                .isEqualTo(new License("BSL-1.0", "permissive"));
        assertThat(License.identify("The Unlicense", null))
                .isEqualTo(new License("Unlicense", "permissive"));
        assertThat(License.identify("CC0 1.0 Universal", null))
                .isEqualTo(new License("CC0-1.0", "permissive"));
        assertThat(License.identify("ISC License", null))
                .isEqualTo(new License("ISC", "permissive"));
    }

    @Test
    void bare_spdx_identifiers_resolve_by_exact_id_match() {
        // The bare ids npm/Cargo/gemspecs actually declare - matched by exact spdx-id, NOT by a substring token
        // (which never matched a bare id, so before this they all resolved to UNKNOWN: a proxy deny bypass and a
        // mass false quarantine of bare-MIT packages).
        assertThat(License.identify("MIT", null)).isEqualTo(new License("MIT", "permissive"));
        assertThat(License.identify("Apache-2.0", null)).isEqualTo(new License("Apache-2.0", "permissive"));
        assertThat(License.identify("AGPL-3.0", null)).isEqualTo(new License("AGPL-3.0", "network-copyleft"));
        assertThat(License.identify("EPL-2.0", null)).isEqualTo(new License("EPL-2.0", "weak-copyleft"));
        assertThat(License.identify("BSD-3-Clause", null)).isEqualTo(new License("BSD-3-Clause", "permissive"));
        assertThat(License.identify("MPL-2.0", null)).isEqualTo(new License("MPL-2.0", "weak-copyleft"));
        assertThat(License.identify("ISC", null)).isEqualTo(new License("ISC", "permissive"));
        // The exact id is case-insensitive.
        assertThat(License.identify("mit", null).spdxId()).isEqualTo("MIT");
        assertThat(License.identify("apache-2.0", null).spdxId()).isEqualTo("Apache-2.0");
    }

    @Test
    void a_version_suffixed_bare_id_folds_into_its_family_row() {
        // The table carries the GPL/LGPL/BSD families, not one row per point release, so a versioned bare id folds
        // into its family: "GPL-3.0" -> the strong-copyleft GPL row, and the modern "-only"/"-or-later" spellings too.
        assertThat(License.identify("GPL-3.0", null)).isEqualTo(new License("GPL", "strong-copyleft"));
        assertThat(License.identify("GPL-3.0-only", null).spdxId()).isEqualTo("GPL");
        assertThat(License.identify("GPL-2.0-or-later", null).spdxId()).isEqualTo("GPL");
        assertThat(License.identify("LGPL-3.0", null).spdxId()).isEqualTo("LGPL");
        assertThat(License.identify("BSD-2-Clause", null).spdxId()).isEqualTo("BSD");
        // A bare id that names no family is still unknown - the exact match does not invent a license.
        assertThat(License.identify("NOPE-9.9", null)).isEqualTo(License.UNKNOWN);
    }

    @Test
    void the_exact_id_match_does_not_hijack_a_verbose_name() {
        // A value carrying whitespace is a verbose name for the substring fallback, never a bare id - so "MIT License"
        // still resolves through the name tokens, and a substring like "commit" never trips the MIT id.
        assertThat(License.identify("MIT License", null).spdxId()).isEqualTo("MIT");
        assertThat(License.identify("Committed Works License", null)).isEqualTo(License.UNKNOWN);
    }

    @Test
    void ordering_is_honoured_so_the_most_specific_rule_wins() {
        // "affero" carries "general public license" too, but AGPL sits above GPL in the table, so it wins.
        assertThat(License.identify("Affero General Public License", null).spdxId()).isEqualTo("AGPL-3.0");
        // "lesser general public" also contains "general public license"; LGPL sits above GPL and wins.
        assertThat(License.identify("Lesser General Public License", null).spdxId()).isEqualTo("LGPL");
        // A dual "Apache or BSD" grant matches both the apache and bsd rules; apache sits above bsd and wins.
        assertThat(License.identify("Apache License or BSD", null).spdxId()).isEqualTo("Apache-2.0");
    }

    @Test
    void a_url_alone_identifies_a_license() {
        assertThat(License.identify(null, "https://www.gnu.org/licenses/gpl-3.0.txt").spdxId()).isEqualTo("GPL");
        assertThat(License.identify(null, "https://opensource.org/licenses/MIT").spdxId()).isEqualTo("MIT");
        assertThat(License.identify(null, "https://www.eclipse.org/legal/epl-2.0/").spdxId()).isEqualTo("EPL-2.0");
    }

    @Test
    void an_unmatched_or_empty_name_is_unknown() {
        assertThat(License.identify("Some Bespoke License", null)).isEqualTo(License.UNKNOWN);
        assertThat(License.identify(null, null)).isEqualTo(License.UNKNOWN);
        assertThat(License.identify("", "  ")).isEqualTo(License.UNKNOWN);
        assertThat(License.UNKNOWN.identified()).isFalse();
    }
}
