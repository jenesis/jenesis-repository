package build.jenesis.repository.format.signing;

import module java.base;
import build.jenesis.repository.format.ArtifactSignatures;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1UTCTime;
import org.bouncycastle.asn1.ASN1GeneralizedTime;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.CMSAttributes;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.oiw.OIWObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSSignedDataParser;
import org.bouncycastle.cms.CMSTypedStream;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.util.Store;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;

/**
 * The PKCS#7 / CMS twin of {@link OpenPgpVerification}: what a {@code SignedData} structure states about itself with no
 * trust at all ({@link #facts}), and whether it verifies over the bytes it commits to by a signer whose chain reaches
 * an anchor the deployment holds ({@link #verify}). One verifier for every format that carries a CMS signature - NuGet's
 * {@code .signature.p7s} inside the package, Swift's detached archive signature - so that, as with OpenPGP, the format
 * supplies the evidence and knows nothing about certificates.
 *
 * <h2>Two shapes of CMS, one reader</h2>
 *
 * <p>A CMS signature either <em>encapsulates</em> the content it signs (NuGet: a small statement naming the package's
 * hash) or is <em>detached</em> from it (Swift: the signature covers the archive bytes). {@link #verify} takes the
 * evidence's {@link ArtifactSignatures.Signed} as the covered bytes and reads them through
 * {@link CMSSignedDataParser}, which streams - the covered bytes are digested as they pass and never held - and when
 * the structure encapsulates its own content the caller passes {@code null} and reads that content back through
 * {@link Facts#encapsulated()}, since it is the thing to check next (the statement's hash against the artifact).
 *
 * <h2>Trust is a chain to an anchor, and nothing here fetches anything</h2>
 *
 * <p>An OpenPGP signer is trusted when its key is in the keyring; a CMS signer is trusted when its certificate chains
 * to one of the deployment's anchors ({@code signature-trusted-certificates}, a PEM bundle) through the certificates
 * the structure carries with it. That is a PKIX path build with revocation checking off: an OCSP or CRL fetch at
 * inspection time would make a publish depend on a third party answering, which no other dimension does, and a
 * revoked signing certificate is a continuity fact (the signer changed) rather than a validity one. Chain validity is
 * judged at the signature's own signing time when it carries one, so a signature made while its certificate was
 * valid stays valid after the certificate expires - the rule every package ecosystem with author signing applies,
 * because otherwise every release would go unsigned on its certificate's expiry day.
 *
 * <p>Nothing here is NuGet's or Swift's. The hash-statement format, the archive surgery a package needs before it can
 * be hashed, where the signature sits - each of those is the format's own, in its {@code ArtifactSignatures} leg.
 */
public final class Pkcs7Verification {

    /** What checking one signature against a set of anchors produced. */
    public enum Result {
        /** The signature verifies over the bytes, by a certificate that chains to an anchor. */
        VALID,
        /** The signature does not verify over the bytes, or the structure names no usable signer. */
        INVALID,
        /** The signature verifies, but no anchor the deployment holds is at the root of its chain. */
        NO_KEY
    }

    /**
     * What a CMS signature states about itself, read with no trust at all.
     *
     * @param spkiSha256    the signing certificate's public key, as the lower-case hex SHA-256 of its encoded
     *                      SubjectPublicKeyInfo - the identity a pin names ({@code x509:<hex>}), stable across a
     *                      certificate's re-issue for the same key
     * @param subject       the signing certificate's subject, for an operator reading a finding
     * @param keyAlgorithm  {@code RSA}, {@code EC} or as the key names itself
     * @param keyBits       the modulus size for RSA, the field size for EC, {@code 0} when neither
     * @param hashAlgorithm the digest the signer used, in the JCA spelling ({@code SHA-256}), or the OID when the
     *                      digest is one this reader does not name
     * @param signingTime   the {@code signingTime} signed attribute when the signer included one, else {@code null}
     * @param notAfter      the signing certificate's expiry
     * @param encapsulated  the content the structure carries when it encapsulates what it signs, else empty
     */
    public record Facts(String spkiSha256, String subject, String keyAlgorithm, int keyBits, String hashAlgorithm,
                        Instant signingTime, Instant notAfter, Optional<byte[]> encapsulated) {
    }

    private Pkcs7Verification() {
    }

