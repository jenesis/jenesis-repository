package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.npm.NpmImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins whether the npm importer's screen coordinate and its stored/served coordinate agree (audit finding N2).
 * {@link NpmImporter#importTarget} derives the coordinate from the tarball's {@code <name>/-/<file>.tgz} source PATH -
 * the label the import edge screens the asset against. {@link NpmImporter#importArtifact} ignores that path coordinate
 * and reads {@code name}/{@code version} from the tarball's own {@code package/package.json}, keying
 * {@code npm/<name>/versions/<version>} and {@code npm/<name>/tarballs/<file>} on it, with no check that the two match.
 *
 * <p>Like Composer/CocoaPods, npm's importer now validates the embedded {@code package.json} coordinate against the
 * path the edge screened and REFUSES a mismatch, so a tarball can never be screened under one coordinate and then
 * stored/served under another (the N2 screen-label bypass). These cells prove the refusal and the matching baseline.
 * Container-free.
 */
class NpmImportCoordinateAgreementTest {

    @TempDir
    Path root;

    /**
     * The N2 fix: the import edge screens against {@code importTarget}'s path coordinate ({@code innocent@1.0.0}); a
     * tarball whose {@code package.json} declares a DIFFERENT coordinate ({@code evil-payload@9.9.9}) is REFUSED rather
     * than stored under a coordinate the gate never screened. Nothing is published under either coordinate.
     */
    @Test
    void a_tarball_whose_manifest_disagrees_with_the_path_is_refused_not_stored() throws IOException {
        ArtifactStore store = store();
        String path = "innocent/-/innocent-1.0.0.tgz";

        // The label the import edge screens this asset against - taken purely from the source path.
        ArtifactDescriptor screened = new NpmImporter().importTarget(path).orElseThrow();
        assertThat(screened.coordinate()).isEqualTo("innocent");
        assertThat(screened.version()).isEqualTo("1.0.0");

        // The tarball's own package.json declares a DIFFERENT coordinate; the importer must refuse the mismatch.
        byte[] tgz = tarGz("package/package.json",
                "{\"name\":\"evil-payload\",\"version\":\"9.9.9\"}".getBytes(StandardCharsets.UTF_8));
        new NpmImporter().importArtifact(path, new ByteArrayInputStream(tgz), store);

        Blobs blobs = new Blobs(store);
        assertThat(blobs.exists("npm/evil-payload/versions/9.9.9"))
                .as("a manifest that disagrees with the screened path is NOT stored under the package.json coordinate")
                .isFalse();
        assertThat(blobs.exists("npm/innocent/versions/1.0.0"))
                .as("nor mis-filed under the screened path coordinate").isFalse();
        assertThat(blobs.list("npm"))
                .as("the mismatched tarball is refused outright - the screen-label bypass is closed")
                .doesNotContain("evil-payload", "innocent");
    }

    /**
     * The honest baseline: when a tarball's {@code package.json} coordinate DOES match its source path, the screen
     * label and the served coordinate coincide. This is the case the gap above silently breaks.
     */
    @Test
    void a_matching_tarball_serves_under_the_coordinate_it_was_screened_against() throws IOException {
        ArtifactStore store = store();
        String path = "lodash/-/lodash-4.17.11.tgz";

        ArtifactDescriptor screened = new NpmImporter().importTarget(path).orElseThrow();
        assertThat(screened.coordinate()).isEqualTo("lodash");
        assertThat(screened.version()).isEqualTo("4.17.11");

        byte[] tgz = tarGz("package/package.json",
                "{\"name\":\"lodash\",\"version\":\"4.17.11\"}".getBytes(StandardCharsets.UTF_8));
        new NpmImporter().importArtifact(path, new ByteArrayInputStream(tgz), store);

        assertThat(new Blobs(store).exists("npm/lodash/versions/4.17.11"))
                .as("a package.json that matches the path serves under the screened coordinate")
                .isTrue();
    }

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    /** A gzipped tar with one regular-file entry whose path fits the 100-byte name field (no ustar prefix split). */
    private static byte[] tarGz(String name, byte[] content) throws IOException {
        byte[] header = new byte[512];
        field(header, 0, name, 100);
        field(header, 100, "0000644", 8);
        field(header, 108, "0000000", 8);
        field(header, 116, "0000000", 8);
        field(header, 124, String.format("%011o", content.length), 12);
        field(header, 136, "00000000000", 12);
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        header[156] = '0';                       // typeflag: regular file
        field(header, 257, "ustar", 6);          // magic
        header[263] = '0';
        header[264] = '0';                       // version "00"
        int checksum = 0;
        for (byte b : header) {
            checksum += b & 0xFF;
        }
        field(header, 148, String.format("%06o", checksum), 6);
        header[154] = 0;
        header[155] = ' ';

        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        tar.write(header);
        tar.write(content);
        tar.write(new byte[(512 - content.length % 512) % 512]);
        tar.write(new byte[1024]);               // two zero blocks: end of archive
        ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(gzipped)) {
            gzip.write(tar.toByteArray());
        }
        return gzipped.toByteArray();
    }

    private static void field(byte[] header, int offset, String value, int length) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, offset, Math.min(bytes.length, length));
    }
}
