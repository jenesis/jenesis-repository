package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.apache.commons.compress;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.debian.DebianSignature;
import build.jenesis.repository.format.signing.OpenPgpSigner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the {@code .deb} embedded-signature verify streams end to end: a package whose {@code data.tar} member is
 * larger than the maximum size of a Java array verifies {@link DebianSignature.Result#VALID VALID} without ever being
 * held whole in memory. Before this pass {@code verify} concatenated every {@code ar} member ({@code data.tar}
 * included) into a {@link ByteArrayOutputStream} to form the signed data - {@code ~2x} the package on the heap, and a
 * package past {@link Integer#MAX_VALUE} bytes could not even be represented, so once a trusted keyring was provisioned
 * a multi-gigabyte {@code .deb} OOM'd on push; now the reopenable blob is read twice (the small signature member(s)
 * first, the large signed members streamed second) so the package's bytes never sit in heap (the {@code }
 * bounded-heap mould, here for the Debian signature-verify path). Were any step to buffer the signed data into a
 * {@code byte[]}, materialising more than {@code Integer.MAX_VALUE} bytes would blow the array limit and throw.
 */
class DebianSignatureStreamingTest {

    private static final byte[] DEBIAN_BINARY = "2.0\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONTROL = "control.tar.gz bytes".getBytes(StandardCharsets.UTF_8);

    @Test
    void a_deb_larger_than_the_array_limit_verifies_streamed() throws IOException {
        OpenPgpSigner.KeyMaterial key = OpenPgpSigner.generate("Uploader <u@example.com>", Duration.ofDays(365));
        // A data.tar member past Integer.MAX_VALUE: the old verify concatenated every member (data.tar included) into a
        // ByteArrayOutputStream, which cannot hold this many bytes - so a completing VALID must have streamed instead.
        long dataSize = (1L << 31) + (1L << 20);   // 2 GiB + 1 MiB, comfortably past the array limit and even-length

        // Sign the concatenation of the non-signature members (debian-binary ++ control ++ data), streamed so the
        // >2 GiB payload is digested in bounded heap - the signing counterpart of the streaming verify.
        byte[] signature = new OpenPgpSigner(key.secretKey()).detachedSignature(signedData(dataSize), OpenPgpSigner.Encoding.ARMOURED);

        // Each open() regenerates the whole ar archive on the fly (the huge data member is synthetic zero bytes, never
        // a byte[]); verify reopens it per pass, so the package is never held whole.
        DebianSignature.Source deb = () -> debStream(dataSize, signature);

        assertThat(DebianSignature.verify(deb, key.publicKey()))
                .as("a multi-gigabyte signed .deb verifies without buffering data.tar")
                .isEqualTo(DebianSignature.Result.VALID);
    }

    @Test
    void a_deb_with_an_oversize_signature_member_is_refused_without_buffering_it() throws IOException {
        OpenPgpSigner.KeyMaterial key = OpenPgpSigner.generate("Uploader <u@example.com>", Duration.ofDays(365));
        // A _gpgorigin signature member past the Java array limit (and far past the MAX_SIGNATURE cap). A legitimate
        // detached signature is a few kilobytes, so this is purely hostile - a .deb declaring a huge signature member.
        // The former readAllBytes() would try to buffer the whole member (OutOfMemoryError once it is past the array
        // limit); the bounded readNBytes(MAX_SIGNATURE + 1) reads only the cap, finds it over-size and refuses it -
        // UNTRUSTED (signed, but no trusted verifier), in bounded heap. The large data.tar members were already
        // streamed; this was the one member read whole.
        long hostileSignatureSize = (1L << 31) + (1L << 20);   // 2 GiB + 1 MiB, past Integer.MAX_VALUE
        DebianSignature.Source deb = () -> debStreamWithSignature(hostileSignatureSize);

        assertThat(DebianSignature.verify(deb, key.publicKey()))
                .as("an oversize signature member is refused (never VALID) without buffering it whole")
                .isEqualTo(DebianSignature.Result.UNTRUSTED);
    }

    @Test
    void a_normally_sized_signed_deb_still_verifies_under_the_bounded_read() throws IOException {
        OpenPgpSigner.KeyMaterial key = OpenPgpSigner.generate("Uploader <u@example.com>", Duration.ofDays(365));
        byte[] data = "data.tar.gz bytes".getBytes(StandardCharsets.UTF_8);
        InputStream signed = new SequenceInputStream(Collections.enumeration(List.of(
                new ByteArrayInputStream(DEBIAN_BINARY),
                new ByteArrayInputStream(CONTROL),
                new ByteArrayInputStream(data))));
        byte[] signature = new OpenPgpSigner(key.secretKey()).detachedSignature(signed, OpenPgpSigner.Encoding.ARMOURED);
        DebianSignature.Source deb = () -> smallDeb(data, signature);

        assertThat(DebianSignature.verify(deb, key.publicKey()))
                .as("a normally-sized signed .deb still verifies VALID under the bounded signature-member read")
                .isEqualTo(DebianSignature.Result.VALID);
    }

