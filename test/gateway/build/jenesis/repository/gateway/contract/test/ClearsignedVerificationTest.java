package build.jenesis.repository.gateway.contract.test;

import module org.junit.jupiter.api;
import module java.base;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.format.signing.OpenPgpSigner;
import build.jenesis.repository.format.signing.OpenPgpVerification;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OpenPGP verifier over clearsigned documents - the form a Helm provenance file and an apt {@code InRelease}
 * take: the signature is read with no key, the text is read back in its canonical form, the signature is verified
 * over that text by the keyring's key, and Helm's provenance grammar names (or fails to name) the covered archive.
 */
class ClearsignedVerificationTest {

    private static OpenPgpSigner signer;
    private static byte[] keyring;

    @BeforeAll
    static void key() throws IOException {
        signer = new OpenPgpSigner(OpenPgpSigner.generate("Publisher <publisher@example.com>", Duration.ofDays(365))
                .secretKey());
        keyring = signer.publicKeyring();
    }

    @Test
    void a_clearsigned_document_states_its_signer_and_gives_its_text_back() throws IOException {
        byte[] text = "name: my-chart\nversion: 1.0.0   \n\nfiles:\n  my-chart-1.0.0.tgz: sha256:00\n"
                .getBytes(StandardCharsets.UTF_8);
        byte[] document = signer.clearSigned(text);

        assertThat(OpenPgpVerification.isClearsigned(document)).isTrue();
        Optional<OpenPgpVerification.Facts> facts = OpenPgpVerification.facts(document);
        assertThat(facts).isPresent();
        assertThat(facts.get().keyId()).isEqualTo(signer.keyId());
        assertThat(facts.get().hashAlgorithm()).isEqualTo("SHA-256");
        // Canonical: trailing whitespace dropped, lines joined by \n, no trailing newline.
        assertThat(OpenPgpVerification.cleartext(document)).contains(
                "name: my-chart\nversion: 1.0.0\n\nfiles:\n  my-chart-1.0.0.tgz: sha256:00".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void a_detached_signature_is_not_clearsigned_and_a_clearsigned_document_is_not_detached() throws IOException {
        byte[] detached = signer.detachedSignature("artifact".getBytes(StandardCharsets.UTF_8),
                OpenPgpSigner.Encoding.ARMOURED);
        assertThat(OpenPgpVerification.isClearsigned(detached)).isFalse();
        assertThat(OpenPgpVerification.cleartext(detached)).isEmpty();
        assertThat(OpenPgpVerification.verifyClearsigned(detached, keyring)).isEqualTo(OpenPgpVerification.Result.INVALID);
        assertThat(OpenPgpVerification.cleartext("not signed at all".getBytes(StandardCharsets.UTF_8))).isEmpty();
    }

    @Test
    void the_signature_verifies_over_the_text_it_carries_by_the_keyring_key() throws IOException {
        byte[] document = signer.clearSigned("a statement\nover two lines\n".getBytes(StandardCharsets.UTF_8));

        assertThat(OpenPgpVerification.verifyClearsigned(document, keyring)).isEqualTo(OpenPgpVerification.Result.VALID);
        assertThat(OpenPgpVerification.verifyClearsigned(document, null)).isEqualTo(OpenPgpVerification.Result.NO_KEY);
    }

    @Test
    void a_document_whose_text_was_altered_after_signing_is_invalid() throws IOException {
        byte[] document = signer.clearSigned("version: 1.0.0\n".getBytes(StandardCharsets.UTF_8));
        String altered = new String(document, StandardCharsets.UTF_8).replace("version: 1.0.0", "version: 1.0.1");

        assertThat(OpenPgpVerification.verifyClearsigned(altered.getBytes(StandardCharsets.UTF_8), keyring))
                .isEqualTo(OpenPgpVerification.Result.INVALID);
    }

    @Test
    void helms_provenance_names_the_archive_by_its_digest_or_names_nothing() throws Exception {
        byte[] archive = HelmFixtures.chart(HelmFixtures.NAME, HelmFixtures.VERSION);
        byte[] statement = HelmFixtures.statement(HelmFixtures.NAME, HelmFixtures.VERSION, archive);
        byte[] other = HelmFixtures.chart(HelmFixtures.NAME, "2.0.0");

        assertThat(OpenPgpVerification.provenanceNames(statement, over(archive))).isTrue();
        assertThat(OpenPgpVerification.provenanceNames(statement, over(other))).isFalse();
        assertThat(OpenPgpVerification.provenanceNames("no files map here\n".getBytes(StandardCharsets.UTF_8),
                over(archive))).isFalse();
    }

    private static ArtifactSignatures.Signed over(byte[] bytes) {
        return () -> new ByteArrayInputStream(bytes);
    }
}
