package build.jenesis.repository.multipart.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.multipart.MultipartBody;
import build.jenesis.repository.multipart.MultipartForm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The writer beside the reader: an envelope {@link MultipartForm} writes is one {@link MultipartBody} reads back part
 * for part, its declared length is the length of what it writes, and it can be opened again for a second send.
 */
class MultipartFormTest {

    private static final byte[] WHEEL = "the wheel's bytes\r\n--not-a-boundary\r\n".getBytes(StandardCharsets.UTF_8);

    @Test
    void the_reader_reads_back_every_field_and_file_the_writer_wrote() throws IOException {
        MultipartForm form = form();
        MultipartBody body = MultipartBody.over(form.open(), MultipartBody.boundary(form.contentType()).orElseThrow());

        MultipartBody.Part action = body.next().orElseThrow();
        assertThat(action.name()).isEqualTo(":action");
        assertThat(action.text(1024)).contains("file_upload");
        MultipartBody.Part name = body.next().orElseThrow();
        assertThat(name.text(1024)).contains("acme-demo");
        MultipartBody.Part content = body.next().orElseThrow();
        assertThat(content.file()).isTrue();
        assertThat(content.filename()).contains("acme_demo-1.0-py3-none-any.whl");
        assertThat(content.stream().readAllBytes()).isEqualTo(WHEEL);
        assertThat(body.next()).isEmpty();
    }

    @Test
    void the_declared_length_is_what_is_written_and_a_second_open_writes_it_again() throws IOException {
        MultipartForm form = form();
        byte[] first;
        try (InputStream in = form.open()) {
            first = in.readAllBytes();
        }
        assertThat(form.length()).isEqualTo(first.length);
        try (InputStream in = form.open()) {
            assertThat(in.readAllBytes()).isEqualTo(first);
        }
    }

    @Test
    void a_file_of_unknown_length_makes_the_envelope_one() {
        MultipartForm form = MultipartForm.create().file("content", "x.bin", "application/octet-stream", -1,
                () -> new ByteArrayInputStream(WHEEL));
        assertThat(form.length()).isEqualTo(-1);
    }

    @Test
    void a_name_that_would_end_its_header_early_is_refused() {
        assertThatThrownBy(() -> MultipartForm.create().field("a\"b", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MultipartForm.create().file("content", "x\r\n.bin", "application/octet-stream", 1,
                () -> new ByteArrayInputStream(new byte[1]))).isInstanceOf(IllegalArgumentException.class);
    }

    private static MultipartForm form() {
        return MultipartForm.create()
                .field(":action", "file_upload")
                .field("name", "acme-demo")
                .file("content", "acme_demo-1.0-py3-none-any.whl", "application/octet-stream", WHEEL.length,
                        () -> new ByteArrayInputStream(WHEEL));
    }
}
