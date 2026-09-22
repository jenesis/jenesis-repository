package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.format.signing.OpenPgpSigner;
import build.jenesis.repository.format.signing.OpenPgpVerification;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The consumer half of OpenPGP: what a detached signature states about itself with no key at all, and what checking it
 * against a keyring produces.
 *
 * <p>The two halves are tested apart because they are needed apart. Reading the facts is what lets an artifact be
 * described - "signed by this key, over SHA-256, in March" - before anyone has decided whether that key is trusted,
 * which is exactly the order the gate needs them in: the inspector records what it found, and a dimension decides
 * later what that is worth.
 */
class OpenPgpVerificationTest {

    private static final String IDENTITY = "Publisher <publisher@example.com>";

    private static OpenPgpSigner signer;
    private static byte[] keyring;

    @BeforeAll
    static void key() throws IOException {
        signer = new OpenPgpSigner(OpenPgpSigner.generate(IDENTITY, Duration.ofDays(365)).secretKey());
        keyring = signer.publicKeyring();
    }

    @Test
    void a_signature_states_its_algorithms_and_issuer_without_any_key() throws IOException {
        byte[] signature = signer.detachedSignature("artifact".getBytes(StandardCharsets.UTF_8),
                OpenPgpSigner.Encoding.ARMOURED);

        // Deliberately no keyring argument: this is the read an inspector does before it knows whether it trusts
        // anyone, and a grade computed from it is a fact about the signature rather than about the signer.
        Optional<OpenPgpVerification.Facts> facts = OpenPgpVerification.facts(signature);

        assertThat(facts).isPresent();
        assertThat(facts.get().keyAlgorithm()).isEqualTo("RSA");
        assertThat(facts.get().hashAlgorithm()).isEqualTo("SHA-256");
        assertThat(facts.get().keyId()).isEqualTo(signer.keyId());
        assertThat(facts.get().created()).isNotNull();
        assertThat(facts.get().issuer()).isNotBlank();
    }

