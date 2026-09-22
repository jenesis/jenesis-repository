package build.jenesis.repository.gateway.contract.test;

import module org.junit.jupiter.api;
import module java.base;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.format.nuget.NuGetFormat;
import build.jenesis.repository.format.signing.Pkcs7Verification;
import build.jenesis.repository.signing.testkit.Pkcs7Fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

/**
 * NuGet's leg of the signature seam over packages a client's {@code nuget sign} would make: the signature is found
 * inside the archive, the covered bytes are the package as it was before signing - rebuilt byte for byte, since that is
 * what the hash statement names - and a package that breaks NuGet's placement rules is reported rather than guessed
 * at. The verifier is the shared one; this proves what the format hands it.
 */
class NuGetSignedPackageTest {

    private static final String PATH = "/nuget/v3-flatcontainer/acme.widget/1.0.0/acme.widget.1.0.0.nupkg";

    private static Pkcs7Fixtures.Authority authority;
    private static Pkcs7Fixtures.Signer publisher;
    private final NuGetFormat format = new NuGetFormat();

    @BeforeAll
    static void certificates() throws GeneralSecurityException, IOException {
        authority = Pkcs7Fixtures.authority("Acme Root");
        publisher = Pkcs7Fixtures.signer(authority, "Acme Publisher", 2048);
    }

    @Test
    void a_nupkg_expects_an_optional_pkcs7_signature_and_nothing_else_does() {
        assertThat(format.expects(PATH))
                .containsExactly(ArtifactSignatures.Expectation.optional(ArtifactSignatures.Scheme.PKCS7));
        assertThat(format.expects("/nuget/v3-flatcontainer/acme.widget/index.json")).isEmpty();
        assertThat(format.expects("/nuget/v3-flatcontainer/acme.widget/1.0.0/acme.widget.nuspec")).isEmpty();
    }

    @Test
    void an_unsigned_package_yields_no_evidence() throws Exception {
        byte[] unsigned = Pkcs7Fixtures.nupkg(Pkcs7Fixtures.entries());

        assertThat(format.evidence(PATH, material(unsigned))).isEmpty();
    }

    @Test
    void the_evidence_is_the_embedded_signature_over_the_package_as_it_was_before_signing() throws Exception {
        SequencedMap<String, byte[]> entries = Pkcs7Fixtures.entries();
        byte[] unsigned = Pkcs7Fixtures.nupkg(entries);
        byte[] signature = Pkcs7Fixtures.attached(publisher, Pkcs7Fixtures.statement(unsigned));
        byte[] signed = Pkcs7Fixtures.signedNupkg(entries, signature);

        List<ArtifactSignatures.Evidence> evidence = format.evidence(PATH, material(signed));

        assertThat(evidence).hasSize(1);
        assertThat(evidence.getFirst().scheme()).isEqualTo(ArtifactSignatures.Scheme.PKCS7);
        assertThat(evidence.getFirst().signature()).isEqualTo(signature);
        assertThat(evidence.getFirst().location()).isEqualTo(PATH + "!.signature.p7s");
        // The rebuild is the unsigned archive byte for byte - which is what makes the hash statement checkable by a
        // verifier that knows nothing about zip files.
        try (InputStream rebuilt = evidence.getFirst().signed().open()) {
            assertThat(rebuilt.readAllBytes()).isEqualTo(unsigned);
        }
        // And it can be opened again: the verifier streams it once for the digest, the inspector may have drained it
        // before - each open is a fresh rebuild.
        try (InputStream again = evidence.getFirst().signed().open()) {
            assertThat(again.readAllBytes()).isEqualTo(unsigned);
        }
        assertThat(Pkcs7Verification.verify(evidence.getFirst().signed(), signature,
                Pkcs7Fixtures.pem(authority.certificate()))).isEqualTo(Pkcs7Verification.Result.VALID);
    }

    @Test
    void a_package_altered_after_signing_fails_the_statement() throws Exception {
        SequencedMap<String, byte[]> entries = Pkcs7Fixtures.entries();
        byte[] signature = Pkcs7Fixtures.attached(publisher, Pkcs7Fixtures.statement(Pkcs7Fixtures.nupkg(entries)));
        entries.put("lib/net8.0/acme.widget.dll", "the dll, swapped after signing".getBytes(StandardCharsets.UTF_8));
        byte[] altered = Pkcs7Fixtures.signedNupkg(entries, signature);

        List<ArtifactSignatures.Evidence> evidence = format.evidence(PATH, material(altered));

        assertThat(evidence).hasSize(1);
        assertThat(Pkcs7Verification.verify(evidence.getFirst().signed(), signature,
                Pkcs7Fixtures.pem(authority.certificate()))).isEqualTo(Pkcs7Verification.Result.INVALID);
    }

    @Test
    void a_signature_entry_that_is_not_last_is_refused_rather_than_rebuilt() throws Exception {
        // The signature first and a real entry after it: NuGet's client refuses to sign such a package, and the
        // rebuild refuses to guess where the unsigned form ends.
        byte[] signature = Pkcs7Fixtures.attached(publisher, "Version:1\n\n".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            ZipEntry stored = new ZipEntry(".signature.p7s");
            stored.setMethod(ZipEntry.STORED);
            stored.setSize(signature.length);
            stored.setCompressedSize(signature.length);
            CRC32 crc = new CRC32();
            crc.update(signature);
            stored.setCrc(crc.getValue());
            zip.putNextEntry(stored);
            zip.write(signature);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("acme.widget.nuspec"));
            zip.write("<package/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        List<ArtifactSignatures.Evidence> evidence = format.evidence(PATH, material(out.toByteArray()));

        assertThat(evidence).hasSize(1);
        assertThatIOException().isThrownBy(() -> evidence.getFirst().signed().open().close())
                .withMessageContaining("last");
    }

    private static ArtifactSignatures.Material material(byte[] body) {
        return new ArtifactSignatures.Material() {
            @Override
            public Optional<PublishInterceptor.Content.Bounded> sibling(String path, int limit) {
                return Optional.empty();
            }

            @Override
            public Optional<ArtifactSignatures.Signed> body() {
                return Optional.of(() -> new ByteArrayInputStream(body));
            }
        };
    }
}
