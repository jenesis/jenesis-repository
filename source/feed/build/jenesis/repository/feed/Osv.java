package build.jenesis.repository.feed;

import module java.base;

import tools.jackson.databind.JsonNode;

/**
 * The OSV schema, read once.
 *
 * <p>Several advisory sources publish OSV documents - OSV itself, the OpenSSF advisories - and each was walking
 * {@code affected[].ranges[].events[]} with its own transcription of the traversal. A schema detail handled in one
 * and missed in the other means one source reports a fixed version and the other reports none for the same
 * advisory, which reads as a feed disagreement rather than as a parser difference.
 */
public final class Osv {

    private Osv() {
    }

    /**
     * The fixed versions this document records for {@code name}, comma-joined, or {@code null} when it records
     * none - the shape the advisory row wants.
     */
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
