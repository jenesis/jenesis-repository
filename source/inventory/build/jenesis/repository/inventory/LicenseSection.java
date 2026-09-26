package build.jenesis.repository.inventory;

import module java.base;
import module tools.jackson.databind;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.metadata.SectionMutation;
import build.jenesis.repository.metadata.Signal;

/**
 * The {@code licenses} section codec of the consolidated metadata document: the licenses a coordinate
 * version <em>declares</em>, unioned across the version's artifacts exactly as the {@code licenses/} sidecar this
 * section replaces did. This is the section-scoped form of {@link LicenseInventory}'s union-only merge - a sibling
 * publish only ever ADDS to the declared set, never replaces it, so a racing or withheld sibling whose metadata read
 * came back empty can never launder a license out of the record.
 *
 * <p>The load-bearing tri-state carries through the envelope: an <em>absent</em> section (the coordinate was never
 * inspected) versus a <em>present-but-empty</em> one ({@link build.jenesis.repository.metadata.State#EMPTY} -
 * inspected, none declared) versus a derived one carrying declarations. The {@code data} payload is
 * {@code {"declared":[{"name":..,"url":..}]}} - a stable, order-independent shape - so the search-index sweep and the
 * rollup identity join a release to its declared licenses without re-parsing an artifact. All methods are pure; a
 * mutation returns a fresh {@link Section} and never touches its argument (§11).
 */
public final class LicenseSection {

    /** The section tag - the short built-in name the licenses subsystem owns in the document. */
    public static final String TAG = "licenses";

    /** The contributor-owned section schema version. */
    public static final int SCHEMA = 1;

    private static final String DECLARED_FIELD = "declared";
    private static final String NAME_FIELD = "name";
    private static final String URL_FIELD = "url";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private LicenseSection() {
    }

    /** The declared licenses carried by a licenses section, in stored order; empty for an absent or empty section, so
     *  a reader distinguishes "none declared" through the section's {@link Section#state()} rather than the list size. */
    public static List<LicenseInventory.Declared> declared(Optional<Section> section) {
        if (section.isEmpty()) {
            return List.of();
        }
        return section.get().payload().map(LicenseSection::parse).orElse(List.of());
    }

    private static List<LicenseInventory.Declared> parse(JsonNode data) {
        List<LicenseInventory.Declared> declared = new ArrayList<>();
        JsonNode array = data.path(DECLARED_FIELD);
        if (array.isArray()) {
            for (JsonNode entry : array) {
                String name = text(entry.path(NAME_FIELD));
                String url = text(entry.path(URL_FIELD));
                if (name != null || url != null) {
                    declared.add(new LicenseInventory.Declared(name, url));
                }
            }
        }
        return declared;
    }

    /**
     * A section-scoped union mutation: fold {@code licenses} into the current section's declared set, dropping wholly
     * blank declarations and normalising a blank name or URL to {@code null} so a re-declared license dedupes against
     * one already stored. A first inspection with no declaration still writes a present-but-empty section (so
     * "inspected, none declared" stays distinct from absent); an empty (or wholly-redundant) declaration over an
     * existing section is a no-op, never a clobber. Pure and idempotent - re-running converges to the same set - and
     * re-derivable each CAS attempt, as {@link SectionMutation} requires.
     */
    public static SectionMutation union(List<LicenseInventory.Declared> licenses, Instant updated) {
        return current -> {
            LinkedHashSet<LicenseInventory.Declared> union = new LinkedHashSet<>(declared(current));
            for (LicenseInventory.Declared license : licenses) {
                if (!blank(license)) {
                    union.add(normalize(license));
                }
            }
            return section(new ArrayList<>(union), updated);
        };
    }

