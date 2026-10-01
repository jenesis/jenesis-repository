package build.jenesis.repository.gateway.contract.test;

import module org.junit.jupiter.api;
import module java.base;
import build.jenesis.repository.format.signing.OpenPgpSigner;
import build.jenesis.repository.format.signing.SigningKeys;
import build.jenesis.repository.settings.SecretCipher;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.testkit.FaultInjectingStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A repository's signing key and the keyring it serves are one document: a rotation lands both or neither, whatever
 * stops it and however many nodes rotate at once, so the served keyring always verifies what the repository signs;
 * and the secret half is stored sealed with the deployment's master key, opened only by a node that holds it.
 */
class SigningKeysTest {

    private static final String KEY = "rpm/keyring/signing";
    private static final String IDENTITY = "Jenesis Test <test@jenesis.build>";
    private static final Duration WINDOW = Duration.ofDays(90);
    private static final byte[] CONTENT = "the index a repository signs".getBytes(StandardCharsets.UTF_8);
    private static final SecretCipher CIPHER = SecretCipher.of("primary:" + Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII)));
    private static final SecretCipher NONE = SecretCipher.of(null);

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
    }

    /** A key that is already due for rotation, as one written thirty days before it expires would be. */
    private void provisionNearExpiry(SecretCipher cipher) throws IOException {
        new SigningKeys(store, KEY, IDENTITY, Duration.ofDays(30), WINDOW, cipher).provision();
    }

    private SigningKeys keys(ArtifactStore over, SecretCipher cipher) {
        return new SigningKeys(over, KEY, IDENTITY, Duration.ofDays(730), WINDOW, cipher);
    }

    /** The stored signer as it stands - asked with no rotation window, so nothing is rotated to answer. */
    private OpenPgpSigner standing() throws IOException {
        return new SigningKeys(store, KEY, IDENTITY, Duration.ofDays(30), Duration.ZERO, CIPHER).signer()
                .orElseThrow();
    }

    private static boolean verifies(OpenPgpSigner signer, byte[] keyring) throws IOException {
        return OpenPgpSigner.verifies(CONTENT, signer.detachedSignature(CONTENT, OpenPgpSigner.Encoding.ARMOURED),
                keyring);
    }

    @Test
    void a_rotation_is_one_write_and_the_served_keyring_verifies_the_fresh_key_and_keeps_the_retiring_one()
            throws IOException {
        provisionNearExpiry(CIPHER);
        OpenPgpSigner retiring = standing();
        List<String> writes = new ArrayList<>();
        FaultInjectingStore traced = FaultInjectingStore.wrap(store).tracing((op, key) -> {
            if (key.equals(KEY) && op == FaultInjectingStore.Op.WRITE_VERSIONED) {
                writes.add(key);
            }
        });

        OpenPgpSigner fresh = keys(traced, CIPHER).signer().orElseThrow();

        assertThat(writes).as("the fresh key and the keyring that verifies it land in one write").hasSize(1);
        assertThat(fresh.keyId()).isNotEqualTo(retiring.keyId());
        byte[] keyring = keys(store, CIPHER).publicKeyring().orElseThrow();
        assertThat(verifies(fresh, keyring)).as("the served keyring verifies the fresh key").isTrue();
        assertThat(verifies(retiring, keyring)).as("and still the retiring one, through the overlap").isTrue();
        assertThat(keys(store, CIPHER).signer().orElseThrow().keyId()).as("a healthy key is not rotated again")
                .isEqualTo(fresh.keyId());
    }

    @Test
    void a_rotation_that_stops_before_it_lands_leaves_the_pair_that_stood() throws IOException {
        provisionNearExpiry(CIPHER);
        byte[] before = store.readVersioned(KEY).orElseThrow().content();
        FaultInjectingStore failing = FaultInjectingStore.wrap(store)
                .failNextOn(FaultInjectingStore.Op.WRITE_VERSIONED, KEY::equals);

        assertThatThrownBy(() -> keys(failing, CIPHER).signer()).isInstanceOf(IOException.class);

        assertThat(store.readVersioned(KEY).orElseThrow().content()).as("nothing of the rotation landed")
                .isEqualTo(before);
        OpenPgpSigner standing = standing();
        assertThat(verifies(standing, keys(store, CIPHER).publicKeyring().orElseThrow()))
                .as("the key that still signs is the one the keyring serves").isTrue();
    }

    @Test
    void two_nodes_rotating_at_once_sign_with_the_one_key_that_landed() throws Exception {
        provisionNearExpiry(CIPHER);
        ArtifactStore other = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        CyclicBarrier together = new CyclicBarrier(2);
        List<OpenPgpSigner> signers;
        try (ExecutorService nodes = Executors.newFixedThreadPool(2)) {
            Future<OpenPgpSigner> first = nodes.submit(() -> {
                together.await(1, TimeUnit.MINUTES);
                return keys(store, CIPHER).signer().orElseThrow();
            });
            Future<OpenPgpSigner> second = nodes.submit(() -> {
                together.await(1, TimeUnit.MINUTES);
                return keys(other, CIPHER).signer().orElseThrow();
            });
            signers = List.of(first.get(), second.get());
        }

        byte[] keyring = keys(store, CIPHER).publicKeyring().orElseThrow();
        for (OpenPgpSigner signer : signers) {
            assertThat(verifies(signer, keyring)).as("whichever node signed, the served keyring verifies it").isTrue();
        }
        assertThat(signers.get(0).keyId()).as("both nodes sign with the key that landed")
                .isEqualTo(signers.get(1).keyId());
    }

    @Test
    void the_secret_key_is_stored_sealed_and_opens_only_where_the_master_key_is_held() throws IOException {
        keys(store, CIPHER).provision();

        String stored = new String(store.readVersioned(KEY).orElseThrow().content(), StandardCharsets.UTF_8);
        assertThat(stored).contains("secret=enc:v1:primary:").doesNotContain("PRIVATE KEY")
                .doesNotContain("secret=plain:");
        assertThatThrownBy(() -> keys(store, NONE).signer()).as("a node without the master key cannot sign with it")
                .isInstanceOf(IOException.class);
        assertThat(keys(store, NONE).publicKeyring()).as("while the public half is served to anyone").isPresent();
    }

    @Test
    void a_key_stored_in_the_clear_is_sealed_when_a_node_holding_the_master_key_signs_with_it() throws IOException {
        OpenPgpSigner clear = keys(store, NONE).provision();
        assertThat(new String(store.readVersioned(KEY).orElseThrow().content(), StandardCharsets.UTF_8))
                .contains("secret=plain:");

        OpenPgpSigner sealed = keys(store, CIPHER).signer().orElseThrow();

        assertThat(sealed.keyId()).as("the same key, now sealed").isEqualTo(clear.keyId());
        assertThat(new String(store.readVersioned(KEY).orElseThrow().content(), StandardCharsets.UTF_8))
                .contains("secret=enc:v1:primary:").doesNotContain("secret=plain:");
    }

    @Test
    void one_key_is_provisioned_however_many_ask() throws IOException {
        OpenPgpSigner first = keys(store, CIPHER).provision();
        OpenPgpSigner second = keys(store, CIPHER).provision();

        assertThat(second.keyId()).isEqualTo(first.keyId());
        assertThat(keys(store, CIPHER).keyId()).contains(first.keyId());
    }
}
