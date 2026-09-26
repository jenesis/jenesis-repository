package build.jenesis.repository.metadata;

import module java.base;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Where a pass keeps what it derived about one coordinate, and how it decides whether it may skip deriving it
 * again.
 *
 * <p>Both questions are mechanical and neither belongs to a domain module. <b>Where</b> is the pass's own section of
 * the coordinate's consolidated metadata document; an absent section means this coordinate has not been judged
 * here. <b>Whether</b> is a fingerprint of the inputs:
 * the same inputs mean re-deriving would reach the same answer, so the pass skips and says it did.
 *
 * <p>Five passes had written both out by hand - the four AI passes plus the bytecode reachability sweep - and the
 * {@code readCache} of each was the same code but for the type of its coordinate. Extracting a helper would have
 * given divergence a shared subroutine; moving the <em>decision</em> here is what stops it. Read the format strictly in
 * one pass and leniently in another and the two disagree about whether a cached answer is usable, which shows up as a
 * pass that will not stop re-deriving.
 *
 * <p><b>Why here and not on the maintenance context.</b> {@code RepositoryContext} is the surface that already
 * hands a pass its store, its clock and its meters, and this belongs beside them. But the metadata SPI re-exports
 * the compliance SPI, so putting it there would drag compliance into all thirty modules that implement a
 * maintenance task - a retention sweep does not need the gate's contracts to reap a directory. It lives instead in
 * the module that owns the concept, which every pass that keeps an outcome already requires.
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

    /**
     * The stored document for {@code key} when it parses and carries this pass's format stamp, else a fresh
     * stamped one. A document that is absent, unparseable or written in an older shape is not an error - the pass
     * simply derives it again - so this answers an empty document rather than failing.
     */
    public ObjectNode read(Key key) throws IOException {
        if (meta.section(key.ecosystem(), key.coordinate(), key.version(), tag)
                .flatMap(Section::payload).orElse(null) instanceof ObjectNode node
                && format.equals(node.path(FORMAT).asString(null))) {
            return node;
        }
        return fresh();
    }

    /**
     * Store what the pass derived. A write that cannot land is logged by the caller and not raised: losing a cache
     * entry costs one re-derivation on the next pass, which is strictly better than failing the sweep that
     * produced it.
     */
    public void write(Key key, ObjectNode document, Instant when) throws IOException {
        meta.mutate(key.ecosystem(), key.coordinate(), key.version(), tag,
                _ -> Section.derived(tag, schema, when, Signal.NEUTRAL, document));
    }

    /**
     * Whether the stored outcome was derived from exactly these inputs, so re-deriving would reach the same answer
     * and the pass may skip this coordinate and say it did.
     *
     * <p>A pass that also needs its <em>output</em> to still be present asks that separately: a fingerprint match
     * on its own cannot tell an unchanged input from a verdict somebody lost, and a bare-fingerprint skip would
     * mask that loss forever.
     */
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