    /** A licenses section for a declared set: {@link build.jenesis.repository.metadata.State#EMPTY} when the set is
     *  empty (inspected, none declared), derived otherwise; licenses never carry a gate {@link Signal}. */
    public static Section section(List<LicenseInventory.Declared> declared, Instant updated) {
        List<LicenseInventory.Declared> normalized = new ArrayList<>();
        for (LicenseInventory.Declared license : declared) {
            if (!blank(license)) {
                normalized.add(normalize(license));
            }
        }
        if (normalized.isEmpty()) {
            return Section.empty(TAG, SCHEMA, updated);
        }
        return Section.derived(TAG, SCHEMA, updated, Signal.NEUTRAL, data(normalized));
    }

    private static JsonNode data(List<LicenseInventory.Declared> declared) {
        ObjectNode node = JSON.createObjectNode();
        ArrayNode array = node.putArray(DECLARED_FIELD);
        for (LicenseInventory.Declared license : declared) {
            ObjectNode entry = array.addObject();
            if (license.name() == null) {
                entry.putNull(NAME_FIELD);
            } else {
                entry.put(NAME_FIELD, license.name());
            }
            if (license.url() == null) {
                entry.putNull(URL_FIELD);
            } else {
                entry.put(URL_FIELD, license.url());
            }
        }
        return node;
    }

    /**
     * The rollup-identity fingerprint of a licenses section - the bytes {@link InventoryIdentity#member} folds into the
     * per-version member digest, re-pointed from the old {@code licenses/} sidecar bytes onto this section. It is
     * a <em>canonical</em>, order-independent encoding of the declared set (sorted, length-delimited), so an incremental
     * re-fold and a full {@link StoreRepositoryInventory#rebuildIdentity} agree on the member regardless of the order
     * two sibling publishes happened to union in. Returns empty for an absent section (never inspected), and a present
     * (possibly empty) byte array for a present section - so the member digest keeps distinguishing absent from
     * present-but-empty exactly as before.
     */
    public static Optional<byte[]> fingerprint(Optional<Section> section) {
        return fingerprintOf(section.map(present -> declared(Optional.of(present))));
    }

    /**
     * The rollup-identity fingerprint of a <em>declared set</em> - the single definition every member computation
     * routes through. Because it is a canonical encoding of the <em>set</em> and not of the stored bytes, a
     * coordinate's member digest does not depend on how the section happens to be serialised.
     * Empty for an absent set (never inspected); a present (possibly empty) byte array for a present one, preserving
     * the absent-versus-present-but-empty distinction the member digest depends on.
     */
    public static Optional<byte[]> fingerprintOf(Optional<List<LicenseInventory.Declared>> declared) {
        if (declared.isEmpty()) {
            return Optional.empty();
        }
        List<LicenseInventory.Declared> sorted = new ArrayList<>();
        for (LicenseInventory.Declared license : declared.get()) {
            if (!blank(license)) {
                sorted.add(normalize(license));
            }
        }
        sorted.sort(Comparator.comparing((LicenseInventory.Declared license) -> license.name() == null ? "" : license.name())
                .thenComparing(license -> license.url() == null ? "" : license.url()));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (LicenseInventory.Declared license : sorted) {
            append(bytes, license.name());
            append(bytes, license.url());
        }
        return Optional.of(bytes.toByteArray());
    }

    private static void append(ByteArrayOutputStream bytes, String value) {
        byte[] encoded = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        // A length prefix delimits the field so two declarations never smear into one fingerprint.
        bytes.write(encoded.length >>> 8);
        bytes.write(encoded.length & 0xFF);
        bytes.writeBytes(encoded);
    }

    private static boolean blank(LicenseInventory.Declared license) {
        return (license.name() == null || license.name().isBlank())
                && (license.url() == null || license.url().isBlank());
    }

    private static LicenseInventory.Declared normalize(LicenseInventory.Declared license) {
        String name = license.name() == null || license.name().isBlank() ? null : license.name();
        String url = license.url() == null || license.url().isBlank() ? null : license.url();
        return new LicenseInventory.Declared(name, url);
    }

    private static String text(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asString();
        return value.isBlank() ? null : value;
    }
}
