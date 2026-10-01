package build.jenesis.repository.compliance.spi.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.License;
import build.jenesis.repository.compliance.LicenseTable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The built-in identification table, read from its classpath resource: every row's identifier, names and URLs resolve
 * to that row, the specific beats the general (LGPL and AGPL before GPL, a version before its family, "or later"
 * before the plain version), versions stay distinct, and a short name inside another word is no licence at all.
 */
class LicenseIdentificationTest {

    private static final LicenseTable TABLE = LicenseTable.defaults();

    /** The identifiers the table promises to know, each with its category. */
    private static final Map<String, String> KNOWN = Map.ofEntries(
            Map.entry("0BSD", "permissive"), Map.entry("AFL-3.0", "permissive"),
            Map.entry("AGPL-3.0", "network-copyleft"), Map.entry("AGPL-3.0-only", "network-copyleft"),
            Map.entry("AGPL-3.0-or-later", "network-copyleft"), Map.entry("Apache-1.1", "permissive"),
            Map.entry("Apache-2.0", "permissive"), Map.entry("Artistic-2.0", "permissive"),
            Map.entry("BlueOak-1.0.0", "permissive"), Map.entry("BSD-2-Clause", "permissive"),
            Map.entry("BSD-3-Clause", "permissive"), Map.entry("BSD-4-Clause", "permissive"),
            Map.entry("BSL-1.0", "permissive"), Map.entry("CC0-1.0", "permissive"),
            Map.entry("CC-BY-3.0", "permissive"), Map.entry("CC-BY-4.0", "permissive"),
            Map.entry("CC-BY-SA-4.0", "weak-copyleft"), Map.entry("CDDL-1.0", "weak-copyleft"),
            Map.entry("CDDL-1.1", "weak-copyleft"), Map.entry("CPL-1.0", "weak-copyleft"),
            Map.entry("ECL-2.0", "permissive"), Map.entry("EPL-1.0", "weak-copyleft"),
            Map.entry("EPL-2.0", "weak-copyleft"), Map.entry("EUPL-1.1", "strong-copyleft"),
            Map.entry("EUPL-1.2", "strong-copyleft"), Map.entry("GPL-2.0", "strong-copyleft"),
            Map.entry("GPL-2.0-only", "strong-copyleft"), Map.entry("GPL-2.0-or-later", "strong-copyleft"),
            Map.entry("GPL-2.0-with-classpath-exception", "weak-copyleft"),
            Map.entry("GPL-3.0", "strong-copyleft"), Map.entry("GPL-3.0-only", "strong-copyleft"),
            Map.entry("GPL-3.0-or-later", "strong-copyleft"), Map.entry("ISC", "permissive"),
            Map.entry("LGPL-2.0", "weak-copyleft"), Map.entry("LGPL-2.1", "weak-copyleft"),
            Map.entry("LGPL-3.0", "weak-copyleft"), Map.entry("MIT", "permissive"), Map.entry("MIT-0", "permissive"),
            Map.entry("MPL-1.1", "weak-copyleft"), Map.entry("MPL-2.0", "weak-copyleft"),
            Map.entry("MS-PL", "permissive"), Map.entry("MS-RL", "weak-copyleft"),
            Map.entry("ODbL-1.0", "weak-copyleft"), Map.entry("OFL-1.1", "weak-copyleft"),
            Map.entry("OpenSSL", "permissive"), Map.entry("PostgreSQL", "permissive"),
            Map.entry("PSF-2.0", "permissive"), Map.entry("Python-2.0", "permissive"), Map.entry("Ruby", "permissive"),
            Map.entry("SSPL-1.0", "network-copyleft"), Map.entry("Unicode-DFS-2016", "permissive"),
            Map.entry("Unlicense", "permissive"), Map.entry("UPL-1.0", "permissive"), Map.entry("W3C", "permissive"),
            Map.entry("WTFPL", "permissive"), Map.entry("Zlib", "permissive"));

