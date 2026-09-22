package build.jenesis.repository.cli;

import module java.base;

import tools.jackson.databind.json.JsonMapper;

/**
 * What {@code --json} does, and why it is not implemented one command at a time.
 *
 * <p>The obvious way to add a machine-readable mode is to give every command a second renderer beside its text
 * one. There are a few hundred print sites here, so that is a few hundred opportunities for the two to disagree -
 * a field in one and not the other, a rename applied once - and no way to notice, because nothing compares them.
 * It also re-serialises data the CLI has already parsed, so any field the client's record does not model is
 * silently dropped from the output an agent is trying to read.
 *
 * <p>So {@code --json} takes the other route, which this tool's thinness makes available: every command is one or
 * more HTTP calls, and the server already answers in JSON. In JSON mode the client records what the server sent
 * and the human rendering is suppressed, so what a program receives is the API's own answer - complete, including
 * fields this CLI has never heard of, and incapable of drifting from the text mode because it is not derived from
 * it.
 *
 * <p>State is static because a command line is one shot. {@link Cli} sets it up and clears it in a finally, so
 * running commands in the same JVM - which the tests do - cannot leak one invocation's mode into the next.
 */
final class Output {

    /** One response, as it came off the wire. */
    private record Document(String contentType, String body) {

        private boolean isJson() {
            return contentType != null && contentType.contains("json");
        }
    }

    /** The mapper this module already carries; the client parses every response with it. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static boolean json;

    private static final List<Document> DOCUMENTS = new ArrayList<>();

    private Output() {
    }

    static void json() {
        json = true;
    }

    static boolean isJson() {
        return json;
    }

    static void reset() {
        json = false;
        DOCUMENTS.clear();
    }

    /**
     * {@code stream} writing UTF-8, whatever the process was started under.
     *
     * <p>{@code System.out} and {@code System.err} encode with {@code stdout.encoding}, which the launcher derives
     * from the locale of the terminal or, when the stream is redirected - which is how a program calls this tool -
     * from the platform's native encoding. Under a {@code C}/{@code POSIX} locale that is US-ASCII, and a
     * {@link PrintStream} replaces every character it cannot encode with {@code ?}: a coordinate with an accented
     * letter came out of {@code --json} as a different coordinate, silently, in the one mode whose promise is the
     * server's answer verbatim. JSON is UTF-8 by definition (RFC 8259), so the document is encoded as UTF-8 here
     * and handed to the stream as bytes, which a {@code PrintStream} passes through untouched. The human rendering
     * deliberately keeps the terminal's own charset: it is read by a person on that terminal, and forcing UTF-8
     * on a Latin-1 console would turn the same accented letter into two wrong ones instead of one {@code ?}.
     */
    static PrintStream utf8(PrintStream stream) {
        return new PrintStream(stream, true, StandardCharsets.UTF_8);
    }

    /** Drop every response recorded so far, keeping the mode. What a refreshing command calls between polls: a
     *  hundred status reads must still leave exactly one JSON value to flush, and the one that matters is the
     *  last. */
    static void forget() {
        DOCUMENTS.clear();
    }

    /** Record a response the server sent. Called by {@link RepositoryClient} only while JSON mode is on. */
    static void record(String contentType, String body) {
        if (json && body != null && !body.isBlank()) {
            DOCUMENTS.add(new Document(contentType, body));
        }
    }

    /**
     * Print what was collected, as exactly one JSON value.
     *
     * <p>One document is printed as it arrived, so a caller can hand it straight to a parser. Several - a command
     * that makes more than one call - become an array, because a stream of concatenated objects is not JSON and
     * would break the parser it was meant to serve. A body that is not JSON at all (a PEM, a CSV export, an XML
     * SBOM) is wrapped with its content type rather than spliced in raw, for the same reason. Nothing collected
     * means the command did its work without the server answering with a body, and {@code ok} says so.
     */
    static void flush(PrintStream out) {
        if (DOCUMENTS.isEmpty()) {
            out.println("{\"ok\":true}");
            return;
        }
        if (DOCUMENTS.size() == 1) {
            out.println(render(DOCUMENTS.getFirst()));
            return;
        }
        out.println(DOCUMENTS.stream().map(Output::render)
                .collect(Collectors.joining(",", "[", "]")));
    }

    private static String render(Document document) {
        if (document.isJson()) {
            return document.body().strip();
        }
        SequencedMap<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("contentType", document.contentType());
        wrapped.put("body", document.body());
        return document(wrapped);
    }

    /** One JSON document from its fields. */
    static String document(SequencedMap<String, Object> fields) {
        return JSON.writeValueAsString(fields);
    }

    /** One JSON string, escaped by the mapper. */
    static String text(String value) {
        return JSON.writeValueAsString(value);
    }

}
