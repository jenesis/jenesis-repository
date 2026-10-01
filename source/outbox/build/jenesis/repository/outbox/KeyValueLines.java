package build.jenesis.repository.outbox;

import module java.base;

/**
 * The small-object encoding outbox users store their entries in: URL-encoded {@code key=value} lines. Encoding each
 * value means a path, an error message or a JSON detail carrying a newline or {@code =} cannot corrupt the object, and
 * a missing or malformed field reads as its zero rather than failing - refusing to read delivery bookkeeping would
 * strand the delivery.
 *
 * <p>Unlike the store SPI's {@code LineDocument}, which frames named fields under a magic and a version and flattens a
 * newline in a value, this codec has no header and keeps every byte of a value: a lossless value and a self-identifying
 * document are different requirements.
 */
public final class KeyValueLines {

    private KeyValueLines() {
    }

    /** Append one {@code key=value} line, the value URL-encoded; a {@code null} value is written as empty. */
    public static void line(StringBuilder builder, String key, String value) {
        builder.append(key).append('=')
                .append(URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)).append('\n');
    }

    /** Every {@code key=value} line of {@code content}, values decoded; a line without {@code =} is ignored. */
    public static Map<String, String> fields(byte[] content) {
        Map<String, String> fields = new HashMap<>();
        for (String raw : new String(content, StandardCharsets.UTF_8).split("\n")) {
            int split = raw.indexOf('=');
            if (split > 0) {
                fields.put(raw.substring(0, split), URLDecoder.decode(raw.substring(split + 1), StandardCharsets.UTF_8));
            }
        }
        return fields;
    }

    /** A comma-joined set field back into a sorted set; empty or absent reads as no members. */
    public static Set<String> members(String joined) {
        Set<String> members = new TreeSet<>();
        if (joined != null && !joined.isBlank()) {
            members.addAll(Arrays.asList(joined.split(",")));
        }
        return members;
    }

    public static int parseInt(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value.trim());
        } catch (NumberFormatException _) {
            return 0;
        }
    }

    public static long parseLong(String value) {
        try {
            return value == null ? 0L : Long.parseLong(value.trim());
        } catch (NumberFormatException _) {
            return 0L;
        }
    }
}
