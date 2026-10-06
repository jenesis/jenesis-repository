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
 * "reached":[{"coordinate","version","repository","held":<bool>,"findings":<count>,"worst":<severity or "">,
 * "path":[{"coordinate","version"}]}]}}, a component's {@code repository} present only where a fallback's repository
 * holds it and its {@code path} only where the closure reaches it through another dependency.
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
     *  held by the version's own repository where {@code repository} is empty, and reached along {@code path} - from
     *  the dependency the version names itself down to this one, both included, which is this one alone where the
     *  version names it or where the closure stopped at it. */
    public record Reached(String coordinate, String version, String repository, boolean held, int findings,
                          String worst, List<ClosureSection.Hop> path) {

        public Reached {
            repository = repository == null ? "" : repository;
            worst = worst == null ? "" : worst;
            path = path == null || path.isEmpty() ? List.of(new ClosureSection.Hop(coordinate, version))
                    : List.copyOf(path);
        }

        /** A version the closure reaches directly, or stopped at. */
        public Reached(String coordinate, String version, String repository, boolean held, int findings,
                       String worst) {
            this(coordinate, version, repository, held, findings, worst, List.of());
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
                List<ClosureSection.Hop> path = new ArrayList<>();
                for (JsonNode hop : entry.path("path")) {
                    path.add(new ClosureSection.Hop(hop.path("coordinate").asString(""),
                            hop.path("version").asString("")));
                }
                reached.add(new Reached(entry.path("coordinate").asString(""), entry.path("version").asString(""),
                        entry.path("repository").asString(""), entry.path("held").asBoolean(false),
                        entry.path("findings").asInt(0), entry.path("worst").asString(""), path));
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
                if (entry.path().size() > 1) {
                    ArrayNode path = row.putArray("path");
                    for (ClosureSection.Hop hop : entry.path()) {
                        path.addObject().put("coordinate", hop.coordinate()).put("version", hop.version());
                    }
                }
            }
            return Section.derived(TAG, SCHEMA, exposure.derived(), Signal.NEUTRAL, data);
        };
    }
}
