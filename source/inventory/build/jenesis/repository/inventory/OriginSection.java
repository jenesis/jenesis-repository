package build.jenesis.repository.inventory;

import module java.base;
import module tools.jackson.databind;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.metadata.SectionMutation;
import build.jenesis.repository.metadata.Signal;
import build.jenesis.repository.metadata.State;

/**
 * The {@code origin} section codec of the consolidated metadata document (EPIC 25, §6.2): the
 * <em>provenance-of-source</em> trail of a coordinate version - where <em>this</em> deployment's bytes for it came from.
 * Distinct from the {@code provenance} attestation summary ("who built it and can they prove it"): origin answers "which
 * supply channel the operator's own deployment acquired it through - the hardened front door, or a hand upload?".
 *
 * <p>The {@code data} payload is {@code {"acquisitions":[ row, ... ]}}. Two row shapes, one built-in {@code source}
 * discriminator each:
 * <ul>
 *   <li>a <b>{@code local-upload}</b> row {@code {"source":"local-upload","at":<instant>,"sha256":<hex>}} - a hand
 *       upload through the publish path;</li>
 *   <li>a <b>{@code fallback}</b> row {@code {"source":"fallback","repository":<name>,"fallbackIndex":<n>,
 *       "target":<url>,"at":<instant>,"sha256":<hex>,"stored":<bool>,"screening":<default|harden|unscreened>,
 *       "lastServed":<instant>,"serves":<count>}} - bytes fetched from an ordered {@code Upstream} fallback, for both a
 *       store (caching) and a no-store (pass-through) fallback.</li>
 * </ul>
 *
 * <p><b>One row per distinct {@code (source, sha256)}</b> - not per request. A repeated no-copy serve of the
 * <em>same</em> bytes updates the fallback row's {@code lastServed}/{@code serves}; a digest <em>change</em> appends a
 * <em>new</em> row, so the origin trail doubles as the visible drift history beside the /verdict. The row's
 * {@code sha256} reconciles with the sibling {@code verdict} section's digest-pinned record (both name the same bytes),
 * so verdict ("what the screen decided about digest D") and origin ("where D came from") point at each other by digest
 * rather than duplicating source/validators (§6.2).
 *
 * <p><b>Reader-tolerant, row-carrying.</b> A mutation parses only the built-in fields it owns and carries every other
 * row - and every unrecognised field on a row it touches - verbatim (the §5.2 row-level carry, as {@code findings}), so
 * a newer writer's row survives an older node untouched. All methods are pure and return a fresh {@link Section} (§11);
 * the section carries a {@link Signal#NEUTRAL neutral} signal - the gate does not consume origin in v1 (§6.2).
 */
public final class OriginSection {

    /** The section tag - the short built-in name the origin-tracking subsystem owns in the document. */
    public static final String TAG = "origin";

    /** The contributor-owned section schema version. */
    public static final int SCHEMA = 1;

    /** The {@code source} discriminator of a hand-upload acquisition row (the publish path). */
    public static final String LOCAL_UPLOAD = "local-upload";

    /** The {@code source} discriminator of a fallback-fetch acquisition row (the walk's fallback-fetch path). */
    public static final String FALLBACK = "fallback";

    private static final String ACQUISITIONS_FIELD = "acquisitions";
    private static final String SOURCE_FIELD = "source";
    private static final String SHA256_FIELD = "sha256";
    private static final String AT_FIELD = "at";
    private static final String REPOSITORY_FIELD = "repository";
    private static final String FALLBACK_INDEX_FIELD = "fallbackIndex";
    private static final String TARGET_FIELD = "target";
    private static final String STORED_FIELD = "stored";
    private static final String SCREENING_FIELD = "screening";
    private static final String LAST_SERVED_FIELD = "lastServed";
    private static final String SERVES_FIELD = "serves";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private OriginSection() {
    }

