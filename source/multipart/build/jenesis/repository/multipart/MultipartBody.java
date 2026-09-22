package build.jenesis.repository.multipart;

import module java.base;
import org.apache.commons.fileupload2.core.MultipartInput;
import org.apache.commons.fileupload2.core.ParameterParser;

/**
 * The one streaming reader for a {@code multipart/form-data} request body: a forward-only cursor over the parts of an
 * envelope, where a <em>file</em> part is handed out as a stream bounded to that part and a small <em>field</em> part
 * is read whole against an explicit byte bound.
 *
 * <h2>Why this exists in its own module</h2>
 * The product parses multipart bodies in five places that must not know about each other - the NuGet push and its
 * quality inspector, the PyPI (twine) upload and its quality inspector, and the console's settings import - and it used
 * to do so with four private copies of the same walk ({@code NuGetFormat.firstFilePart}, {@code PyPiFormat.Form},
 * {@code NuGetQualityInspector.multipart} and {@code PyPiQualityInspector.fields}) plus, for the console, Spring's
 * {@code MultipartResolver}. an earlier change retired the first two; the last two, which had also each hand-derived the
 * boundary from the body's own leading delimiter - see {@link #declaredBoundary(byte[])}. The resolver is deliberately switched off in every app
 * ({@code spring.servlet.multipart.enabled=false}), because it - and {@code FormContentFilter} - would drain an
 * <em>artifact</em> request body before the format handler ever read it: twine's upload and {@code dotnet nuget push}
 * are both {@code multipart/form-data}. So the console cannot use the resolver, and the two formats already could not.
 * One reader, in a module of its own, is what lets all five share the mechanism (PRINCIPLES &sect;2: shared mechanism
 * is reused, never copied) without the console reaching a format module or a format reaching the console.
 *
 * <p>The module stays {@code java.base}-light on purpose: {@code java.base} plus the one already-pinned, permissively
 * licensed parser both formats were using ({@code org.apache.commons.fileupload2.core}, PRINCIPLES &sect;8 - the
 * boundary scan is not something to hand-roll over binary bodies), and nothing else. It names no format, no server, no
 * Spring type and no store.
 *
 * <h2>Streaming (PRINCIPLES &sect;1)</h2>
 * {@link Part#stream()} returns {@link MultipartInput#newInputStream() the part's own bounded view} of the request
 * body, so an uploaded artifact is copied network-to-store in bounded chunks and is never materialised. Nothing here
 * ever holds a file part; the only heap read is {@link Part#bytes(int)}, and it is bounded by a limit the caller
 * states.
 *
 * <h2>Bounds, and what happens at one (PRINCIPLES &sect;1, Contract clause 12)</h2>
 * <ul>
 *   <li><b>A file part is unbounded, deliberately.</b> A {@code .nupkg} or a wheel has no size cap - a multi-gigabyte
 *       package that no heap could hold still publishes, because it only ever streams. That is what the publish paths
 *       rely on and this reader does not change it.</li>
 *   <li><b>A field part is bounded, explicitly.</b> {@link Part#bytes(int)} / {@link Part#text(int)} read at most
 *       {@code limit} bytes and <b>reaching the bound is an outcome, never a shorter value</b>: an over-limit part
 *       yields {@link Optional#empty()}, which the caller maps to a visible refusal. A truncated value that still
 *       parsed would be the dangerous outcome - a half-read settings bundle imported as if it were whole, or a cut-off
 *       {@code name} field forging a different project coordinate. This mirrors
 *       {@code ArchiveInflation#entry}, the free core.s one archive-inflation read and the same doctrine; it is
 *       restated rather than required here because a multipart FIELD is not an archive member - the shared bound is
 *       about how far one archive entry may inflate, and this is about how long a form field may be.</li>
 *   <li><b>Before this reader existed there was no field bound at all:</b> the PyPI upload accumulated its
 *       {@code name} field into an unbounded {@code ByteArrayOutputStream}, so a body that declared a gigabyte-long
 *       form field was buffered whole. {@link #FIELD_LIMIT} is that hole closed.</li>
 *   <li><b>Part count is not bounded</b> - a part the caller does not read is drained to the next boundary and
 *       discarded, so an envelope with many parts costs time but not heap, exactly as before.</li>
 * </ul>
 *
 * <h2>Using it</h2>
 * The cursor is forward-only and single-pass. {@link #next()} advances to the next part, first releasing the previous
 * one - draining it if the caller never read it, closing the handed-out stream if it did - so a caller never has to
 * remember to finish a part before moving on. A part the caller keeps a stream on may be read across a commit
 * boundary and the walk resumed afterwards (which is what the PyPI upload does: it reads the form fields that arrive
 * <em>after</em> the distribution while the distribution is already stored).
 *
 * <p>Not thread-safe and not reusable: it is a per-request cursor over a socket, held by one request thread.
 */
public final class MultipartBody {

