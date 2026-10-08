package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;
import module org.junit.jupiter.params;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.signing.OpenPgpSigner;
import build.jenesis.repository.format.signing.SigningKeys;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.StoredListing;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A repository whose signing key is near expiry rotates it when a publish rewrites the index it signs - Debian's
 * {@code Release}, RPM's {@code repomd.xml} - and signs that index with the fresh key, while the keyring it serves
 * carries the fresh key beside the retiring one. The signature the publish wrote verifies against the keyring served
 * after it and not against the one served before, so it is the fresh key's; and a second publish under the now
 * healthy key leaves the keyring as it is.
 */
class SigningKeyRotationOnWriteTest {

    /** One format that signs its own index: where its key is kept and served, how a package is pushed, and the index
     *  and detached signature a push writes. */
    record Signing(String format, String key, String keyring, String document, String signature,
                   List<Push> pushes) {

        @Override
        public String toString() {
            return format;
        }
    }

    /** A package pushed to {@code path}. */
    record Push(String path, byte[] body) {
    }

    static Stream<Signing> signings() throws IOException {
        return Stream.of(
                new Signing("debian", "debian/keyring/signing", "/debian/keyring/public.asc",
                        "/debian/dists/rotating/Release", "/debian/dists/rotating/Release.gpg", List.of(
                        new Push("/debian/rotating/pool/main/tool_1.0_amd64.deb",
                                Packages.deb("tool", "1.0", "amd64")),
                        new Push("/debian/rotating/pool/main/tool_1.1_amd64.deb",
                                Packages.deb("tool", "1.1", "amd64")))),
                new Signing("rpm", "rpm/keyring/signing", "/rpm/keyring/public.asc",
                        "/rpm/rotating/repodata/repomd.xml", "/rpm/rotating/repodata/repomd.xml.asc", List.of(
                        new Push("/rpm/rotating/pool/tool-1.0-1.x86_64.rpm",
                                Packages.rpm("tool", "1.0", "1", "x86_64")),
                        new Push("/rpm/rotating/pool/tool-1.1-1.x86_64.rpm",
                                Packages.rpm("tool", "1.1", "1", "x86_64")))));
    }

    @TempDir
    Path root;

    @ParameterizedTest(name = "{0}")
    @MethodSource("signings")
    void a_near_expiry_key_rotates_when_the_signed_index_is_written(Signing signing) throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        RepositoryFormat format = format(signing.format());
        // Thirty days of validity is inside the format's rotation window, as a key the repository failed to rotate
        // for most of its life would be.
        new SigningKeys(new Blobs(store).store(), signing.key(), "Jenesis Test <test@jenesis.build>",
                Duration.ofDays(30), Duration.ofDays(90)).provision();
        byte[] retiring = get(format, store, signing.keyring());

        push(format, store, signing.pushes().getFirst());
        byte[] document = get(format, store, signing.document());
        byte[] signature = get(format, store, signing.signature());
        byte[] keyring = get(format, store, signing.keyring());

        assertThat(OpenPgpSigner.verifies(document, signature, keyring))
                .as("the index the push wrote verifies against the keyring served after it").isTrue();
        assertThat(OpenPgpSigner.verifies(document, signature, retiring))
                .as("and was not signed by the retiring key, which is all the keyring before held").isFalse();
        assertThat(keyring).as("the served keyring changed, carrying the fresh key").isNotEqualTo(retiring);

        push(format, store, signing.pushes().getLast());
        assertThat(get(format, store, signing.keyring()))
                .as("a publish under the now healthy key does not rotate again").isEqualTo(keyring);
        assertThat(OpenPgpSigner.verifies(get(format, store, signing.document()),
                get(format, store, signing.signature()), keyring))
                .as("and the index it rewrote is signed by the same fresh key").isTrue();
    }

    private static void push(RepositoryFormat format, ArtifactStore store, Push push) throws IOException {
        ContractExchange exchange = ContractExchange.of("PUT", push.path(), push.body());
        format.handle(exchange, store);
        assertThat(exchange.status()).as("the push of %s", push.path()).isEqualTo(201);
        StoredListing.settle();
    }

    private static byte[] get(RepositoryFormat format, ArtifactStore store, String path) throws IOException {
        ContractExchange exchange = ContractExchange.of("GET", path);
        format.handle(exchange, store);
        assertThat(exchange.status()).as("GET %s", path).isEqualTo(200);
        return exchange.responseBytes();
    }

    private static RepositoryFormat format(String name) {
        return ServiceLoader.load(RepositoryFormat.class).stream().map(ServiceLoader.Provider::get)
                .filter(format -> format.name().equals(name)).findFirst().orElseThrow();
    }
}