    /**
     * Read what a CMS structure states about its signer and content. Empty when the bytes are not a CMS
     * {@code SignedData}, when it names no signer, or when the signer's certificate is not carried within it - a
     * structure that expects its reader to already hold the certificate cannot be identified without trust, and every
     * package ecosystem's signatures carry their chain.
     */
    public static Optional<Facts> facts(byte[] signature) throws IOException {
        if (signature == null || signature.length == 0) {
            return Optional.empty();
        }
        try {
            CMSSignedDataParser parser = new CMSSignedDataParser(digests(), signature);
            byte[] encapsulated = null;
            CMSTypedStream content = parser.getSignedContent();
            if (content != null) {
                encapsulated = content.getContentStream().readAllBytes();
            }
            Optional<Signer> signer = signer(parser);
            if (signer.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(facts(signer.get(), Optional.ofNullable(encapsulated)));
        } catch (CMSException | CertificateException | OperatorCreationException | RuntimeException notCms) {
            return Optional.empty();
        }
    }

    /**
     * Verify a CMS signature over the bytes it covers, by a signer whose chain reaches one of the anchors.
     *
     * @param covered   the bytes a detached signature commits to; {@code null} for a structure that encapsulates its
     *                  own content, which is then verified over that content
     * @param signature the CMS {@code SignedData}
     * @param pemAnchors the deployment's trust anchors as concatenated PEM certificates; {@code null} or empty means
     *                  the deployment holds none, so the outcome is {@link Result#NO_KEY} for a signature that verifies
     */
    public static Result verify(ArtifactSignatures.Signed covered, byte[] signature, byte[] pemAnchors)
            throws IOException {
        try {
            CMSSignedDataParser parser = new CMSSignedDataParser(digests(), signature);
            CMSTypedStream encapsulated = parser.getSignedContent();
            if (encapsulated != null) {
                // The structure signs content it carries. The signature is judged over that content, and when the
                // caller also hands the bytes the content is ABOUT, the content must be a digest statement that names
                // them - NuGet's shape, the one encapsulating scheme this verifier meets today.
                byte[] statement = encapsulated.getContentStream().readAllBytes();
                Result signed = judge(parser, pemAnchors);
                if (signed == Result.INVALID || covered == null) {
                    return signed;
                }
                return statementNames(statement, covered) ? signed : Result.INVALID;
            }
            if (covered == null) {
                return Result.INVALID;
            }
            try (InputStream body = covered.open()) {
                CMSSignedDataParser detached = new CMSSignedDataParser(digests(), new CMSTypedStream(body), signature);
                detached.getSignedContent().drain();
                return judge(detached, pemAnchors);
            }
        } catch (CMSException | CertificateException | OperatorCreationException | RuntimeException unusable) {
            return Result.INVALID;
        }
    }

    /**
     * Whether a digest statement names the covered bytes. The grammar is NuGet's package-signature content -
     * {@code Version:1}, a blank line, then {@code <digest OID>-Hash:<base64>} - and the digest is computed by streaming
     * the covered bytes once. A statement in no grammar this reader knows names nothing.
     */
    static boolean statementNames(byte[] statement, ArtifactSignatures.Signed covered) throws IOException {
        String text = new String(statement, StandardCharsets.UTF_8);
        for (String line : text.split("\\r?\\n")) {
            int dash = line.indexOf("-Hash:");
            if (dash <= 0) {
                continue;
            }
            String algorithm = digestName(line.substring(0, dash).strip());
            String expected = line.substring(dash + "-Hash:".length()).strip();
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance(algorithm);
            } catch (NoSuchAlgorithmException unknown) {
                return false;
            }
            byte[] buffer = new byte[8192];
            try (InputStream body = covered.open()) {
                for (int read = body.read(buffer); read != -1; read = body.read(buffer)) {
                    digest.update(buffer, 0, read);
                }
            }
            byte[] declared;
            try {
                declared = Base64.getDecoder().decode(expected);
            } catch (IllegalArgumentException notBase64) {
                return false;
            }
            return MessageDigest.isEqual(declared, digest.digest());
        }
        return false;
    }

    /** Whether any of the anchors is at the root of the chain a signature carries - the probe an inspector runs to pick
     *  the trust source that speaks for this signer, without digesting anything. */
    public static boolean anchored(byte[] signature, byte[] pemAnchors) throws IOException {
        if (pemAnchors == null || pemAnchors.length == 0) {
            return false;
        }
        try {
            CMSSignedDataParser parser = new CMSSignedDataParser(digests(), signature);
            CMSTypedStream content = parser.getSignedContent();
            if (content != null) {
                content.drain();
            }
            Optional<Signer> signer = signer(parser);
            return signer.isPresent() && chains(signer.get(), parser.getCertificates(), pemAnchors);
        } catch (CMSException | CertificateException | OperatorCreationException | RuntimeException notCms) {
            return false;
        }
    }

