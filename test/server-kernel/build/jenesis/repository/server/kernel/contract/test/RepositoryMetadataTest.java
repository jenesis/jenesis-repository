package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.format.maven.MavenMetadata;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.cleanup.RetentionProvider;
import build.jenesis.repository.staging.StagingProvider;
import build.jenesis.repository.server.kernel.RepositoryProperties;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Maven format serves {@code maven-metadata.xml} by generating it on read from a repository's published version
 * folders, scoped to the tenant and named repository. A different repository in the same tenant shares nothing.
 * Versions are published straight through {@link Publication} into the scoped store - the same space the generator
 * reads - so this isolates the scoping and generation from the compliance gate, which its own tests cover. The proxy
 * flag is read from {@link Repositories} to confirm the deployment is hosted.
 */
class RepositoryMetadataTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private Repositories repositories;

    @BeforeEach
    void setUp() throws IOException {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        RepositoryProperties properties = new RepositoryProperties();
        properties.setProxyEnabled(false);
        LiveConfig live = new LiveConfig(new Settings(store), properties, AdvisorySource.none(), _ -> null);
        repositories = new Repositories(store, Authorization.anonymous(), live,
                StagingProvider.resolve(_ -> null), RetentionProvider.resolve(_ -> null));
    }

    @Test
    void generated_metadata_lists_a_repositorys_versions_and_is_scoped() throws IOException {
        Publication publication = new Publication(store.scope("acme").scope("releases"));
        publication.link("/maven/org/example/lib/1.0/lib-1.0.jar", "h1");
        publication.link("/maven/org/example/lib/2.0/lib-2.0.jar", "h2");

        String xml = new String(new MavenMetadata(store.scope("acme").scope("releases"))
                .serve("/maven/org/example/lib/maven-metadata.xml").orElseThrow(), StandardCharsets.UTF_8);
        assertThat(xml).contains("<version>1.0</version>").contains("<version>2.0</version>")
                .contains("<release>2.0</release>");

        assertThat(new MavenMetadata(store.scope("acme").scope("other"))
                .serve("/maven/org/example/lib/maven-metadata.xml")).isEmpty();
        assertThat(repositories.proxying()).isFalse();
    }

    @Test
    void opt_in_computation_reconciles_a_stored_document_and_derives_when_absent_scoped() throws IOException {
        // with the computation opt-in, a stored document has only its <versions> list reconciled against the
        // stored folders (every other field preserved), and a coordinate that never had a document uploaded falls
        // back to a full derivation. Driven over the tenant-and-repo scoped store the format serves from, so it also
        // holds the computed metadata to its repository - a sibling repository sees nothing.
        ArtifactStore scoped = store.scope("acme").scope("releases");
        Publication publication = new Publication(scoped);
        publication.link("/maven/org/example/lib/1.0/lib-1.0.jar", "h1");
        publication.link("/maven/org/example/lib/2.0/lib-2.0.jar", "h2");
        byte[] document = ("<metadata>\n  <versioning>\n    <latest>9-CUSTOM</latest>\n"
                + "    <versions>\n      <version>1.0</version>\n    </versions>\n  </versioning>\n</metadata>")
                .getBytes(StandardCharsets.UTF_8);
        publication.link("/maven/org/example/lib/maven-metadata.xml",
                publication.storeBlob(new ByteArrayInputStream(document)));

        MavenMetadata metadata = new MavenMetadata(scoped);
        String merged = new String(metadata.computed("/maven/org/example/lib/maven-metadata.xml").orElseThrow(),
                StandardCharsets.UTF_8);
        assertThat(merged).as("the missing folder version is added, the publisher's field kept verbatim")
                .contains("<version>1.0</version>").contains("<version>2.0</version>")
                .contains("<latest>9-CUSTOM</latest>");

        publication.link("/maven/org/other/tool/3.0/tool-3.0.jar", "h3");
        assertThat(new String(metadata.computed("/maven/org/other/tool/maven-metadata.xml").orElseThrow(),
                StandardCharsets.UTF_8)).as("a coordinate with no stored document is derived").contains("<version>3.0</version>");

        assertThat(new MavenMetadata(store.scope("acme").scope("other"))
                .computed("/maven/org/example/lib/maven-metadata.xml"))
                .as("a sibling repository shares no computed metadata").isEmpty();
    }
}
