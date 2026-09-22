package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.signing.OpenPgpSigner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The repository's OpenPGP signing key: its expiry and its rotation. A generated key carries an expiration; a key
 * within its rotation window (or past it) is due for rotation while a fresh one is not; and merging the retiring
 * key's public half with a fresh one keeps both in the served keyring, de-duplicates a repeat, and prunes a key
 * expired past the cutoff.
 *
 * <p>It used to be named for the Debian format, which is where the key handling happened to live before the RPM
 * format turned out to carry a byte-identical copy of it. The rotation policy this asserts is now one policy rather
 * than one per format - which is the point of asserting it in one place.
 */
class OpenPgpSignerTest {

    private static final String IDENTITY = "Test <test@example.com>";

    @Test
    void a_generated_key_carries_its_expiry() throws IOException {
        Instant now = Instant.now();
        OpenPgpSigner signer = new OpenPgpSigner(OpenPgpSigner.generate(IDENTITY, Duration.ofDays(730)).secretKey());

        assertThat(signer.expiresAt())
                .isAfter(now.plus(Duration.ofDays(729)))
                .isBefore(now.plus(Duration.ofDays(731)));
    }

    @Test
    void a_fresh_key_is_not_due_for_rotation_but_a_near_expiry_one_is() throws IOException {
        OpenPgpSigner signer = new OpenPgpSigner(OpenPgpSigner.generate(IDENTITY, Duration.ofDays(730)).secretKey());
        Instant now = Instant.now();

        assertThat(signer.dueForRotation(now, Duration.ofDays(90))).as("a fresh key").isFalse();
        assertThat(signer.dueForRotation(now.plus(Duration.ofDays(700)), Duration.ofDays(90)))
                .as("within 90 days of a 730-day expiry").isTrue();
    }

    @Test
    void a_short_lived_key_is_immediately_due_for_rotation() throws IOException {
        OpenPgpSigner signer = new OpenPgpSigner(OpenPgpSigner.generate(IDENTITY, Duration.ofSeconds(1)).secretKey());

        assertThat(signer.dueForRotation(Instant.now(), Duration.ofDays(90))).isTrue();
    }

    @Test
    void merging_keeps_both_keys_dedups_a_repeat_and_prunes_the_expired() throws IOException {
        byte[] retiring = OpenPgpSigner.generate(IDENTITY, Duration.ofDays(730)).publicKey();
        byte[] fresh = OpenPgpSigner.generate(IDENTITY, Duration.ofDays(730)).publicKey();
        Instant now = Instant.now();

        byte[] both = OpenPgpSigner.mergePublicKeyrings(retiring, fresh, now);
        assertThat(both.length).as("the served keyring carries the retiring and the fresh key")
                .isGreaterThan(retiring.length);

        byte[] again = OpenPgpSigner.mergePublicKeyrings(both, fresh, now);
        assertThat(again.length).as("merging the same fresh key again does not duplicate it").isEqualTo(both.length);

        byte[] pruned = OpenPgpSigner.mergePublicKeyrings(both, fresh, now.plus(Duration.ofDays(1000)));
        assertThat(pruned.length).as("keys expired past the cutoff are dropped").isLessThan(both.length);
    }
}
