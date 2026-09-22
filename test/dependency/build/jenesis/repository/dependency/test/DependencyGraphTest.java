package build.jenesis.repository.dependency.test;

import module java.base;
import module org.junit.jupiter.api;
import java.util.jar.Attributes;
import build.jenesis.repository.dependency.ArtifactSbom;
import build.jenesis.repository.dependency.CycloneDxParser;
import build.jenesis.repository.dependency.DependencyComponent;
import build.jenesis.repository.dependency.DependencyGraph;
import build.jenesis.repository.dependency.MalformedSbomException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The dependency-graph primitive pinned to the reference CycloneDX shape - the SBOM the Jenesis build embeds in
 * every jar - with no network and no framework: the JSON and XML parsers produce the same graph, the coordinate/purl
 * model resolves edges to nodes, the extractor reads the SBOM out of a jar without buffering it, and every
 * unreadable input (a non-jar blob, an SBOM-less jar, malformed bytes, a DOCTYPE/XXE document, an oversized
 * document) degrades to an empty graph rather than throwing.
 */
class DependencyGraphTest {

    private static final String APP = "com.example/app/1.0.0";
    private static final String LOG4J_CORE = "org.apache.logging.log4j/log4j-core/2.14.1";
    private static final String LOG4J_API = "org.apache.logging.log4j/log4j-api/2.14.1";
    private static final String LOG4J_CORE_PURL = "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1";

    private static final String JSON_BOM = """
            {
              "bomFormat": "CycloneDX",
              "specVersion": "1.6",
              "serialNumber": "urn:uuid:00000000-0000-0000-0000-000000000000",
              "version": 1,
              "metadata": {
                "tools": { "components": [ { "type": "application", "name": "Jenesis" } ] },
                "component": {
                  "type": "library",
                  "bom-ref": "com.example/app/1.0.0",
                  "group": "com.example",
                  "name": "app",
                  "version": "1.0.0",
                  "purl": "pkg:maven/com.example/app@1.0.0"
                }
              },
              "components": [
                {
                  "type": "library",
                  "bom-ref": "org.apache.logging.log4j/log4j-core/2.14.1",
                  "group": "org.apache.logging.log4j",
                  "name": "log4j-core",
                  "version": "2.14.1",
                  "hashes": [ { "alg": "SHA-256", "content": "deadbeef" } ],
                  "purl": "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1"
                },
                {
                  "type": "library",
                  "bom-ref": "org.apache.logging.log4j/log4j-api/2.14.1",
                  "group": "org.apache.logging.log4j",
                  "name": "log4j-api",
                  "version": "2.14.1",
                  "purl": "pkg:maven/org.apache.logging.log4j/log4j-api@2.14.1"
                }
              ],
              "dependencies": [
                { "ref": "com.example/app/1.0.0", "dependsOn": [ "org.apache.logging.log4j/log4j-core/2.14.1" ] },
                { "ref": "org.apache.logging.log4j/log4j-core/2.14.1", "dependsOn": [ "org.apache.logging.log4j/log4j-api/2.14.1" ] },
                { "ref": "org.apache.logging.log4j/log4j-api/2.14.1" }
              ]
            }
            """;