    @Test
    void the_table_knows_the_identifiers_packages_declare_each_under_its_category() {
        for (Map.Entry<String, String> known : KNOWN.entrySet()) {
            assertThat(TABLE.identify(known.getKey(), null)).as(known.getKey())
                    .isEqualTo(new License(known.getKey(), known.getValue()));
            assertThat(TABLE.identify(known.getKey().toLowerCase(Locale.ROOT), null).spdxId())
                    .as("a bare identifier matches whatever its case").isEqualTo(known.getKey());
        }
    }

    @Test
    void every_row_resolves_its_identifier_each_of_its_names_and_each_of_its_urls_to_itself() {
        for (LicenseTable.Row row : TABLE.rows()) {
            String id = row.license().spdxId();
            assertThat(row.names()).as("%s publishes a name", id).isNotEmpty();
            for (String name : row.names()) {
                assertThat(TABLE.identify(name, null)).as("the name '%s'", name).isEqualTo(row.license());
                assertThat(TABLE.identify("Licensed under the " + name + " terms", null))
                        .as("the name '%s' inside a sentence", name).isEqualTo(row.license());
            }
            for (String url : row.urls()) {
                assertThat(TABLE.identify(null, "https://" + url)).as("the URL %s", url).isEqualTo(row.license());
                assertThat(TABLE.identify(null, "http://www." + url + ".html"))
                        .as("the URL %s over http with a suffix", url).isEqualTo(row.license());
            }
        }
        Set<String> withUrl = new HashSet<>();
        for (LicenseTable.Row row : TABLE.rows()) {
            if (!row.urls().isEmpty()) {
                withUrl.add(row.license().spdxId());
            }
        }
        assertThat(withUrl).as("every identifier the table knows has a URL it is published at")
                .containsAll(KNOWN.keySet());
    }

    @Test
    void the_names_a_licence_is_published_under_resolve_to_it() {
        Map<String, String> published = Map.ofEntries(
                Map.entry("The Apache Software License, Version 2.0", "Apache-2.0"),
                Map.entry("Apache License, Version 2.0", "Apache-2.0"),
                Map.entry("Apache License 2.0", "Apache-2.0"),
                Map.entry("GNU Lesser General Public License v2.1", "LGPL-2.1"),
                Map.entry("GNU Lesser General Public License (LGPL), Version 2.1", "LGPL-2.1"),
                Map.entry("GNU Library General Public License v2 only", "LGPL-2.0-only"),
                Map.entry("GNU General Public License, version 2", "GPL-2.0"),
                Map.entry("GNU General Public License v3.0 or later", "GPL-3.0-or-later"),
                Map.entry("GNU General Public License v2 or later (GPLv2+)", "GPL-2.0-or-later"),
                Map.entry("GNU Affero General Public License v3.0", "AGPL-3.0"),
                Map.entry("GNU General Public License, version 2, with the Classpath Exception",
                        "GPL-2.0-with-classpath-exception"),
                Map.entry("Eclipse Public License - v 2.0", "EPL-2.0"),
                Map.entry("Eclipse Public License - v 1.0", "EPL-1.0"),
                Map.entry("Eclipse Distribution License - v 1.0", "BSD-3-Clause"),
                Map.entry("Mozilla Public License, Version 2.0", "MPL-2.0"),
                Map.entry("Common Development and Distribution License (CDDL) v1.0", "CDDL-1.0"),
                Map.entry("The 2-Clause BSD License", "BSD-2-Clause"),
                Map.entry("New BSD License", "BSD-3-Clause"),
                Map.entry("The MIT License", "MIT"),
                Map.entry("MIT No Attribution", "MIT-0"),
                Map.entry("Boost Software License 1.0", "BSL-1.0"),
                Map.entry("CC0 1.0 Universal", "CC0-1.0"),
                Map.entry("Creative Commons Attribution-ShareAlike 4.0 International", "CC-BY-SA-4.0"),
                Map.entry("Creative Commons Attribution 4.0 International", "CC-BY-4.0"),
                Map.entry("European Union Public Licence v. 1.2", "EUPL-1.2"),
                Map.entry("Universal Permissive License v1.0", "UPL-1.0"),
                Map.entry("SIL Open Font License 1.1", "OFL-1.1"),
                Map.entry("Python Software Foundation License", "PSF-2.0"),
                Map.entry("The Unlicense", "Unlicense"),
                Map.entry("ISC License", "ISC"),
                Map.entry("Server Side Public License, v 1", "SSPL-1.0"));
        for (Map.Entry<String, String> name : published.entrySet()) {
            assertThat(TABLE.identify(name.getKey(), null).spdxId()).as(name.getKey()).isEqualTo(name.getValue());
        }
    }

