package build.jenesis.repository.dependency.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.dependency.DependencyComponent;
import build.jenesis.repository.dependency.DependencyGraph;
import build.jenesis.repository.dependency.SpdxParser;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SPDX side of SBOM ingestion pinned to the reference SPDX 2.x shape, the parity counterpart to the CycloneDX
 * coverage in {@link DependencyGraphTest}: the JSON and the classic tag-value serialisations produce the same
 * {@link DependencyGraph}, the package the document {@code DESCRIBES} is the root, {@code DEPENDS_ON} /
 * {@code DEPENDENCY_OF} relationships resolve to edges, the {@code purl} external reference carries the coordinate,
 * and every unreadable input (neither JSON nor tag-value, truncated, malformed, or oversized) degrades to the empty
 * graph rather than throwing. No network, no framework.
 */
class SpdxParserTest {

    private static final String APP_PURL = "pkg:maven/com.example/app@1.0.0";
    private static final String LOG4J_PURL = "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1";

    private static final String JSON = """
            {
              "spdxVersion": "SPDX-2.3",
              "dataLicense": "CC0-1.0",
              "SPDXID": "SPDXRef-DOCUMENT",
              "name": "app",
              "documentNamespace": "https://jenesis.build/spdx/app-0",
              "creationInfo": { "created": "2024-01-01T00:00:00Z", "creators": [ "Tool: Jenesis" ] },
              "packages": [
                {
                  "SPDXID": "SPDXRef-Package-0",
                  "name": "app",
                  "versionInfo": "1.0.0",
                  "downloadLocation": "NOASSERTION",
                  "filesAnalyzed": false,
                  "externalRefs": [
                    { "referenceCategory": "PACKAGE-MANAGER", "referenceType": "purl",
                      "referenceLocator": "pkg:maven/com.example/app@1.0.0" }
                  ]
                },
                {
                  "SPDXID": "SPDXRef-Package-1",
                  "name": "log4j-core",
                  "versionInfo": "2.14.1",
                  "downloadLocation": "NOASSERTION",
                  "filesAnalyzed": false,
                  "checksums": [ { "algorithm": "SHA256", "checksumValue": "deadbeef" } ],
                  "externalRefs": [
                    { "referenceCategory": "PACKAGE-MANAGER", "referenceType": "purl",
                      "referenceLocator": "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1" }
                  ]
                }
              ],
              "relationships": [
                { "spdxElementId": "SPDXRef-DOCUMENT", "relationshipType": "DESCRIBES",
                  "relatedSpdxElement": "SPDXRef-Package-0" },
                { "spdxElementId": "SPDXRef-Package-0", "relationshipType": "DEPENDS_ON",
                  "relatedSpdxElement": "SPDXRef-Package-1" }
              ]
            }
            """;

    private static final String TAG_VALUE = """
            SPDXVersion: SPDX-2.3
            DataLicense: CC0-1.0
            SPDXID: SPDXRef-DOCUMENT
            DocumentName: app
            DocumentNamespace: https://jenesis.build/spdx/app-0
            Creator: Tool: Jenesis
            Created: 2024-01-01T00:00:00Z

            PackageName: app
            SPDXID: SPDXRef-Package-0
            PackageVersion: 1.0.0
            PackageDownloadLocation: NOASSERTION
            FilesAnalyzed: false
            PackageCopyrightText: <text>Copyright: not
            a real tag: SPDXID: SPDXRef-FORGED</text>
            ExternalRef: PACKAGE-MANAGER purl pkg:maven/com.example/app@1.0.0

            PackageName: log4j-core
            SPDXID: SPDXRef-Package-1
            PackageVersion: 2.14.1
            PackageDownloadLocation: NOASSERTION
            FilesAnalyzed: false
            PackageChecksum: SHA256: deadbeef
            ExternalRef: PACKAGE-MANAGER purl pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1

            Relationship: SPDXRef-DOCUMENT DESCRIBES SPDXRef-Package-0
            Relationship: SPDXRef-Package-0 DEPENDS_ON SPDXRef-Package-1
            """;

