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
 * Verifies a {@code .deb}'s embedded OpenPGP signature against an operator-trusted keyring, so a hosted repository can
 * refuse a package that is unsigned, tampered, or signed by a key it does not trust. The signature is the debsig
 * {@code _gpgorigin} ({@code /_gpgbuilder} / {@code _gpgcheck}) {@code ar} member - a detached signature over the
 * concatenation of the archive's other members' contents ({@code debian-binary}, {@code control.tar.*},
 * {@code data.tar.*}), in archive order, which is what {@code debsigs --sign=origin} produces. A package may carry
 * more than one signature; it is {@link Result#VALID} as soon as one verifies against a trusted key.
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

    /** A reopenable {@code .deb} blob: {@link #open} yields a fresh stream over the same bytes each call, so the archive
     *  can be read twice (once to lift the small signature members, once to stream the large signed members) without
     *  ever holding the package in heap. The caller owns and closes each returned stream. */
    @FunctionalInterface
    public interface Source {
        InputStream open() throws IOException;
    }

    private static final Set<String> SIGNATURE_MEMBERS = Set.of("_gpgorigin", "_gpgbuilder", "_gpgcheck");

    /** The most a signature {@code ar} member is read into heap. A debsig detached signature ({@code _gpgorigin}) is a
     *  few hundred bytes to a few kilobytes; a megabyte is generous (the same bound the sibling
     *  {@code MAX_TRUSTED_KEY} debian cap uses). This is deliberately <em>not</em> the shared archive-inflation bound
     *  ({@code ArchiveInflation}): an {@code ar} member is stored uncompressed, so its declared size is the transfer
     *  the client already paid for and no ratio is the attacker's to choose - the concern that bound exists for.
     *  Read through a bounded {@code readNBytes(MAX + 1)} so a hostile
     *  {@code .deb} declaring a huge {@code _gpgorigin} member cannot OOM the verifier: the large {@code data.tar.*}
     *  members are already streamed in 8 KiB chunks, and this was the one member read whole ({@code readAllBytes}). */
    private static final int MAX_SIGNATURE = 1024 * 1024;

    private DebianSignature() {
    }

    public static Result verify(byte[] deb, byte[] trustedKeyring) throws IOException {
        return verify(() -> new ByteArrayInputStream(deb), trustedKeyring);
    }

    /**
     * Verify a {@code .deb}'s embedded signature streaming, so a publish never holds the whole (unbounded) package -
     * above all its {@code data.tar.*} member - in heap just to check the signature. The former single pass buffered
     * every {@code ar} member (including {@code data.tar}) into a {@link ByteArrayOutputStream} to form the signed
     * data, which is {@code ~2x} the package on the heap and cannot even represent a package past the {@code byte[]}
     * array limit. Instead the {@link Source reopenable} blob is read twice: the first pass lifts only the small
     * {@code _gpgorigin} signature member(s) and builds a verifier for each that names a trusted key; the second pass
     * re-reads the archive and feeds every non-signature member's bytes straight into those verifiers in bounded
     * chunks. An OpenPGP signature is a digest over the signed data, so feeding the members chunk-by-chunk (in archive
     * order, regardless of where the signature member sits) computes exactly that digest without materialising the
     * bytes. A multi-gigabyte {@code .deb} therefore verifies in bounded heap.
     */
    public static Result verify(Source deb, byte[] trustedKeyring) throws IOException {
        PGPPublicKeyRingCollection trusted;
        try (InputStream in = PGPUtil.getDecoderStream(new ByteArrayInputStream(trustedKeyring))) {
            trusted = new PGPPublicKeyRingCollection(in, new JcaKeyFingerprintCalculator());
        } catch (PGPException e) {
            throw new IOException("Could not read the trusted keyring", e);
        }

        // First pass: lift the signature member(s) - each a small detached signature, safe to hold whole - and build a
        // verifier for every one that names a key in the trusted ring. The unbounded members are skipped, never read.
        boolean signed = false;
        List<PGPSignature> verifiers = new ArrayList<>();
        try (ArArchiveInputStream archive = new ArArchiveInputStream(deb.open())) {
            for (ArArchiveEntry entry = archive.getNextEntry(); entry != null; entry = archive.getNextEntry()) {
                if (!SIGNATURE_MEMBERS.contains(entry.getName())) {
                    continue;   // getNextEntry() advances past this member's bytes; the large payload is never buffered
                }
                signed = true;
                // Bounded read: a legitimate detached signature is tiny, so read at most MAX_SIGNATURE + 1 bytes. An
                // over-cap member is a hostile/oversized signature, never a usable one - skip it (getNextEntry() below
                // advances past its remaining bytes), so it can never yield a trusted verifier and the package stays
                // UNTRUSTED (refused), never VALID. This is the one member formerly read whole (readAllBytes) - the
                // OOM vector a huge _gpgorigin declared, since .deb uploads are otherwise uncapped.
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
                // a verifier that cannot finalise (a malformed signature) is simply not a valid one; try the next
            }
        }
        return Result.UNTRUSTED;
    }

    /**
     * The same material, handed to the format-general signature seam instead of being verified here: every signature
     * member, each paired with a reopenable stream of exactly the bytes it commits to.
     *
     * <p>It is the same two passes {@link #verify} makes - lift the small signature members, then stream the large
     * ones - split so that <em>checking</em> the signature belongs to the shared inspector rather than to this format.
     * What stays here is the only part that is genuinely Debian's: that a {@code .deb}'s signature covers the
     * concatenation of the archive's <em>other</em> members in archive order, rather than the file itself. A Maven
     * {@code .asc} covers the file, an RPM header signature covers the header; one verifier reads all three because
     * each format composes the stream and none of them owns the checking.
     *
     * <p>The composed stream is opened afresh per call and never materialised, so a multi-gigabyte package is verified
     * in bounded heap exactly as it is here.
     */
    public static List<ArtifactSignatures.Evidence> evidence(Source deb) throws IOException {
        List<ArtifactSignatures.Evidence> evidence = new ArrayList<>();
        try (ArArchiveInputStream archive = new ArArchiveInputStream(deb.open())) {
            for (ArArchiveEntry entry = archive.getNextEntry(); entry != null; entry = archive.getNextEntry()) {
                if (!SIGNATURE_MEMBERS.contains(entry.getName())) {
                    continue;   // getNextEntry() advances past this member's bytes; the large payload is never read
                }
                // The same bounded read verify() takes: an over-cap member is a hostile signature, never a usable one,
                // and yielding no evidence for it leaves the package reported as carrying nothing this one could use.
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

    /**
     * A stream over the archive's non-signature members, concatenated in archive order - the bytes a debsig signature
     * is made over. Reads one member at a time and holds none, so the package's size does not bound what can be
     * verified.
     */
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

    /** Parse a detached signature member and, if it names a key in the trusted ring, return an initialised verifier
     *  ready to be fed the signed data; {@code null} for a malformed signature or one signed by an untrusted key. */
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