    @Test
    void bytes_that_are_not_a_signature_read_as_no_facts() throws IOException {
        assertThat(OpenPgpVerification.facts("not a signature at all".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(OpenPgpVerification.facts(new byte[0])).isEmpty();
    }

    @Test
    void a_signature_verifies_over_the_bytes_it_was_made_for() throws IOException {
        byte[] body = "the artifact's bytes".getBytes(StandardCharsets.UTF_8);
        byte[] signature = signer.detachedSignature(body, OpenPgpSigner.Encoding.ARMOURED);

        assertThat(OpenPgpVerification.verify(source(body), signature, keyring))
                .isEqualTo(OpenPgpVerification.Result.VALID);
    }

    @Test
    void a_single_changed_byte_is_invalid_rather_than_untrusted() throws IOException {
        byte[] body = "the artifact's bytes".getBytes(StandardCharsets.UTF_8);
        byte[] signature = signer.detachedSignature(body, OpenPgpSigner.Encoding.ARMOURED);
        byte[] tampered = body.clone();
        tampered[0] ^= 0x01;

        // The distinction the gate gates on: tampering is INVALID, an unknown signer is NO_KEY, and collapsing the two
        // would either wave a modified artifact through or refuse every artifact whose maintainer we simply do not
        // know.
        assertThat(OpenPgpVerification.verify(source(tampered), signature, keyring))
                .isEqualTo(OpenPgpVerification.Result.INVALID);
    }

    @Test
    void a_bundle_of_several_armoured_keyrings_answers_for_every_key_in_it() throws IOException {
        // The trusted-keys setting promises "one or more concatenated -----BEGIN PGP PUBLIC KEY BLOCK----- sections",
        // and the armour decoder stops at the first block's end: a second publisher pasted after the first was
        // never read, so every signature by it was NO_KEY. Each block is read on its own now.
        OpenPgpSigner other = new OpenPgpSigner(OpenPgpSigner.generate("Other <other@example.com>",
                Duration.ofDays(365)).secretKey());
        byte[] bundle = (new String(keyring, StandardCharsets.US_ASCII)
                + new String(other.publicKeyring(), StandardCharsets.US_ASCII)).getBytes(StandardCharsets.US_ASCII);
        byte[] body = "signed by the second key in the bundle".getBytes(StandardCharsets.UTF_8);
        byte[] signature = other.detachedSignature(body, OpenPgpSigner.Encoding.ARMOURED);

        assertThat(OpenPgpVerification.fingerprint(other.keyId(), bundle)).isPresent();
        assertThat(OpenPgpVerification.fingerprint(signer.keyId(), bundle)).isPresent();
        assertThat(OpenPgpVerification.verify(source(body), signature, bundle))
                .isEqualTo(OpenPgpVerification.Result.VALID);
        assertThat(OpenPgpVerification.verify(source(body), signer.detachedSignature(body,
                OpenPgpSigner.Encoding.ARMOURED), bundle)).isEqualTo(OpenPgpVerification.Result.VALID);
    }

    @Test
    void a_signature_by_a_key_the_ring_does_not_carry_is_no_key() throws IOException {
        byte[] body = "the artifact's bytes".getBytes(StandardCharsets.UTF_8);
        byte[] signature = signer.detachedSignature(body, OpenPgpSigner.Encoding.ARMOURED);
        byte[] stranger = new OpenPgpSigner(OpenPgpSigner.generate("Other <other@example.com>",
                Duration.ofDays(365)).secretKey()).publicKeyring();

        assertThat(OpenPgpVerification.verify(source(body), signature, stranger))
                .isEqualTo(OpenPgpVerification.Result.NO_KEY);
        assertThat(OpenPgpVerification.verify(source(body), signature, null))
                .isEqualTo(OpenPgpVerification.Result.NO_KEY);
    }

    @Test
    void a_binary_encoded_signature_verifies_the_same_as_an_armoured_one() throws IOException {
        byte[] body = "terraform serves this one raw".getBytes(StandardCharsets.UTF_8);
        byte[] binary = signer.detachedSignature(body, OpenPgpSigner.Encoding.BINARY);

        assertThat(OpenPgpVerification.facts(binary)).isPresent();
        assertThat(OpenPgpVerification.verify(source(body), binary, keyring))
                .isEqualTo(OpenPgpVerification.Result.VALID);
    }

    @Test
    void the_key_behind_a_signature_reports_its_size_and_expiry() throws IOException {
        byte[] signature = signer.detachedSignature("x".getBytes(StandardCharsets.UTF_8),
                OpenPgpSigner.Encoding.ARMOURED);
        String keyId = OpenPgpVerification.facts(signature).orElseThrow().keyId();

        Optional<OpenPgpVerification.KeyFacts> key = OpenPgpVerification.keyFacts(keyId, keyring);

        assertThat(key).isPresent();
        assertThat(key.get().bits()).isEqualTo(3072);
        assertThat(key.get().expiry()).isNotNull().isAfter(Instant.now());
        assertThat(key.get().signingCapable()).isTrue();
        assertThat(OpenPgpVerification.fingerprint(keyId, keyring)).get().asString().hasSize(40);
    }

    /**
     * The claim that makes this usable on a publish thread: a body far larger than any sensible heap budget is
     * verified by streaming, so what can be checked is not bounded by what can be held.
     *
     * <p>Sixty-four mebibytes is two full prefix-inspection tiers, generated rather than allocated, and the assertion
     * is only that it verifies - the point is that the verifier never had the body.
     */
    @Test
    void a_body_larger_than_the_inspection_tier_is_verified_by_streaming() throws IOException {
        long length = 64L * 1024 * 1024;
        byte[] signature;
        try (InputStream body = generated(length)) {
            signature = signer.detachedSignature(body, OpenPgpSigner.Encoding.ARMOURED);
        }

        assertThat(OpenPgpVerification.verify(() -> generated(length), signature, keyring))
                .isEqualTo(OpenPgpVerification.Result.VALID);
    }

    private static ArtifactSignatures.Signed source(byte[] body) {
        return () -> new ByteArrayInputStream(body);
    }

    /** A deterministic stream of {@code length} bytes that is never materialised, so the test's own heap does not
     *  become the thing under test. */
    private static InputStream generated(long length) {
        return new InputStream() {

            private long produced;

            @Override
            public int read() {
                return produced < length ? (int) (produced++ % 251) : -1;
            }

            @Override
            public int read(byte[] buffer, int offset, int count) {
                if (produced >= length) {
                    return -1;
                }
                int written = (int) Math.min(count, length - produced);
                for (int index = 0; index < written; index++) {
                    buffer[offset + index] = (byte) ((produced + index) % 251);
                }
                produced += written;
                return written;
            }
        };
    }
}
