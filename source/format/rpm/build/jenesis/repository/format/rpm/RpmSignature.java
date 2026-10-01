package build.jenesis.repository.format.rpm;

import module java.base;

import build.jenesis.repository.format.ArtifactSignatures;

/**
 * The publisher's signature in an RPM's own signature header, the one {@code gpgcheck=1} verifies, as signature-seam
 * evidence. The {@code RSA} and {@code DSA} entries a current {@code rpmsign} writes sign the main header blob - the
 * bytes {@link RpmHeader.Package#headerStart} to {@link RpmHeader.Package#headerEnd} delimit - while the older
 * {@code PGP} and {@code GPG} entries sign that blob and the payload together. This class composes the signed stream;
 * verifying it is the shared inspector's.
 *
 * <p>Bounded: only the package's front is materialised, a signature larger than
 * {@link ArtifactSignatures.Material#LARGEST_SIGNATURE} is left out, and the header-and-payload stream is the caller's
 * reopened body with the front skipped.
 */
final class RpmSignature {

    private RpmSignature() {
    }

    /** Every OpenPGP signature in the package's signature header, each with the bytes it commits to; empty when
     *  unsigned. Throws when the bytes are not an RPM front: present but unreadable, never absent. */
    static List<ArtifactSignatures.Evidence> evidence(ArtifactSignatures.Signed body) throws IOException {
        byte[] region;
        try (InputStream in = body.open()) {
            region = RpmHeader.readHeaderRegion(in);
        }
        RpmHeader.Package parsed = RpmHeader.parse(region);
        int start = (int) parsed.headerStart();
        int end = (int) parsed.headerEnd();
        byte[] header = Arrays.copyOfRange(region, start, end);
        List<ArtifactSignatures.Evidence> evidence = new ArrayList<>();
        for (RpmHeader.Signature signature : RpmHeader.signatures(region,
                ArtifactSignatures.Material.LARGEST_SIGNATURE)) {
            ArtifactSignatures.Signed signed = signature.coversPayload()
                    ? () -> fromHeader(body, start)
                    : () -> new ByteArrayInputStream(header);
            evidence.add(new ArtifactSignatures.Evidence(ArtifactSignatures.Scheme.OPENPGP_DETACHED,
                    signature.bytes(), signed, "signature header, " + signature.name()));
        }
        return List.copyOf(evidence);
    }

    /** The package from its main header's first byte to its end, as the header-and-payload signatures cover it. */
    private static InputStream fromHeader(ArtifactSignatures.Signed body, int start) throws IOException {
        InputStream in = body.open();
        try {
            in.skipNBytes(start);
        } catch (IOException | RuntimeException failed) {
            in.close();
            throw failed;
        }
        return in;
    }
}
