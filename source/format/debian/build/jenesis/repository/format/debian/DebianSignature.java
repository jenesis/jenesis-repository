package build.jenesis.repository.format.debian;

import module java.base;
import module org.apache.commons.compress;
import build.jenesis.repository.format.ArtifactSignatures;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider;

/**
 * Verifies a {@code .deb}'s embedded OpenPGP signature against an operator-trusted keyring. The signature is the debsig
 * {@code _gpgorigin} ({@code /_gpgbuilder} / {@code _gpgcheck}) {@code ar} member: a detached signature over the
 * concatenation of the other members ({@code debian-binary}, {@code control.tar.*}, {@code data.tar.*}) in archive
 * order, as {@code debsigs --sign=origin} produces. A package is {@link Result#VALID} once one signature verifies.
 */
public final class DebianSignature {

    public enum Result {
        /** Signed, and a signature verifies against a key in the trusted ring. */
        VALID,
        /** Carries a signature, but none verifies against a trusted key (a bad signature or an untrusted signer). */
        UNTRUSTED,
        /** Carries no signature member at all. */
        UNSIGNED
    }

    /** A reopenable {@code .deb} blob: {@link #open} yields a fresh stream each call, so the archive can be read twice
     *  without holding it in heap. The caller closes each stream. */
    @FunctionalInterface
    public interface Source {
        InputStream open() throws IOException;
    }

    private static final Set<String> SIGNATURE_MEMBERS = Set.of("_gpgorigin", "_gpgbuilder", "_gpgcheck");

    /** The most a signature {@code ar} member is read into heap, through a bounded {@code readNBytes(MAX + 1)}: a
     *  debsig signature is a few kilobytes. Not the archive-inflation bound, since an {@code ar} member is stored
     *  uncompressed and its size is what the client already sent. */
    private static final int MAX_SIGNATURE = 1024 * 1024;

    private DebianSignature() {
    }

    public static Result verify(byte[] deb, byte[] trustedKeyring) throws IOException {
        return verify(() -> new ByteArrayInputStream(deb), trustedKeyring);
    }

    /** Verify a {@code .deb}'s embedded signature in bounded heap. The blob is read twice: the first pass lifts the
     *  small {@code _gpgorigin} members and builds a verifier for each that names a trusted key; the second feeds every
     *  other member's bytes, in archive order, into those verifiers in chunks, which computes the signature's digest
     *  without materialising the package. */
    public static Result verify(Source deb, byte[] trustedKeyring) throws IOException {
        PGPPublicKeyRingCollection trusted;
        try (InputStream in = PGPUtil.getDecoderStream(new ByteArrayInputStream(trustedKeyring))) {
            trusted = new PGPPublicKeyRingCollection(in, new JcaKeyFingerprintCalculator());
        } catch (PGPException e) {
            throw new IOException("Could not read the trusted keyring", e);
        }

        // First pass: lift the small signature members and build a verifier for each naming a trusted key.
        boolean signed = false;
        List<PGPSignature> verifiers = new ArrayList<>();
        try (ArArchiveInputStream archive = new ArArchiveInputStream(deb.open())) {
            for (ArArchiveEntry entry = archive.getNextEntry(); entry != null; entry = archive.getNextEntry()) {
                if (!SIGNATURE_MEMBERS.contains(entry.getName())) {
                    continue;   // getNextEntry() advances past this member's bytes; the large payload is never buffered
                }
                signed = true;
                // An over-cap member is never a usable signature: skipped, so it yields no verifier and the package
                // stays UNTRUSTED.
                byte[] signatureMember = archive.readNBytes(MAX_SIGNATURE + 1);
                if (signatureMember.length > MAX_SIGNATURE) {
                    continue;
                }
                PGPSignature verifier = trustedVerifier(signatureMember, trusted);
                if (verifier != null) {
                    verifiers.add(verifier);
                }
            }
        }
        if (!signed) {
            return Result.UNSIGNED;
        }
        if (verifiers.isEmpty()) {
            return Result.UNTRUSTED;   // signed, but no signature names a trusted key - no need to re-read the payload
        }

        // Second pass: feed every non-signature member's bytes, in archive order, into each verifier's running digest.
        byte[] buffer = new byte[8192];
        try (ArArchiveInputStream archive = new ArArchiveInputStream(deb.open())) {
            for (ArArchiveEntry entry = archive.getNextEntry(); entry != null; entry = archive.getNextEntry()) {
                if (SIGNATURE_MEMBERS.contains(entry.getName())) {
                    continue;
                }
                for (int read = archive.read(buffer); read != -1; read = archive.read(buffer)) {
                    for (PGPSignature verifier : verifiers) {
                        verifier.update(buffer, 0, read);
                    }
                }
            }
        }
        for (PGPSignature verifier : verifiers) {
            try {
                if (verifier.verify()) {
                    return Result.VALID;
                }
            } catch (PGPException e) {
                // A verifier that cannot finalise is not a valid one; try the next.
            }
        }
        return Result.UNTRUSTED;
    }

