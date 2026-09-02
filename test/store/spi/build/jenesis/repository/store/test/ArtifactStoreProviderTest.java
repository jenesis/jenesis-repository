package build.jenesis.repository.store.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ServiceLoader resolution of the store backend: the bundled filesystem provider answers to its own name and reads its
 * root from the config lookup. No backend selected (null/blank) falls back to filesystem so a default deployment always
 * has a working store, but an <em>explicitly named</em> backend that no provider answers to fails loudly rather than
 * silently persisting against the local disk (a misconfigured {@code store=s3} must not boot against ephemeral storage).
 */
class ArtifactStoreProviderTest {

    @TempDir
    Path root;

    @Test
    void the_filesystem_backend_resolves_by_name_and_reads_its_root_from_config() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        store.write("blobs/x", new ByteArrayInputStream("hi".getBytes(StandardCharsets.UTF_8)));
        assertThat(store.exists("blobs/x")).isTrue();
    }

    @Test
    void no_backend_selected_falls_back_to_the_filesystem_provider() throws IOException {
        for (String unselected : new String[] {null, "", "  "}) {
            ArtifactStore store = ArtifactStoreProvider.resolve(
                    unselected, key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
            store.write("blobs/y", new ByteArrayInputStream("yo".getBytes(StandardCharsets.UTF_8)));
            assertThat(store.exists("blobs/y")).isTrue();
        }
    }

    @Test
    void an_explicitly_named_backend_with_no_provider_fails_loudly() {
        // A misconfigured or misspelled explicit selection must not silently serve and persist against the local
        // filesystem while the intended bucket 404s - it fails loudly, naming the backend it could not resolve, the
        // default it refuses to fall back to, and what is actually installed.
        assertThatThrownBy(() -> ArtifactStoreProvider.resolve(
                "does-not-exist", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'does-not-exist'")
                .hasMessageContaining("refusing to fall back to the 'filesystem' default")
                .hasMessageContaining("filesystem")
                .hasMessageContaining("needy");
    }

    @Test
    void a_selected_backend_whose_required_configuration_is_unset_fails_naming_the_key() {
        // An exclusive SPI must not self-disable the way an optional capability may: a store that cannot be
        // configured has to fail at resolution, not quietly persist somewhere else. The message names the key, so
        // the person reading it knows what to set.
        assertThatThrownBy(() -> ArtifactStoreProvider.resolve("filesystem", key -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("filesystem")
                .hasMessageContaining("jenreg.filesystem.root");
    }

    @Test
    void an_unselected_deployment_is_validated_too_rather_than_getting_an_invented_root() {
        // The load-bearing half. The fallback is validated exactly as an explicit selection is, which is what lets
        // filesystem stay the default CHOICE without being a silent one: an operator who configures nothing is
        // told what to set instead of getting a store at a path nobody picked - which, in a container, is the
        // writable layer, and gone on the next restart.
        assertThatThrownBy(() -> ArtifactStoreProvider.resolve(null, key -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jenreg.filesystem.root");
    }

    @Test
    void a_second_fully_configured_backend_is_refused_rather_than_quietly_ignored(@TempDir Path root) {
        // A deployment has exactly one store. Configuring a second one means the operator believes something untrue
        // about where their bytes go, and the failure it prevents is silent: the repository serves and persists
        // happily against the selected backend while everything the operator expects to find is in the bucket they
        // also configured and nothing reads.
        assertThatThrownBy(() -> ArtifactStoreProvider.resolve("filesystem", key -> switch (key) {
            case "jenreg.filesystem.root" -> root.toString();
            case "NEEDY_BUCKET" -> "a-bucket-that-was-meant";
            default -> null;
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("filesystem")
                .hasMessageContaining("needy")
                .hasMessageContaining("NEEDY_BUCKET")
                .hasMessageContaining("exactly one store");
    }

    @Test
    void a_rival_backend_that_is_merely_present_but_unconfigured_is_not_a_mix(@TempDir Path root) throws IOException {
        // The other half, and the reason the rule is "every required key" rather than "any key". The shipped
        // properties files declare every backend's keys so relaxed binding can reach them, and several carry real
        // defaults; a rule that fired on a present-but-blank key would refuse to start on every deployment there
        // is. Blank and absent both mean unconfigured.
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem", key -> switch (key) {
            case "jenreg.filesystem.root" -> root.toString();
            case "NEEDY_BUCKET" -> "   ";
            default -> null;
        });
        store.write("a", new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));
        assertThat(store.exists("a")).isTrue();
    }
}
