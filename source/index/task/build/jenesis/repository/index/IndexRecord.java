package build.jenesis.repository.index;

import module java.base;

/**
 * One line of the published index: the pointer metadata of a single served artifact - its serving request path,
 * stored size and SHA-256 straight from the publication pointer, the neutral ecosystem/coordinate/version the owning
 * format describes from the path, whether it is a prerelease, and the instant its coordinate version was published.
 * Serialised as one NDJSON object per artifact terminated by a newline, so a consumer streams the index line by line;
 * no artifact blob is ever opened to build it (path/size/hash come from the pointer, the coordinate from the path
 * alone, the publish instant from the {@code published/} sidecar).
 */
public record IndexRecord(String path, long size, String sha256, String ecosystem, String coordinate,
                          String version, boolean prerelease, Instant published) {

    /** This record as one NDJSON line - a JSON object terminated by {@code '\n'} - UTF-8 encoded. */
    public byte[] line() {
        StringBuilder json = new StringBuilder(160);
        json.append('{');
        string(json, "path", path).append(',');
        json.append("\"size\":").append(size).append(',');
        string(json, "sha256", sha256).append(',');
        string(json, "ecosystem", ecosystem).append(',');
        string(json, "coordinate", coordinate).append(',');
        string(json, "version", version).append(',');
        json.append("\"prerelease\":").append(prerelease).append(',');
        string(json, "published", published == null ? null : published.toString());
        json.append("}\n");
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static StringBuilder string(StringBuilder json, String name, String value) {
        json.append('"').append(name).append("\":");
        if (value == null) {
            return json.append("null");
        }
        json.append('"');
        escape(json, value);
        return json.append('"');
    }

    private static void escape(StringBuilder json, String value) {
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20) {
                        json.append("\\u").append(String.format(Locale.ROOT, "%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
    }
}
