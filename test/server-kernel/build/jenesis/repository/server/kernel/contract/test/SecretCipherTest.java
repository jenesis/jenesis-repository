package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.settings.SecretCipher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The AES-256-GCM envelope cipher for SECRET settings: a keyed round-trip, a fresh IV per encryption, rotation (an
 * older key still decrypts while the newest key seals new values), fail-closed decryption (wrong/absent key), and
 * fail-fast parsing of a malformed master-key environment value (§9).
 */
class SecretCipherTest {

    private static String key(byte fill) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, fill);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    void a_keyed_round_trip_produces_an_addressed_envelope_that_is_not_the_plaintext() {
        SecretCipher cipher = SecretCipher.of("k1:" + key((byte) 1));
        assertThat(cipher.configured()).isTrue();

        String envelope = cipher.encrypt("s3cr3t-token");
        assertThat(SecretCipher.isEnvelope(envelope)).isTrue();
        assertThat(envelope).startsWith("enc:v1:k1:").doesNotContain("s3cr3t-token");
        assertThat(cipher.decrypt(envelope)).isEqualTo("s3cr3t-token");
    }

    @Test
    void a_fresh_iv_makes_each_encryption_distinct_yet_both_decrypt() {
        SecretCipher cipher = SecretCipher.of("k1:" + key((byte) 2));
        String first = cipher.encrypt("same");
        String second = cipher.encrypt("same");
        assertThat(first).isNotEqualTo(second);   // a fresh random IV per call
        assertThat(cipher.decrypt(first)).isEqualTo("same");
        assertThat(cipher.decrypt(second)).isEqualTo("same");
    }

    @Test
    void a_rotation_key_id_decrypts_an_older_key_envelope_while_the_newest_key_writes() {
        SecretCipher old = SecretCipher.of("old:" + key((byte) 3));
        String sealedUnderOld = old.encrypt("legacy");

        // Rotation: the new key is prepended (active writer), the old key is retained as a candidate decryptor.
        SecretCipher rotated = SecretCipher.of("new:" + key((byte) 4) + ",old:" + key((byte) 3));
        assertThat(rotated.decrypt(sealedUnderOld)).isEqualTo("legacy");        // old key still opens the old envelope
        assertThat(rotated.encrypt("fresh")).startsWith("enc:v1:new:");         // new values seal under the newest key
    }

    @Test
    void decryption_fails_closed_when_the_key_bytes_are_wrong() {
        String envelope = SecretCipher.of("k1:" + key((byte) 5)).encrypt("x");
        SecretCipher wrongBytes = SecretCipher.of("k1:" + key((byte) 6));   // same id, different bytes -> tag mismatch
        assertThatThrownBy(() -> wrongBytes.decrypt(envelope)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void decryption_fails_closed_when_no_candidate_key_matches_the_envelope() {
        String envelope = SecretCipher.of("k1:" + key((byte) 7)).encrypt("x");
        SecretCipher otherId = SecretCipher.of("k2:" + key((byte) 7));   // the envelope names k1, absent here
        assertThatThrownBy(() -> otherId.decrypt(envelope))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("k1");
        SecretCipher unconfigured = SecretCipher.of(null);
        assertThatThrownBy(() -> unconfigured.decrypt(envelope)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void an_unconfigured_cipher_reports_it_and_refuses_to_encrypt() {
        assertThat(SecretCipher.of(null).configured()).isFalse();
        assertThat(SecretCipher.of("   ").configured()).isFalse();
        assertThatThrownBy(() -> SecretCipher.of(null).encrypt("x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void a_malformed_master_key_environment_value_fails_fast_naming_the_variable() {
        assertThatThrownBy(() -> SecretCipher.of("k1:not-valid-base64!!!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JENREG_SECRETS_KEY");
        // A base64 value that is not 32 bytes (here 16) is refused.
        assertThatThrownBy(() -> SecretCipher.of("k1:" + Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JENREG_SECRETS_KEY");
        // An entry with no <key-id>:<key> shape is refused.
        assertThatThrownBy(() -> SecretCipher.of("no-colon-entry"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JENREG_SECRETS_KEY");
    }

    @Test
    void is_envelope_recognises_only_the_versioned_prefix() {
        assertThat(SecretCipher.isEnvelope("enc:v1:k1:abcd")).isTrue();
        assertThat(SecretCipher.isEnvelope("plaintext")).isFalse();
        assertThat(SecretCipher.isEnvelope(null)).isFalse();
        assertThat(SecretCipher.isEnvelope("")).isFalse();
    }
}
