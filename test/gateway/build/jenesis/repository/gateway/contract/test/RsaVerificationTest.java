package build.jenesis.repository.gateway.contract.test;

import module org.junit.jupiter.api;
import module java.base;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.format.signing.RsaVerification;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bare-RSA verifier: what a signature member's name states, and how a signature is judged against a bundle of
 * public keys that names some of its keys and not others - by the named key alone when the signature names a
 * listed key, by the unnamed keys otherwise, and by nobody when the bundle is empty.
 */
class RsaVerificationTest {

    private static final byte[] CONTROL = "the control segment's compressed bytes".getBytes(StandardCharsets.UTF_8);

    private static KeyPair publisher;
    private static KeyPair stranger;

    @BeforeAll
    static void keys() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        publisher = generator.generateKeyPair();
        stranger = generator.generateKeyPair();
    }

    @Test
    void a_member_name_states_the_key_file_and_the_digest() {
        assertThat(RsaVerification.facts(".SIGN.RSA256.alpine-devel@lists.alpinelinux.org-4a6a0840.rsa.pub"))
                .contains(new RsaVerification.Facts("alpine-devel@lists.alpinelinux.org-4a6a0840.rsa.pub", "SHA-256"));
        assertThat(RsaVerification.facts(".SIGN.RSA.publisher.rsa.pub"))
                .contains(new RsaVerification.Facts("publisher.rsa.pub", "SHA-1"));
        assertThat(RsaVerification.facts(".SIGN.RSA512.publisher.rsa.pub").orElseThrow().jca()).isEqualTo("SHA512withRSA");
        assertThat(RsaVerification.facts(".PKGINFO")).isEmpty();
        assertThat(RsaVerification.facts(".SIGN.DSA.publisher.rsa.pub")).isEmpty();
        assertThat(RsaVerification.facts(null)).isEmpty();
    }

    @Test
    void a_bundle_names_its_keys_by_the_comment_line_before_each() throws GeneralSecurityException {
        byte[] bundle = bundle(Map.of("publisher.rsa.pub", publisher.getPublic()), List.of(stranger.getPublic()));

        List<RsaVerification.Key> keys = RsaVerification.keys(bundle);

        assertThat(keys).hasSize(2);
        assertThat(keys.get(0).name()).contains("publisher.rsa.pub");
        assertThat(keys.get(0).key()).isEqualTo(publisher.getPublic());
        assertThat(keys.get(1).name()).isEmpty();
        assertThat(RsaVerification.keys(null)).isEmpty();
        assertThat(RsaVerification.keys("not a bundle".getBytes(StandardCharsets.UTF_8))).isEmpty();
    }

    @Test
    void a_signature_naming_a_listed_key_is_judged_by_that_key_alone() throws Exception {
        byte[] signature = sign(publisher, "SHA256withRSA", CONTROL);
        RsaVerification.Facts facts = RsaVerification.facts(".SIGN.RSA256.publisher.rsa.pub").orElseThrow();
        byte[] bundle = bundle(Map.of("publisher.rsa.pub", publisher.getPublic()), List.of());

        RsaVerification.Verdict verdict = RsaVerification.verify(over(CONTROL), signature, facts, bundle);
        assertThat(verdict.result()).isEqualTo(RsaVerification.Result.VALID);
        assertThat(verdict.key()).contains(publisher.getPublic());
        assertThat(RsaVerification.bits(verdict.key().orElseThrow())).isEqualTo(2048);
        assertThat(RsaVerification.holds(facts, bundle)).isTrue();

        // The named key does not verify: the signer claimed exactly that key, so this is INVALID, not "no key".
        byte[] altered = "the control segment, altered".getBytes(StandardCharsets.UTF_8);
        assertThat(RsaVerification.verify(over(altered), signature, facts, bundle).result())
                .isEqualTo(RsaVerification.Result.INVALID);
        byte[] strangersUnderThatName = bundle(Map.of("publisher.rsa.pub", stranger.getPublic()), List.of());
        assertThat(RsaVerification.verify(over(CONTROL), signature, facts, strangersUnderThatName).result())
                .isEqualTo(RsaVerification.Result.INVALID);
    }

    @Test
    void a_signature_naming_no_listed_key_is_tried_against_the_unnamed_keys() throws Exception {
        byte[] signature = sign(publisher, "SHA256withRSA", CONTROL);
        RsaVerification.Facts facts = RsaVerification.facts(".SIGN.RSA256.publisher.rsa.pub").orElseThrow();

        byte[] unnamed = bundle(Map.of(), List.of(stranger.getPublic(), publisher.getPublic()));
        assertThat(RsaVerification.verify(over(CONTROL), signature, facts, unnamed).result())
                .isEqualTo(RsaVerification.Result.VALID);
        assertThat(RsaVerification.holds(facts, unnamed)).isTrue();

        byte[] strangersOnly = bundle(Map.of(), List.of(stranger.getPublic()));
        assertThat(RsaVerification.verify(over(CONTROL), signature, facts, strangersOnly).result())
                .isEqualTo(RsaVerification.Result.NO_KEY);
        byte[] otherNamesOnly = bundle(Map.of("somebody-else.rsa.pub", stranger.getPublic()), List.of());
        assertThat(RsaVerification.verify(over(CONTROL), signature, facts, otherNamesOnly).result())
                .isEqualTo(RsaVerification.Result.NO_KEY);
        assertThat(RsaVerification.holds(facts, otherNamesOnly)).isFalse();
        assertThat(RsaVerification.verify(over(CONTROL), signature, facts, null).result())
                .isEqualTo(RsaVerification.Result.NO_KEY);
    }

    @Test
    void the_older_sha1_form_verifies_when_the_signer_made_it_so() throws Exception {
        byte[] signature = sign(publisher, "SHA1withRSA", CONTROL);
        RsaVerification.Facts facts = RsaVerification.facts(".SIGN.RSA.publisher.rsa.pub").orElseThrow();

        assertThat(RsaVerification.verify(over(CONTROL), signature, facts,
                bundle(Map.of("publisher.rsa.pub", publisher.getPublic()), List.of())).result())
                .isEqualTo(RsaVerification.Result.VALID);
    }

    static byte[] sign(KeyPair pair, String algorithm, byte[] content) throws GeneralSecurityException {
        java.security.Signature signer = java.security.Signature.getInstance(algorithm);
        signer.initSign(pair.getPrivate());
        signer.update(content);
        return signer.sign();
    }

    static byte[] bundle(Map<String, PublicKey> named, List<PublicKey> unnamed) {
        StringBuilder out = new StringBuilder();
        named.forEach((name, key) -> out.append("# ").append(name).append('\n').append(pem(key)));
        unnamed.forEach(key -> out.append(pem(key)));
        return out.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static String pem(PublicKey key) {
        return "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(key.getEncoded()) + "\n-----END PUBLIC KEY-----\n";
    }

    private static ArtifactSignatures.Signed over(byte[] bytes) {
        return () -> new ByteArrayInputStream(bytes);
    }
}