    private static Result judge(CMSSignedDataParser parser, byte[] pemAnchors)
            throws CMSException, CertificateException, OperatorCreationException, IOException {
        Optional<Signer> signer = signer(parser);
        if (signer.isEmpty()) {
            return Result.INVALID;
        }
        if (!signer.get().information().verify(new JcaSimpleSignerInfoVerifierBuilder().build(signer.get().holder()))) {
            return Result.INVALID;
        }
        return chains(signer.get(), parser.getCertificates(), pemAnchors) ? Result.VALID : Result.NO_KEY;
    }

    private record Signer(SignerInformation information, X509CertificateHolder holder, X509Certificate certificate) {
    }

    private static Optional<Signer> signer(CMSSignedDataParser parser) throws CMSException, CertificateException {
        Collection<SignerInformation> signers = parser.getSignerInfos().getSigners();
        if (signers.isEmpty()) {
            return Optional.empty();
        }
        SignerInformation information = signers.iterator().next();
        @SuppressWarnings("unchecked")
        Collection<X509CertificateHolder> matches = parser.getCertificates().getMatches(information.getSID());
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        X509CertificateHolder holder = matches.iterator().next();
        return Optional.of(new Signer(information, holder, new JcaX509CertificateConverter().getCertificate(holder)));
    }

    private static boolean chains(Signer signer, Store<X509CertificateHolder> carried, byte[] pemAnchors)
            throws CertificateException {
        List<X509Certificate> intermediates = new ArrayList<>();
        @SuppressWarnings("unchecked")
        Collection<X509CertificateHolder> holders = carried.getMatches(null);
        for (X509CertificateHolder holder : holders) {
            intermediates.add(new JcaX509CertificateConverter().getCertificate(holder));
        }
        return X509Chains.anchored(signer.certificate(), intermediates, pemAnchors, signingTime(signer.information()));
    }

    private static Facts facts(Signer signer, Optional<byte[]> encapsulated) throws CertificateException {
        X509Certificate certificate = signer.certificate();
        PublicKey key = certificate.getPublicKey();
        int bits = switch (key) {
            case RSAPublicKey rsa -> rsa.getModulus().bitLength();
            case ECPublicKey ec -> ec.getParams().getCurve().getField().getFieldSize();
            default -> 0;
        };
        return new Facts(spkiSha256(key), certificate.getSubjectX500Principal().getName(), key.getAlgorithm(), bits,
                digestName(signer.information().getDigestAlgOID()), signingTime(signer.information()),
                certificate.getNotAfter().toInstant(), encapsulated);
    }

    /** The lower-case hex SHA-256 of a key's encoded SubjectPublicKeyInfo: the spelling {@code x509:} pins use. */
    public static String spkiSha256(PublicKey key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Instant signingTime(SignerInformation information) {
        if (information.getSignedAttributes() == null) {
            return null;
        }
        Attribute attribute = information.getSignedAttributes().get(CMSAttributes.signingTime);
        if (attribute == null || attribute.getAttrValues().size() == 0) {
            return null;
        }
        ASN1Encodable value = attribute.getAttrValues().getObjectAt(0);
        try {
            return switch (value) {
                case ASN1UTCTime utc -> utc.getDate().toInstant();
                case ASN1GeneralizedTime generalized -> generalized.getDate().toInstant();
                default -> null;
            };
        } catch (ParseException unreadable) {
            return null;
        }
    }

    private static String digestName(String oid) {
        switch (oid.toUpperCase(Locale.ROOT).replace("-", "")) {
            case "SHA256": return "SHA-256";
            case "SHA384": return "SHA-384";
            case "SHA512": return "SHA-512";
            default: break;
        }
        if (NISTObjectIdentifiers.id_sha256.getId().equals(oid)) {
            return "SHA-256";
        } else if (NISTObjectIdentifiers.id_sha384.getId().equals(oid)) {
            return "SHA-384";
        } else if (NISTObjectIdentifiers.id_sha512.getId().equals(oid)) {
            return "SHA-512";
        } else if (NISTObjectIdentifiers.id_sha224.getId().equals(oid)) {
            return "SHA-224";
        } else if (OIWObjectIdentifiers.idSHA1.getId().equals(oid)) {
            return "SHA-1";
        } else if (PKCSObjectIdentifiers.md5.getId().equals(oid)) {
            return "MD5";
        }
        return oid;
    }

    private static org.bouncycastle.operator.DigestCalculatorProvider digests() throws OperatorCreationException {
        return new JcaDigestCalculatorProviderBuilder().build();
    }
}
