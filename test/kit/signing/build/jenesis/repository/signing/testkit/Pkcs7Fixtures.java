package build.jenesis.repository.signing.testkit;

import module java.base;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

import java.security.cert.X509Certificate;

/**
 * A certificate authority, signers under it, and the CMS structures and NuGet packages the PKCS#7 tests need - built
 * with the same BouncyCastle the product verifies with, so a round trip proves the reader against a writer that
 * follows the specifications rather than against itself. Every artefact is deterministic in shape and fresh in bytes:
 * keys are generated per fixture, never checked in.
 */
public final class Pkcs7Fixtures {

    /** NuGet spells the digest of its hash statement as an OID; SHA-256's. */
    public static final String SHA256_OID = "2.16.840.1.101.3.4.2.1";

    public record Authority(X509Certificate certificate, PrivateKey key) {
    }

    public record Signer(X509Certificate certificate, PrivateKey key, List<X509Certificate> chain) {
    }

    private static final AtomicLong SERIAL = new AtomicLong(1);

    private Pkcs7Fixtures() {
    }

    /** A self-signed RSA-2048 authority valid for a year around now. */
    public static Authority authority(String commonName) throws GeneralSecurityException, IOException {
        KeyPair pair = rsa(2048);
        X500Name name = new X500Name("CN=" + commonName);
        Instant now = Instant.now();
        X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(
                new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(SERIAL.getAndIncrement()),
                        Date.from(now.minus(Duration.ofDays(1))), Date.from(now.plus(Duration.ofDays(365))),
                        name, pair.getPublic())
                        .addExtension(Extension.basicConstraints, true, new BasicConstraints(true))
                        .addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign))
                        .build(signer("SHA256withRSA", pair.getPrivate())));
        return new Authority(certificate, pair.getPrivate());
    }

    /** A signing certificate issued by the authority, with an RSA key of the given size, valid for a year. */
    public static Signer signer(Authority authority, String commonName, int bits) throws GeneralSecurityException, IOException {
        KeyPair pair = rsa(bits);
        Instant now = Instant.now();
        X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(
                new JcaX509v3CertificateBuilder(authority.certificate(), BigInteger.valueOf(SERIAL.getAndIncrement()),
                        Date.from(now.minus(Duration.ofDays(1))), Date.from(now.plus(Duration.ofDays(365))),
                        new X500Name("CN=" + commonName), pair.getPublic())
                        .addExtension(Extension.basicConstraints, true, new BasicConstraints(false))
                        .addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature))
                        .build(signer("SHA256withRSA", authority.key())));
        return new Signer(certificate, pair.getPrivate(), List.of(certificate, authority.certificate()));
    }

    /** The certificates as a PEM bundle, the shape the {@code signature-trusted-certificates} setting holds. */
    public static byte[] pem(X509Certificate... certificates) throws GeneralSecurityException {
        StringBuilder out = new StringBuilder();
        for (X509Certificate certificate : certificates) {
            out.append("-----BEGIN CERTIFICATE-----\n")
                    .append(Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                            .encodeToString(certificate.getEncoded()))
                    .append("\n-----END CERTIFICATE-----\n");
        }
        return out.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /** A CMS {@code SignedData} that encapsulates its content, carrying the signer's chain - NuGet's shape. */
    public static byte[] attached(Signer signer, byte[] content) throws GeneralSecurityException, IOException {
        return cms(signer, content, true);
    }

    /** A CMS {@code SignedData} detached from the content it signs, carrying the signer's chain - Swift's shape. */
    public static byte[] detached(Signer signer, byte[] content) throws GeneralSecurityException, IOException {
        return cms(signer, content, false);
    }

    private static byte[] cms(Signer signer, byte[] content, boolean encapsulate)
            throws GeneralSecurityException, IOException {
        try {
            CMSSignedDataGenerator generator = new CMSSignedDataGenerator();
            generator.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(
                    new JcaDigestCalculatorProviderBuilder().build())
                    .build(signer("SHA256withRSA", signer.key()), signer.certificate()));
            generator.addCertificates(new JcaCertStore(signer.chain()));
            return generator.generate(new CMSProcessableByteArray(content), encapsulate).getEncoded();
        } catch (org.bouncycastle.cms.CMSException | org.bouncycastle.operator.OperatorCreationException failed) {
            throw new IOException(failed);
        }
    }

    /** NuGet's package-signature content over an archive: {@code Version:1}, a blank line, {@code <OID>-Hash:<base64>}. */
    public static byte[] statement(byte[] archive) throws GeneralSecurityException {
        String hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(archive));
        return ("Version:1\n\n" + SHA256_OID + "-Hash:" + hash + "\n\n").getBytes(StandardCharsets.UTF_8);
    }

    /** An unsigned {@code .nupkg}: the entries in the order given, deflated as a client writes them. */
    public static byte[] nupkg(SequencedMap<String, byte[]> entries) throws IOException {
        return zip(entries, null);
    }

    /** The same package after signing: the signature appended as a stored entry, last, as NuGet requires. */
    public static byte[] signedNupkg(SequencedMap<String, byte[]> entries, byte[] signature) throws IOException {
        return zip(entries, signature);
    }

    /** A {@code .nupkg} signed over its own unsigned form by the signer: what a client's {@code nuget sign} makes. */
    public static byte[] signedNupkg(SequencedMap<String, byte[]> entries, Signer signer)
            throws GeneralSecurityException, IOException {
        return signedNupkg(entries, attached(signer, statement(nupkg(entries))));
    }

    public static SequencedMap<String, byte[]> entries() {
        SequencedMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("acme.widget.nuspec", ("<?xml version=\"1.0\"?><package><metadata><id>acme.widget</id>"
                + "<version>1.0.0</version></metadata></package>").getBytes(StandardCharsets.UTF_8));
        entries.put("lib/net8.0/acme.widget.dll", "not really a dll, but enough bytes to deflate".repeat(40)
                .getBytes(StandardCharsets.UTF_8));
        return entries;
    }

    /** Every entry's timestamp, fixed: a {@code ZipEntry} without one takes the moment it is written, at a
     *  two-second resolution, and the unsigned archive a statement hashes and the signed archive a verifier strips
     *  back to it are written moments apart - so once in a while they straddled a boundary, the stripped archive's
     *  timestamps differed from the hashed one's, and a signature that verified on every other run read INVALID
     *  (a strict lane, 2026-09-13 17:10, and again at 23:40). What NuGet's client writes is the same bytes twice. */
    private static final long ENTRY_TIME = 1_700_000_000_000L;

    private static byte[] zip(SequencedMap<String, byte[]> entries, byte[] signature) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                ZipEntry member = new ZipEntry(entry.getKey());
                member.setTime(ENTRY_TIME);
                zip.putNextEntry(member);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
            if (signature != null) {
                ZipEntry stored = new ZipEntry(".signature.p7s");
                stored.setTime(ENTRY_TIME);
                stored.setMethod(ZipEntry.STORED);
                stored.setSize(signature.length);
                stored.setCompressedSize(signature.length);
                CRC32 crc = new CRC32();
                crc.update(signature);
                stored.setCrc(crc.getValue());
                zip.putNextEntry(stored);
                zip.write(signature);
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static KeyPair rsa(int bits) throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }

    private static org.bouncycastle.operator.ContentSigner signer(String algorithm, PrivateKey key) throws IOException {
        try {
            return new JcaContentSignerBuilder(algorithm).build(key);
        } catch (org.bouncycastle.operator.OperatorCreationException failed) {
            throw new IOException(failed);
        }
    }
}