    @Test
    void the_json_serialisation_parses_to_the_reference_graph() {
        assertReferenceGraph(SpdxParser.parse(JSON.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void the_tag_value_serialisation_parses_to_the_reference_graph() {
        assertReferenceGraph(SpdxParser.parse(TAG_VALUE.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void the_two_serialisations_produce_the_same_graph() {
        DependencyGraph json = SpdxParser.parse(JSON.getBytes(StandardCharsets.UTF_8));
        DependencyGraph tagValue = SpdxParser.parse(TAG_VALUE.getBytes(StandardCharsets.UTF_8));
        assertThat(tagValue).as("JSON and tag-value SPDX parse to the identical graph").isEqualTo(json);
    }

    /** The reference shape both serialisations must yield: root app -> log4j-core, purls carried, checksum read, and
     *  (for the tag-value case) the multi-line {@code <text>} copyright that smuggles a fake {@code SPDXID:} line
     *  neither becomes a component nor forges an edge. */
    private static void assertReferenceGraph(DependencyGraph graph) {
        assertThat(graph.isEmpty()).as("the reference SPDX names components").isFalse();
        assertThat(graph.root()).as("the DESCRIBES-d package is the root").isPresent();
        DependencyComponent root = graph.root().orElseThrow();
        assertThat(root.ref()).isEqualTo("SPDXRef-Package-0");
        assertThat(root.name()).isEqualTo("app");
        assertThat(root.version()).isEqualTo("1.0.0");
        assertThat(root.coordinate()).isEqualTo(APP_PURL);

        assertThat(graph.dependencies()).hasSize(1);
        DependencyComponent log4j = graph.dependencies().getFirst();
        assertThat(log4j.ref()).isEqualTo("SPDXRef-Package-1");
        assertThat(log4j.name()).isEqualTo("log4j-core");
        assertThat(log4j.coordinate()).isEqualTo(LOG4J_PURL);
        assertThat(log4j.sha256()).isEqualTo("deadbeef");

        assertThat(graph.directDependencies()).extracting(DependencyComponent::coordinate)
                .containsExactly(LOG4J_PURL);
        assertThat(graph.edges()).hasSize(1);
        assertThat(graph.componentsByRef()).doesNotContainKey("SPDXRef-FORGED");   // the <text>-smuggled tag is inert
    }

    @Test
    void a_dependency_of_relationship_is_read_as_the_reversed_edge() {
        // SPDX's inverse form: "log4j DEPENDENCY_OF app" is the same graph edge as "app DEPENDS_ON log4j".
        String spdx = """
                SPDXVersion: SPDX-2.3
                SPDXID: SPDXRef-DOCUMENT

                PackageName: app
                SPDXID: SPDXRef-Package-0
                ExternalRef: PACKAGE-MANAGER purl pkg:maven/com.example/app@1.0.0

                PackageName: log4j-core
                SPDXID: SPDXRef-Package-1
                ExternalRef: PACKAGE-MANAGER purl pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1

                Relationship: SPDXRef-DOCUMENT DESCRIBES SPDXRef-Package-0
                Relationship: SPDXRef-Package-1 DEPENDENCY_OF SPDXRef-Package-0
                """;
        DependencyGraph graph = SpdxParser.parse(spdx.getBytes(StandardCharsets.UTF_8));
        assertThat(graph.directDependencies()).as("DEPENDENCY_OF is folded into the forward edge")
                .extracting(DependencyComponent::coordinate).containsExactly(LOG4J_PURL);
    }

    @Test
    void the_document_describes_array_names_the_root_when_no_relationship_does() {
        String spdx = """
                { "spdxVersion": "SPDX-2.3", "SPDXID": "SPDXRef-DOCUMENT",
                  "documentDescribes": [ "SPDXRef-Package-0" ],
                  "packages": [ { "SPDXID": "SPDXRef-Package-0", "name": "app", "versionInfo": "1.0.0" } ] }
                """;
        DependencyGraph graph = SpdxParser.parse(spdx.getBytes(StandardCharsets.UTF_8));
        assertThat(graph.root()).isPresent();
        assertThat(graph.root().orElseThrow().name()).isEqualTo("app");
    }

    @Test
    void a_purl_less_maven_central_ref_recovers_the_group_so_the_coordinate_matches_cyclonedx() {
        // The round-trip of SpdxWriter's own output: for a purl-less Maven coordinate the writer records the joined
        // group:name:version as a maven-central external ref (not a purl). The parser must read that ref back to recover
        // the group, otherwise the SPDX-sourced component keys coordinate() as "log4j-core:2.14.1" (group dropped) while
        // the CycloneDX-sourced one keys "org.apache.logging.log4j:log4j-core:2.14.1" - diverging the dependents index.
        String json = """
                { "spdxVersion": "SPDX-2.3", "SPDXID": "SPDXRef-DOCUMENT",
                  "documentDescribes": [ "SPDXRef-Package-0" ],
                  "packages": [ {
                    "SPDXID": "SPDXRef-Package-0", "name": "log4j-core", "versionInfo": "2.14.1",
                    "externalRefs": [ { "referenceCategory": "PACKAGE-MANAGER", "referenceType": "maven-central",
                      "referenceLocator": "org.apache.logging.log4j:log4j-core:2.14.1" } ] } ] }
                """;
        String tagValue = """
                SPDXVersion: SPDX-2.3
                SPDXID: SPDXRef-DOCUMENT

                PackageName: log4j-core
                SPDXID: SPDXRef-Package-0
                PackageVersion: 2.14.1
                ExternalRef: PACKAGE-MANAGER maven-central org.apache.logging.log4j:log4j-core:2.14.1

                Relationship: SPDXRef-DOCUMENT DESCRIBES SPDXRef-Package-0
                """;
        for (String spdx : new String[] {json, tagValue}) {
            DependencyComponent root = SpdxParser.parse(spdx.getBytes(StandardCharsets.UTF_8)).root().orElseThrow();
            assertThat(root.name()).isEqualTo("log4j-core");
            assertThat(root.version()).isEqualTo("2.14.1");
            assertThat(root.coordinate()).as("the maven-central ref restores the group into the coordinate")
                    .isEqualTo("org.apache.logging.log4j:log4j-core:2.14.1");
        }
    }

    @Test
    void a_maven_central_ref_that_only_repeats_name_and_version_leaves_the_component_unchanged() {
        // The whole-repository form SpdxWriter emits: the component's name already carries the joined group ("com.example
        // :lib0"), so the maven-central locator "com.example:lib0:1.0.0" only repeats name:version and adds no separate
        // group. The parser must not mis-split it - the coordinate stays the already-correct joined value.
        String json = """
                { "spdxVersion": "SPDX-2.3", "SPDXID": "SPDXRef-DOCUMENT",
                  "documentDescribes": [ "SPDXRef-Package-0" ],
                  "packages": [ {
                    "SPDXID": "SPDXRef-Package-0", "name": "com.example:lib0", "versionInfo": "1.0.0",
                    "externalRefs": [ { "referenceCategory": "PACKAGE-MANAGER", "referenceType": "maven-central",
                      "referenceLocator": "com.example:lib0:1.0.0" } ] } ] }
                """;
        DependencyComponent root = SpdxParser.parse(json.getBytes(StandardCharsets.UTF_8)).root().orElseThrow();
        assertThat(root.name()).isEqualTo("com.example:lib0");
        assertThat(root.coordinate()).as("a name-only-repeating locator adds no group")
                .isEqualTo("com.example:lib0:1.0.0");
    }

    @Test
    void a_malformed_spdx_document_is_empty_rather_than_throwing() {
        assertThat(SpdxParser.parse("{ \"spdxVersion\": \"SPDX-2.3\", \"packages\": [".getBytes(StandardCharsets.UTF_8)))
                .as("a truncated JSON SPDX degrades to the empty graph").isEqualTo(DependencyGraph.EMPTY);
        assertThat(SpdxParser.parse("not an sbom at all".getBytes(StandardCharsets.UTF_8)))
                .as("free text that is neither JSON nor SPDX tag-value is empty").isEqualTo(DependencyGraph.EMPTY);
        assertThat(SpdxParser.parse(new byte[0]))
                .as("empty bytes are empty").isEqualTo(DependencyGraph.EMPTY);
    }

    @Test
    void an_oversized_stream_is_refused_rather_than_buffered() throws IOException {
        // The whole document is held in heap only up to MAX_DOCUMENT; a stream past it is refused, not buffered.
        byte[] oversized = new byte[SpdxParser.MAX_DOCUMENT + 1];
        Arrays.fill(oversized, (byte) ' ');
        assertThat(SpdxParser.parse(new ByteArrayInputStream(oversized)))
                .as("a stream larger than MAX_DOCUMENT yields the empty graph").isEqualTo(DependencyGraph.EMPTY);
    }
}

