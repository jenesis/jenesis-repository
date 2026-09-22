package build.jenesis.repository.inventory;

import module java.base;
import module tools.jackson.databind;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.metadata.SectionMutation;
import build.jenesis.repository.metadata.Signal;
import build.jenesis.repository.metadata.State;

/**
 * The {@code downloads} section codec of the consolidated metadata document: how often a coordinate version was
 * downloaded and when it last was - the observation the {@code not-downloaded-for} retention criterion evicts by,
 * folded into the document beside the publish facts rather than kept as its own {@code downloaded/} sidecar, so it
 * evicts with the version's document and survives a copy of the store as the document does.
 *
 * <p>The {@code data} payload is {@code {"count":<long>, "last":<instant>}}. The one mutation, {@link #add}, is a
 * delta: it is re-derived over whatever the document holds on every compare-and-set attempt, so two nodes each
 * adding their own count both land, and the newest instant of the two wins.
 */
public final class DownloadsSection {

    /** The section tag - the short built-in name the download tracker owns in the document. */
    public static final String TAG = "downloads";

    /** The contributor-owned section schema version. */
    public static final int SCHEMA = 1;

    private static final String COUNT_FIELD = "count";

    private static final String LAST_FIELD = "last";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private DownloadsSection() {
    }

    /** What a coordinate version's downloads section says: how many downloads it counted and the newest one. */
    public record Facts(long count, Instant last) {
    }

    /** The download facts a section carries, or empty when the version has never been recorded as downloaded. */
    public static Optional<Facts> facts(Optional<Section> section) {
        if (section.isEmpty() || section.get().state() != State.DERIVED) {
            return Optional.empty();
        }
        return section.get().payload().map(data -> new Facts(
                data.path(COUNT_FIELD).asLong(0L),
                instant(data.path(LAST_FIELD))));
    }

    /**
     * Add {@code delta} downloads, the newest of them at {@code last}. A delta rather than a total, so the mutation
     * is correct on every retry of the compare-and-set and under a peer node adding its own.
     */
    public static SectionMutation add(long delta, Instant last, Instant updated) {
        return current -> {
            Optional<Facts> facts = facts(current);
            long count = facts.map(Facts::count).orElse(0L) + delta;
            Instant known = facts.map(Facts::last).orElse(null);
            Instant newest = known != null && (last == null || known.isAfter(last)) ? known : last;
            return section(count, newest, updated);
        };
    }

    /** A downloads section for the given facts. */
    public static Section section(long count, Instant last, Instant updated) {
        ObjectNode data = JSON.createObjectNode();
        data.put(COUNT_FIELD, count);
        if (last == null) {
            data.putNull(LAST_FIELD);
        } else {
            data.put(LAST_FIELD, last.toString());
        }
        return Section.derived(TAG, SCHEMA, updated, Signal.NEUTRAL, data);
    }

    private static Instant instant(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        try {
            return Instant.parse(node.asString());
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }
}
