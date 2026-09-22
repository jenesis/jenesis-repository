package build.jenesis.repository.gateway.contract.test;

import module org.junit.jupiter.api;
import module java.base;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.format.signing.Pkcs7Verification;
import build.jenesis.repository.format.signing.X509Verification;
import build.jenesis.repository.signing.testkit.Pkcs7Fixtures;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The verifier for a bare signature whose signer's chain rides with the artifact: what the chain states with no
 * trust, the signature by the leaf over the covered bytes under whichever digest the signer used, and the chain to
 * the deployment's anchors - reaching one, reaching a stranger's, reaching none.
 */
class X509VerificationTest {

    private static final byte[] MEMBER = "the data.tar.gz bytes".getBytes(StandardCharsets.UTF_8);

    private static Pkcs7Fixtures.Authority authority;
    private static Pkcs7Fixtures.Authority stranger;
    private static Pkcs7Fixtures.Signer publisher;
    private static byte[] chain;
    private static byte[] anchors;

    @BeforeAll
    static void certificates() throws GeneralSecurityException, IOException {
        authority = Pkcs7Fixtures.authority("Gem Root");
        stranger = Pkcs7Fixtures.authority("Somebody Else's Root");
        publisher = Pkcs7Fixtures.signer(authority, "Gem Publisher", 2048);
        chain = Pkcs7Fixtures.pem(publisher.certificate(), authority.certificate());
        anchors = Pkcs7Fixtures.pem(authority.certificate());
    }

    @Test
    void the_chain_states_its_signer_with_no_trust() {
        Optional<X509Verification.Facts> facts = X509Verification.facts(chain);

        assertThat(facts).isPresent();
        assertThat(facts.get().spkiSha256()).isEqualTo(Pkcs7Verification.spkiSha256(publisher.certificate().getPublicKey()));
        assertThat(facts.get().subject()).contains("Gem Publisher");
        assertThat(facts.get().keyAlgorithm()).isEqualTo("RSA");
        assertThat(facts.get().keyBits()).isEqualTo(2048);
        assertThat(facts.get().notAfter()).isEqualTo(publisher.certificate().getNotAfter().toInstant());
        assertThat(X509Verification.facts(null)).isEmpty();
        assertThat(X509Verification.facts("not a chain".getBytes(StandardCharsets.UTF_8))).isEmpty();
    }

    @Test
    void a_signature_by_the_leaf_over_the_member_verifies_and_the_chain_reaches_the_anchor() throws Exception {
        byte[] signature = sign(publisher, "SHA256withRSA", MEMBER);

        X509Verification.Verdict verdict = X509Verification.verify(over(MEMBER), signature, chain, anchors);

        assertThat(verdict.result()).isEqualTo(X509Verification.Result.VALID);
        assertThat(verdict.hashAlgorithm()).contains("SHA-256");
        assertThat(X509Verification.anchored(chain, anchors)).isTrue();
    }

    @Test
    void an_older_gems_sha1_signature_verifies_and_is_reported_as_such() throws Exception {
        byte[] signature = sign(publisher, "SHA1withRSA", MEMBER);

        X509Verification.Verdict verdict = X509Verification.verify(over(MEMBER), signature, chain, anchors);

        assertThat(verdict.result()).isEqualTo(X509Verification.Result.VALID);
        assertThat(verdict.hashAlgorithm()).contains("SHA-1");
    }

    @Test
    void a_signature_over_other_bytes_is_invalid() throws Exception {
        byte[] signature = sign(publisher, "SHA256withRSA", MEMBER);
        byte[] altered = "the data.tar.gz bytes, altered".getBytes(StandardCharsets.UTF_8);

        assertThat(X509Verification.verify(over(altered), signature, chain, anchors).result())
                .isEqualTo(X509Verification.Result.INVALID);
        assertThat(X509Verification.verify(over(MEMBER), new byte[0], chain, anchors).result())
                .isEqualTo(X509Verification.Result.INVALID);
        assertThat(X509Verification.verify(over(MEMBER), signature, null, anchors).result())
                .isEqualTo(X509Verification.Result.INVALID);
    }

    @Test
    void a_good_signature_whose_chain_reaches_no_anchor_the_deployment_holds_is_no_key() throws Exception {
        byte[] signature = sign(publisher, "SHA256withRSA", MEMBER);
        byte[] strangers = Pkcs7Fixtures.pem(stranger.certificate());

        assertThat(X509Verification.verify(over(MEMBER), signature, chain, strangers).result())
                .isEqualTo(X509Verification.Result.NO_KEY);
        assertThat(X509Verification.verify(over(MEMBER), signature, chain, null).result())
                .isEqualTo(X509Verification.Result.NO_KEY);
        assertThat(X509Verification.anchored(chain, strangers)).isFalse();
        // A chain that carries only the leaf still reaches an anchor that issued it directly.
        assertThat(X509Verification.anchored(Pkcs7Fixtures.pem(publisher.certificate()), anchors)).isTrue();
    }

    static byte[] sign(Pkcs7Fixtures.Signer signer, String algorithm, byte[] content) throws GeneralSecurityException {
        java.security.Signature signature = java.security.Signature.getInstance(algorithm);
        signature.initSign(signer.key());
        signature.update(content);
        return signature.sign();
    }

    private static ArtifactSignatures.Signed over(byte[] bytes) {
        return () -> new ByteArrayInputStream(bytes);
    }
}
