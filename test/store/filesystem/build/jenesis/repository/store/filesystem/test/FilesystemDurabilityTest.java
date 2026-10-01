package build.jenesis.repository.store.filesystem.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.filesystem.FilesystemArtifactStore;
import build.jenesis.repository.store.filesystem.FilesystemArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The filesystem store forces every write to the disk before it answers unless a deployment says otherwise: the test
 * lanes run relaxed, so this is where the shipped default is held, read off the provider with nothing set.
 */
class FilesystemDurabilityTest {

    @TempDir
    Path root;

    private FilesystemArtifactStore store(String durability) {
        return (FilesystemArtifactStore) new FilesystemArtifactStoreProvider().create(key -> switch (key) {
            case "jenrepo.filesystem.root" -> root.toString();
            case FilesystemArtifactStoreProvider.DURABILITY_KEY -> durability;
            default -> null;
        });
    }

    @Test
    void a_deployment_that_sets_nothing_writes_durably() {
        // The test JVM runs relaxed; nothing set means nothing set, so the process's own choice is lifted for this
        // assertion and put back.
        String process = System.clearProperty(FilesystemArtifactStoreProvider.DURABILITY_KEY);
        try {
            assertThat(FilesystemArtifactStoreProvider.DURABILITY_DEFAULT).isEqualTo("strict");
            assertThat(store(null).durable()).as("nothing set").isTrue();
            assertThat(store("strict").durable()).isTrue();
        } finally {
            if (process != null) {
                System.setProperty(FilesystemArtifactStoreProvider.DURABILITY_KEY, process);
            }
        }
    }

    @Test
    void a_lookup_that_names_nothing_takes_the_processs_own_choice() {
        String process = System.setProperty(FilesystemArtifactStoreProvider.DURABILITY_KEY, "relaxed");
        try {
            assertThat(store(null).durable()).as("the JVM was started relaxed").isFalse();
            assertThat(store("strict").durable()).as("a lookup that names one wins").isTrue();
        } finally {
            if (process == null) {
                System.clearProperty(FilesystemArtifactStoreProvider.DURABILITY_KEY);
            } else {
                System.setProperty(FilesystemArtifactStoreProvider.DURABILITY_KEY, process);
            }
        }
    }

    @Test
    void relaxed_leaves_the_flush_to_the_operating_system_and_every_scope_agrees() throws IOException {
        FilesystemArtifactStore relaxed = store("relaxed");
        assertThat(relaxed.durable()).isFalse();
        ArtifactStore scoped = relaxed.scope("acme").scope("app");
        assertThat(((FilesystemArtifactStore) scoped).durable()).as("a scoped view keeps its store's durability")
                .isFalse();
        scoped.write("a/b", new ByteArrayInputStream("bytes".getBytes(StandardCharsets.UTF_8)));
        assertThat(scoped.readVersioned("a/b").orElseThrow().content()).asString(StandardCharsets.UTF_8)
                .isEqualTo("bytes");
    }

    @Test
    void a_value_the_store_does_not_offer_is_refused_naming_the_key() {
        assertThatThrownBy(() -> store("sometimes")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(FilesystemArtifactStoreProvider.DURABILITY_KEY);
    }
}