    /** A {@code .deb} whose {@code _gpgorigin} signature member is {@code signatureSize} synthetic zero bytes (never a
     *  {@code byte[]}), generated on the fly on a producer thread so the oversize member never sits in heap - the fixture
     *  the bounded signature-member read is proven against. */
    private static InputStream debStreamWithSignature(long signatureSize) throws IOException {
        PipedInputStream in = new PipedInputStream(1 << 16);
        PipedOutputStream out = new PipedOutputStream(in);
        Thread producer = new Thread(() -> {
            try (ArArchiveOutputStream archive = new ArArchiveOutputStream(out)) {
                member(archive, "debian-binary", new ByteArrayInputStream(DEBIAN_BINARY), DEBIAN_BINARY.length);
                member(archive, "control.tar.gz", new ByteArrayInputStream(CONTROL), CONTROL.length);
                member(archive, "data.tar.gz", new ByteArrayInputStream(CONTROL), CONTROL.length);
                member(archive, "_gpgorigin", new SyntheticInputStream(signatureSize), signatureSize);
            } catch (IOException e) {
                // the reader observes a truncated archive and the assertion fails; nothing to recover here
            }
        }, "deb-ar-oversize-sig-producer");
        producer.setDaemon(true);
        producer.start();
        return in;
    }

    /** A small in-heap {@code .deb} ({@code ar} archive) with a real detached signature member last - the normal-case
     *  control the bounded read must still verify VALID. */
    private static InputStream smallDeb(byte[] data, byte[] signature) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ArArchiveOutputStream archive = new ArArchiveOutputStream(bytes)) {
            member(archive, "debian-binary", new ByteArrayInputStream(DEBIAN_BINARY), DEBIAN_BINARY.length);
            member(archive, "control.tar.gz", new ByteArrayInputStream(CONTROL), CONTROL.length);
            member(archive, "data.tar.gz", new ByteArrayInputStream(data), data.length);
            member(archive, "_gpgorigin", new ByteArrayInputStream(signature), signature.length);
        }
        return new ByteArrayInputStream(bytes.toByteArray());
    }

    /** The signed data: the non-signature members' contents concatenated in archive order, the data member synthetic
     *  so the >2 GiB payload is fed to the signer without ever being assembled into one array. */
    private static InputStream signedData(long dataSize) {
        return new SequenceInputStream(Collections.enumeration(List.of(
                new ByteArrayInputStream(DEBIAN_BINARY),
                new ByteArrayInputStream(CONTROL),
                new SyntheticInputStream(dataSize))));
    }

    /** A {@code .deb} ({@code ar} archive) generated on the fly on a producer thread, so its multi-GB data member never
     *  sits in heap: {@code debian-binary}, {@code control.tar.gz}, a synthetic {@code data.tar.gz} of {@code dataSize}
     *  zero bytes, then the {@code _gpgorigin} signature member last (the archive order the fixtures use). The returned
     *  stream is the pipe's read end; closing it (which the verify does per pass) ends the producer. */
    private static InputStream debStream(long dataSize, byte[] signature) throws IOException {
        PipedInputStream in = new PipedInputStream(1 << 16);
        PipedOutputStream out = new PipedOutputStream(in);
        Thread producer = new Thread(() -> {
            try (ArArchiveOutputStream archive = new ArArchiveOutputStream(out)) {
                member(archive, "debian-binary", new ByteArrayInputStream(DEBIAN_BINARY), DEBIAN_BINARY.length);
                member(archive, "control.tar.gz", new ByteArrayInputStream(CONTROL), CONTROL.length);
                member(archive, "data.tar.gz", new SyntheticInputStream(dataSize), dataSize);
                member(archive, "_gpgorigin", new ByteArrayInputStream(signature), signature.length);
            } catch (IOException e) {
                // the reader will observe a truncated archive and the assertion will fail; nothing to recover here
            }
        }, "deb-ar-producer");
        producer.setDaemon(true);
        producer.start();
        return in;
    }

    /** Write one {@code ar} member, streaming {@code content}'s {@code length} bytes in bounded chunks. */
    private static void member(ArArchiveOutputStream archive, String name, InputStream content, long length)
            throws IOException {
        archive.putArchiveEntry(new ArArchiveEntry(name, length));
        byte[] buffer = new byte[1 << 16];
        for (int read = content.read(buffer); read != -1; read = content.read(buffer)) {
            archive.write(buffer, 0, read);
        }
        archive.closeArchiveEntry();
    }

    /** An input stream that yields {@code size} synthetic zero bytes in bounded chunks, never backed by an array of the
     *  whole length - feeding it proves the consumer streams rather than allocating the full size. */
    private static final class SyntheticInputStream extends InputStream {

        private long remaining;

        private SyntheticInputStream(long size) {
            this.remaining = size;
        }

        @Override
        public int read() {
            if (remaining <= 0) {
                return -1;
            }
            remaining--;
            return 0;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (remaining <= 0) {
                return -1;
            }
            int read = (int) Math.min(length, remaining);
            Arrays.fill(buffer, offset, offset + read, (byte) 0);
            remaining -= read;
            return read;
        }
    }
}
