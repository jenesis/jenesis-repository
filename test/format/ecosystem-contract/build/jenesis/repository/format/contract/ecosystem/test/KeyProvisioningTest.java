package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.StoredCounter;
import build.jenesis.repository.store.StoredListing;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provisioning a repository's signing key is its operator's act and answers at once: the formats that sign their
 * own index declare the request administration, so the edge takes the operator's right for it and announces nothing,
 * and the repositories already indexed are signed on the node's derivation thread rather than on the request.
 */
class KeyProvisioningTest {

    @TempDir
    Path root;

    @AfterEach
    void settle() {
        StoredListing.settle();
        StoredCounter.settle();
    }

    @Test
    void the_formats_that_sign_their_index_declare_provisioning_administration() {
        assertThat(format("rpm").administers("POST", "/rpm/keyring")).isTrue();
        assertThat(format("debian").administers("POST", "/debian/keyring")).isTrue();
        assertThat(format("debian").administers("POST", "/debian/keyring/trusted")).isTrue();
        assertThat(format("terraform").administers("POST", "/terraform/registry/keys")).isTrue();

        assertThat(format("rpm").administers("PUT", "/rpm/release/pool/a-1.0-1.x86_64.rpm"))
                .as("a publish is not administration").isFalse();
        assertThat(format("rpm").administers("GET", "/rpm/keyring/public.asc"))
                .as("nor is reading the public key").isFalse();
        assertThat(format("debian").administers("PUT", "/debian/sid/pool/main/a_1.0_amd64.deb")).isFalse();
        assertThat(format("terraform").administers("GET", "/terraform/registry/keys")).isFalse();
    }

    @Test
    void provisioning_answers_before_the_repositories_already_indexed_are_signed() throws Exception {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        ContractExchange publish = ContractExchange.of("PUT", "/rpm/release/pool/tool-1.0-1.x86_64.rpm",
                Packages.rpm("tool", "1.0", "1", "x86_64"));
        format("rpm").handle(publish, store);
        assertThat(publish.status()).isEqualTo(201);
        assertThat(get(store, "/rpm/release/repodata/repomd.xml.asc").status())
                .as("an unsigned repository serves no signature").isEqualTo(404);

        // The node's derivation thread is held busy, so whatever provisioning hands it waits behind this.
        CountDownLatch busy = new CountDownLatch(1);
        StoredListing.later("held-" + UUID.randomUUID(), () -> {
            try {
                busy.await(1, TimeUnit.MINUTES);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            ContractExchange provision = ContractExchange.of("POST", "/rpm/keyring", new byte[0]);
            format("rpm").handle(provision, store);

            assertThat(provision.status()).as("the key is provisioned and answered at once").isEqualTo(200);
            assertThat(provision.responseText()).contains("BEGIN PGP PUBLIC KEY BLOCK");
            assertThat(get(store, "/rpm/release/repodata/repomd.xml.asc").status())
                    .as("the repository indexed before the key is not signed on the request").isEqualTo(404);
        } finally {
            busy.countDown();
        }
        StoredListing.settle();

        assertThat(get(store, "/rpm/release/repodata/repomd.xml.asc").status())
                .as("and is signed on the derivation thread").isEqualTo(200);
    }

    private static ContractExchange get(ArtifactStore store, String path) throws IOException {
        ContractExchange get = ContractExchange.of("GET", path);
        format("rpm").handle(get, store);
        return get;
    }

    private static RepositoryFormat format(String name) {
        return RepositoryFormat.installed(name).orElseThrow();
    }
}
