package build.jenesis.repository.format.signing;

import module java.base;
import build.jenesis.repository.format.ArtifactSignatures;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;

/**
 * The verifier for a bare signature whose signer's X.509 chain the artifact carries itself - a gem's
 * {@code data.tar.gz.sig} beside the {@code cert_chain} its gemspec lists, which {@code gem install --trust-policy}
 * checks the same way: the leaf's key verifies the signature over the member's bytes, and the chain must reach a
 * certificate the deployment trusts ({@code signature-trusted-certificates}, the same anchors a CMS signer chains
 * to). The digest is not named by the material, so SHA-256 (what RubyGems has written since 2.x) is tried first and
 * SHA-1 (what older gems carry) second; whichever verifies is the fact reported.
 */
public final class X509Verification {

    /** What checking one signature against the chain and the anchors produced. */
    public enum Result {
        /** The signature verifies by the chain's leaf, and the chain reaches an anchor. */
        VALID,
        /** The signature does not verify by the chain's leaf, or the chain is not one. */
        INVALID,
        /** The signature verifies by the leaf, but the chain reaches no anchor the deployment holds. */
        NO_KEY
    }

    /** What the carried chain states about its signer, read with no trust at all. */
    public record Facts(String spkiSha256, String subject, String keyAlgorithm, int keyBits, Instant notAfter) {
    }

    /** A verification's result and, when the signature verified, the digest it verified under. */
    public record Verdict(Result result, Optional<String> hashAlgorithm) {
    }

    private static final List<String> DIGESTS = List.of("SHA-256", "SHA-1", "SHA-512");

    private X509Verification() {
    }

    /** The chain's leaf as facts: empty when the bytes carry no certificate. */
    public static Optional<Facts> facts(byte[] pemChain) {
        List<X509Certificate> chain = chain(pemChain);
        if (chain.isEmpty()) {
            return Optional.empty();
        }
        X509Certificate leaf = chain.getFirst();
        PublicKey key = leaf.getPublicKey();
        int bits = switch (key) {
            case RSAPublicKey rsa -> rsa.getModulus().bitLength();
            case ECPublicKey ec -> ec.getParams().getCurve().getField().getFieldSize();
            default -> 0;
        };
        return Optional.of(new Facts(Pkcs7Verification.spkiSha256(key), leaf.getSubjectX500Principal().getName(),
                key.getAlgorithm(), bits, leaf.getNotAfter().toInstant()));
    }

    /** Verify a signature over the covered bytes by the chain's leaf, and the chain against the anchors. */
    public static Verdict verify(ArtifactSignatures.Signed covered, byte[] signature, byte[] pemChain, byte[] pemAnchors)
            throws IOException {
        List<X509Certificate> chain = chain(pemChain);
        if (chain.isEmpty() || signature == null || signature.length == 0) {
            return new Verdict(Result.INVALID, Optional.empty());
        }
        X509Certificate leaf = chain.getFirst();
        String algorithm = leaf.getPublicKey().getAlgorithm();
        for (String digest : DIGESTS) {
            try {
                java.security.Signature verifier = java.security.Signature.getInstance(
                        digest.replace("-", "") + "with" + ("EC".equals(algorithm) ? "ECDSA" : algorithm));
                verifier.initVerify(leaf.getPublicKey());
                byte[] buffer = new byte[8192];
                try (InputStream body = covered.open()) {
                    for (int read = body.read(buffer); read != -1; read = body.read(buffer)) {
                        verifier.update(buffer, 0, read);
                    }
                }
                if (verifier.verify(signature)) {
                    return new Verdict(anchored(pemChain, pemAnchors) ? Result.VALID : Result.NO_KEY,
                            Optional.of(digest));
                }
            } catch (GeneralSecurityException | RuntimeException unusable) {
                // Not this digest, or not a signature this key can check: the next digest, then INVALID.
            }
        }
        return new Verdict(Result.INVALID, Optional.empty());
    }

    /** Whether the carried chain reaches one of the anchors, the probe that picks the trust source for a signer. */
    public static boolean anchored(byte[] pemChain, byte[] pemAnchors) {
        List<X509Certificate> chain = chain(pemChain);
        if (chain.isEmpty() || pemAnchors == null || pemAnchors.length == 0) {
            return false;
        }
        try {
            return X509Chains.anchored(chain.getFirst(), chain.subList(1, chain.size()), pemAnchors, null);
        } catch (CertificateException unreadable) {
            return false;
        }
    }

    private static List<X509Certificate> chain(byte[] pemChain) {
        try {
            return X509Chains.certificates(pemChain);
        } catch (CertificateException notCertificates) {
            return List.of();
        }
    }
}
