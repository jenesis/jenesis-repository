package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A coordinate as an artifact spells it, answered as the layout owning its ecosystem keys it - what a screen records a
 * publish under, so the facts it records land on the version every read resolves to. Asked of every installed format
 * here, as a deployment carries them.
 */
class CanonicalCoordinateTest {

    @TempDir
    Path root;

    @Test
    void a_declared_spelling_is_answered_as_the_layout_keys_it() {
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null));

        assertThat(inventory.canonical("NuGet", "Demo", "1.0.0"))
                .as("a NuGet id is lower-cased in every path the protocol serves")
                .hasValueSatisfying(coordinate -> assertThat(coordinate.coordinate()).isEqualTo("demo"));
        assertThat(inventory.canonical("PyPI", "Demo_Pkg", "1.0.0"))
                .as("a PyPI version's paths are file names only the store knows, so no path places it from the "
                        + "coordinate alone - and its inspector reports the PEP 503 form itself")
                .isEmpty();
        assertThat(inventory.canonical("NoSuchEcosystem", "demo", "1.0.0"))
                .as("nothing installed places it").isEmpty();
    }
}
