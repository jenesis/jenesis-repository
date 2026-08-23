package build.jenesis.repository.store;

import module java.base;

/**
 * The members of a JSON object as raw text, for the listings whose document is a JSON object of entries - a conda
 * {@code repodata.json}'s {@code packages}, an npm packument's {@code versions}, an OCI tag list. A
 * {@link StoredListing.Codec} over such a document splits it here into its members, each member's value kept verbatim
 * as its fragment, and joins them back in the same shape, so an entry is replaced without the rest of the document
 * being parsed into a tree and re-serialised. Only the object's own members are scanned; the value text is passed
 * through byte for byte.
 */
public final class JsonMembers {

    private JsonMembers() {
    }

    /** The members of the object {@code json} is, in document order, each value as its raw text. */
    public static LinkedHashMap<String, String> split(String json) {
        LinkedHashMap<String, String> members = new LinkedHashMap<>();
        int at = skipWhitespace(json, 0);
        if (at >= json.length() || json.charAt(at) != '{') {
            throw new IllegalArgumentException("not a JSON object");
        }
        at = skipWhitespace(json, at + 1);
        if (at < json.length() && json.charAt(at) == '}') {
            return members;
        }
        while (true) {
            if (at >= json.length() || json.charAt(at) != '"') {
                throw new IllegalArgumentException("malformed JSON object at offset " + at);
            }
            int nameEnd = stringEnd(json, at);
            String name = unescape(json.substring(at + 1, nameEnd));
            at = skipWhitespace(json, nameEnd + 1);
            if (at >= json.length() || json.charAt(at) != ':') {
                throw new IllegalArgumentException("malformed JSON object at offset " + at);
            }
            at = skipWhitespace(json, at + 1);
            int valueEnd = valueEnd(json, at);
            members.put(name, json.substring(at, valueEnd));
            at = skipWhitespace(json, valueEnd);
            if (at >= json.length()) {
                throw new IllegalArgumentException("unterminated JSON object");
            }
            if (json.charAt(at) == '}') {
                return members;
            }
            if (json.charAt(at) != ',') {
                throw new IllegalArgumentException("malformed JSON object at offset " + at);
            }
            at = skipWhitespace(json, at + 1);
        }
    }

    /** The object of these members, each value taken as raw JSON text. */
    public static String join(Map<String, String> members) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> member : members.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append(quote(member.getKey())).append(':').append(member.getValue());
        }
        return json.append('}').toString();
    }

    /** {@code text} as a JSON string literal. */
    public static String quote(String text) {
        StringBuilder json = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                default -> {
                    if (c < 0x20) {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        return json.append('"').toString();
    }

    /** The text of a JSON string literal, unescaped. */
    public static String unquote(String literal) {
        if (literal.length() < 2 || literal.charAt(0) != '"' || literal.charAt(literal.length() - 1) != '"') {
            throw new IllegalArgumentException("not a JSON string: " + literal);
        }
        return unescape(literal.substring(1, literal.length() - 1));
    }

    /** The end offset (exclusive) of the JSON value starting at {@code from}. */
    public static int valueEnd(String json, int from) {
        if (from >= json.length()) {
            throw new IllegalArgumentException("missing JSON value");
        }
        char c = json.charAt(from);
        if (c == '"') {
            return stringEnd(json, from) + 1;
        }
        if (c == '{' || c == '[') {
            int depth = 0;
            int at = from;
            while (at < json.length()) {
                char current = json.charAt(at);
                if (current == '"') {
                    at = stringEnd(json, at) + 1;
                    continue;
                }
                if (current == '{' || current == '[') {
                    depth++;
                } else if (current == '}' || current == ']') {
                    depth--;
                    if (depth == 0) {
                        return at + 1;
                    }
                }
                at++;
            }
            throw new IllegalArgumentException("unterminated JSON value");
        }
        int at = from;
        while (at < json.length() && ",}] \t\r\n".indexOf(json.charAt(at)) < 0) {
            at++;
        }
        return at;
    }

    private static int stringEnd(String json, int open) {
        int at = open + 1;
        while (at < json.length()) {
            char c = json.charAt(at);
            if (c == '\\') {
                at += 2;
                continue;
            }
            if (c == '"') {
                return at;
            }
            at++;
        }
        throw new IllegalArgumentException("unterminated JSON string");
    }

    private static int skipWhitespace(String json, int at) {
        while (at < json.length() && " \t\r\n".indexOf(json.charAt(at)) >= 0) {
            at++;
        }
        return at;
    }

    private static String unescape(String escaped) {
        if (escaped.indexOf('\\') < 0) {
            return escaped;
        }
        StringBuilder text = new StringBuilder(escaped.length());
        for (int i = 0; i < escaped.length(); i++) {
            char c = escaped.charAt(i);
            if (c != '\\') {
                text.append(c);
                continue;
            }
            char next = escaped.charAt(++i);
            switch (next) {
                case 'n' -> text.append('\n');
                case 'r' -> text.append('\r');
                case 't' -> text.append('\t');
                case 'b' -> text.append('\b');
                case 'f' -> text.append('\f');
                case 'u' -> {
                    text.append((char) Integer.parseInt(escaped.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> text.append(next);
            }
        }
        return text.toString();
    }
}
