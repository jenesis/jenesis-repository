package build.jenesis.repository.index;

import module java.base;

import tools.jackson.databind.json.JsonMapper;

/**
 * One line of the published index: a served artifact's request path, stored size and SHA-256 from its pointer, the
 * ecosystem, coordinate and version its format describes from the path, whether it is a prerelease, and when its
 * version was published. Serialised as one NDJSON object per line; no artifact blob is opened to build it.
 */
public record IndexRecord(String path, long size, String sha256, String ecosystem, String coordinate,
                          String version, boolean prerelease, Instant published) {

    /** One mapper, built once; stateless and thread-safe. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** This record as one UTF-8 NDJSON line. Written by the mapper because a coordinate, version or path is
     *  publisher-chosen and may carry any character; the newline is explicit because NDJSON framing is this method's
     *  business. */
    public byte[] line() {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("path", path);
        line.put("size", size);
        line.put("sha256", sha256);
        line.put("ecosystem", ecosystem);
        line.put("coordinate", coordinate);
        line.put("version", version);
        line.put("prerelease", prerelease);
        // The instant's rendering is decided here, not by a mapper setting: consumers read ISO-8601.
        line.put("published", published == null ? null : published.toString());
        return (JSON.writeValueAsString(line) + "\n").getBytes(StandardCharsets.UTF_8);
    }
}
