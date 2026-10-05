package build.jenesis.repository.inventory;

import module java.base;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.metadata.SectionMutation;
import build.jenesis.repository.metadata.Signal;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code advised} section codec of the consolidated metadata document: the name an advisory database knows a
 * cached copy's version by, where it is not the copy's own coordinate - a Debian binary package under its source
 * package, at the source's version. Recorded when the copy is screened at its fill, from what its inspector read, so
 * every later screen of the copy - the scan pass, a rescan - asks the feeds the same question the fill did. Absent for
 * a version the databases know by its own coordinate. The {@code data} payload is
 * {@code {"ecosystem","coordinate","version"}}.
 */
public final class AdvisedSection {

    /** The section tag. */
    public static final String TAG = "advised";

    /** The contributor-owned section schema version. */
    public static final int SCHEMA = 1;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private AdvisedSection() {
    }

    /** What a section records, or empty for a version with none. */
    public static Optional<AdvisorySource.Query> advised(Optional<Section> section) {
        return section.flatMap(Section::payload).flatMap(data -> {
            String ecosystem = data.path("ecosystem").asString("");
            String coordinate = data.path("coordinate").asString("");
            String version = data.path("version").asString("");
            return ecosystem.isEmpty() || coordinate.isEmpty() || version.isEmpty() ? Optional.empty()
                    : Optional.of(new AdvisorySource.Query(ecosystem, coordinate, version));
        });
    }

    /** Record {@code advised} as the version's, as of {@code at}. */
    public static SectionMutation record(AdvisorySource.Query advised, Instant at) {
        return _ -> {
            ObjectNode data = JSON.createObjectNode().put("ecosystem", advised.ecosystem())
                    .put("coordinate", advised.coordinate()).put("version", advised.version());
            return Section.derived(TAG, SCHEMA, at, Signal.NEUTRAL, data);
        };
    }
}
