package build.jenesis.repository.sigstore.testkit;

import module java.base;
import java.security.Signature;
import java.security.cert.X509Certificate;
import org.bouncycastle.asn1.DERUTF8String;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * A Sigstore instance small enough to run inside a test: a certificate authority standing in for Fulcio, a
 * transparency log standing in for Rekor, the trusted root that names both, and writers that produce a v0.3 bundle,
 * a PEP 740 attestation and cosign's annotations over given bytes.
 *
 * <p>Public, and in a kit, because a JUnit test module is a leaf: as a package-private class in the gateway test
 * module this was reachable by the twelve suites there and by nothing else, which put every end-to-end suite - the
 * ones that prove the wiring rather than the cryptography - one copy away from having a fake Sigstore of its own.
 *
 * <p>Nothing here runs a service. It writes the documents Fulcio and Rekor would have produced, so a verifier meets
 * material of exactly the right shape without a container; the suite that needs the real ones drives the official
 * images instead.
 */
public final class SigstoreFixtures {

    public static final String LOG_HOST = "rekor.test";

    private SigstoreFixtures() {
    }

    public static KeyPair ec() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    public static X509Certificate authority(KeyPair pair, String name) throws Exception {
        X500Name subject = new X500Name("O=sigstore.test,CN=" + name);
        return new JcaX509CertificateConverter().getCertificate(new JcaX509v3CertificateBuilder(
                subject, BigInteger.ONE, Date.from(Instant.now().minus(Duration.ofDays(1))),
                Date.from(Instant.now().plus(Duration.ofDays(365))), subject, pair.getPublic())
                .addExtension(Extension.basicConstraints, true, new BasicConstraints(true))
                .addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign))
                .build(new JcaContentSignerBuilder("SHA256withECDSA").build(pair.getPrivate())));
    }

    /** A leaf as Fulcio issues one: ten minutes of validity, the identity as a URI SAN, the issuer in the DER
     *  extension, digital signature and code signing. */
    public static X509Certificate leaf(KeyPair ca, X509Certificate caCertificate, PublicKey certified, String issuer,
                                String subject) throws Exception {
        return new JcaX509CertificateConverter().getCertificate(new JcaX509v3CertificateBuilder(
                caCertificate, BigInteger.valueOf(2), Date.from(Instant.now().minusSeconds(5)),
                Date.from(Instant.now().plus(Duration.ofMinutes(10))), new X500Name(new RDN[0]), certified)
                .addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature))
                .addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_codeSigning))
                .addExtension(Extension.subjectAlternativeName, true,
                        new GeneralNames(new GeneralName(GeneralName.uniformResourceIdentifier, subject)))
                .addExtension(new org.bouncycastle.asn1.ASN1ObjectIdentifier("1.3.6.1.4.1.57264.1.8"), false,
                        new DERUTF8String(issuer))
                .build(new JcaContentSignerBuilder("SHA256withECDSA").build(ca.getPrivate())));
    }

    /** The trusted root as its protobuf JSON: one CA, one log. */
    public static byte[] trustedRoot(X509Certificate caCertificate, PublicKey logKey) throws Exception {
        String json = "{\"mediaType\":\"application/vnd.dev.sigstore.trustedroot+json;version=0.1\","
                + "\"certificateAuthorities\":[{\"subject\":{\"organization\":\"sigstore.test\",\"commonName\":\"fake-fulcio\"},"
                + "\"uri\":\"https://fulcio.test\",\"certChain\":{\"certificates\":[{\"rawBytes\":\""
                + Base64.getEncoder().encodeToString(caCertificate.getEncoded()) + "\"}]},"
                + "\"validFor\":{\"start\":\"2020-01-01T00:00:00Z\"}}],"
                + "\"tlogs\":[{\"baseUrl\":\"https://" + LOG_HOST + "\",\"hashAlgorithm\":\"SHA2_256\","
                + "\"publicKey\":{\"rawBytes\":\"" + Base64.getEncoder().encodeToString(logKey.getEncoded())
                + "\",\"keyDetails\":\"PKIX_ECDSA_P256_SHA_256\",\"validFor\":{\"start\":\"2020-01-01T00:00:00Z\"}},"
                + "\"logId\":{\"keyId\":\"" + Base64.getEncoder().encodeToString(sha256(logKey.getEncoded())) + "\"}}]}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /** A v0.3 bundle: the certificate, a hashed-rekord entry the log signed and proved, and the signature. */
    public static byte[] bundle(KeyPair signer, X509Certificate leaf, KeyPair log, byte[] artifact) throws Exception {
        byte[] digest = sha256(artifact);
        byte[] signature = sign(signer.getPrivate(), artifact);
        String pem = "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(leaf.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
        // RFC 8785 canonical JSON: keys sorted, no whitespace - what Rekor stores and what a verifier rebuilds.
        String body = "{\"apiVersion\":\"0.0.1\",\"kind\":\"hashedrekord\",\"spec\":{\"data\":{\"hash\":{\"algorithm\":\"sha256\","
                + "\"value\":\"" + HexFormat.of().formatHex(digest) + "\"}},\"signature\":{\"content\":\""
                + Base64.getEncoder().encodeToString(signature) + "\",\"publicKey\":{\"content\":\""
                + Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.UTF_8)) + "\"}}}}";
        String canonicalBody = Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
        long integratedTime = Instant.now().getEpochSecond();
        long logIndex = 7;
        String logId = HexFormat.of().formatHex(sha256(log.getPublic().getEncoded()));
        byte[] receipt = sign(log.getPrivate(), ("{\"body\":\"" + canonicalBody + "\",\"integratedTime\":" + integratedTime
                + ",\"logID\":\"" + logId + "\",\"logIndex\":" + logIndex + "}").getBytes(StandardCharsets.UTF_8));
        byte[] leafHash = leafHash(body.getBytes(StandardCharsets.UTF_8));
        String checkpoint = checkpoint(log, leafHash);
        String json = "{\"mediaType\":\"application/vnd.dev.sigstore.bundle.v0.3+json\","
                + "\"verificationMaterial\":{\"certificate\":{\"rawBytes\":\""
                + Base64.getEncoder().encodeToString(leaf.getEncoded()) + "\"},"
                + "\"tlogEntries\":[{\"logIndex\":\"" + logIndex + "\",\"logId\":{\"keyId\":\""
                + Base64.getEncoder().encodeToString(sha256(log.getPublic().getEncoded())) + "\"},"
                + "\"kindVersion\":{\"kind\":\"hashedrekord\",\"version\":\"0.0.1\"},"
                + "\"integratedTime\":\"" + integratedTime + "\","
                + "\"inclusionPromise\":{\"signedEntryTimestamp\":\"" + Base64.getEncoder().encodeToString(receipt) + "\"},"
                + "\"inclusionProof\":{\"logIndex\":\"0\",\"rootHash\":\"" + Base64.getEncoder().encodeToString(leafHash)
                + "\",\"treeSize\":\"1\",\"hashes\":[],\"checkpoint\":{\"envelope\":"
                + quoted(checkpoint) + "}},"
                + "\"canonicalizedBody\":\"" + canonicalBody + "\"}]},"
                + "\"messageSignature\":{\"messageDigest\":{\"algorithm\":\"SHA2_256\",\"digest\":\""
                + Base64.getEncoder().encodeToString(digest) + "\"},\"signature\":\""
                + Base64.getEncoder().encodeToString(signature) + "\"}}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /** A signed note as Rekor writes its checkpoints: origin, size and root, a blank line, then the signature line
     *  with the key hint (the first four bytes of the log id) before the signature. */
    public static String checkpoint(KeyPair log, byte[] rootHash) throws Exception {
        String header = LOG_HOST + "\n1\n" + Base64.getEncoder().encodeToString(rootHash) + "\n";
        byte[] signature = sign(log.getPrivate(), header.getBytes(StandardCharsets.UTF_8));
        byte[] hint = sha256(log.getPublic().getEncoded());
        byte[] keySig = new byte[4 + signature.length];
        System.arraycopy(hint, 0, keySig, 0, 4);
        System.arraycopy(signature, 0, keySig, 4, signature.length);
        return header + "\n— " + LOG_HOST + " " + Base64.getEncoder().encodeToString(keySig) + "\n";
    }

    public static String quoted(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    public static byte[] sign(PrivateKey key, byte[] content) throws GeneralSecurityException {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(key);
        signature.update(content);
        return signature.sign();
    }

    public static byte[] sha256(byte[] content) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(content);
    }

    /** The RFC 6962 leaf hash: SHA-256 over a {@code 0x00} prefix and the leaf's content. */
    public static byte[] leafHash(byte[] leaf) throws GeneralSecurityException {
        byte[] prefixed = new byte[leaf.length + 1];
        System.arraycopy(leaf, 0, prefixed, 1, leaf.length);
        return sha256(prefixed);
    }

    /**
     * A PEP 740 attestation over an artifact, as {@code twine upload --attestations} sends one: the certificate, a
     * {@code dsse}-kind transparency-log entry the log proved, and a DSSE-signed in-toto statement naming the
     * artifact by its SHA-256 - the shape the PyPI leg turns back into a Sigstore bundle.
     */
    public static byte[] pep740Attestation(KeyPair signer, X509Certificate leaf, KeyPair log, byte[] artifact, String file)
            throws Exception {
        byte[] digest = sha256(artifact);
        String statement = "{\"_type\":\"https://in-toto.io/Statement/v1\",\"subject\":[{\"name\":\"" + file
                + "\",\"digest\":{\"sha256\":\"" + HexFormat.of().formatHex(digest) + "\"}}],"
                + "\"predicateType\":\"https://docs.pypi.org/attestations/publish/v1\",\"predicate\":null}";
        byte[] payload = statement.getBytes(StandardCharsets.UTF_8);
        String payloadType = "application/vnd.in-toto+json";
        byte[] type = payloadType.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream pae = new ByteArrayOutputStream();
        pae.writeBytes(("DSSEv1 " + type.length + " ").getBytes(StandardCharsets.US_ASCII));
        pae.writeBytes(type);
        pae.writeBytes((" " + payload.length + " ").getBytes(StandardCharsets.US_ASCII));
        pae.writeBytes(payload);
        byte[] signature = sign(signer.getPrivate(), pae.toByteArray());
        String pem = "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(leaf.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
        String envelopeJson = "{\"payload\":\"" + Base64.getEncoder().encodeToString(payload) + "\",\"payloadType\":\""
                + payloadType + "\",\"signatures\":[{\"sig\":\"" + Base64.getEncoder().encodeToString(signature) + "\"}]}";
        String body = "{\"apiVersion\":\"0.0.1\",\"kind\":\"dsse\",\"spec\":{\"envelopeHash\":{\"algorithm\":\"sha256\","
                + "\"value\":\"" + HexFormat.of().formatHex(sha256(envelopeJson.getBytes(StandardCharsets.UTF_8))) + "\"},"
                + "\"payloadHash\":{\"algorithm\":\"sha256\",\"value\":\"" + HexFormat.of().formatHex(sha256(payload))
                + "\"},\"signatures\":[{\"signature\":\"" + Base64.getEncoder().encodeToString(signature)
                + "\",\"verifier\":\"" + Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.UTF_8)) + "\"}]}}";
        String entry = entry(log, body, 11);
        return ("{\"version\":1,\"verification_material\":{\"certificate\":\""
                + Base64.getEncoder().encodeToString(leaf.getEncoded()) + "\",\"transparency_entries\":[" + entry + "]},"
                + "\"envelope\":{\"statement\":\"" + Base64.getEncoder().encodeToString(payload) + "\",\"signature\":\""
                + Base64.getEncoder().encodeToString(signature) + "\"}}").getBytes(StandardCharsets.UTF_8);
    }

    /** A transparency-log entry the log signed and proved, over a canonical body, in the bundle's JSON shape. */
    public static String entry(KeyPair log, String body, long logIndex) throws Exception {
        String canonicalBody = Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
        long integratedTime = Instant.now().getEpochSecond();
        String logId = HexFormat.of().formatHex(sha256(log.getPublic().getEncoded()));
        byte[] receipt = sign(log.getPrivate(), ("{\"body\":\"" + canonicalBody + "\",\"integratedTime\":" + integratedTime
                + ",\"logID\":\"" + logId + "\",\"logIndex\":" + logIndex + "}").getBytes(StandardCharsets.UTF_8));
        byte[] leafHash = leafHash(body.getBytes(StandardCharsets.UTF_8));
        String kind = body.contains("\"kind\":\"dsse\"") ? "dsse" : "hashedrekord";
        return "{\"logIndex\":\"" + logIndex + "\",\"logId\":{\"keyId\":\""
                + Base64.getEncoder().encodeToString(sha256(log.getPublic().getEncoded())) + "\"},"
                + "\"kindVersion\":{\"kind\":\"" + kind + "\",\"version\":\"0.0.1\"},"
                + "\"integratedTime\":\"" + integratedTime + "\","
                + "\"inclusionPromise\":{\"signedEntryTimestamp\":\"" + Base64.getEncoder().encodeToString(receipt) + "\"},"
                + "\"inclusionProof\":{\"logIndex\":\"0\",\"rootHash\":\"" + Base64.getEncoder().encodeToString(leafHash)
                + "\",\"treeSize\":\"1\",\"hashes\":[],\"checkpoint\":{\"envelope\":" + quoted(checkpoint(log, leafHash)) + "}},"
                + "\"canonicalizedBody\":\"" + canonicalBody + "\"}";
    }

    /**
     * What {@code cosign sign} pushes beside an image: the simple-signing payload naming the image by manifest digest,
     * and the signature manifest tagged {@code sha256-<hex>.sig} whose one layer is that payload, annotated with the
     * signature over it, the Fulcio certificate and the transparency-log receipt - the shape the OCI leg turns back
     * into a Sigstore bundle. The payload is what the signing key signs (ECDSA over its SHA-256), and it is what the
     * log records, so the manifest digest it names is the one thing binding the signature to the image.
     */
    public record CosignSignature(byte[] payload, String layerHex, byte[] manifest) {
    }

    public static CosignSignature cosign(KeyPair signer, X509Certificate leaf, KeyPair log, String image,
                                  String manifestHex) throws Exception {
        byte[] payload = ("{\"critical\":{\"identity\":{\"docker-reference\":\"registry.test/" + image + "\"},"
                + "\"image\":{\"docker-manifest-digest\":\"sha256:" + manifestHex + "\"},"
                + "\"type\":\"cosign container image signature\"},\"optional\":null}")
                .getBytes(StandardCharsets.UTF_8);
        byte[] digest = sha256(payload);
        byte[] signature = sign(signer.getPrivate(), payload);
        String pem = "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(leaf.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
        String body = "{\"apiVersion\":\"0.0.1\",\"kind\":\"hashedrekord\",\"spec\":{\"data\":{\"hash\":{\"algorithm\":\"sha256\","
                + "\"value\":\"" + HexFormat.of().formatHex(digest) + "\"}},\"signature\":{\"content\":\""
                + Base64.getEncoder().encodeToString(signature) + "\",\"publicKey\":{\"content\":\""
                + Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.UTF_8)) + "\"}}}}";
        String canonicalBody = Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
        long integratedTime = Instant.now().getEpochSecond();
        long logIndex = 23;
        String logId = HexFormat.of().formatHex(sha256(log.getPublic().getEncoded()));
        byte[] receipt = sign(log.getPrivate(), ("{\"body\":\"" + canonicalBody + "\",\"integratedTime\":" + integratedTime
                + ",\"logID\":\"" + logId + "\",\"logIndex\":" + logIndex + "}").getBytes(StandardCharsets.UTF_8));
        // cosign's legacy Rekor bundle, as the dev.sigstore.cosign/bundle annotation carries it.
        String bundle = "{\"SignedEntryTimestamp\":\"" + Base64.getEncoder().encodeToString(receipt) + "\",\"Payload\":{"
                + "\"body\":\"" + canonicalBody + "\",\"integratedTime\":" + integratedTime + ",\"logIndex\":" + logIndex
                + ",\"logID\":\"" + logId + "\"}}";
        String layerHex = HexFormat.of().formatHex(digest);
        String manifest = "{\"schemaVersion\":2,\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\","
                + "\"config\":{\"mediaType\":\"application/vnd.oci.image.config.v1+json\",\"size\":2,\"digest\":\"sha256:"
                + HexFormat.of().formatHex(sha256("{}".getBytes(StandardCharsets.UTF_8))) + "\"},"
                + "\"layers\":[{\"mediaType\":\"application/vnd.dev.cosign.simplesigning.v1+json\",\"size\":" + payload.length
                + ",\"digest\":\"sha256:" + layerHex + "\",\"annotations\":{"
                + "\"dev.cosignproject.cosign/signature\":\"" + Base64.getEncoder().encodeToString(signature) + "\","
                + "\"dev.sigstore.cosign/certificate\":" + quoted(pem) + ","
                + "\"dev.sigstore.cosign/chain\":\"\","
                + "\"dev.sigstore.cosign/bundle\":" + quoted(bundle) + "}}]}";
        return new CosignSignature(payload, layerHex, manifest.getBytes(StandardCharsets.UTF_8));
    }
}
