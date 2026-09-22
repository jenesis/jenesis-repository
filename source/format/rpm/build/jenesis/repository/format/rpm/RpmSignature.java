package build.jenesis.repository.format.rpm;

import module java.base;

import build.jenesis.repository.format.ArtifactSignatures;

/**
 * The publisher's signature an RPM carries in its own signature header - the one {@code gpgcheck=1} verifies - as
 * the evidence the signature seam takes.
 *
 * <p>What is RPM's alone is <em>where</em> the signature lives and <em>what</em> it covers. It is not a sidecar: the
 * package's front is a lead, a signature header and the main header, and the signature header's {@code RSA} and
 * {@code DSA} entries (the pair a current {@code rpmsign} writes) sign the main header blob - the header structure
 * from its magic through the end of its data store, exactly the bytes {@link RpmHeader.Package#headerStart} to
 * {@link RpmHeader.Package#headerEnd} delimit - while the older {@code PGP} and {@code GPG} entries sign that blob
 * and the payload behind it together. Composing the signed stream for each is this class's job; checking it is not,
 * which is why the signature is handed over as {@link ArtifactSignatures.Evidence} rather than verified here, and
 * why the module carries no OpenPGP dependency for it. It is the same shape {@code DebianSignature} gives the
 * debsig member, over a different container.
 *
 * <p>Bounded: only the front of the package is materialised (the header region the format already reads to index
 * a push), a signature larger than {@link ArtifactSignatures.Material#LARGEST_SIGNATURE} is left out rather than
 * handed to a verifier, and the header-and-payload stream is the caller's own reopened body with the front skipped,
 * so a package's size does not bound what can be verified.
 */
final class RpmSignature {

    private RpmSignature() {
    }

    /** Every OpenPGP signature the package at {@code body} carries in its signature header, each with the bytes it
     *  commits to; empty for an unsigned package. Throws when the bytes are not an RPM front - present but
     *  unreadable, in the seam's terms, never absent. */
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

    /** The package from its main header's first byte to its end - the header blob and the payload, as the
     *  header-and-payload signatures cover them - over a fresh stream of the body. */
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
