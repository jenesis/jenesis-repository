package build.jenesis.repository.gateway.contract.test;

import module org.junit.jupiter.api;
import module java.base;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.format.signing.Pkcs7Verification;
import build.jenesis.repository.signing.testkit.Pkcs7Fixtures;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PKCS#7 verifier against structures a specification-following writer made: what it reads with no trust, and how
 * it answers over covered bytes for the two shapes of CMS - encapsulating a hash statement (NuGet) and detached over
 * the bytes (Swift) - by a chain that reaches the anchor, one that reaches a stranger's, and none at all.
 */
class Pkcs7VerificationTest {

    private static final byte[] ARCHIVE = "the archive's bytes, as published".getBytes(StandardCharsets.UTF_8);

    private static Pkcs7Fixtures.Authority authority;
    private static Pkcs7Fixtures.Authority stranger;
    private static Pkcs7Fixtures.Signer publisher;
    private static byte[] anchors;

    @BeforeAll
    static void certificates() throws GeneralSecurityException, IOException {
        authority = Pkcs7Fixtures.authority("Acme Root");
        stranger = Pkcs7Fixtures.authority("Somebody Else's Root");
        publisher = Pkcs7Fixtures.signer(authority, "Acme Publisher", 2048);
        anchors = Pkcs7Fixtures.pem(authority.certificate());
    }

    @Test
    void a_signature_states_its_signer_algorithms_and_content_without_any_trust() throws Exception {
        byte[] statement = Pkcs7Fixtures.statement(ARCHIVE);
        byte[] signature = Pkcs7Fixtures.attached(publisher, statement);

        Optional<Pkcs7Verification.Facts> facts = Pkcs7Verification.facts(signature);

        assertThat(facts).isPresent();
        assertThat(facts.get().spkiSha256())
                .isEqualTo(Pkcs7Verification.spkiSha256(publisher.certificate().getPublicKey()))
                .matches("[0-9a-f]{64}");
        assertThat(facts.get().subject()).contains("Acme Publisher");
        assertThat(facts.get().keyAlgorithm()).isEqualTo("RSA");
        assertThat(facts.get().keyBits()).isEqualTo(2048);
        assertThat(facts.get().hashAlgorithm()).isEqualTo("SHA-256");
        assertThat(facts.get().signingTime()).isNotNull();
        assertThat(facts.get().notAfter()).isEqualTo(publisher.certificate().getNotAfter().toInstant());
        assertThat(facts.get().encapsulated()).contains(statement);
    }

    @Test
    void bytes_that_are_not_a_cms_structure_read_as_no_facts() throws IOException {
        assertThat(Pkcs7Verification.facts("not a signature at all".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(Pkcs7Verification.facts(new byte[0])).isEmpty();
        assertThat(Pkcs7Verification.facts(null)).isEmpty();
    }

    @Test
    void a_detached_signature_verifies_over_the_bytes_it_covers_by_a_chain_to_the_anchor() throws Exception {
        byte[] signature = Pkcs7Fixtures.detached(publisher, ARCHIVE);

        assertThat(Pkcs7Verification.verify(over(ARCHIVE), signature, anchors)).isEqualTo(Pkcs7Verification.Result.VALID);
        assertThat(Pkcs7Verification.anchored(signature, anchors)).isTrue();
    }

    @Test
    void a_detached_signature_over_other_bytes_is_invalid() throws Exception {
        byte[] signature = Pkcs7Fixtures.detached(publisher, ARCHIVE);
        byte[] tampered = "the archive's bytes, as altered".getBytes(StandardCharsets.UTF_8);

        assertThat(Pkcs7Verification.verify(over(tampered), signature, anchors))
                .isEqualTo(Pkcs7Verification.Result.INVALID);
    }

    @Test
    void a_good_signature_whose_chain_reaches_no_anchor_the_deployment_holds_is_no_key() throws Exception {
        byte[] signature = Pkcs7Fixtures.detached(publisher, ARCHIVE);
        byte[] strangers = Pkcs7Fixtures.pem(stranger.certificate());

        assertThat(Pkcs7Verification.verify(over(ARCHIVE), signature, strangers))
                .isEqualTo(Pkcs7Verification.Result.NO_KEY);
        assertThat(Pkcs7Verification.verify(over(ARCHIVE), signature, null))
                .isEqualTo(Pkcs7Verification.Result.NO_KEY);
        assertThat(Pkcs7Verification.anchored(signature, strangers)).isFalse();
        assertThat(Pkcs7Verification.anchored(signature, null)).isFalse();
    }

    @Test
    void an_encapsulated_statement_must_name_the_covered_bytes_for_the_signature_to_stand() throws Exception {
        byte[] signature = Pkcs7Fixtures.attached(publisher, Pkcs7Fixtures.statement(ARCHIVE));
        byte[] other = "some other archive entirely".getBytes(StandardCharsets.UTF_8);

        // Over the bytes the statement names: valid. Over others: the signature is fine and the statement is a lie,
        // which is INVALID for the artifact - the outcome an altered package must produce.
        assertThat(Pkcs7Verification.verify(over(ARCHIVE), signature, anchors)).isEqualTo(Pkcs7Verification.Result.VALID);
        assertThat(Pkcs7Verification.verify(over(other), signature, anchors)).isEqualTo(Pkcs7Verification.Result.INVALID);
        // With nothing to compare against, the signature over its own content is what is judged.
        assertThat(Pkcs7Verification.verify(null, signature, anchors)).isEqualTo(Pkcs7Verification.Result.VALID);
    }

    @Test
    void an_encapsulated_content_that_is_no_statement_names_nothing() throws Exception {
        byte[] signature = Pkcs7Fixtures.attached(publisher, "just some signed text".getBytes(StandardCharsets.UTF_8));

        assertThat(Pkcs7Verification.verify(over(ARCHIVE), signature, anchors))
                .isEqualTo(Pkcs7Verification.Result.INVALID);
        assertThat(Pkcs7Verification.verify(null, signature, anchors)).isEqualTo(Pkcs7Verification.Result.VALID);
    }

    @Test
    void a_signature_by_a_key_under_the_floor_still_verifies_and_states_its_size() throws Exception {
        Pkcs7Fixtures.Signer feeble = Pkcs7Fixtures.signer(authority, "Feeble Publisher", 1536);
        byte[] signature = Pkcs7Fixtures.detached(feeble, ARCHIVE);

        assertThat(Pkcs7Verification.verify(over(ARCHIVE), signature, anchors)).isEqualTo(Pkcs7Verification.Result.VALID);
        assertThat(Pkcs7Verification.facts(signature).orElseThrow().keyBits()).isEqualTo(1536);
    }

    private static ArtifactSignatures.Signed over(byte[] bytes) {
        return () -> new ByteArrayInputStream(bytes);
    }
}
