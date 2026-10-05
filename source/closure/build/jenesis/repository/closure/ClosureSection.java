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
 * The {@code closure} section codec of the consolidated metadata document: a published version's transitive closure as
 * the repository could resolve it from what it holds, when it was resolved, and every subtree that could not be.
 * Absent for a version not yet resolved. The {@code data} payload is {@code {"status":<RESOLVED|PARTIAL|UNDECLARED>,
 * "components":[{"coordinate","version","cached","depth","repository"}], "cuts":[{"coordinate","requirement","reason"}],
 * "truncated":<bool>}}, every component in the version's own ecosystem; a component's {@code repository} is present
 * only where a fallback's repository holds it.
 */
public final class ClosureSection {

    /** The section tag. */
    public static final String TAG = "closure";

    /** The contributor-owned section schema version. */
    public static final int SCHEMA = 1;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ClosureSection() {
    }

    /** Whether every subtree resolved, some were cut, or the version declares nothing this repository can read - a
     *  recipe that is a program, a format whose artifacts declare no dependencies - which is not an empty closure. */
    public enum Status {
        RESOLVED, PARTIAL, UNDECLARED
    }

    /** One held version the closure reaches: a cached copy where {@code cached}, a release otherwise, {@code depth}
     *  edges from the version resolved, held by the repository the version was published to where {@code repository}
     *  is empty and by the fallback's repository it names otherwise. */
    public record Component(String coordinate, String version, boolean cached, int depth, String repository) {

        public Component {
            repository = repository == null ? "" : repository;
        }

        /** Whether a fallback's repository, not the version's own, holds this component. */
        public boolean elsewhere() {
            return !repository.isEmpty();
        }
    }

    /** A dependency whose subtree did not resolve: what was asked for and why it ended there. */
    public record Cut(String coordinate, String requirement, String reason) {
    }

    /** A version's closure: the components it reaches, the cuts, whether a bound stopped it, and when it was read. */
    public record Closure(Status status, List<Component> components, List<Cut> cuts, boolean truncated,
                          Instant resolved) {

        public Closure {
            components = List.copyOf(components);
            cuts = List.copyOf(cuts);
        }
    }

    /** What a section records, or empty for a version not yet resolved. */
    public static Optional<Closure> closure(Optional<Section> section) {
        if (section.isEmpty()) {
            return Optional.empty();
        }
        Instant updated = section.get().updated();
        return section.get().payload().map(data -> closure(data, updated));
    }

    private static Closure closure(JsonNode data, Instant updated) {
        List<Component> components = new ArrayList<>();
        for (JsonNode entry : data.path("components")) {
            components.add(new Component(entry.path("coordinate").asString(""), entry.path("version").asString(""),
                    entry.path("cached").asBoolean(false), entry.path("depth").asInt(0),
                    entry.path("repository").asString("")));
        }
        List<Cut> cuts = new ArrayList<>();
        for (JsonNode entry : data.path("cuts")) {
            cuts.add(new Cut(entry.path("coordinate").asString(""), entry.path("requirement").asString(""),
                    entry.path("reason").asString("")));
        }
        Status status = switch (data.path("status").asString("")) {
            case "PARTIAL" -> Status.PARTIAL;
            case "UNDECLARED" -> Status.UNDECLARED;
            default -> Status.RESOLVED;
        };
        return new Closure(status, components, cuts, data.path("truncated").asBoolean(false), updated);
    }

    /** Record {@code closure} as the version's, replacing what it had; re-derivable each compare-and-set attempt. */
    public static SectionMutation record(Closure closure) {
        return _ -> {
            ObjectNode data = JSON.createObjectNode();
            data.put("status", closure.status().name());
            ArrayNode components = data.putArray("components");
            for (Component component : closure.components()) {
                ObjectNode entry = components.addObject().put("coordinate", component.coordinate())
                        .put("version", component.version()).put("cached", component.cached())
                        .put("depth", component.depth());
                if (component.elsewhere()) {
                    entry.put("repository", component.repository());
                }
            }
            ArrayNode cuts = data.putArray("cuts");
            for (Cut cut : closure.cuts()) {
                cuts.addObject().put("coordinate", cut.coordinate()).put("requirement", cut.requirement())
                        .put("reason", cut.reason());
            }
            data.put("truncated", closure.truncated());
            return Section.derived(TAG, SCHEMA, closure.resolved(), Signal.NEUTRAL, data);
        };
    }
}
