package build.jenesis.repository.format.maven.test;

import build.jenesis.repository.format.maven.MavenMetadata;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stored listing's re-assembly of {@code maven-metadata.xml}: the versions block is rendered from the entries and
 * every byte outside it is served as it was authored. The document the listing splits carries its placeholder BETWEEN
 * the tags - {@code <versions>versions</versions>} - and the placeholder word is a substring of both tags around it,
 * so an assembly that substitutes the word rather than the position dissolves the tags and emits the list three
 * times. The suite that shipped before this one asserted only that a {@code <version>} element was somewhere in the
 * answer, which a dissolved document still satisfies - so this one counts the tags instead.
 */
class MavenMetadataListingJoinTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private MavenMetadata metadata;
    private Publication publication;

    private static final String COORD = "org/example/lib";
    private static final String DOCUMENT = "/maven/" + COORD + "/maven-metadata.xml";

    @BeforeEach
    void setUp() throws IOException {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        publication = new Publication(store);
        metadata = new MavenMetadata(store);
    }

    /** Publish a version's jar and tell the listing about it, exactly as an upload does. */
    private void upload(String version) throws IOException {
        String path = "/maven/" + COORD + "/" + version + "/lib-" + version + ".jar";
        publication.link(path, publication.storeBlob(
                new ByteArrayInputStream(("jar-" + version).getBytes(StandardCharsets.UTF_8))));
        metadata.uploaded(path);
    }

    private static int occurrences(String document, String token) {
        int count = 0;
        for (int at = document.indexOf(token); at >= 0; at = document.indexOf(token, at + token.length())) {
            count++;
        }
        return count;
    }

    @Test
    void a_second_upload_reassembles_the_document_without_dissolving_its_tags() throws IOException {
        upload("1.0");
        // Reading materialises the listing: the document now EXISTS, so the next upload no longer regenerates it
        // from scratch - it splits the stored one into its entries and re-joins them, which is the path under test.
        metadata.served(DOCUMENT).orElseThrow();
        upload("2.0");

        String served = new String(metadata.served(DOCUMENT).orElseThrow(), StandardCharsets.UTF_8);

        assertThat(occurrences(served, "<versions>")).as("one opening tag, intact: %s", served).isEqualTo(1);
        assertThat(occurrences(served, "</versions>")).as("one closing tag, intact: %s", served).isEqualTo(1);
        assertThat(occurrences(served, "<version>1.0</version>")).as("each version listed once").isEqualTo(1);
        assertThat(occurrences(served, "<version>2.0</version>")).as("each version listed once").isEqualTo(1);
        assertThat(served).as("the surrounding document is still the one that was authored")
                .contains("<artifactId>lib</artifactId>").endsWith("</metadata>");
    }
}
