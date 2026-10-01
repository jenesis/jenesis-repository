package build.jenesis.repository.metadata;

import module java.base;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Where a pass keeps what it derived about one coordinate, and whether it may skip deriving it again. <b>Where</b> is
 * the pass's own section of the coordinate's metadata document; an absent section means not yet judged. <b>Whether</b>
 * is a fingerprint of the inputs: the same inputs reach the same answer, so the pass skips and says so. The decision
 * lives here, once, because two passes reading the format differently would disagree on whether a cached answer is
 * usable, which shows up as a pass that never stops re-deriving.
 *
 * <p>It lives in the module that owns the concept rather than on {@code RepositoryContext}, because the metadata SPI
 * re-exports the compliance SPI and every maintenance task would then depend on the gate's contracts.
 */
public final class OutcomeCache {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The field every cache document is stamped with, so a pass can tell its own shape from an older one. */
    private static final String FORMAT = "format";

    /** The field {@link #matches} and {@link #stamp} keep the input fingerprint under. */
    private static final String FINGERPRINT = "fingerprint";

    private final MetadataStore meta;
    private final String tag;
    private final int schema;
    private final String format;

    private OutcomeCache(MetadataStore meta, String tag, int schema, String format) {
        this.meta = meta;
        this.tag = tag;
        this.schema = schema;
        this.format = format;
    }

    /**
     * The cache one pass keeps.
     *
     * @param meta   the consolidated metadata store the coordinate documents live in
     * @param tag    the section tag this pass owns
     * @param schema the section schema version this pass writes
     * @param format the stamp that says a stored document is this pass's own shape
     */
    public static OutcomeCache of(MetadataStore meta, String tag, int schema, String format) {
        return new OutcomeCache(Objects.requireNonNull(meta, "meta"), Objects.requireNonNull(tag, "tag"), schema,
                Objects.requireNonNull(format, "format"));
    }

    /** One coordinate version. */
    public record Key(String ecosystem, String coordinate, String version) {

        public Key {
            Objects.requireNonNull(ecosystem, "ecosystem");
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(version, "version");
        }
    }

    /** The stored document for {@code key} when it parses and carries this pass's format stamp, else a fresh stamped
     *  one - an absent, unparseable or older document is simply derived again. */
    public ObjectNode read(Key key) throws IOException {
        if (meta.section(key.ecosystem(), key.coordinate(), key.version(), tag)
                .flatMap(Section::payload).orElse(null) instanceof ObjectNode node
                && format.equals(node.path(FORMAT).asString(null))) {
            return node;
        }
        return fresh();
    }

    /** Store what the pass derived. A write that cannot land is logged by the caller, not raised: a lost entry costs
     *  one re-derivation, which is better than failing the sweep. */
    public void write(Key key, ObjectNode document, Instant when) throws IOException {
        meta.mutate(key.ecosystem(), key.coordinate(), key.version(), tag,
                _ -> Section.derived(tag, schema, when, Signal.NEUTRAL, document));
    }

    /** Whether the stored outcome was derived from exactly these inputs, so the pass may skip this coordinate. A pass
     *  that also needs its output to still be present asks that separately: a fingerprint alone cannot tell an
     *  unchanged input from a lost verdict. */
    public boolean matches(Key key, String fingerprint) throws IOException {
        return fingerprint.equals(read(key).path(FINGERPRINT).asString(null));
    }

    /** Record that this coordinate was derived from {@code fingerprint}, keeping whatever else the document holds. */
    public void stamp(Key key, String fingerprint, Instant when) throws IOException {
        ObjectNode document = read(key);
        document.put(FINGERPRINT, fingerprint);
        write(key, document, when);
    }

    private ObjectNode fresh() {
        ObjectNode document = JSON.createObjectNode();
        document.put(FORMAT, format);
        return document;
    }
}
