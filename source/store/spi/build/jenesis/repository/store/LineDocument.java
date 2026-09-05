package build.jenesis.repository.store;

import module java.base;

/**
 * A small stored document as lines: a first line naming what it is and which version of itself it is, then named
 * fields one per line, then - after a blank line, when there are any - free lines the document carries whole. The
 * shape half a dozen stored objects each hand-rolled: the listing frame, the generation-index marker, the search
 * manifest and its segment manifest, the license sidecar, the forwarding provenance marker - and the stored report,
 * which was positional lines with no name and no version at all, the one shape a reader cannot tell a foreign object
 * from.
 *
 * <p>The magic line is what makes a torn, older or foreign object read as absent rather than as garbage:
 * {@link #parse} answers empty for anything that does not open with the magic asked for, and a caller treats that as
 * "nothing stored" - the honest direction, and the rule the generation index stated for its marker. A version lets a
 * reader refuse a document newer than it understands. Field values are read back as text; a caller that wants a
 * number parses it and decides what a garbled one means for its document, as the search manifest does.
 *
 * <p>Written byte-for-byte as the search manifest already was ({@code jenesis-search 1}, then {@code name value}
 * lines), so a document that adopts this codec need not migrate what it stored.
 */
public final class LineDocument {

    private final String magic;

    private final int version;

    private final SequencedMap<String, String> fields;

    private final List<String> lines;

    private LineDocument(String magic, int version, SequencedMap<String, String> fields, List<String> lines) {
        this.magic = magic;
        this.version = version;
        this.fields = fields;
        this.lines = lines;
    }

    /** Start a document named {@code magic} at {@code version}. */
    public static Builder of(String magic, int version) {
        return new Builder(requireToken(magic), version);
    }

    /**
     * Read a stored document, or empty when it does not open with {@code magic} - a foreign object, a torn one, or
     * one from before this codec - or when its version line does not parse.
     */
    public static Optional<LineDocument> parse(byte[] bytes, String magic) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        int first = text.indexOf('\n');
        String header = (first < 0 ? text : text.substring(0, first)).trim();
        if (!header.startsWith(magic + " ")) {
            return Optional.empty();
        }
        int version;
        try {
            version = Integer.parseInt(header.substring(magic.length() + 1).trim());
        } catch (NumberFormatException _) {
            return Optional.empty();
        }
        SequencedMap<String, String> fields = new LinkedHashMap<>();
        List<String> lines = new ArrayList<>();
        boolean free = false;
        if (first >= 0) {
            for (String line : text.substring(first + 1).split("\n", -1)) {
                if (free) {
                    lines.add(line);
                } else if (line.isEmpty()) {
                    free = true;
                } else {
                    int space = line.indexOf(' ');
                    fields.put(space < 0 ? line : line.substring(0, space), space < 0 ? "" : line.substring(space + 1));
                }
            }
            if (!lines.isEmpty() && lines.getLast().isEmpty()) {
                lines.removeLast();                          // the document's own terminating newline
            }
        }
        return Optional.of(new LineDocument(magic, version, fields, List.copyOf(lines)));
    }

    public String magic() {
        return magic;
    }

    public int version() {
        return version;
    }

    /** A field's text, or empty when the document does not carry it. */
    public Optional<String> field(String name) {
        return Optional.ofNullable(fields.get(name));
    }

    /** The free lines after the fields, in order; none when the document has no blank separator. */
    public List<String> lines() {
        return lines;
    }

    private static String requireToken(String value) {
        if (value == null || value.isBlank() || value.chars().anyMatch(c -> c == ' ' || c == '\n')) {
            throw new IllegalArgumentException("a line-document name or field carries no space and no newline: " + value);
        }
        return value;
    }

    /** Fields in order, then free lines; a value's newlines become spaces so a line stays a line. */
    public static final class Builder {

        private final StringBuilder text = new StringBuilder();

        private final List<String> lines = new ArrayList<>();

        private Builder(String magic, int version) {
            text.append(magic).append(' ').append(version).append('\n');
        }

        public Builder field(String name, Object value) {
            text.append(requireToken(name)).append(' ')
                    .append(value == null ? "" : value.toString().replace('\n', ' ')).append('\n');
            return this;
        }

        public Builder line(String line) {
            lines.add(line.replace('\n', ' '));
            return this;
        }

        public byte[] bytes() {
            StringBuilder whole = new StringBuilder(text);
            if (!lines.isEmpty()) {
                whole.append('\n');
                for (String line : lines) {
                    whole.append(line).append('\n');
                }
            }
            return whole.toString().getBytes(StandardCharsets.UTF_8);
        }
    }
}
