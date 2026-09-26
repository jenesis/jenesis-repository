package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A repository moved off Artifactory arrives as the files Artifactory stores, at the paths it stores them under, and
 * each format's importer replays them through the format's own publish. These drive that for the three layouts only
 * Artifactory hosts among the incumbents - Alpine, Swift and Terraform - in process, over a filesystem store: the
 * importer is asked where a documented Artifactory path goes, handed the bytes, and the format must then serve the
 * artifact and list it where its clients look.
 */
class ArtifactoryLayoutImportTest {

    @TempDir
    Path root;

    @Test
    void an_alpine_package_is_imported_from_its_branch_repository_and_architecture() throws IOException {
        ArtifactStore store = store("apk");
        RepositoryImporter importer = importer("alpine");
        byte[] apk = Packages.apk("widget", "1.2.3-r0", "x86_64");
        String source = "v3.20/main/x86_64/widget-1.2.3-r0.apk";

        ArtifactDescriptor target = importer.importTarget(source).orElseThrow();
        assertThat(target.coordinate()).isEqualTo("widget");
        assertThat(target.version()).isEqualTo("1.2.3-r0");
        importer.importArtifact(source, new ByteArrayInputStream(apk), store);

        assertThat(get(store, target.path()).responseBytes()).isEqualTo(apk);
        assertThat(get(store, "/apk/main/x86_64/APKINDEX").responseText()).contains("P:widget", "V:1.2.3-r0");
        assertThat(importer.importTarget("v3.20/main/x86_64/APKINDEX.tar.gz"))
                .as("the incumbent's index is signed with its key and derived here").isEmpty();
    }

    @Test
    void a_swift_release_is_imported_from_its_scope_and_name() throws IOException {
        ArtifactStore store = store("swift");
        RepositoryImporter importer = importer("swift");
        byte[] archive = Packages.zip(new LinkedHashMap<>(Map.of("widget-kit/Package.swift",
                "// swift-tools-version:5.9\n".getBytes(StandardCharsets.UTF_8))));
        // A name carrying a hyphen, which only the folder it sits in can split from the version.
        String source = "acme/widget-kit/widget-kit-2.0.1.zip";

        ArtifactDescriptor target = importer.importTarget(source).orElseThrow();
        assertThat(target.coordinate()).isEqualTo("acme.widget-kit");
        assertThat(target.version()).isEqualTo("2.0.1");
        importer.importArtifact(source, new ByteArrayInputStream(archive), store);

        assertThat(get(store, "/swift/swift/acme/widget-kit/2.0.1.zip").responseBytes()).isEqualTo(archive);
        assertThat(get(store, "/swift/swift/acme/widget-kit").responseText()).contains("\"2.0.1\"");
        assertThat(importer.importTarget("acme/widget-kit/2.0.1/Package.swift")).isEmpty();
    }

    @Test
    void a_terraform_provider_is_imported_as_it_is_and_listed_for_its_platform() throws IOException {
        ArtifactStore store = store("terraform-provider");
        RepositoryImporter importer = importer("terraform");
        byte[] binary = "a provider binary".getBytes(StandardCharsets.UTF_8);
        String source = "acme/widget/1.4.0/terraform-provider-widget_1.4.0_linux_amd64.zip";

        ArtifactDescriptor target = importer.importTarget(source).orElseThrow();
        assertThat(target.version()).isEqualTo("1.4.0");
        importer.importArtifact(source, new ByteArrayInputStream(binary), store);

        assertThat(get(store, target.path()).responseBytes()).isEqualTo(binary);
        assertThat(get(store, "/terraform/terraform/v1/providers/acme/widget/versions").responseText())
                .contains("\"1.4.0\"", "\"linux\"", "\"amd64\"");
        assertThat(importer.importTarget("acme/widget/1.4.0/terraform-provider-widget_1.4.0_SHA256SUMS"))
                .as("the sums are derived and signed with this repository's key").isEmpty();
        assertThat(importer.importTarget("acme/widget/1.4.0/terraform-provider-other_1.4.0_linux_amd64.zip"))
                .as("a file naming another provider than its folder").isEmpty();
    }

    @Test
    void a_terraform_module_zip_is_imported_as_the_archive_this_registry_serves() throws IOException {
        ArtifactStore store = store("terraform-module");
        RepositoryImporter importer = importer("terraform");
        byte[] main = "resource \"null_resource\" \"widget\" {}\n".getBytes(StandardCharsets.UTF_8);
        byte[] module = Packages.zip(new LinkedHashMap<>(Map.of("main.tf", main)));
        String source = "acme/network/aws/3.1.0.zip";

        ArtifactDescriptor target = importer.importTarget(source).orElseThrow();
        assertThat(target.version()).isEqualTo("3.1.0");
        importer.importArtifact(source, new ByteArrayInputStream(module), store);

        ContractExchange download = get(store, "/terraform/terraform/v1/modules/acme/network/aws/3.1.0/download");
        assertThat(download.status()).isEqualTo(204);
        assertThat(download.responseHeader("X-Terraform-Get")).endsWith("/modules/acme/network/aws/3.1.0.tar.gz");
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new GZIPInputStream(new ByteArrayInputStream(
                get(store, "/terraform/terraform/modules/acme/network/aws/3.1.0.tar.gz").responseBytes())))) {
            for (TarArchiveEntry entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry()) {
                files.put(entry.getName(), tar.readAllBytes());
            }
        }
        assertThat(files).containsOnlyKeys("main.tf");
        assertThat(files.get("main.tf")).isEqualTo(main);
    }

    @Test
    void a_terraform_module_naming_an_entry_outside_itself_is_refused() throws IOException {
        ArtifactStore store = store("terraform-hostile");
        byte[] module = Packages.zip(new LinkedHashMap<>(Map.of("../escape.tf",
                "x".getBytes(StandardCharsets.UTF_8))));

        Assertions.assertThrows(IOException.class, () -> importer("terraform")
                .importArtifact("acme/network/aws/3.1.1.zip", new ByteArrayInputStream(module), store));
        assertThat(get(store, "/terraform/terraform/v1/modules/acme/network/aws/3.1.1/download").status())
                .isEqualTo(404);
    }

    private static RepositoryImporter importer(String sourceFormat) {
        return ServiceLoader.load(RepositoryFormat.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(RepositoryImporter.class::isInstance)
                .map(RepositoryImporter.class::cast)
                .filter(importer -> importer.imports(sourceFormat))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no importer claims " + sourceFormat));
    }

    private static ContractExchange get(ArtifactStore store, String path) throws IOException {
        ContractExchange exchange = ContractExchange.of("GET", path);
        RepositoryFormat format = ServiceLoader.load(RepositoryFormat.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(candidate -> candidate.handles(path))
                .findFirst()
                .orElseThrow();
        format.handle(exchange, store);
        return exchange;
    }

    private ArtifactStore store(String name) throws IOException {
        Path directory = Files.createDirectories(root.resolve(name));
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? directory.toString() : null);
    }
}
