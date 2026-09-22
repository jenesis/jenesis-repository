package build.jenesis.repository.multipart.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.multipart.MultipartBody;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared multipart reader's contract, pinned directly rather than only through its three callers (the NuGet push,
 * the PyPI upload and the console's settings import). Two properties matter and neither is visible from a happy-path
 * publish test:
 *
 * <ul>
 *   <li><b>A file part streams and is bounded to itself.</b> The stream a caller is handed stops at the part's
 *       terminating boundary and never spills into the envelope's trailer - which is what lets a publish hand it
 *       straight to the content-addressed store instead of buffering an artifact.</li>
 *   <li><b>A field part has a bound, and reaching it is an outcome.</b> Before this reader existed, the PyPI upload
 *       accumulated its {@code name} field into an unbounded {@code ByteArrayOutputStream}: a body declaring a
 *       gigabyte-long form field was buffered whole. Now an over-limit field yields no value at all - never a prefix,
 *       because a truncated value that still parses is exactly the dangerous outcome (a cut-off project name would
 *       forge a different coordinate, a cut-off settings bundle would import as if it were whole).</li>
 * </ul>
 */
class MultipartBodyTest {

    private static final String BOUNDARY = "----JenesisMultipartReaderTest";

    @Test
    void a_content_type_declares_its_boundary_and_nothing_else_does() {
        assertThat(MultipartBody.boundary("multipart/form-data; boundary=" + BOUNDARY)).contains(BOUNDARY);
        assertThat(MultipartBody.boundary("MULTIPART/FORM-DATA")).as("declared without a boundary").isEmpty();
        assertThat(MultipartBody.boundary("application/octet-stream")).as("a bare artifact body").isEmpty();
        assertThat(MultipartBody.boundary(null)).as("no Content-Type at all").isEmpty();
    }

    @Test
    void a_body_declares_its_own_boundary_when_no_header_reaches_the_reader() {
        // The two quality inspectors are handed an artifact's bytes and its path and nothing else, so the envelope has
        // to be recognised from the delimiter its sender wrote. One derivation, so the two formats cannot
        // disagree about what a form is - which they did: one demanded CRLF, the other accepted a bare LF.
        assertThat(MultipartBody.declaredBoundary(("--" + BOUNDARY + "\r\nrest").getBytes(StandardCharsets.UTF_8)))
                .contains(BOUNDARY);
        assertThat(MultipartBody.declaredBoundary(("--" + BOUNDARY + "\nrest").getBytes(StandardCharsets.UTF_8)))
                .as("a bare LF still names a boundary; the parts split on the boundary, not on the line ending")
                .contains(BOUNDARY);
        assertThat(MultipartBody.declaredBoundary("{\"json\": true}".getBytes(StandardCharsets.UTF_8)))
                .as("an ordinary body announces no envelope").isEmpty();
        assertThat(MultipartBody.declaredBoundary("--\r\n".getBytes(StandardCharsets.UTF_8)))
                .as("RFC 2046 boundaries are 1-70 characters, so a bare -- names none").isEmpty();
        assertThat(MultipartBody.declaredBoundary(("--" + BOUNDARY).getBytes(StandardCharsets.UTF_8)))
                .as("a delimiter line that never ends is not a declaration").isEmpty();
        assertThat(MultipartBody.declaredBoundary(new byte[0])).isEmpty();
        assertThat(MultipartBody.declaredBoundary(null)).isEmpty();
    }

    @Test
    void a_declared_boundary_splits_the_body_it_was_read_from() throws IOException {
        byte[] envelope = (field("name", "widget") + "--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8);
        MultipartBody body = MultipartBody.over(new ByteArrayInputStream(envelope),
                MultipartBody.declaredBoundary(envelope).orElseThrow());

        assertThat(body.next().orElseThrow().text(MultipartBody.FIELD_LIMIT)).contains("widget");
    }

    @Test
    void a_file_part_is_a_stream_bounded_to_that_part() throws IOException {
        MultipartBody body = MultipartBody.over(envelope(
                field("name", "widget"),
                file("content", "widget-1.0.whl", "ARTIFACT-BYTES")), BOUNDARY);

        Optional<MultipartBody.Part> file = body.nextFile();
        assertThat(file).isPresent();
        assertThat(file.get().filename()).contains("widget-1.0.whl");
        try (InputStream part = file.get().stream()) {
            assertThat(new String(part.readAllBytes(), StandardCharsets.UTF_8))
                    .as("the part stream stops at its own boundary, never reading the envelope's trailer")
                    .isEqualTo("ARTIFACT-BYTES");
        }
        assertThat(body.next()).as("the envelope ended with the file part").isEmpty();
    }

    @Test
    void the_cursor_drains_an_ignored_part_and_reads_fields_after_the_file() throws IOException {
        // twine may send the naming field either side of the distribution, so the walk has to survive both. A part the
        // caller never touches is drained to its boundary by the cursor itself rather than by every caller.
        MultipartBody body = MultipartBody.over(envelope(
                field("ignored", "x".repeat(5000)),
                file("content", "widget-1.0.whl", "ARTIFACT-BYTES"),
                field("name", "widget")), BOUNDARY);

        Optional<MultipartBody.Part> ignored = body.next();
        assertThat(ignored).isPresent();
        assertThat(ignored.get().name()).isEqualTo("ignored");
        assertThat(ignored.get().file()).isFalse();

        Optional<MultipartBody.Part> file = body.next();       // advancing drains the ignored field
        assertThat(file).isPresent();
        assertThat(file.get().file()).isTrue();
        InputStream part = file.get().stream();
        assertThat(new String(part.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("ARTIFACT-BYTES");
        part.close();

        Optional<MultipartBody.Part> trailing = body.next();   // the walk resumes after the consumed file part
        assertThat(trailing).isPresent();
        assertThat(trailing.get().text(MultipartBody.FIELD_LIMIT)).contains("widget");
        assertThat(body.next()).isEmpty();
    }

    @Test
    void a_field_at_the_bound_is_complete_and_one_past_it_is_no_value_at_all() throws IOException {
        String atLimit = "v".repeat(16);
        assertThat(MultipartBody.over(envelope(field("name", atLimit)), BOUNDARY)
                .next().orElseThrow().text(16))
                .as("a value of exactly the limit is complete, so the bound is not off by one")
                .contains(atLimit);

        assertThat(MultipartBody.over(envelope(field("name", "v".repeat(17))), BOUNDARY)
                .next().orElseThrow().text(16))
                .as("one byte over the bound is an explicit non-value, never a 16-byte prefix")
                .isEmpty();
    }

    @Test
    void a_named_file_control_is_selected_past_any_other_part() throws IOException {
        MultipartBody body = MultipartBody.over(envelope(
                field("_csrf", "token"),
                file("other", "decoy.json", "DECOY"),
                file("bundle", "jenesis-settings.json", "{}")), BOUNDARY);

        Optional<MultipartBody.Part> bundle = body.nextFile("bundle");
        assertThat(bundle).isPresent();
        assertThat(bundle.get().bytes(64)).contains("{}".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void an_empty_body_carries_no_part_rather_than_failing() throws IOException {
        assertThat(MultipartBody.over(new ByteArrayInputStream(new byte[0]), BOUNDARY).nextFile("bundle")).isEmpty();
    }

    private static InputStream envelope(String... parts) {
        return new ByteArrayInputStream((String.join("", parts) + "--" + BOUNDARY + "--\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String field(String name, String value) {
        return "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n";
    }

    private static String file(String name, String filename, String content) {
        return "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n"
                + content + "\r\n";
    }
}