    private static final String XML_BOM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bom xmlns="http://cyclonedx.org/schema/bom/1.6" version="1">
              <metadata>
                <component type="library" bom-ref="com.example/app/1.0.0">
                  <group>com.example</group><name>app</name><version>1.0.0</version>
                  <purl>pkg:maven/com.example/app@1.0.0</purl>
                </component>
              </metadata>
              <components>
                <component type="library" bom-ref="org.apache.logging.log4j/log4j-core/2.14.1">
                  <group>org.apache.logging.log4j</group><name>log4j-core</name><version>2.14.1</version>
                  <hashes><hash alg="SHA-256">deadbeef</hash></hashes>
                  <purl>pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1</purl>
                </component>
                <component type="library" bom-ref="org.apache.logging.log4j/log4j-api/2.14.1">
                  <group>org.apache.logging.log4j</group><name>log4j-api</name><version>2.14.1</version>
                  <purl>pkg:maven/org.apache.logging.log4j/log4j-api@2.14.1</purl>
                </component>
              </components>
              <dependencies>
                <dependency ref="com.example/app/1.0.0">
                  <dependency ref="org.apache.logging.log4j/log4j-core/2.14.1"/>
                </dependency>
                <dependency ref="org.apache.logging.log4j/log4j-core/2.14.1">
                  <dependency ref="org.apache.logging.log4j/log4j-api/2.14.1"/>
                </dependency>
                <dependency ref="org.apache.logging.log4j/log4j-api/2.14.1"/>
              </dependencies>
            </bom>
            """;

    @Test
    void parses_a_json_bom_into_the_root_components_and_edges() {
        DependencyGraph graph = CycloneDxParser.parse(JSON_BOM.getBytes(StandardCharsets.UTF_8));

        assertThat(graph.isEmpty()).isFalse();
        assertThat(graph.rootRef()).isEqualTo(APP);
        assertThat(graph.root()).isPresent();
        assertThat(graph.root().get().coordinate()).isEqualTo("pkg:maven/com.example/app@1.0.0");
        assertThat(graph.components()).extracting(DependencyComponent::ref)
                .containsExactly(APP, LOG4J_CORE, LOG4J_API);
    }

    @Test
    void reads_the_purl_and_sha256_of_a_component() {
        DependencyGraph graph = CycloneDxParser.parse(JSON_BOM.getBytes(StandardCharsets.UTF_8));

        DependencyComponent core = byRef(graph, LOG4J_CORE);
        assertThat(core.group()).isEqualTo("org.apache.logging.log4j");
        assertThat(core.name()).isEqualTo("log4j-core");
        assertThat(core.version()).isEqualTo("2.14.1");
        assertThat(core.purl()).isEqualTo(LOG4J_CORE_PURL);
        assertThat(core.sha256()).isEqualTo("deadbeef");
        assertThat(core.coordinate()).isEqualTo(LOG4J_CORE_PURL);
    }

    @Test
    void the_transitive_set_excludes_the_root_and_direct_edges_resolve() {
        DependencyGraph graph = CycloneDxParser.parse(JSON_BOM.getBytes(StandardCharsets.UTF_8));

        assertThat(graph.dependencies()).extracting(DependencyComponent::ref)
                .containsExactlyInAnyOrder(LOG4J_CORE, LOG4J_API)
                .doesNotContain(APP);
        assertThat(graph.directDependencies()).extracting(DependencyComponent::ref)
                .containsExactly(LOG4J_CORE);                       // app -> log4j-core only, directly
        assertThat(graph.dependenciesOf(LOG4J_CORE)).extracting(DependencyComponent::ref)
                .containsExactly(LOG4J_API);                        // log4j-core -> log4j-api
    }

    /** A third-party emitter that nests the FULL resolved tree rather than the flat one-level form: log4j-core is a
     *  {@code <dependency>} nested inside app, and log4j-api nested inside log4j-core (a grandchild edge). */
    private static final String NESTED_XML_BOM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bom xmlns="http://cyclonedx.org/schema/bom/1.6" version="1">
              <metadata>
                <component type="library" bom-ref="com.example/app/1.0.0">
                  <group>com.example</group><name>app</name><version>1.0.0</version>
                  <purl>pkg:maven/com.example/app@1.0.0</purl>
                </component>
              </metadata>
              <components>
                <component type="library" bom-ref="org.apache.logging.log4j/log4j-core/2.14.1">
                  <group>org.apache.logging.log4j</group><name>log4j-core</name><version>2.14.1</version>
                  <purl>pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1</purl>
                </component>
                <component type="library" bom-ref="org.apache.logging.log4j/log4j-api/2.14.1">
                  <group>org.apache.logging.log4j</group><name>log4j-api</name><version>2.14.1</version>
                  <purl>pkg:maven/org.apache.logging.log4j/log4j-api@2.14.1</purl>
                </component>
              </components>
              <dependencies>
                <dependency ref="com.example/app/1.0.0">
                  <dependency ref="org.apache.logging.log4j/log4j-core/2.14.1">
                    <dependency ref="org.apache.logging.log4j/log4j-api/2.14.1"/>
                  </dependency>
                </dependency>
              </dependencies>
            </bom>
            """;

    @Test
    void the_recursive_xml_dependency_nesting_is_read_fully_not_just_one_level() {
        // A single-level reader records only app -> log4j-core and silently drops the log4j-core -> log4j-api
        // grandchild edge that a full-tree emitter nests. Parsing the whole nesting recovers it.
        DependencyGraph graph = CycloneDxParser.parse(NESTED_XML_BOM.getBytes(StandardCharsets.UTF_8));

        assertThat(graph.isEmpty()).isFalse();
        assertThat(graph.directDependencies()).extracting(DependencyComponent::ref)
                .as("app depends directly on log4j-core").containsExactly(LOG4J_CORE);
        assertThat(graph.dependenciesOf(LOG4J_CORE)).extracting(DependencyComponent::ref)
                .as("the nested grandchild edge log4j-core -> log4j-api survives").containsExactly(LOG4J_API);
        assertThat(graph.dependencies()).extracting(DependencyComponent::ref)
                .as("the whole transitive tree, not just the direct edge")
                .containsExactlyInAnyOrder(LOG4J_CORE, LOG4J_API);
    }

