package build.jenesis.repository.inventory;

import module java.base;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.metadata.SectionMutation;
import build.jenesis.repository.metadata.Signal;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code dependencies} section codec of the consolidated metadata document: what a version's manifest declares it
 * depends on, as the format's inspector read it at publish - each a package coordinate in the version's own ecosystem
 * and the requirement the manifest states for it. It is what the SBOM and the dependents index answer from for an
 * artifact that carries no SBOM of its own, which is every artifact but a jar that embeds one.
 *
 * <p>A declaration, not a resolution: the requirement is recorded exactly as written, and what a client would
 * install for it is the client's to decide. Each file an inspector read a manifest from keeps what it declared, and
 * the version's declarations are their union in path order, so a Maven POM's dependencies survive the jar that
 * follows it. The {@code data} payload is {@code {"declared":[{"coordinate":<coordinate>,"requirement":<requirement>},
 * ...], "files":{<path>:{"declared":[...]}}}}, neutral as a signal.
 *
 * <p>Not yet in CycloneDX's vocabulary, which the sections describing a version converge on (see
 * {@link build.jenesis.repository.metadata.MetadataDocument}): a dependency is named by the format's own spelling of
 * its coordinate, where CycloneDX identifies a component by its package URL. Recording the package URL beside the
 * coordinate is what remains; the requirement already is CycloneDX's version or range, as declared.
 */
public final class DependencySection {

    /** The section tag. */
    public static final String TAG = "dependencies";

    /** The contributor-owned section schema version. */
    public static final int SCHEMA = 1;

    private static final String DECLARED_FIELD = "declared";
    private static final String COORDINATE_FIELD = "coordinate";
    private static final String REQUIREMENT_FIELD = "requirement";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private DependencySection() {
    }

    /** One declared dependency: a package coordinate and the requirement the manifest states, empty where none. */
    public record Declared(String coordinate, String requirement) {

        public Declared {
            Objects.requireNonNull(coordinate, "coordinate");
            requirement = requirement == null ? "" : requirement;
        }
    }

    /** What a section records, or empty when the version has no such section - its publish recorded none, or its
     *  format reads none. An empty list is a manifest that declared none. */
    public static Optional<List<Declared>> declared(Optional<Section> section) {
        if (section.isEmpty()) {
            return Optional.empty();
        }
        return section.get().payload().map(DependencySection::declared);
    }

    private static List<Declared> declared(JsonNode data) {
        List<Declared> declared = new ArrayList<>();
        for (JsonNode entry : data.path(DECLARED_FIELD)) {
            String coordinate = entry.path(COORDINATE_FIELD).asString("");
            if (!coordinate.isBlank()) {
                declared.add(new Declared(coordinate, entry.path(REQUIREMENT_FIELD).asString("")));
            }
        }
        return List.copyOf(declared);
    }

    /** Record what {@code file}'s manifest declared, replacing what the file had and keeping every other file's;
     *  re-derivable each CAS attempt. */
    public static SectionMutation record(String file, List<Declared> declared, Instant updated) {
        return current -> {
            SortedMap<String, JsonNode> files = FileFacts.with(current, file, entry(declared));
            Set<Declared> union = new LinkedHashSet<>();
            files.values().forEach(entry -> union.addAll(declared(entry)));
            ObjectNode data = entry(List.copyOf(union));
            FileFacts.write(data, files);
            return Section.derived(TAG, SCHEMA, updated, Signal.NEUTRAL, data);
        };
    }

    private static ObjectNode entry(List<Declared> declared) {
        ObjectNode data = JSON.createObjectNode();
        ArrayNode list = data.putArray(DECLARED_FIELD);
        for (Declared dependency : declared) {
            list.addObject().put(COORDINATE_FIELD, dependency.coordinate())
                    .put(REQUIREMENT_FIELD, dependency.requirement());
        }
        return data;
    }
}
