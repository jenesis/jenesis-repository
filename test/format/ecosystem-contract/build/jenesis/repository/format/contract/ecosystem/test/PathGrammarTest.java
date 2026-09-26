package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two path rules a client's own spelling depends on, each driven through the format that owns it: npm decodes the
 * encoded slash of a scoped package name and nowhere else, and an Ivy revision joins its module's listing on one of its
 * own files, never on a checksum or a signature a client uploads beside one.
 */
class PathGrammarTest {

    @TempDir
    Path root;

    @Test
    void an_encoded_scope_separator_reaches_the_scoped_package_and_nothing_else_is_decoded() throws IOException {
        ArtifactStore store = store("npm");
        byte[] tarball = "a scoped package".getBytes(StandardCharsets.UTF_8);
        ContractExchange publish = handle(store, ContractExchange.of("PUT", "/npm/@acme%2fwidget",
                Packages.npmEnvelope("@acme/widget", "1.0.0", tarball)));
        assertThat(publish.status()).as("npm publish sends the scope's slash encoded").isEqualTo(201);

        assertThat(handle(store, ContractExchange.of("GET", "/npm/@acme%2fwidget")).responseText())
                .contains("\"@acme/widget\"");
        assertThat(handle(store, ContractExchange.of("GET", "/npm/@acme/widget")).status()).isEqualTo(200);
        assertThat(handle(store, ContractExchange.of("GET", "/npm/..%2fetc")).status())
                .as("an encoded slash anywhere else stays part of a literal name").isEqualTo(404);
    }

    @Test
    void an_ivy_revision_joins_its_listing_on_its_own_file_and_not_on_a_sidecar() throws IOException {
        ArtifactStore store = store("ivy");
        byte[] body = "an ivy artifact".getBytes(StandardCharsets.UTF_8);
        assertThat(handle(store, ContractExchange.of("PUT", "/ivy/com.acme/widget/1.0/widget-1.0.jar", body)).status())
                .isEqualTo(201);
        // A client uploads a file's checksum whatever the gate did with the file, so a revision whose files were all
        // held must not be listed because its checksum landed.
        assertThat(handle(store, ContractExchange.of("PUT", "/ivy/com.acme/widget/2.0/widget-2.0.jar.sha1",
                "0".repeat(40).getBytes(StandardCharsets.UTF_8))).status()).isEqualTo(201);

        String listing = handle(store, ContractExchange.of("GET", "/ivy/com.acme/widget/")).responseText();
        assertThat(listing).contains("1.0").doesNotContain("2.0");
    }

    private static ContractExchange handle(ArtifactStore store, ContractExchange exchange) throws IOException {
        RepositoryFormat format = ServiceLoader.load(RepositoryFormat.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(candidate -> candidate.handles(exchange.path()))
                .findFirst().orElseThrow();
        format.handle(exchange, store);
        return exchange;
    }

    private ArtifactStore store(String name) throws IOException {
        Path directory = Files.createDirectories(root.resolve(name));
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? directory.toString() : null);
    }
}
