package build.jenesis.repository.closure;

import module java.base;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.metadata.SectionMutation;
import build.jenesis.repository.metadata.Signal;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code exposure} section codec of the consolidated metadata document: what a published version's closure
 * reaches that is held for review or carries findings, as the holdings stood when the closure pass last looked - the
 * state the version inherits from the cached copies and releases it relies on, without any lookup of its own. Absent
 * for a version with no closure. The {@code data} payload is
 * {@code {"examined":<components and held cuts looked at>,
 * "reached":[{"coordinate","version","repository","held":<bool>,"findings":<count>,"worst":<severity or "">}]}},
 * a component's {@code repository} present only where a fallback's repository holds it.
 *
 * <p>The signal is neutral: a published version is held for no vulnerability, and what it inherits is shown on its
 * surfaces rather than scored by the gate.
 */
public final class ExposureSection {

    /** The section tag. */
    public static final String TAG = "exposure";

    /** The contributor-owned section schema version. */
    public static final int SCHEMA = 1;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ExposureSection() {
    }

    /** One version the closure reaches that is held for review, carries findings at or above the risk band, or both;
     *  held by the version's own repository where {@code repository} is empty. */
    public record Reached(String coordinate, String version, String repository, boolean held, int findings,
                          String worst) {

        public Reached {
            repository = repository == null ? "" : repository;
            worst = worst == null ? "" : worst;
        }
    }

    /** What a version's closure reaches: every version held or carrying findings, how many it looked at, and when. */
    public record Exposure(List<Reached> reached, int examined, Instant derived) {

        public Exposure {
            reached = List.copyOf(reached);
        }

        /** How many versions it reaches are held for review. */
        public long held() {
            return reached.stream().filter(Reached::held).count();
        }

        /** How many versions it reaches carry findings at or above the risk band. */
        public long vulnerable() {
            return reached.stream().filter(reached -> reached.findings() > 0).count();
        }

        /** Whether the two say the same, whenever each was derived. */
        public boolean sameAs(Exposure other) {
            return other != null && reached.equals(other.reached()) && examined == other.examined();
        }
    }

    /** What a section records, or empty for a version with none. */
    public static Optional<Exposure> exposure(Optional<Section> section) {
        if (section.isEmpty()) {
            return Optional.empty();
        }
        Instant derived = section.get().updated();
        return section.get().payload().map(data -> {
            List<Reached> reached = new ArrayList<>();
            for (JsonNode entry : data.path("reached")) {
                reached.add(new Reached(entry.path("coordinate").asString(""), entry.path("version").asString(""),
                        entry.path("repository").asString(""), entry.path("held").asBoolean(false),
                        entry.path("findings").asInt(0), entry.path("worst").asString("")));
            }
            return new Exposure(reached, data.path("examined").asInt(0), derived);
        });
    }

    /** Record {@code exposure} as the version's, replacing what it had; re-derivable each compare-and-set attempt. */
    public static SectionMutation record(Exposure exposure) {
        return _ -> {
            ObjectNode data = JSON.createObjectNode();
            data.put("examined", exposure.examined());
            ArrayNode reached = data.putArray("reached");
            for (Reached entry : exposure.reached()) {
                ObjectNode row = reached.addObject().put("coordinate", entry.coordinate())
                        .put("version", entry.version()).put("held", entry.held())
                        .put("findings", entry.findings()).put("worst", entry.worst());
                if (!entry.repository().isEmpty()) {
                    row.put("repository", entry.repository());
                }
            }
            return Section.derived(TAG, SCHEMA, exposure.derived(), Signal.NEUTRAL, data);
        };
    }
}
