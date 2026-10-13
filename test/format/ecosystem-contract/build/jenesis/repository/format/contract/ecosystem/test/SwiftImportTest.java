package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.swift.SwiftImporter;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An imported Swift release serves the {@code Package.swift} its source archive carries: a client publishes the
 * manifest beside the archive and resolves a release through it, while an import has the archive alone.
 */
class SwiftImportTest {

    private static final String MANIFEST = "// swift-tools-version:5.9\nimport PackageDescription\n";

    @TempDir
    Path root;

    @Test
    void an_imported_release_serves_the_manifest_its_archive_carries() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(archive)) {
            zip.putNextEntry(new ZipEntry("Widget/Package.swift"));
            zip.write(MANIFEST.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("Widget/Sources/Widget/Widget.swift"));
            zip.write("public func widget() {}\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        new SwiftImporter().importArtifact("registry/acme/Widget/1.0.0.zip",
                new ByteArrayInputStream(archive.toByteArray()), store);

        ContractExchange manifest = ContractExchange.of("GET", "/swift/registry/acme/Widget/1.0.0/Package.swift")
                .header("Accept", "application/vnd.swift.registry.v1+swift");
        swift().handle(manifest, store);
        assertThat(manifest.status()).isEqualTo(200);
        assertThat(new String(manifest.responseBytes(), StandardCharsets.UTF_8)).isEqualTo(MANIFEST);
    }

    private static RepositoryFormat swift() {
        return RepositoryFormat.installed().stream().filter(format -> format.name().equals("swift")).findFirst()
                .orElseThrow();
    }
}