    @Test
    void the_urls_a_licence_is_published_at_resolve_to_it() {
        assertThat(TABLE.identify(null, "https://www.apache.org/licenses/LICENSE-2.0.txt").spdxId())
                .isEqualTo("Apache-2.0");
        assertThat(TABLE.identify("Apache-like", "http://www.gnu.org/licenses/old-licenses/lgpl-2.1.html").spdxId())
                .as("a URL decides where the name says nothing").isEqualTo("LGPL-2.1");
        assertThat(TABLE.identify(null, "https://www.gnu.org/licenses/gpl-3.0.html").spdxId()).isEqualTo("GPL-3.0");
        assertThat(TABLE.identify(null, "https://opensource.org/licenses/MIT").spdxId()).isEqualTo("MIT");
        assertThat(TABLE.identify(null, "https://opensource.org/licenses/MIT-0").spdxId()).isEqualTo("MIT-0");
        assertThat(TABLE.identify(null, "https://www.eclipse.org/legal/epl-v10.html").spdxId()).isEqualTo("EPL-1.0");
        assertThat(TABLE.identify(null, "https://www.mozilla.org/en-US/MPL/2.0/").spdxId()).isEqualTo("MPL-2.0");
        assertThat(TABLE.identify("BSD License", "https://opensource.org/licenses/BSD-2-Clause").spdxId())
                .as("an unversioned name with a precise URL takes the URL's").isEqualTo("BSD-2-Clause");
        assertThat(TABLE.identify("GNU General Public License", "https://www.gnu.org/licenses/old-licenses/gpl-2.0.html")
                .spdxId()).isEqualTo("GPL-2.0");
    }

    @Test
    void the_lesser_and_affero_licences_are_not_the_general_public_licence() {
        assertThat(TABLE.identify("GNU Lesser General Public License v3.0", null).spdxId()).isEqualTo("LGPL-3.0");
        assertThat(TABLE.identify("GNU Affero General Public License v3.0", null).spdxId()).isEqualTo("AGPL-3.0");
        assertThat(TABLE.identify("LGPL-2.1", null).spdxId()).isEqualTo("LGPL-2.1");
        assertThat(TABLE.identify("LGPLv3", null).spdxId()).isEqualTo("LGPL-3.0");
        assertThat(TABLE.identify("AGPLv3", null).spdxId()).isEqualTo("AGPL-3.0");
        assertThat(TABLE.identify("GNU Lesser General Public License", null).spdxId())
                .as("an unversioned LGPL is the first LGPL or later").isEqualTo("LGPL-2.0-or-later");
        assertThat(TABLE.identify("GNU General Public License", null).spdxId())
                .as("an unversioned GPL is any version, which is GPL-1.0 or later").isEqualTo("GPL-1.0-or-later");
        assertThat(TABLE.identify("Affero General Public License", null).spdxId()).isEqualTo("AGPL-3.0");
    }