    /**
     * The shared bound for a small {@code multipart/form-data} <em>field</em> - a project name, an action, a digest.
     * Every field a request body of ours is named by is tiny by the protocol's own nature, so a part claiming more
     * than this is not a field value and is refused rather than buffered. A caller whose payload is genuinely a
     * document rather than a field (the console's settings bundle) states its own limit at its call site instead.
     */
    public static final int FIELD_LIMIT = 64 * 1024;

    /**
     * {@code MultipartInput}'s boundary scanner assumes each read fills its buffer; a raw socket (or a
     * {@code SequenceInputStream} across parts) returns short reads, which makes it mis-scan a boundary when a part is
     * preceded by another part - splitting the streamed body and dropping bytes. A small buffered wrapper restores
     * fill-complete reads without holding the body whole. Both format copies carried this workaround separately; it
     * now lives once, here, so a new caller cannot forget it.
     */
    private static final int READ_BUFFER = 64 * 1024;

    private final MultipartInput input;
    private final ParameterParser parser = new ParameterParser();

    private Part current;
    private boolean started;
    private boolean ended;

    private MultipartBody(MultipartInput input) {
        this.input = input;
    }

    /**
     * The boundary declared by a {@code Content-Type} header, or {@link Optional#empty()} when the header is absent,
     * is not a {@code multipart/form-data} content type, or declares no boundary. A caller that must tell "not a
     * multipart at all" from "a multipart that declares no boundary" (the NuGet push, which also accepts a bare
     * {@code .nupkg} body) checks the media type itself first.
     */
    public static Optional<String> boundary(String contentType) {
        if (contentType == null || !contentType.contains("multipart/form-data")) {
            return Optional.empty();
        }
        String boundary = new ParameterParser().parse(contentType, ';').get("boundary");
        return boundary == null || boundary.isBlank() ? Optional.empty() : Optional.of(boundary);
    }

    /**
     * The boundary a body declares in its <em>own</em> leading delimiter line ({@code --<boundary>} followed by a line
     * ending), or {@link Optional#empty()} when the body does not open with one - which is how a caller that never
     * sees the {@code Content-Type} header tells a multipart envelope from any other request body.
     *
     * <p><b>Why this belongs here.</b> A {@code QualityInspector} is handed the artifact's bytes and its request path
     * and nothing else - the headers are long gone by the time a screen inspects a body - so the twine upload and the
     * {@code dotnet nuget push} envelope are both recognised from the delimiter the sender wrote into the body. Both
     * derived it by hand, with two different answers for the same question: one accepted a bare {@code LF} where the
     * other demanded {@code CRLF}, and one required a non-empty boundary where the other did not. That is a shared
     * concern answered twice (&sect;13), and the third copy of the same hand-scan removed two of.
     *
     * <p>The boundary must be non-empty, as RFC 2046 requires (1-70 characters): a body whose first line is exactly
     * {@code --} announces no envelope, and is an ordinary body that happens to begin with two dashes rather than a
     * broken form. Either line ending is accepted, because a sender that writes {@code LF} has still named a boundary
     * and the reader below splits on the boundary itself, not on the line ending it was announced with.
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

    /**
     * A cursor over {@code body}, split on {@code boundary}. The body is never read past the part the caller is
     * currently looking at, and this reader never closes it - the request owns it.
     */
    public static MultipartBody over(InputStream body, String boundary) throws IOException {
        return new MultipartBody(MultipartInput.builder()
                .setInputStream(new BufferedInputStream(body, READ_BUFFER))
                .setBoundary(boundary.getBytes(StandardCharsets.UTF_8))
                .get());
    }

    /**
     * The next part of the envelope, or {@link Optional#empty()} at its end. The previous part is released first: a
     * stream the caller took is closed (which drains it to this part's boundary; closing it again is a no-op, so a
     * caller's own try-with-resources stays correct), and a part the caller never touched is drained and discarded.
     */
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

    /**
     * The next part that carries a filename <em>and</em> is the named form control, discarding every other part on the
     * way; {@link Optional#empty()} when the envelope ends without one. A {@code null} name matches any file part.
     */
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

    /**
     * One part of the envelope: its form-control name, its filename when it is a file part, and exactly one way to
     * consume its body - streamed ({@link #stream()}) or read whole against a bound ({@link #bytes(int)} /
     * {@link #text(int)}). Valid only until the cursor advances.
     */
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

        /**
         * This part's body as a stream bounded to it - the streaming leg, and the only way an artifact-sized part may
         * be consumed. The caller may close it (and should); the cursor closes it anyway when it advances, so the two
         * cannot disagree about where the envelope continues.
         */
        public InputStream stream() {
            if (consumed) {
                throw new IllegalStateException("The body of multipart part '" + name + "' was already consumed");
            }
            consumed = true;
            stream = input.newInputStream();
            return stream;
        }

        /**
         * This part's complete body when it holds at most {@code limit} bytes, or {@link Optional#empty()} when it
         * holds more. Reading one byte past the limit is what tells the two apart, so an over-limit part is an
         * explicit non-value rather than a prefix the caller could mistake for the whole thing.
         *
         * @param limit the most bytes that count as a complete value - {@link #FIELD_LIMIT} for a form field, or the
         *              caller's own stated bound for a document it uploads
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
