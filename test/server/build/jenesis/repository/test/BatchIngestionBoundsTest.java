package build.jenesis.repository.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.server.BatchIngestion;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An exploded archive is bounded in the bytes it inflates to, not only in its entries. Streaming bounds memory, not
 * the store: a small zip holding one entry compressed a thousand to one writes its whole inflated size, and the entry
 * count cap never sees it. Each bound refuses the entry that crosses it whole - the store keeps nothing of it - stops
 * the walk, and answers {@code 413} with the entries before it standing.
 */
class BatchIngestionBoundsTest {

    private static final int MIB = 1024 * 1024;

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    @Test
    void an_entry_inflating_past_the_byte_cap_is_refused_whole_and_the_store_keeps_nothing_of_it() throws IOException {
        Exchange upload = explode(zip("readme.txt", "a small first member".getBytes(StandardCharsets.UTF_8),
                "zeros.bin", new byte[8 * MIB]), bounds(MIB, 1_000));

        assertThat(upload.status).isEqualTo(413);
        assertThat(upload.body()).contains("\"status\":\"stored\"")
                .contains("\"path\":\"/raw/zeros.bin\",\"status\":\"rejected\",\"reason\":\"archive-bytes\"");
        assertThat(store.list("blobs")).as("only the member before the bound was stored").hasSize(1);
    }

    @Test
    void an_archive_inflating_past_the_ratio_is_refused_before_the_byte_cap_is_near() throws IOException {
        Exchange upload = explode(zip("zeros.bin", new byte[8 * MIB]), bounds(1024L * MIB, 10));

        assertThat(upload.status).isEqualTo(413);
        assertThat(upload.body()).contains("\"reason\":\"archive-ratio\"");
        assertThat(store.list("blobs")).as("nothing of a refused entry is kept").isEmpty();
    }

    @Test
    void an_ordinary_archive_under_both_bounds_explodes_as_before() throws IOException {
        byte[] noise = new byte[2 * MIB];
        new Random(7).nextBytes(noise);
        Exchange upload = explode(zip("small.txt", "hello".repeat(1000).getBytes(StandardCharsets.UTF_8),
                "noise.bin", noise), bounds(1024L * MIB, 100));

        assertThat(upload.status).isEqualTo(200);
        assertThat(store.list("blobs")).hasSize(2);
    }

    private BatchIngestion bounds(long maxBytes, int maxRatio) {
        return new BatchIngestion(() -> true, () -> 10_000, () -> maxBytes, () -> maxRatio);
    }

    private Exchange explode(byte[] archive, BatchIngestion batch) throws IOException {
        Exchange exchange = new Exchange(archive);
        Publication publication = new Publication(store);
        batch.explode(exchange, (path, body) -> {
            publication.link(path, publication.storeBlob(body));
            return BatchIngestion.Outcome.STORED;
        });
        return exchange;
    }

    private static byte[] zip(Object... members) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (int index = 0; index < members.length; index += 2) {
                zip.putNextEntry(new ZipEntry((String) members[index]));
                zip.write((byte[]) members[index + 1]);
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /** An explode request at {@code /raw/} carrying {@code archive}, recording what it was answered. */
    private static final class Exchange implements FormatExchange {

        private final byte[] archive;
        private final ByteArrayOutputStream answered = new ByteArrayOutputStream();
        private int status;

        private Exchange(byte[] archive) {
            this.archive = archive;
        }

        String body() {
            return answered.toString(StandardCharsets.UTF_8);
        }

        @Override
        public String method() {
            return "PUT";
        }

        @Override
        public String path() {
            return "/raw/";
        }

        @Override
        public String queryParameter(String name) {
            return null;
        }

        @Override
        public String requestHeader(String name) {
            return BatchIngestion.EXPLODE_HEADER.equalsIgnoreCase(name) ? "zip" : null;
        }

        @Override
        public InputStream requestStream() {
            return new ByteArrayInputStream(archive);
        }

        @Override
        public void setResponseHeader(String name, String value) {
        }

        @Override
        public OutputStream respond(int status, long contentLength) {
            this.status = status;
            return answered;
        }
    }
}