    @Test
    void distinct_versions_resolve_to_distinct_identifiers() {
        assertThat(TABLE.identify("GPL-2.0", null).spdxId()).isEqualTo("GPL-2.0");
        assertThat(TABLE.identify("GPL-3.0", null).spdxId()).isEqualTo("GPL-3.0");
        assertThat(TABLE.identify("GPLv2", null).spdxId()).isEqualTo("GPL-2.0");
        assertThat(TABLE.identify("GPLv3", null).spdxId()).isEqualTo("GPL-3.0");
        assertThat(TABLE.identify("GPLv2+", null).spdxId()).isEqualTo("GPL-2.0-or-later");
        assertThat(TABLE.identify("GPL-2+", null).spdxId()).as("a Debian short name").isEqualTo("GPL-2.0-or-later");
        assertThat(TABLE.identify("GPL-2.0+", null).spdxId()).as("the SPDX + operator").isEqualTo("GPL-2.0-or-later");
        assertThat(TABLE.identify("GPL-3.0-only", null).spdxId()).isEqualTo("GPL-3.0-only");
        assertThat(TABLE.identify("LGPL v2", null).spdxId()).isEqualTo("LGPL-2.0");
        assertThat(TABLE.identify("LGPL v2.1", null).spdxId()).isEqualTo("LGPL-2.1");
        assertThat(TABLE.identify("Lesser General Public License, version 2.1 or later", null).spdxId())
                .isEqualTo("LGPL-2.1-or-later");
        assertThat(TABLE.identify("MPL 1.1", null).spdxId()).isEqualTo("MPL-1.1");
        assertThat(TABLE.identify("MPL 2.0", null).spdxId()).isEqualTo("MPL-2.0");
        assertThat(TABLE.identify("CDDL 1.0", null).spdxId()).isEqualTo("CDDL-1.0");
        assertThat(TABLE.identify("CDDL 1.1", null).spdxId()).isEqualTo("CDDL-1.1");
        assertThat(TABLE.identify("Apache License, Version 1.1", null).spdxId()).isEqualTo("Apache-1.1");
        assertThat(TABLE.identify("Creative Commons Attribution 3.0", null).spdxId()).isEqualTo("CC-BY-3.0");
    }

    @Test
    void an_spdx_exception_refines_the_licence_it_is_attached_to() {
        assertThat(TABLE.identify("GPL-2.0-only WITH Classpath-exception-2.0", null).spdxId())
                .isEqualTo("GPL-2.0-with-classpath-exception");
        assertThat(TABLE.identify("Apache-2.0 WITH LLVM-exception", null).spdxId()).isEqualTo("Apache-2.0");
        assertThat(TABLE.identify("MIT WITH Some-exception", null).spdxId())
                .as("an exception no row knows leaves its licence as it was").isEqualTo("MIT");
    }

    @Test
    void a_short_name_inside_another_word_is_no_licence() {
        assertThat(TABLE.identify("The Example Corporation Licence", null))
                .as("'mpl' inside 'example' names no Mozilla licence").isEqualTo(License.UNKNOWN);
        assertThat(TABLE.identify("Proprietary - see the disclaimer", null))
                .as("'isc' inside 'disclaimer' is not ISC").isEqualTo(License.UNKNOWN);
        assertThat(TABLE.identify("Unpublished; all rights reserved (abcbsdx)", null)).isEqualTo(License.UNKNOWN);
        assertThat(TABLE.identify("Committed Works License", null))
                .as("'mit' inside 'committed' is not MIT").isEqualTo(License.UNKNOWN);
        assertThat(TABLE.identify("UNLICENSED", null))
                .as("npm's word for a private package is not the Unlicense").isEqualTo(License.UNKNOWN);
        assertThat(TABLE.identify("Simplified terms of use", null)).as("'mpl' inside 'simplified'")
                .isEqualTo(License.UNKNOWN);
        assertThat(TABLE.identify("Libexpat-derived terms", null)).as("'expat' inside 'libexpat'")
                .isEqualTo(License.UNKNOWN);
        assertThat(TABLE.identify("MPL2", null).spdxId()).as("a digit may follow a name").isEqualTo("MPL-2.0");
    }

    @Test
    void a_declaration_that_names_nothing_known_is_unknown() {
        assertThat(TABLE.identify("Some Bespoke License", null)).isEqualTo(License.UNKNOWN);
        assertThat(TABLE.identify("NOPE-9.9", null)).isEqualTo(License.UNKNOWN);
        assertThat(TABLE.identify(null, null)).isEqualTo(License.UNKNOWN);
        assertThat(TABLE.identify("", "  ")).isEqualTo(License.UNKNOWN);
        assertThat(License.UNKNOWN.identified()).isFalse();
    }
}
