package build.jenesis.repository.blobs.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.DetachedExchange;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.testkit.FaultInjectingStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A serve asked for the tail of an artifact opens the blob at the tail rather than reading it whole and throwing the
 * prefix away - over an object store, resuming the last megabyte of a multi-gigabyte download costs the last megabyte.
 */
class BlobServeRangeTest {

    @TempDir
    Path root;

    @Test
    void a_tail_is_opened_at_its_offset_rather_than_read_and_discarded() throws IOException {
        ArtifactStore disk = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        byte[] body = new byte[64];
        for (int index = 0; index < body.length; index++) {
            body[index] = (byte) index;
        }
        Blobs writer = new Blobs(disk);
        String hash = writer.store(new ByteArrayInputStream(body));
        writer.link("files/artifact.bin", hash, body.length);

        FaultInjectingStore counted = FaultInjectingStore.wrap(disk);
        Blobs blobs = new Blobs(counted);
        Tail exchange = new Tail(40);
        blobs.serve(blobs.locate("files/artifact.bin").orElseThrow(), exchange);

        assertThat(exchange.body.toByteArray()).as("the bytes from the offset on")
                .isEqualTo(Arrays.copyOfRange(body, 40, body.length));
        assertThat(counted.calls(FaultInjectingStore.Op.OPEN_FROM)).as("the blob opened at the offset").isEqualTo(1);
        assertThat(counted.calls(FaultInjectingStore.Op.OPEN)).as("and never opened whole").isZero();
    }

    /** An exchange that asks for the content from {@code offset} on and keeps what it is sent. */
    private static final class Tail implements DetachedExchange {

        private final long offset;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();

        Tail(long offset) {
            this.offset = offset;
        }

        @Override
        public long from(long contentLength) {
            return offset;
        }

        @Override
        public String method() {
            return "GET";
        }

        @Override
        public String path() {
            return "files/artifact.bin";
        }

        @Override
        public String queryParameter(String name) {
            return null;
        }

        @Override
        public String requestHeader(String name) {
            return null;
        }

        @Override
        public InputStream requestStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public void setResponseHeader(String name, String value) {
        }

        @Override
        public OutputStream respond(int status, long contentLength) {
            return body;
        }
    }
}
