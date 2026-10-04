package build.jenesis.repository.inventory;

import module java.base;
import module tools.jackson.databind;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.metadata.SectionMutation;
import build.jenesis.repository.metadata.Signal;
import build.jenesis.repository.metadata.State;

/**
 * The {@code cached} section codec of the consolidated metadata document: the version is held here as a copy a
 * pull-through fetched from an upstream, not as a release this repository published. Its presence is membership of
 * the <em>cached</em> set, beside and apart from the {@code published} section's published set, and that separation is
 * the point of it: retention and every other reader of the published set enumerate {@link PublishedSection} and so
 * never see a cached copy - there is no filter any of them has to remember - while the readers that are about
 * everything the repository holds (the scheduled advisory and health scans, the repository's overview and a
 * coordinate's page) ask for both.
 *
 * <p>The {@code data} payload is {@code {"at":<instant>, "upstream":<url>}}: when the copy was first cached, and the
 * upstream it came from as the fill named it. A version that is both - published here and also cached from an
 * upstream - is a release; the recording never writes this section over a published one.
 */
public final class CachedSection {

    /** The section tag - the short built-in name the holdings subsystem owns in the document. */
    public static final String TAG = "cached";

    /** The contributor-owned section schema version. */
    public static final int SCHEMA = 1;

    private static final String AT_FIELD = "at";
    private static final String UPSTREAM_FIELD = "upstream";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private CachedSection() {
    }

    /** When the copy was first cached and where from, or empty when the version is not a cached member. */
    public static Optional<Facts> facts(Optional<Section> section) {
        if (section.isEmpty() || section.get().state() != State.DERIVED) {
            return Optional.empty();
        }
        return section.get().payload()
                .map(data -> new Facts(Section.instant(data.path(AT_FIELD)), Section.text(data.path(UPSTREAM_FIELD))))
                .filter(facts -> facts.at() != null);
    }

    /** Whether a section marks the version as held from an upstream: a derived section with a real instant. */
    public static boolean cached(Optional<Section> section) {
        return facts(section).isPresent();
    }

    /** A cached copy's facts: when it was first cached and the upstream it came from ({@code null} where the fill did
     *  not name one). */
    public record Facts(Instant at, String upstream) {
    }

    /** Record a cached copy, keeping the first instant and upstream of one already recorded - a re-fill of the same
     *  version is the same holding, so the facts never drift with it. Re-derivable each compare-and-set attempt. */
    public static SectionMutation record(Instant at, String upstream) {
        return current -> cached(current) ? current.get() : section(at, upstream);
    }

    /** A cached section for the given facts. */
    public static Section section(Instant at, String upstream) {
        ObjectNode data = JSON.createObjectNode();
        data.put(AT_FIELD, at.toString());
        if (upstream == null) {
            data.putNull(UPSTREAM_FIELD);
        } else {
            data.put(UPSTREAM_FIELD, upstream);
        }
        return Section.derived(TAG, SCHEMA, at, Signal.NEUTRAL, data);
    }
}
