package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.StoredListing;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code .deb} is indexed whichever of the three compressions {@code dpkg-deb} writes its {@code control.tar} in -
 * gzip, xz or zstd - so the stanza a push reads lands in the suite's {@code Packages} index under its own
 * architecture. The fixtures compress with the libraries the format decompresses with; that the format also reads
 * what the real {@code xz} and {@code zstd} tools write is {@code DebianFormatTest}'s.
 */
class DebianControlCompressionTest {

    @TempDir
    Path root;

    @Test
    void a_deb_whose_control_is_gzipped_is_indexed() throws IOException {
        indexed("control.tar.gz", "amd64");
    }

    @Test
    void a_deb_whose_control_is_xz_compressed_is_indexed() throws IOException {
        indexed("control.tar.xz", "arm64");
    }

    @Test
    void a_deb_whose_control_is_zstd_compressed_is_indexed() throws IOException {
        indexed("control.tar.zst", "riscv64");
    }

    private void indexed(String control, String architecture) throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        byte[] deb = Packages.deb("tool", "3.0", architecture, "", control);
        ContractExchange put = ContractExchange.of("PUT", "/debian/trixie/pool/main/tool_3.0_" + architecture + ".deb",
                deb);
        debian().handle(put, store);
        assertThat(put.status()).as("the push of a .deb whose control is %s", control).isEqualTo(201);
        StoredListing.settle();

        ContractExchange packages = ContractExchange.of("GET",
                "/debian/dists/trixie/main/binary-" + architecture + "/Packages");
        debian().handle(packages, store);
        assertThat(packages.status()).isEqualTo(200);
        assertThat(packages.responseText())
                .contains("Package: tool")
                .contains("Version: 3.0")
                .contains("Architecture: " + architecture)
                .contains("SHA256: " + Packages.sha256(deb));
    }

    private static RepositoryFormat debian() {
        return ServiceLoader.load(RepositoryFormat.class).stream().map(ServiceLoader.Provider::get)
                .filter(format -> format.name().equals("debian")).findFirst().orElseThrow();
    }
}
