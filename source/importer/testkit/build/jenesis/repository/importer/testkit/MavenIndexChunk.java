package build.jenesis.repository.importer.testkit;

import module java.base;

/**
 * The legacy Nexus repository-index chunk, encoded, so a test can script an incumbent that publishes one.
 *
 * <p>It lives in the kit rather than in a suite because two test modules need the same bytes - the Maven connector's
 * own suite, which drives the index walk record by record, and the shared import contract, whose Maven fixture needs
 * the <em>derived</em> walk (the leg reached only when a repository exposes no directory listing). A second copy of a
 * binary encoder is how two suites end up disagreeing about the format they both claim to test, and the one that
 * drifts is green over the reader it no longer matches.
 *
 * <p>The layout, as {@code RepositoryIndex} reads it: a GZIP stream of a version byte and a timestamp long, then per
 * record an int field count and per field a flag byte, a modified-UTF-8 name, an int length and the value bytes. Each
 * record's {@code i} field is itself GZIP-compressed, which is what exercises the per-field compression flag.
 */
public final class MavenIndexChunk {

    private MavenIndexChunk() {
    }

    /** One index record: {@code u} carries {@code group|artifact|version|classifier}, {@code i} the packaging. */
    public static Map<String, String> record(String group, String artifact, String version, String packaging) {
        return new LinkedHashMap<>(Map.of(
                "u", group + "|" + artifact + "|" + version + "|NA",
                "i", packaging + "|0|0|0|0|0|0"));
    }

    /** The chunk, GZIP-encoded, holding {@code records} in order. */
    public static byte[] of(List<Map<String, String>> records) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
                out.writeByte(1);
                out.writeLong(1234567890L);
                for (Map<String, String> record : records) {
                    out.writeInt(record.size());
                    for (Map.Entry<String, String> field : record.entrySet()) {
                        boolean compressed = field.getKey().equals("i");
                        byte[] value = field.getValue().getBytes(StandardCharsets.UTF_8);
                        if (compressed) {
                            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                            try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
                                gzip.write(value);
                            }
                            value = buffer.toByteArray();
                        }
                        out.writeByte(compressed ? 0x08 : 0);
                        out.writeUTF(field.getKey());
                        out.writeInt(value.length);
                        out.write(value);
                    }
                }
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
