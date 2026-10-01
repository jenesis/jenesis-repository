package build.jenesis.repository.multipart;

import module java.base;
import org.apache.commons.fileupload2.core.MultipartInput;
import org.apache.commons.fileupload2.core.ParameterParser;

/**
 * The one streaming reader for a {@code multipart/form-data} request body: a forward-only cursor over an envelope's
 * parts, where a <em>file</em> part is handed out as a stream bounded to that part and a small <em>field</em> part is
 * read whole against an explicit byte bound.
 *
 * <p>The NuGet push, the PyPI (twine) upload, their quality inspectors and the console's settings import all parse
 * multipart bodies and must not know about each other. Spring's {@code MultipartResolver} is switched off in every app
 * ({@code spring.servlet.multipart.enabled=false}), because it and {@code FormContentFilter} would drain an artifact
 * upload before the format handler read it. So one reader lives in a module of its own, {@code java.base} plus the
 * pinned {@code org.apache.commons.fileupload2.core} boundary parser, naming no format, server, Spring type or store.
 *
 * <h2>Streaming</h2>
 * {@link Part#stream()} returns {@link MultipartInput#newInputStream() the part's own bounded view} of the body, so an
 * artifact copies network-to-store and is never materialised; the only heap read is {@link Part#bytes(int)}, bounded by
 * the caller.
 *
 * <h2>Bounds (Contract clause 12)</h2>
 * <ul>
 *   <li><b>A file part is unbounded</b>: a multi-gigabyte package publishes, because it only ever streams.</li>
 *   <li><b>A field part is bounded</b> ({@link #FIELD_LIMIT}, or the caller's limit): {@link Part#bytes(int)} /
 *       {@link Part#text(int)} read at most {@code limit} bytes, and reaching the bound yields
 *       {@link Optional#empty()}, never a shorter value - a truncated settings bundle or a cut-off {@code name} field
 *       must not parse as whole. The doctrine of {@code ArchiveInflation#entry}, applied to form fields.</li>
 *   <li><b>Part count is not bounded</b>: an unread part is drained to the next boundary and discarded, costing time
 *       but not heap.</li>
 * </ul>
 *
 * <h2>Using it</h2>
 * Forward-only and single-pass. {@link #next()} first releases the previous part - draining it if unread, closing the
 * handed-out stream if read - so a caller never has to finish a part. A caller may keep a part's stream across a commit
 * and resume the walk afterwards, as the PyPI upload does for the fields that follow the distribution.
 *
 * <p>Not thread-safe and not reusable: a per-request cursor over a socket.
 */
public final class MultipartBody {

    /** The shared bound for a small form field - a project name, an action, a digest. Every field a request of ours
     *  carries is tiny by protocol, so a larger part is refused rather than buffered; a caller uploading a document
     *  (the settings bundle) states its own limit. */
    public static final int FIELD_LIMIT = 64 * 1024;

    /** {@code MultipartInput}'s boundary scanner assumes each read fills its buffer; a socket returns short reads,
     *  which makes it mis-scan a boundary after a preceding part, splitting the body and dropping bytes. A small
     *  buffered wrapper restores full reads without holding the body; it lives here so no caller forgets it. */
    private static final int READ_BUFFER = 64 * 1024;

    private final MultipartInput input;
    private final ParameterParser parser = new ParameterParser();

    private Part current;
    private boolean started;
    private boolean ended;

    private MultipartBody(MultipartInput input) {
        this.input = input;
    }

    /** The boundary a {@code Content-Type} header declares, or {@link Optional#empty()} when the header is absent, not
     *  {@code multipart/form-data}, or names no boundary. A caller that must tell "not multipart" from "multipart
     *  without a boundary" (the NuGet push, which also takes a bare {@code .nupkg}) checks the media type itself. */
    public static Optional<String> boundary(String contentType) {
        if (contentType == null || !contentType.contains("multipart/form-data")) {
            return Optional.empty();
        }
        String boundary = new ParameterParser().parse(contentType, ';').get("boundary");
        return boundary == null || boundary.isBlank() ? Optional.empty() : Optional.of(boundary);
    }

    /**
     * The boundary a body declares in its own leading delimiter line ({@code --<boundary>} and a line ending), or
     * {@link Optional#empty()} when it opens with none - how a caller without the headers, such as a
     * {@code QualityInspector} handed only bytes and a path, tells a multipart envelope from any other body.
     *
     * <p>The boundary must be non-empty (RFC 2046, 1-70 characters): a first line of exactly {@code --} is an ordinary
     * body beginning with two dashes. Either line ending is accepted, since the reader splits on the boundary, not the
     * line ending.
     */
    public static Optional<String> declaredBoundary(byte[] body) {
        if (body == null || body.length < 3 || body[0] != '-' || body[1] != '-') {
            return Optional.empty();
        }
        int newline = -1;
        for (int index = 2; index < body.length; index++) {
            if (body[index] == '\n') {
                newline = index;
                break;
            }
        }
        if (newline < 0) {
            return Optional.empty();
        }
        int end = body[newline - 1] == '\r' ? newline - 1 : newline;
        return end <= 2 ? Optional.empty() : Optional.of(new String(body, 2, end - 2, StandardCharsets.UTF_8));
    }