    @Test
    void the_xml_serialisation_parses_to_the_same_graph_as_the_json_one() {
        DependencyGraph json = CycloneDxParser.parse(JSON_BOM.getBytes(StandardCharsets.UTF_8));
        DependencyGraph xml = CycloneDxParser.parse(XML_BOM.getBytes(StandardCharsets.UTF_8));

        assertThat(xml.rootRef()).isEqualTo(json.rootRef());
        assertThat(xml.components()).extracting(DependencyComponent::coordinate)
                .containsExactlyElementsOf(json.components().stream().map(DependencyComponent::coordinate).toList());
        assertThat(xml.directDependencies()).extracting(DependencyComponent::ref).containsExactly(LOG4J_CORE);
        assertThat(byRef(xml, LOG4J_CORE).sha256()).isEqualTo("deadbeef");
    }

    @Test
    void extracts_the_sbom_a_manifest_points_at_inside_a_jar() throws IOException {
        byte[] jar = jar("META-INF/sbom/app.cdx.json", true, JSON_BOM.getBytes(StandardCharsets.UTF_8));

        Optional<DependencyGraph> graph = ArtifactSbom.graph(new ByteArrayInputStream(jar));

        assertThat(graph).isPresent();
        assertThat(graph.get().rootRef()).isEqualTo(APP);
        assertThat(graph.get().dependencies()).extracting(DependencyComponent::ref)
                .containsExactlyInAnyOrder(LOG4J_CORE, LOG4J_API);
    }

    @Test
    void finds_the_sbom_by_convention_when_the_manifest_does_not_point_at_it() throws IOException {
        byte[] jar = jar("META-INF/sbom/app.cdx.xml", false, XML_BOM.getBytes(StandardCharsets.UTF_8));

        Optional<DependencyGraph> graph = ArtifactSbom.graph(new ByteArrayInputStream(jar));

        assertThat(graph).isPresent();
        assertThat(graph.get().directDependencies()).extracting(DependencyComponent::ref).containsExactly(LOG4J_CORE);
    }

    @Test
    void a_jar_without_an_embedded_sbom_yields_no_graph() throws IOException {
        byte[] jar = jar(null, false, null);

        assertThat(ArtifactSbom.graph(new ByteArrayInputStream(jar))).isEmpty();
    }

    @Test
    void a_non_jar_blob_yields_no_graph() throws IOException {
        assertThat(ArtifactSbom.graph(new ByteArrayInputStream("not a zip".getBytes(StandardCharsets.UTF_8)))).isEmpty();
    }

    @Test
    void malformed_and_unrecognised_documents_are_the_empty_graph() {
        assertThat(CycloneDxParser.parse("this is not a bom".getBytes(StandardCharsets.UTF_8))).isEqualTo(DependencyGraph.EMPTY);
        assertThat(CycloneDxParser.parse("{ broken".getBytes(StandardCharsets.UTF_8)).isEmpty()).isTrue();
        assertThat(CycloneDxParser.parse(new byte[0]).isEmpty()).isTrue();
    }

