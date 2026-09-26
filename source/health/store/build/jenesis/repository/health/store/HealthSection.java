package build.jenesis.repository.health.store;

import module java.base;
import module tools.jackson.databind;
import build.jenesis.repository.compliance.HealthSource.Health;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.metadata.SectionMutation;
import build.jenesis.repository.metadata.Signal;
import build.jenesis.repository.metadata.State;

/**
 * The {@code health} section codec of the consolidated per-<em>coordinate</em> metadata document: the
 * maintainer-health a source scored for a coordinate's project - version-independent, so it lives in the
 * {@code @coordinate} document rather than a version document - consolidated out of the separate {@code health/}
 * sidecar this section replaces. The payload is exactly the fields the sidecar carried
 * ({@code {"sourceRepository"?:.., "overall":.., "maintenance":.., "review":.., "provenance":..}}); the instant the
 * health was scored rides the envelope's {@code updated} field, the same freshness instant that gates the merge.
 *
 * <p><strong>The monotonic guard is now the section's merge semantics.</strong> A record already carrying a
 * {@code scannedAt} (its {@code updated}) newer than or equal to an incoming write is left standing - a stale refresh
 * (a slow sweep finishing after a fresh rescan) never rolls a coordinate's health backwards - preserving the
 * {@code StoreHealthLedger} guard exactly, now as the section owner's own merge inside the shared section-scoped
 * compare-and-set. Idempotent and re-derivable each CAS attempt, as {@link SectionMutation} requires. All methods are
 * pure and never touch their argument (§11).
 */
public final class HealthSection {

    /** The section tag - the short built-in name the maintainer-health subsystem owns in the coordinate document. */
    public static final String TAG = "health";

    /** The contributor-owned section schema version. */
    public static final int SCHEMA = 1;

    private static final String SOURCE_REPOSITORY_FIELD = "sourceRepository";
    private static final String OVERALL_FIELD = "overall";
    private static final String MAINTENANCE_FIELD = "maintenance";
    private static final String REVIEW_FIELD = "review";
    private static final String PROVENANCE_FIELD = "provenance";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HealthSection() {
    }

    /** A coordinate's scored health located in a health section - the record and the instant it was scored (the
     *  section's {@code updated}) - or empty when the section is absent or does not carry a health payload. */
    public static Optional<Stored> stored(Optional<Section> section) {
        if (section.isEmpty() || section.get().state() != State.DERIVED) {
            return Optional.empty();
        }
        return section.get().payload().flatMap(data -> {
            JsonNode overall = data.path(OVERALL_FIELD);
            if (!overall.isNumber()) {
                return Optional.empty();                        // not a health payload
            }
            Health health = new Health(text(data.path(SOURCE_REPOSITORY_FIELD)), overall.asDouble(),
                    data.path(MAINTENANCE_FIELD).asDouble(Health.NOT_EVALUATED),
                    data.path(REVIEW_FIELD).asDouble(Health.NOT_EVALUATED),
                    data.path(PROVENANCE_FIELD).asDouble(Health.NOT_EVALUATED));
            return Optional.of(new Stored(health, section.get().updated()));
        });
    }

    /**
     * Upsert a coordinate's health under the monotonic-{@code scannedAt} guard: keep the current section when its
     * scored instant is at least as fresh as this write (a stale refresh must not roll the health backwards), else
     * write the new record stamped at {@code scannedAt}. Last-writer-wins by the scored instant, not by who reaches
     * the store last - the guard {@code StoreHealthLedger.record} enforced, now as section merge.
     */
    public static SectionMutation record(Health health, Instant scannedAt) {
        return current -> {
            Optional<Stored> existing = stored(current);
            if (existing.isPresent() && !existing.get().scannedAt().isBefore(scannedAt)) {
                return current.orElseThrow();                   // present-and-fresher: leave it standing
            }
            return section(health, scannedAt);
        };
    }

    /** A health section for a scored record, stamped with the instant it was scored (its {@code updated}). */
    public static Section section(Health health, Instant scannedAt) {
        ObjectNode data = JSON.createObjectNode();
        if (health.sourceRepository() != null) {
            data.put(SOURCE_REPOSITORY_FIELD, health.sourceRepository());
        }
        data.put(OVERALL_FIELD, health.overall());
        data.put(MAINTENANCE_FIELD, health.maintenance());
        data.put(REVIEW_FIELD, health.review());
        data.put(PROVENANCE_FIELD, health.provenance());
        return Section.derived(TAG, SCHEMA, scannedAt, Signal.NEUTRAL, data);
    }

    private static String text(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asString();
        return value.isBlank() ? null : value;
    }

    /** A parsed health section: the scored health and the instant it was scored. */
    public record Stored(Health health, Instant scannedAt) {
    }
}
