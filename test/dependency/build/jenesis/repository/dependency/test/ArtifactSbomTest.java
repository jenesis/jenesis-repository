package build.jenesis.repository.dependency.test;

import module java.base;
import module org.junit.jupiter.api;
import java.util.jar.Attributes;
import build.jenesis.repository.dependency.ArtifactSbom;
import build.jenesis.repository.dependency.DependencyComponent;
import build.jenesis.repository.dependency.DependencyGraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The embedded-SBOM extractor is bounded against a deflate bomb: reaching the SBOM entry means inflating every jar
 * entry that precedes it, so a max-ratio deflated ("deflate bomb") or arbitrarily large entry standing before (or in
 * place of) the SBOM must not pin the reading thread inflating gigabytes - the vector that let a crafted hosted jar
 * turn a single {@code /api/sbom} GET into a minutes-to-hours request. The scan is capped at a fixed inflated-byte
 * budget: a jar that does not surface its SBOM within it is read as carrying none, while a legitimately-positioned SBOM
 * (early, or behind a modest prefix) still reads. No network, no framework - the extractor is driven over in-memory
 * jars built here.
 */
class ArtifactSbomTest {

    private static final String BOM = """
            { "bomFormat": "CycloneDX", "specVersion": "1.6", "version": 1,
              "metadata": { "component": { "type": "library", "bom-ref": "r", "group": "com.example",
                "name": "app", "version": "1.0.0", "purl": "pkg:maven/com.example/app@1.0.0" } },
              "components": [ { "type": "library", "bom-ref": "c", "group": "org.apache.logging.log4j",
                "name": "log4j-core", "version": "2.14.1",
                "purl": "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1" } ],
              "dependencies": [] }
            """;

    private static final String LOG4J_PURL = "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1";

    private static final String SPDX_JSON = """
            { "spdxVersion": "SPDX-2.3", "dataLicense": "CC0-1.0", "SPDXID": "SPDXRef-DOCUMENT", "name": "app",
              "documentNamespace": "https://jenesis.build/spdx/app-0",
              "creationInfo": { "created": "2024-01-01T00:00:00Z", "creators": [ "Tool: Jenesis" ] },
              "packages": [
                { "SPDXID": "SPDXRef-Package-0", "name": "app", "versionInfo": "1.0.0",
                  "externalRefs": [ { "referenceCategory": "PACKAGE-MANAGER", "referenceType": "purl",
                    "referenceLocator": "pkg:maven/com.example/app@1.0.0" } ] },
                { "SPDXID": "SPDXRef-Package-1", "name": "log4j-core", "versionInfo": "2.14.1",
                  "externalRefs": [ { "referenceCategory": "PACKAGE-MANAGER", "referenceType": "purl",
                    "referenceLocator": "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1" } ] } ],
              "relationships": [
                { "spdxElementId": "SPDXRef-DOCUMENT", "relationshipType": "DESCRIBES",
                  "relatedSpdxElement": "SPDXRef-Package-0" },
                { "spdxElementId": "SPDXRef-Package-0", "relationshipType": "DEPENDS_ON",
                  "relatedSpdxElement": "SPDXRef-Package-1" } ] }
            """;

    private static final String SPDX_TAG_VALUE = """
            SPDXVersion: SPDX-2.3
            DataLicense: CC0-1.0
            SPDXID: SPDXRef-DOCUMENT
            DocumentName: app

            PackageName: app
            SPDXID: SPDXRef-Package-0
            PackageVersion: 1.0.0
            ExternalRef: PACKAGE-MANAGER purl pkg:maven/com.example/app@1.0.0

            PackageName: log4j-core
            SPDXID: SPDXRef-Package-1
            PackageVersion: 2.14.1
            ExternalRef: PACKAGE-MANAGER purl pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1

            Relationship: SPDXRef-DOCUMENT DESCRIBES SPDXRef-Package-0
            Relationship: SPDXRef-Package-0 DEPENDS_ON SPDXRef-Package-1
            """;

    @Test
    void an_embedded_spdx_json_sbom_is_read() throws IOException {
        DependencyGraph graph = ArtifactSbom.graph(new ByteArrayInputStream(jar(List.of(),
                "META-INF/sbom/app.spdx.json", SPDX_JSON.getBytes(StandardCharsets.UTF_8)))).orElseThrow();
        assertThat(graph.directDependencies()).as("the SPDX JSON is dispatched to the SPDX parser and its edge read")
                .extracting(DependencyComponent::coordinate).containsExactly(LOG4J_PURL);
    }

    @Test
    void an_embedded_spdx_tag_value_sbom_is_read() throws IOException {
        DependencyGraph graph = ArtifactSbom.graph(new ByteArrayInputStream(jar(List.of(),
                "META-INF/sbom/app.spdx", SPDX_TAG_VALUE.getBytes(StandardCharsets.UTF_8)))).orElseThrow();
        assertThat(graph.directDependencies()).as("the tag-value SPDX is dispatched to the SPDX parser too")
                .extracting(DependencyComponent::coordinate).containsExactly(LOG4J_PURL);
    }