    @Test
    void strict_parsing_distinguishes_a_malformed_bom_from_a_genuinely_empty_one() throws Exception {
        // The served SBOM path uses parseStrict, which raises MalformedSbomException when a document announces JSON or
        // XML but does not decode - so a subsection can render "could not derive this SBOM" distinct from "no SBOM".
        assertThatThrownBy(() -> CycloneDxParser.parseStrict("{ broken".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(MalformedSbomException.class);
        assertThatThrownBy(() -> CycloneDxParser.parseStrict("<bom><compon".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(MalformedSbomException.class);
        // A document that is not a BOM at all, or a well-formed BOM that simply carries no components, is a genuine
        // negative - an empty graph, never a failure - in strict mode too.
        assertThat(CycloneDxParser.parseStrict("this is not a bom".getBytes(StandardCharsets.UTF_8)).isEmpty()).isTrue();
        assertThat(CycloneDxParser.parseStrict(new byte[0]).isEmpty()).isTrue();
        assertThat(CycloneDxParser.parseStrict("{\"bomFormat\":\"CycloneDX\"}".getBytes(StandardCharsets.UTF_8))
                .isEmpty()).isTrue();
    }

    @Test
    void a_doctype_document_is_refused_so_no_external_entity_is_resolved() {
        // XXE hardening: an XML BOM carrying a DOCTYPE (the vector for a file:// external entity) is rejected
        // outright rather than parsed, since the document rides inside an untrusted artifact.
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE bom [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
                <bom xmlns="http://cyclonedx.org/schema/bom/1.6" version="1">
                  <components><component type="library" bom-ref="&xxe;"><name>x</name></component></components>
                </bom>
                """;

        assertThat(CycloneDxParser.parse(xxe.getBytes(StandardCharsets.UTF_8)).isEmpty()).isTrue();
    }

    @Test
    void an_oversized_document_is_refused_rather_than_held_in_heap() throws IOException {
        // A stream that would exceed MAX_DOCUMENT is refused without materialising it as a graph.
        InputStream oversize = repeating((byte) '{', (long) CycloneDxParser.MAX_DOCUMENT + 16);

        assertThat(CycloneDxParser.parse(oversize).isEmpty()).isTrue();
    }

    /** The JDK's own XML element-depth limit (default 100 via {@code conf/jaxp.properties}); lifted for the one test
     *  that needs the DOM to build a deep tree so the parser's own recursion bound - not the ambient JDK default - is
     *  what rejects it. */
    private static final String MAX_ELEMENT_DEPTH = "jdk.xml.maxElementDepth";

    @Test
    void a_pathologically_deep_xml_nesting_is_bounded_rather_than_overflowing_the_stack() {
        // CycloneDX's XML dependency graph nests recursively, so a ~200 KB BOM nesting a few thousand <dependency>
        // deep would overflow the stack in the walk - a StackOverflowError that, unlike a parse error, escapes the
        // read path and wedges the reverse-dependency sweep on that one blob forever. The JDK's own element-depth
        // limit is lifted here so the deep tree actually builds and the recursion runs (production may run it
        // unbounded); the parser's MAX_NESTING cap must then refuse the document as the ordinary empty graph.
        int depth = 20 * CycloneDxParser.MAX_NESTING;           // well past the few-thousand-deep stack-overflow point
        StringBuilder xml = new StringBuilder("<bom xmlns=\"http://cyclonedx.org/schema/bom/1.6\"><dependencies>");
        for (int level = 0; level < depth; level++) {
            xml.append("<dependency ref=\"pkg:maven/com.example/d").append(level).append("@1.0.0\">");
        }
        xml.append("</dependency>".repeat(depth)).append("</dependencies></bom>");

        String previous = System.getProperty(MAX_ELEMENT_DEPTH);
        System.setProperty(MAX_ELEMENT_DEPTH, "0");             // let the DOM build the deep tree the walk descends
        try {
            DependencyGraph graph = CycloneDxParser.parse(xml.toString().getBytes(StandardCharsets.UTF_8));
            assertThat(graph.isEmpty())
                    .as("a nesting past MAX_NESTING is refused as the empty graph, never a StackOverflowError").isTrue();
        } finally {
            if (previous == null) {
                System.clearProperty(MAX_ELEMENT_DEPTH);
            } else {
                System.setProperty(MAX_ELEMENT_DEPTH, previous);
            }
        }
    }

    private static DependencyComponent byRef(DependencyGraph graph, String ref) {
        DependencyComponent component = graph.componentsByRef().get(ref);
        assertThat(component).as("component " + ref).isNotNull();
        return component;
    }

    /** Build a jar whose manifest optionally points at {@code sbomEntry}, with a class entry ahead of the SBOM so
     *  the extractor is shown streaming past leading content rather than buffering the whole archive. */
    private static byte[] jar(String sbomEntry, boolean declareLocation, byte[] sbom) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (declareLocation && sbomEntry != null) {
            manifest.getMainAttributes().putValue("Sbom-Location", sbomEntry);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out, manifest)) {
            jar.putNextEntry(new JarEntry("com/example/App.class"));
            jar.write(new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
            jar.closeEntry();
            if (sbomEntry != null && sbom != null) {
                jar.putNextEntry(new JarEntry(sbomEntry));
                jar.write(sbom);
                jar.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** A stream that yields {@code count} copies of {@code value} without allocating them up front. */
    private static InputStream repeating(byte value, long count) {
        return new InputStream() {
            private long remaining = count;

            @Override
            public int read() {
                if (remaining <= 0) {
                    return -1;
                }
                remaining--;
                return value & 0xFF;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                if (remaining <= 0) {
                    return -1;
                }
                int produced = (int) Math.min(length, remaining);
                Arrays.fill(buffer, offset, offset + produced, value);
                remaining -= produced;
                return produced;
            }
        };
    }
}