    /** One acquisition row parsed from the section - a {@code local-upload} carries only {@code source}/{@code sha256}/
     *  {@code at}; a {@code fallback} additionally carries the fallback identity, {@code stored}/{@code screening}
     *  policy, and the {@code lastServed}/{@code serves} no-copy counters. Absent numeric/boolean fields read as their
     *  zero/false defaults; {@code fallbackIndex} is {@code -1} when absent. */
    public record Acquisition(String source, String sha256, Instant at, String repository, int fallbackIndex,
                              String target, boolean stored, String screening, Instant lastServed, long serves) {

        /** Whether this row is a hand upload (the system-of-record channel that is never cache-evicted). */
        public boolean localUpload() {
            return LOCAL_UPLOAD.equals(source);
        }

        /** Whether this row is a fallback fetch (a re-heatable cache entry when {@code stored}). */
        public boolean fallback() {
            return FALLBACK.equals(source);
        }
    }

    /** The acquisition rows a section carries, in document order, or an empty list when the section is absent or not a
     *  derived origin record. Unrecognised rows are skipped in this typed view (still carried by a {@link SectionMutation}). */
    public static List<Acquisition> acquisitions(Optional<Section> section) {
        if (section.isEmpty() || section.get().state() != State.DERIVED) {
            return List.of();
        }
        Optional<JsonNode> payload = section.get().payload();
        if (payload.isEmpty()) {
            return List.of();
        }
        JsonNode rows = payload.get().path(ACQUISITIONS_FIELD);
        if (!rows.isArray()) {
            return List.of();
        }
        List<Acquisition> parsed = new ArrayList<>();
        for (JsonNode row : rows) {
            String source = text(row.path(SOURCE_FIELD));
            String sha256 = text(row.path(SHA256_FIELD));
            if (source == null) {
                continue;
            }
            parsed.add(new Acquisition(source, sha256, instant(row.path(AT_FIELD)), text(row.path(REPOSITORY_FIELD)),
                    row.path(FALLBACK_INDEX_FIELD).asInt(-1), text(row.path(TARGET_FIELD)),
                    row.path(STORED_FIELD).asBoolean(false), text(row.path(SCREENING_FIELD)),
                    instant(row.path(LAST_SERVED_FIELD)), row.path(SERVES_FIELD).asLong(0)));
        }
        return List.copyOf(parsed);
    }

    /** Whether the coordinate version carries a {@code local-upload} origin row - the system-of-record marker a cache
     *  eviction must respect (a local-upload blob is never cache-evicted, §6.2). */
    public static boolean hasLocalUpload(Optional<Section> section) {
        return acquisitions(section).stream().anyMatch(Acquisition::localUpload);
    }

    /** Whether the coordinate version's <em>only</em> origin is one or more {@code fallback} rows (no {@code local-upload}
     *  row) - a re-heatable cache entry whose blob a quota-pressure eviction may reclaim while retaining these records
     *  (the §6.2 eviction dividend). Empty/absent origin is not re-heatable (nothing recorded to key the decision off). */
    public static boolean reheatableFallbackOnly(Optional<Section> section) {
        List<Acquisition> rows = acquisitions(section);
        return !rows.isEmpty() && rows.stream().noneMatch(Acquisition::localUpload)
                && rows.stream().anyMatch(Acquisition::fallback);
    }

    /**
     * Record a hand upload through the publish path (§6.2): append a {@code local-upload} row for {@code (local-upload,
     * sha256)} when absent, else converge (idempotent - a re-publish of the same bytes refreshes the row's {@code at}
     * without duplicating it). One row per distinct {@code (source, sha256)}: a different-bytes upload appends a new
     * row, so the shadowing of a fallback by a local upload stays visible. Re-derivable each CAS attempt.
     */
    public static SectionMutation recordUpload(String sha256, Instant at) {
        return current -> {
            ArrayNode rows = rows(current);
            ObjectNode existing = find(rows, LOCAL_UPLOAD, sha256);
            if (existing == null) {
                ObjectNode row = rows.addObject();
                row.put(SOURCE_FIELD, LOCAL_UPLOAD);
                putSha(row, sha256);
                row.put(AT_FIELD, at.toString());
            } else {
                existing.put(AT_FIELD, at.toString());
            }
            return section(rows, at);
        };
    }