    /** The same material handed to the format-general signature seam: every signature member, each with a reopenable
     *  stream of exactly the bytes it commits to, the concatenation of the other members in archive order. Verifying
     *  belongs to the shared inspector; composing the stream is Debian's. Opened afresh per call and never
     *  materialised. */
    public static List<ArtifactSignatures.Evidence> evidence(Source deb) throws IOException {
        List<ArtifactSignatures.Evidence> evidence = new ArrayList<>();
        try (ArArchiveInputStream archive = new ArArchiveInputStream(deb.open())) {
            for (ArArchiveEntry entry = archive.getNextEntry(); entry != null; entry = archive.getNextEntry()) {
                if (!SIGNATURE_MEMBERS.contains(entry.getName())) {
                    continue;   // getNextEntry() advances past this member's bytes; the large payload is never read
                }
                // An over-cap member is never a usable signature and yields no evidence.
                byte[] member = archive.readNBytes(MAX_SIGNATURE + 1);
                if (member.length > MAX_SIGNATURE) {
                    continue;
                }
                evidence.add(new ArtifactSignatures.Evidence(ArtifactSignatures.Scheme.OPENPGP_DETACHED, member,
                        () -> signedMembers(deb), entry.getName()));
            }
        }
        return List.copyOf(evidence);
    }

    /** The archive's non-signature members concatenated in archive order, one member at a time. */
    private static InputStream signedMembers(Source deb) throws IOException {
        ArArchiveInputStream archive = new ArArchiveInputStream(deb.open());
        return new InputStream() {

            private boolean positioned;

            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) == -1 ? -1 : one[0] & 0xFF;
            }

            @Override
            public int read(byte[] buffer, int offset, int count) throws IOException {
                while (true) {
                    if (positioned) {
                        int read = archive.read(buffer, offset, count);
                        if (read != -1) {
                            return read;
                        }
                        positioned = false;   // this member is spent; fall through to the next
                    }
                    ArArchiveEntry entry = archive.getNextEntry();
                    if (entry == null) {
                        return -1;
                    }
                    positioned = !SIGNATURE_MEMBERS.contains(entry.getName());
                }
            }

            @Override
            public void close() throws IOException {
                archive.close();
            }
        };
    }

    /** Parse a detached signature member and return a verifier ready for the signed data if it names a trusted key;
     *  {@code null} for a malformed or untrusted signature. */
    private static PGPSignature trustedVerifier(byte[] signatureMember, PGPPublicKeyRingCollection trusted)
            throws IOException {
        try (InputStream in = PGPUtil.getDecoderStream(new ByteArrayInputStream(signatureMember))) {
            Object object = new PGPObjectFactory(in, new JcaKeyFingerprintCalculator()).nextObject();
            if (!(object instanceof PGPSignatureList list) || list.isEmpty()) {
                return null;
            }
            PGPSignature signature = list.get(0);
            PGPPublicKey key = trusted.getPublicKey(signature.getKeyID());
            if (key == null) {
                return null;
            }
            signature.init(new JcaPGPContentVerifierBuilderProvider(), key);
            return signature;
        } catch (PGPException e) {
            return null;
        }
    }
}
