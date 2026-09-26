package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The npm importer's tarball reader, driven directly (no live Nexus, no npm client). It builds a gzipped tar whose
 * {@code package/package.json} path is split across the ustar {@code prefix} and {@code name} fields - a
 * spec-compliant layout that a spec reader ({@code NpmImporter}, now on Commons
 * Compress) reconstructs but the former 100-byte-name hand-walk read as {@code package.json} and dropped, silently
 * losing the version. The importer is discovered through the same {@code RepositoryImporter} SPI every importer uses, so
 * the test never names the concrete class.
 */
class NpmImportTarTest {

    @TempDir
    Path root;

    @Test
    void a_ustar_prefix_tarball_is_imported_not_dropped() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        byte[] tgz = ustarPrefixTarGz("package", "package.json",
                "{\"name\":\"@acme/widget\",\"version\":\"1.2.3\"}".getBytes(StandardCharsets.UTF_8));

        npmImporter().importArtifact("@acme/widget/-/widget-1.2.3.tgz", new ByteArrayInputStream(tgz), store);

        Blobs blobs = new Blobs(store);
        assertThat(blobs.exists("npm/@acme/widget/versions/1.2.3"))
                .as("the scoped version was read from the prefix-split package.json").isTrue();
        assertThat(blobs.exists("npm/@acme/widget/tarballs/widget-1.2.3.tgz"))
                .as("the tarball was stored under the regenerated packument's path").isTrue();
        ByteArrayOutputStream version = new ByteArrayOutputStream();
        blobs.read("npm/@acme/widget/versions/1.2.3", version);
        // The importer computes the dist integrity over the tarball bytes - proof it actually parsed the metadata.
        assertThat(version.toString(StandardCharsets.UTF_8)).contains("\"1.2.3\"").contains("sha512-");
    }

    @Test
    void an_over_ceiling_tarball_imports_nothing() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        // A body past MAX_TARBALL (256 MiB) - streamed cheaply, never a real 256 MiB array in the test - is dropped
        // before it is buffered whole or parsed, so nothing lands under npm/.
        InputStream oversized = new InputStream() {
            private long remaining = 256L * 1024 * 1024 + 1;

            @Override
            public int read() {
                if (remaining <= 0) {
                    return -1;
                }
                remaining--;
                return 0;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (remaining <= 0) {
                    return -1;
                }
                int n = (int) Math.min(len, remaining);
                Arrays.fill(b, off, off + n, (byte) 0);
                remaining -= n;
                return n;
            }
        };

        npmImporter().importArtifact("acme/-/acme-1.0.0.tgz", oversized, store);

        assertThat(new Blobs(store).list("npm"))
                .as("an over-256MiB body is dropped - no tarball, no version pointer imported").isEmpty();
    }

    @Test
    void a_package_json_inflating_past_the_ceiling_is_rejected() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        // A tiny tarball (it compresses to a few KB) whose package/package.json inflates past MAX_PACKAGE_JSON (8 MiB):
        // the gunzip-bomb cap returns no manifest, so the importer writes nothing rather than reading an unbounded body.
        byte[] inflated = new byte[8 * 1024 * 1024 + 16];
        Arrays.fill(inflated, (byte) ' ');
        byte[] tgz = tarGz("package/package.json", inflated);

        npmImporter().importArtifact("acme/-/acme-1.0.0.tgz", new ByteArrayInputStream(tgz), store);

        assertThat(new Blobs(store).list("npm"))
                .as("a package.json past the gunzip-bomb cap is rejected - nothing imported").isEmpty();
    }

    private static RepositoryImporter npmImporter() {
        // The importer is a format capability now, discovered as a RepositoryFormat and filtered by
        // instanceof RepositoryImporter (mirroring the built-in importers), never a second discovered service.
        return ServiceLoader.load(RepositoryFormat.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(format -> format instanceof RepositoryImporter)
                .map(format -> (RepositoryImporter) format)
                .filter(importer -> importer.imports("npm"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no npm RepositoryImporter discovered"));
    }

    /**
     * A gzipped tar with one entry whose path is split across the ustar {@code prefix} (offset 345) and {@code name}
     * (offset 0) fields - {@code prefix}/{@code name}. A reader that only reads the name field sees {@code name} alone.
     */
    private static byte[] ustarPrefixTarGz(String prefix, String name, byte[] content) throws IOException {
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
        field(header, 257, "ustar", 6);          // magic "ustar\0"
        header[263] = '0';
        header[264] = '0';                       // version "00"
        field(header, 345, prefix, 155);
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

    /** A gzipped tar with one regular-file entry whose whole path fits the 100-byte name field (no ustar prefix split) -
     *  the plain layout the ceiling cells need, distinct from the prefix-split archive the reconstruction cell uses. */
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
        header[156] = '0';
        field(header, 257, "ustar", 6);
        header[263] = '0';
        header[264] = '0';
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
        tar.write(new byte[1024]);
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