    /**
     * Record a fallback fetch through the walk's fallback-fetch path (§6.2), for both a store and a no-store fallback:
     * append a {@code fallback} row for {@code (fallback, sha256)} when absent (the first acquisition of these bytes,
     * {@code serves}=1, {@code lastServed}={@code at} - the synchronous first write, §9), else <em>update</em> the
     * existing row's {@code lastServed} and increment {@code serves} (a repeated no-copy serve of unchanged bytes - the
     * best-effort coalesced refresh, §6.2/§4). A digest change is a new {@code sha256} and so appends a new row. The
     * fallback identity/policy fields are set on first acquisition and preserved on refresh. Re-derivable each CAS
     * attempt.
     */
    public static SectionMutation recordFallback(String repository, int fallbackIndex, String target, String sha256,
                                                 boolean stored, String screening, Instant at) {
        return current -> {
            ArrayNode rows = rows(current);
            ObjectNode existing = find(rows, FALLBACK, sha256);
            if (existing == null) {
                ObjectNode row = rows.addObject();
                row.put(SOURCE_FIELD, FALLBACK);
                row.put(REPOSITORY_FIELD, repository);
                row.put(FALLBACK_INDEX_FIELD, fallbackIndex);
                if (target != null) {
                    row.put(TARGET_FIELD, target);
                }
                putSha(row, sha256);
                row.put(AT_FIELD, at.toString());
                row.put(STORED_FIELD, stored);
                if (screening != null) {
                    row.put(SCREENING_FIELD, screening);
                }
                row.put(LAST_SERVED_FIELD, at.toString());
                row.put(SERVES_FIELD, 1L);
            } else {
                existing.put(LAST_SERVED_FIELD, at.toString());
                existing.put(SERVES_FIELD, existing.path(SERVES_FIELD).asLong(0) + 1);
            }
            return section(rows, at);
        };
    }

    /** The current row array as a fresh mutable copy - every existing row (recognised or not) carried verbatim so a
     *  mutation never drops another writer's row or an unknown field on a row (§5.2 row-level carry). */
    private static ArrayNode rows(Optional<Section> current) {
        ArrayNode rows = JSON.createArrayNode();
        current.flatMap(Section::payload).map(data -> data.path(ACQUISITIONS_FIELD)).filter(JsonNode::isArray)
                .ifPresent(existing -> existing.forEach(rows::add));
        return rows;
    }

    /** The first row whose {@code source} and {@code sha256} match, or {@code null} - the {@code (source, sha256)} row
     *  identity. A row with no readable sha never matches a real digest (so a torn row is carried, not overwritten). */
    private static ObjectNode find(ArrayNode rows, String source, String sha256) {
        for (JsonNode row : rows) {
            if (row instanceof ObjectNode object && source.equals(text(object.path(SOURCE_FIELD)))
                    && sha256 != null && sha256.equals(text(object.path(SHA256_FIELD)))) {
                return object;
            }
        }
        return null;
    }

    private static void putSha(ObjectNode row, String sha256) {
        if (sha256 == null || sha256.isBlank()) {
            row.putNull(SHA256_FIELD);
        } else {
            row.put(SHA256_FIELD, sha256);
        }
    }

    private static Section section(ArrayNode rows, Instant updated) {
        ObjectNode data = JSON.createObjectNode();
        data.set(ACQUISITIONS_FIELD, rows);
        return Section.derived(TAG, SCHEMA, updated, Signal.NEUTRAL, data);
    }

    private static String text(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asString();
        return value.isBlank() ? null : value;
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
