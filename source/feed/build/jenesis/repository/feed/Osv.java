package build.jenesis.repository.feed;

import module java.base;

import tools.jackson.databind.JsonNode;

/**
 * The OSV schema, read once for every source that publishes OSV documents, so two sources cannot disagree over the same
 * advisory through a difference in how they walk {@code affected[].ranges[].events[]}.
 */
public final class Osv {

    private Osv() {
    }

    /** The fixed versions this document records for {@code name}, comma-joined, or {@code null} when none. */
    public static String fixedVersions(JsonNode vuln, String name) {
        List<String> fixed = new ArrayList<>();
        for (JsonNode entry : vuln.path("affected")) {
            if (name.equals(entry.path("package").path("name").asString(null))) {
                for (JsonNode range : entry.path("ranges")) {
                    for (JsonNode event : range.path("events")) {
                        String version = event.path("fixed").asString(null);
                        if (version != null && !fixed.contains(version)) {
                            fixed.add(version);
                        }
                    }
                }
            }
        }
        return fixed.isEmpty() ? null : String.join(", ", fixed);
    }
}
