package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.apache.commons.compress;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.debian.DebianSignature;
import build.jenesis.repository.format.signing.OpenPgpSigner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code .deb} embedded-signature verifier: a package whose {@code _gpgorigin} detached signature (over the
 * concatenation of the archive's other members) verifies against a trusted key is {@link DebianSignature.Result#VALID
 * VALID}; a tampered package or one signed by an untrusted key is {@link DebianSignature.Result#UNTRUSTED UNTRUSTED};
 * and a package with no signature member is {@link DebianSignature.Result#UNSIGNED UNSIGNED}. Fixtures are signed with
 * a real OpenPGP key (Bouncy Castle), so this exercises the whole parse-and-verify path offline.
 */
class DebianSignatureTest {

    private static final byte[] DEBIAN_BINARY = "2.0\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONTROL = "control.tar.gz bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DATA = "data.tar.gz bytes".getBytes(StandardCharsets.UTF_8);

    @Test
    void a_deb_signed_by_a_trusted_key_is_valid() throws IOException {
        OpenPgpSigner.KeyMaterial signer = OpenPgpSigner.generate("Uploader <u@example.com>", Duration.ofDays(365));
        byte[] deb = ar(DEBIAN_BINARY, CONTROL, DATA, detached(signer, concat(DEBIAN_BINARY, CONTROL, DATA)));

        assertThat(DebianSignature.verify(deb, signer.publicKey())).isEqualTo(DebianSignature.Result.VALID);
    }

    @Test
    void a_tampered_deb_is_untrusted() throws IOException {
        OpenPgpSigner.KeyMaterial signer = OpenPgpSigner.generate("Uploader <u@example.com>", Duration.ofDays(365));
        byte[] signature = detached(signer, concat(DEBIAN_BINARY, CONTROL, DATA));
        // the data member is swapped for other bytes after signing, so the signature no longer covers it
        byte[] deb = ar(DEBIAN_BINARY, CONTROL, "swapped payload".getBytes(StandardCharsets.UTF_8), signature);

        assertThat(DebianSignature.verify(deb, signer.publicKey())).isEqualTo(DebianSignature.Result.UNTRUSTED);
    }

    @Test
    void a_deb_signed_by_an_untrusted_key_is_untrusted() throws IOException {
        OpenPgpSigner.KeyMaterial signer = OpenPgpSigner.generate("Uploader <u@example.com>", Duration.ofDays(365));
        OpenPgpSigner.KeyMaterial other = OpenPgpSigner.generate("Other <o@example.com>", Duration.ofDays(365));
        byte[] deb = ar(DEBIAN_BINARY, CONTROL, DATA, detached(signer, concat(DEBIAN_BINARY, CONTROL, DATA)));

        assertThat(DebianSignature.verify(deb, other.publicKey())).isEqualTo(DebianSignature.Result.UNTRUSTED);
    }

    @Test
    void a_deb_without_a_signature_is_unsigned() throws IOException {
        OpenPgpSigner.KeyMaterial signer = OpenPgpSigner.generate("Uploader <u@example.com>", Duration.ofDays(365));
        byte[] deb = ar(DEBIAN_BINARY, CONTROL, DATA, null);

        assertThat(DebianSignature.verify(deb, signer.publicKey())).isEqualTo(DebianSignature.Result.UNSIGNED);
    }

    private static byte[] detached(OpenPgpSigner.KeyMaterial signer, byte[] content) throws IOException {
        return new OpenPgpSigner(signer.secretKey()).detachedSignature(content, OpenPgpSigner.Encoding.ARMOURED);
    }

    private static byte[] concat(byte[]... parts) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part);
        }
        return out.toByteArray();
    }

    /** Assemble an {@code ar} archive shaped like a {@code .deb}; a null {@code gpgorigin} leaves it unsigned. */
    private static byte[] ar(byte[] debianBinary, byte[] control, byte[] data, byte[] gpgorigin) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ArArchiveOutputStream archive = new ArArchiveOutputStream(out)) {
            member(archive, "debian-binary", debianBinary);
            member(archive, "control.tar.gz", control);
            member(archive, "data.tar.gz", data);
            if (gpgorigin != null) {
                member(archive, "_gpgorigin", gpgorigin);
            }
        }
        return out.toByteArray();
    }

    private static void member(ArArchiveOutputStream archive, String name, byte[] content) throws IOException {
        archive.putArchiveEntry(new ArArchiveEntry(name, content.length));
        archive.write(content);
        archive.closeArchiveEntry();
    }
}
