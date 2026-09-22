package build.jenesis.repository.gateway.contract.test;

import module java.base;
import build.jenesis.repository.format.signing.OpenPgpSigner;

/**
 * A chart archive and its provenance file the way {@code helm package --sign} makes them: a gzipped tar with
 * {@code <name>/Chart.yaml} inside, and a clearsigned document carrying that {@code Chart.yaml} followed by a
 * {@code files:} map naming the archive by SHA-256 - what {@code helm verify} reads. The tar is written by hand
 * (ustar headers, nothing else), so the tests carry no archiver of their own.
 */
final class HelmFixtures {

    static final String NAME = "my-chart";
    static final String VERSION = "1.0.0";
    static final String FILE = NAME + "-" + VERSION + ".tgz";

    private HelmFixtures() {
    }

    static String chartYaml(String name, String version) {
        return "apiVersion: v2\nname: " + name + "\nversion: " + version + "\ndescription: a signed chart\n";
    }

    /** The chart archive: {@code <name>/Chart.yaml} and a values file, gzipped. */
    static byte[] chart(String name, String version) throws IOException {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        entry(tar, name + "/Chart.yaml", chartYaml(name, version).getBytes(StandardCharsets.UTF_8));
        entry(tar, name + "/values.yaml", "replicaCount: 1\n".getBytes(StandardCharsets.UTF_8));
        tar.write(new byte[1024]);   // two zero blocks end a tar
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream zip = new GZIPOutputStream(gz)) {
            zip.write(tar.toByteArray());
        }
        return gz.toByteArray();
    }

    /** The provenance statement over an archive, before signing: Chart.yaml, a document separator, the files map. */
    static byte[] statement(String name, String version, byte[] archive) throws GeneralSecurityException {
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive));
        return (chartYaml(name, version) + "\n...\nfiles:\n  " + name + "-" + version + ".tgz: sha256:" + digest + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** The {@code .prov}: the statement clearsigned by the publisher. */
    static byte[] provenance(OpenPgpSigner signer, String name, String version, byte[] archive)
            throws IOException, GeneralSecurityException {
        return signer.clearSigned(statement(name, version, archive));
    }

    private static void entry(ByteArrayOutputStream tar, String name, byte[] content) throws IOException {
        byte[] header = new byte[512];
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, header, 0, nameBytes.length);
        octal(header, 100, 8, 0644);
        octal(header, 108, 8, 0);
        octal(header, 116, 8, 0);
        octal(header, 124, 12, content.length);
        octal(header, 136, 12, Instant.now().getEpochSecond());
        Arrays.fill(header, 148, 156, (byte) ' ');
        header[156] = '0';
        System.arraycopy("ustar\0".getBytes(StandardCharsets.US_ASCII), 0, header, 257, 6);
        System.arraycopy("00".getBytes(StandardCharsets.US_ASCII), 0, header, 263, 2);
        int sum = 0;
        for (byte b : header) {
            sum += b & 0xFF;
        }
        byte[] checksum = String.format("%06o\0 ", sum).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(checksum, 0, header, 148, 8);
        tar.write(header);
        tar.write(content);
        int padding = (512 - content.length % 512) % 512;
        tar.write(new byte[padding]);
    }

    private static void octal(byte[] header, int at, int length, long value) {
        byte[] text = String.format("%0" + (length - 1) + "o", value).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(text, 0, header, at, text.length);
        header[at + length - 1] = 0;
    }
}
