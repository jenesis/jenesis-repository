package build.jenesis.repository.index;

import module java.base;

import tools.jackson.databind.json.JsonMapper;

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

    /** One mapper for the index, built once: stateless and thread-safe by contract. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * This record as one NDJSON line - a JSON object terminated by {@code '\n'} - UTF-8 encoded.
     *
     * <p>Written by the mapper. A coordinate, a version and a request path are all values a publisher chooses,
     * so any of them can carry a quote, a backslash or a control character; an escaper written here is a
     * parser's worth of correctness kept by hand, beside a dependency this module already has every reason to
     * take. The newline stays explicit because NDJSON's framing is one object per line, which is this method's
     * business rather than the mapper's.
     */
    public byte[] line() {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("path", path);
        line.put("size", size);
        line.put("sha256", sha256);
        line.put("ecosystem", ecosystem);
        line.put("coordinate", coordinate);
        line.put("version", version);
        line.put("prerelease", prerelease);
        // The instant's rendering is this class's decision rather than a mapper setting another module could
        // change: a consumer reads ISO-8601, and an epoch number would be a silent wire change.
        line.put("published", published == null ? null : published.toString());
        return (JSON.writeValueAsString(line) + "\n").getBytes(StandardCharsets.UTF_8);
    }
}
