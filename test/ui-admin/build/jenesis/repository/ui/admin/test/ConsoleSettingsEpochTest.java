package build.jenesis.repository.ui.admin.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.settings.SettingsDocuments;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Epoch;
import build.jenesis.repository.ui.store.SettingsAdmin;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A settings write from the console moves the settings epoch, at every level it writes - the deployment's, a
 * tenant's, a repository's - because the repository server re-reads the stored settings on its schedule only when the
 * epoch moved: a console write that left it alone was invisible to the server beside it until something else wrote.
 */
class ConsoleSettingsEpochTest {

    @TempDir
    Path root;

    @Test
    void every_console_write_moves_the_epoch_the_repository_servers_re_read_watches() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        SettingsAdmin settings = new SettingsAdmin(store);
        Epoch epoch = new Epoch(store, SettingsDocuments.EPOCH);
        List<String> seen = new ArrayList<>(List.of(epoch.current()));

        settings.save("proxy-enabled", "false");
        seen.add(epoch.current());
        settings.save("acme", "vulnerability-threshold", "HIGH");
        seen.add(epoch.current());
        settings.saveRepository("acme", "libs", Map.of("routing", "writable"), true);
        seen.add(epoch.current());

        assertThat(seen).as("each write leaves a token the one before it did not").doesNotHaveDuplicates();
    }
}
