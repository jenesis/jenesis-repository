package build.jenesis.repository.format.signing;

import module java.base;

import java.security.cert.CertPathBuilder;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;

/**
 * The one PKIX path build every X.509-signed scheme shares: from a signing certificate, through the intermediates
 * the artifact carries, to one of the anchors the deployment configured - revocation off (an OCSP or CRL fetch at
 * inspection time would make a publish depend on a third party answering), judged at the signature's own time when
 * it states one, so a signature made while its certificate was valid stays valid after the certificate expires.
 */
final class X509Chains {

    private X509Chains() {
    }

    /** The certificates in a PEM bundle, in order; empty for no bytes. Bytes that are not certificates throw. */
    static List<X509Certificate> certificates(byte[] pem) throws CertificateException {
        List<X509Certificate> certificates = new ArrayList<>();
        if (pem == null || pem.length == 0) {
            return certificates;
        }
        for (java.security.cert.Certificate certificate : CertificateFactory.getInstance("X.509")
                .generateCertificates(new ByteArrayInputStream(pem))) {
            if (certificate instanceof X509Certificate x509) {
                certificates.add(x509);
            }
        }
        return certificates;
    }

    /** Whether a path from the leaf reaches one of the anchors through the intermediates, at the given time (or now). */
    static boolean anchored(X509Certificate leaf, List<X509Certificate> intermediates, byte[] pemAnchors, Instant at)
            throws CertificateException {
        Set<TrustAnchor> anchors = new HashSet<>();
        for (X509Certificate anchor : certificates(pemAnchors)) {
            anchors.add(new TrustAnchor(anchor, null));
        }
        if (anchors.isEmpty()) {
            return false;
        }
        try {
            X509CertSelector selector = new X509CertSelector();
            selector.setCertificate(leaf);
            PKIXBuilderParameters parameters = new PKIXBuilderParameters(anchors, selector);
            parameters.setRevocationEnabled(false);
            parameters.addCertStore(CertStore.getInstance("Collection",
                    new CollectionCertStoreParameters(intermediates)));
            if (at != null) {
                parameters.setDate(Date.from(at));
            }
            CertPathBuilder.getInstance("PKIX").build(parameters);
            return true;
        } catch (CertPathBuilderException noPath) {
            return false;
        } catch (GeneralSecurityException unusable) {
            return false;
        }
    }
}