    @Test
    void a_deflate_bomb_before_an_spdx_sbom_is_bounded_and_the_scan_never_reaches_it() throws IOException {
        // The same bound guards the SPDX path: a 512 MiB-inflated entry standing before a valid SPDX SBOM must not be
        // inflated through to it. Bounded, the scan aborts inside the bomb and reads the jar as carrying no SBOM.
        byte[] jar = jar(List.of(512L * 1024 * 1024), "META-INF/sbom/app.spdx.json",
                SPDX_JSON.getBytes(StandardCharsets.UTF_8));
        Optional<DependencyGraph> graph = assertTimeoutPreemptively(Duration.ofSeconds(30),
                () -> ArtifactSbom.graph(new ByteArrayInputStream(jar)));
        assertThat(graph).as("the scan is bounded before the SPDX SBOM, exactly as for CycloneDX").isEmpty();
    }

    @Test
    void a_malformed_spdx_sbom_reads_as_empty_rather_than_deranging_the_scan() throws IOException {
        // A truncated SPDX JSON parses to nothing; the extractor returns empty (a permanent negative) rather than
        // letting a parse throw escape and derail the sweep that scans every blob.
        byte[] jar = jar(List.of(), "META-INF/sbom/app.spdx.json",
                "{ \"spdxVersion\": \"SPDX-2.3\", \"packages\": [".getBytes(StandardCharsets.UTF_8));
        assertThat(ArtifactSbom.graph(new ByteArrayInputStream(jar)))
                .as("a malformed SPDX SBOM is an empty negative, not a throw").isEmpty();
    }

    @Test
    void a_normal_jar_with_an_early_sbom_is_read() throws IOException {
        Optional<DependencyGraph> graph = ArtifactSbom.graph(new ByteArrayInputStream(jar(List.of(), true)));
        assertThat(graph).as("a normal jar's embedded BOM parses to a non-empty graph").isPresent();
        assertThat(graph.orElseThrow().isEmpty()).isFalse();
    }

    @Test
    void an_sbom_behind_a_modest_prefix_is_still_read() throws IOException {
        // A few MB of ordinary entries before the SBOM is well inside the scan budget, so the BOM still reads - the
        // bound refuses bombs, not legitimately-positioned SBOMs.
        assertThat(ArtifactSbom.graph(new ByteArrayInputStream(jar(List.of(4L * 1024 * 1024), true)))).isPresent();
    }

    @Test
    void a_deflate_bomb_before_the_sbom_is_bounded_and_the_scan_never_reaches_it() throws IOException {
        // A single entry that inflates to far more than the scan budget, standing before a perfectly valid SBOM entry.
        // Unbounded, the scan would inflate the whole bomb and then read the SBOM (a non-empty graph); bounded, it
        // aborts inside the bomb and never reaches the SBOM - so an empty result is the proof the scan was bounded, and
        // it returns in well under the time inflating a real max-ratio bomb through to the SBOM would take.
        byte[] jar = jar(List.of(512L * 1024 * 1024), true);   // 512 MiB inflated, >> the 64 MiB scan budget
        Optional<DependencyGraph> graph = assertTimeoutPreemptively(Duration.ofSeconds(30),
                () -> ArtifactSbom.graph(new ByteArrayInputStream(jar)));
        assertThat(graph).as("the scan is bounded: it aborts inside the bomb and never reaches the SBOM behind it")
                .isEmpty();
    }

    @Test
    void a_jar_carrying_no_sbom_is_empty() throws IOException {
        assertThat(ArtifactSbom.graph(new ByteArrayInputStream(jar(List.of(), false)))).isEmpty();
    }

    /** A jar declaring {@code Sbom-Location} (when {@code withSbom}), with one highly-compressible ("deflate bomb")
     *  entry per element of {@code prefixInflatedBytes} written before the SBOM entry. */
    private static byte[] jar(List<Long> prefixInflatedBytes, boolean withSbom) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (withSbom) {
            manifest.getMainAttributes().putValue("Sbom-Location", "META-INF/sbom/app.cdx.json");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out, manifest)) {
            int index = 0;
            for (long inflated : prefixInflatedBytes) {
                jar.putNextEntry(new JarEntry("big/zeros-" + index++ + ".bin"));
                writeZeros(jar, inflated);
                jar.closeEntry();
            }
            if (withSbom) {
                jar.putNextEntry(new JarEntry("META-INF/sbom/app.cdx.json"));
                jar.write(BOM.getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** A jar declaring {@code Sbom-Location} at {@code sbomEntry} and carrying {@code sbom} there, with one
     *  highly-compressible ("deflate bomb") entry per element of {@code prefixInflatedBytes} written before it - the
     *  general form used to embed an SPDX SBOM (JSON or tag-value) at an arbitrary path. */
    private static byte[] jar(List<Long> prefixInflatedBytes, String sbomEntry, byte[] sbom) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Sbom-Location", sbomEntry);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out, manifest)) {
            int index = 0;
            for (long inflated : prefixInflatedBytes) {
                jar.putNextEntry(new JarEntry("big/zeros-" + index++ + ".bin"));
                writeZeros(jar, inflated);
                jar.closeEntry();
            }
            jar.putNextEntry(new JarEntry(sbomEntry));
            jar.write(sbom);
            jar.closeEntry();
        }
        return out.toByteArray();
    }

    /** Write {@code count} zero bytes a chunk at a time, never holding them all in heap - zeros deflate to a fraction
     *  of a percent, so a small compressed jar carries a huge inflated entry (the bomb the scan must refuse). */
    private static void writeZeros(OutputStream out, long count) throws IOException {
        byte[] chunk = new byte[64 * 1024];
        long remaining = count;
        while (remaining > 0) {
            out.write(chunk, 0, (int) Math.min(chunk.length, remaining));
            remaining -= chunk.length;
        }
    }
}