    /** A cursor over {@code body}, split on {@code boundary}. The body is never read past the current part, and never
     *  closed here - the request owns it. */
    public static MultipartBody over(InputStream body, String boundary) throws IOException {
        return new MultipartBody(MultipartInput.builder()
                .setInputStream(new BufferedInputStream(body, READ_BUFFER))
                .setBoundary(boundary.getBytes(StandardCharsets.UTF_8))
                .get());
    }

    /** The next part, or {@link Optional#empty()} at the end. The previous part is released first: a taken stream is
     *  closed (draining it; a second close is a no-op, so try-with-resources stays correct), and an untouched part is
     *  drained. */
    public Optional<Part> next() throws IOException {
        if (ended) {
            return Optional.empty();
        }
        boolean more;
        if (started) {
            current.release();
            current = null;
            more = input.readBoundary();
        } else {
            started = true;
            more = input.skipPreamble();
        }
        if (!more) {
            ended = true;
            return Optional.empty();
        }
        Map<String, String> disposition = disposition(input.readHeaders());
        current = new Part(disposition.getOrDefault("name", ""), disposition.get("filename"));
        return Optional.of(current);
    }

    /** The next part that carries a filename - the uploaded file - discarding every field part on the way. */
    public Optional<Part> nextFile() throws IOException {
        return nextFile(null);
    }

    /** The next file part that is the named form control, discarding every other part; {@link Optional#empty()} when
     *  the envelope ends without one. A {@code null} name matches any file part. */
    public Optional<Part> nextFile(String name) throws IOException {
        for (Optional<Part> part = next(); part.isPresent(); part = next()) {
            if (part.get().file() && (name == null || name.equals(part.get().name()))) {
                return part;
            }
        }
        return Optional.empty();
    }

    /** The {@code Content-Disposition} parameters of one part's header block, empty when it declares none. */
    private Map<String, String> disposition(String headers) {
        for (String line : headers.split("\r\n")) {
            if (line.regionMatches(true, 0, "Content-Disposition:", 0, "Content-Disposition:".length())) {
                return parser.parse(line.substring(line.indexOf(':') + 1), ';');
            }
        }
        return Map.of();
    }

    /** One part: its form-control name, its filename when it is a file part, and exactly one way to consume its body -
     *  streamed ({@link #stream()}) or read whole against a bound ({@link #bytes(int)} / {@link #text(int)}). Valid
     *  until the cursor advances. */
    public final class Part {

        private final String name;
        private final String filename;

        private InputStream stream;
        private boolean consumed;

        private Part(String name, String filename) {
            this.name = name;
            this.filename = filename;
        }

        /** The form-control name ({@code Content-Disposition; name=}), or {@code ""} when the part declares none. */
        public String name() {
            return name;
        }

        /** The uploaded filename, present exactly when this is a file part rather than a form field. */
        public Optional<String> filename() {
            return Optional.ofNullable(filename);
        }

        /** Whether this part carries a filename, i.e. is an uploaded file rather than a small form field. */
        public boolean file() {
            return filename != null;
        }

        /** This part's body as a stream bounded to it - the only way to consume an artifact-sized part. The caller
         *  should close it; the cursor closes it anyway on advancing, so the two agree on where the envelope
         *  continues. */
        public InputStream stream() {
            if (consumed) {
                throw new IllegalStateException("The body of multipart part '" + name + "' was already consumed");
            }
            consumed = true;
            stream = input.newInputStream();
            return stream;
        }

        /**
         * This part's complete body when it holds at most {@code limit} bytes, else {@link Optional#empty()}. Reading
         * one byte past the limit tells the two apart, so an over-limit part is an explicit non-value, never a prefix.
         *
         * @param limit the most bytes that count as a complete value - {@link #FIELD_LIMIT} for a form field, or the
         *     caller's own bound for a document it uploads
         */
        public Optional<byte[]> bytes(int limit) throws IOException {
            if (limit < 0 || limit == Integer.MAX_VALUE) {
                throw new IllegalArgumentException("A bounded read needs a limit in [0, Integer.MAX_VALUE): " + limit);
            }
            try (InputStream part = stream()) {
                byte[] read = part.readNBytes(limit + 1);
                return read.length > limit ? Optional.empty() : Optional.of(read);
            }
        }

        /** {@link #bytes(int)} decoded as UTF-8 - a form field's value, complete or not at all. */
        public Optional<String> text(int limit) throws IOException {
            return bytes(limit).map(value -> new String(value, StandardCharsets.UTF_8));
        }

        /** Read this part's body to its boundary and throw it away, so the cursor can advance past it. */
        public void discard() throws IOException {
            if (!consumed) {
                consumed = true;
                input.readBodyData(OutputStream.nullOutputStream());
            }
        }

        /** Leave this part behind: close a handed-out stream (draining it), or drain an untouched body. */
        private void release() throws IOException {
            if (stream == null) {
                discard();
            } else {
                stream.close();
            }
        }
    }
}
