package build.jenesis.repository.multipart;

import module java.base;

/**
 * A {@code multipart/form-data} envelope to send: the writer beside {@link MultipartBody}, the reader. Text fields and
 * file parts in the order given, the file parts streamed from wherever their bytes are rather than held, and the
 * envelope's whole length known up front whenever every part's is - so a client publishing a large artifact in a form
 * sends a {@code Content-Length} rather than a chunked body a registry may refuse.
 *
 * <p>Opened once per send: {@link #open()} returns a fresh stream each time, which is what lets a request that is
 * retried or redirected send the same envelope again.
 */
public final class MultipartForm {

    /** Where a file part's bytes come from, each time the envelope is opened. */
    @FunctionalInterface
    public interface Content {
        InputStream open() throws IOException;
    }

    private record Part(byte[] head, Content content, long length) {
    }

    private static final byte[] CRLF = {'\r', '\n'};

    private final String boundary;
    private final List<Part> parts = new ArrayList<>();

    private MultipartForm(String boundary) {
        this.boundary = boundary;
    }

    /** An empty form under a boundary no part's bytes can contain by chance. */
    public static MultipartForm create() {
        return new MultipartForm("jenesis-" + UUID.randomUUID());
    }

    /** A text field. */
    public MultipartForm field(String name, String value) {
        byte[] text = value.getBytes(StandardCharsets.UTF_8);
        parts.add(new Part(head("form-data; name=\"" + quoted(name) + "\"", Optional.empty()),
                () -> new ByteArrayInputStream(text), text.length));
        return this;
    }

    /** A file part of {@code length} bytes ({@code -1} when not known), opened from {@code content} on each send. */
    public MultipartForm file(String name, String filename, String contentType, long length, Content content) {
        parts.add(new Part(head("form-data; name=\"" + quoted(name) + "\"; filename=\"" + quoted(filename) + "\"",
                Optional.of(contentType)), content, length));
        return this;
    }

    /** The {@code Content-Type} the envelope is sent with, naming its boundary. */
    public String contentType() {
        return "multipart/form-data; boundary=" + boundary;
    }

    /** The envelope's length in bytes, or {@code -1} when a file part's is not known. */
    public long length() {
        long total = closing().length;
        for (Part part : parts) {
            if (part.length() < 0) {
                return -1;
            }
            total += part.head().length + part.length() + 2;
        }
        return total;
    }

    /** The envelope's bytes, from the start. */
    public InputStream open() throws IOException {
        List<InputStream> streams = new ArrayList<>();
        try {
            for (Part part : parts) {
                streams.add(new ByteArrayInputStream(part.head()));
                streams.add(part.content().open());
                streams.add(new ByteArrayInputStream(CRLF));
            }
        } catch (IOException | RuntimeException failed) {
            for (InputStream opened : streams) {
                opened.close();
            }
            throw failed;
        }
        streams.add(new ByteArrayInputStream(closing()));
        return new SequenceInputStream(Collections.enumeration(streams));
    }

    private byte[] head(String disposition, Optional<String> contentType) {
        return ("--" + boundary + "\r\nContent-Disposition: " + disposition + "\r\n"
                + contentType.map(type -> "Content-Type: " + type + "\r\n").orElse("") + "\r\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private byte[] closing() {
        return ("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
    }

    /** A name or filename as a quoted string: a quote or line break in it would end the header early. */
    private static String quoted(String value) {
        if (value.indexOf('"') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("a form part cannot be named " + value);
        }
        return value;
    }
}
