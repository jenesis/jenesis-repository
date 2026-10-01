package build.jenesis.repository.cli;

import module java.base;

import tools.jackson.databind.json.JsonMapper;

/**
 * What {@code --json} does: the client records what the server sent and the human rendering is suppressed, so a
 * program receives the API's own answer - complete, including fields this CLI does not model, and unable to drift
 * from the text mode. A second renderer per command would be a few hundred print sites to keep in step.
 *
 * <p>State is static because a command line is one shot; {@link Cli} clears it in a finally, so commands run in one
 * JVM - as the tests do - cannot leak a mode into the next.
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
     * <p>A redirected {@code System.out} encodes in the platform's charset, US-ASCII under a {@code C} locale, which
     * would turn an accented letter of the server's answer into {@code ?}. JSON is UTF-8 by definition (RFC 8259).
     * The human rendering keeps the terminal's charset, since a person on that terminal reads it.
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
     * <p>One document is printed as it arrived; several become an array, since concatenated objects are not JSON; a
     * body that is not JSON (a PEM, a CSV, an XML SBOM) is wrapped with its content type; and nothing collected is
     * {@code {"ok":true}}.
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
