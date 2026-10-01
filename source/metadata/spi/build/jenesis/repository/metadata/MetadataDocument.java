package build.jenesis.repository.metadata;

import module java.base;
import module tools.jackson.databind;
import module org.slf4j;

/**
 * The consolidated, versioned, tagged per-coordinate metadata document: a top-level {@code format} version and a
 * {@code sections} object mapping each contributor's tag to its {@link Section} envelope. This type owns reader
 * tolerance and round-trip fidelity.
 *
 * <p><strong>Total, section-carrying read.</strong> {@link #read} never throws: a torn or foreign object reads as an
 * empty document with a warning, so one stray object cannot blank a coordinate's trail. Every section is held as its
 * raw envelope; a reader parses only the sections it asks for ({@link #section}), and {@link #mutate} re-parses only
 * the tags it transforms and re-serialises every other section verbatim - so an older node never drops a newer node's
 * section.
 *
 * <p><strong>Format guard.</strong> A reader whose {@link #FORMAT} is older than the document's renders what it
 * recognises, but {@link #mutate} refuses to write rather than downgrade the envelope.
 *
 * <p>Immutable; {@link #mutate} returns a new document. Writers of different sections conflict only on the store's CAS
 * token and converge on retry; writers of the same section keep that section owner's merge semantics.
 */
public final class MetadataDocument {

    /** This reader's envelope format version. A document with a higher {@code format} is rendered but not mutated.
     *  Bumped only on a breaking envelope change. */
    public static final int FORMAT = 1;

    private static final String FORMAT_FIELD = "format";
    private static final String SECTIONS_FIELD = "sections";
    private static final String SCHEMA_FIELD = "schema";
    private static final String UPDATED_FIELD = "updated";
    private static final String STATE_FIELD = "state";
    private static final String ERROR_FIELD = "error";
    private static final String SIGNAL_FIELD = "signal";
    private static final String DATA_FIELD = "data";
    private static final String SEVERITY_FIELD = "severity";
    private static final String KIND_FIELD = "kind";
    private static final String MESSAGE_FIELD = "message";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Logger LOGGER = LoggerFactory.getLogger(MetadataDocument.class);

    private final int format;

    // Tag -> raw section envelope, in document order. The typed view parses on demand and a mutate re-parses only the
    // tags it touches, so an unmutated section round-trips verbatim.
    private final SequencedMap<String, JsonNode> sections;

    private MetadataDocument(int format, SequencedMap<String, JsonNode> sections) {
        this.format = format;
        this.sections = sections;
    }

    /** An empty document at this reader's {@link #FORMAT}. */
    public static MetadataDocument empty() {
        return new MetadataDocument(FORMAT, new LinkedHashMap<>());
    }

    /** Read a document from its stored bytes, totally: a torn or foreign object reads as {@link #empty} with a warning,
     *  and every section is held raw, so an unreadable section is carried, not dropped. */
    public static MetadataDocument read(byte[] content) {
        JsonNode root;
        try {
            root = JSON.readTree(content);
        } catch (RuntimeException e) {
            LOGGER.warn("Skipping an unreadable metadata document", e);
            return empty();
        }
        if (root == null || !root.isObject()) {
            // A non-object under meta/ (a foreign file, an empty body) is not this document: read as empty.
            return empty();
        }
        int format = root.path(FORMAT_FIELD).asInt(FORMAT);
        SequencedMap<String, JsonNode> sections = new LinkedHashMap<>();
        JsonNode sectionsNode = root.path(SECTIONS_FIELD);
        if (sectionsNode.isObject()) {
            sectionsNode.properties().forEach(entry -> sections.put(entry.getKey(), entry.getValue()));
        }
        return new MetadataDocument(format, sections);
    }

    /** This document's envelope format version - {@link #FORMAT}, or higher for one a newer node wrote. */
    public int format() {
        return format;
    }

    /** Whether this document's format is newer than this reader knows - the case {@link #mutate} refuses to write. */
    public boolean newerThanKnown() {
        return format > FORMAT;
    }

    /** Every section tag present, in document order - including tags this reader does not recognise. */
    public SequencedSet<String> tags() {
        return new LinkedHashSet<>(sections.keySet());
    }

    /** Whether a section with this tag is present (in any state). */
    public boolean has(String tag) {
        return sections.containsKey(tag);
    }

    /** The raw section envelope for a tag, or {@code null} when absent - the node a carry preserves, for a generic
     *  renderer or a diagnostic. */
    public JsonNode raw(String tag) {
        return sections.get(tag);
    }

    /** The typed view of one section, parsed tolerantly; empty when the tag is absent or its envelope does not parse as
     *  a {@link Section}, in which case {@link #mutate} still carries the raw node. */
    public Optional<Section> section(String tag) {
        JsonNode node = sections.get(tag);
        if (node == null || !node.isObject()) {
            return Optional.empty();
        }
        try {
            int schema = node.path(SCHEMA_FIELD).asInt(0);
            Instant updated = Instant.parse(node.path(UPDATED_FIELD).asString());
            State state = State.ofWire(node.path(STATE_FIELD).asString(State.DERIVED.wire()));
            SectionError error = null;
            JsonNode errorNode = node.path(ERROR_FIELD);
            if (errorNode.isObject()) {
                error = new SectionError(errorNode.path(KIND_FIELD).asString(""),
                        errorNode.path(MESSAGE_FIELD).asString(""));
            }
            Signal signal = Signal.NEUTRAL;
            JsonNode signalNode = node.path(SIGNAL_FIELD);
            if (signalNode.isObject()) {
                signal = Signal.of(severity(signalNode.path(SEVERITY_FIELD).asString(null)));
            }
            JsonNode data = node.path(DATA_FIELD);
            // The Section invariant (error present iff state==error) holds for the typed view; a raw node violating it
            // is still carried.
            return Optional.of(new Section(tag, schema, updated, state,
                    state == State.ERROR ? (error == null ? new SectionError("unknown", "") : error) : null,
                    signal, data.isMissingNode() ? null : data));
        } catch (RuntimeException e) {
            LOGGER.warn("Carrying an unparsable metadata section '" + tag + "' through untouched", e);
            return Optional.empty();
        }
    }

    /**
     * Apply each section transform in one logical step; sections not named in {@code mutations} are carried verbatim.
     *
     * @throws IllegalStateException when {@link #newerThanKnown()} - a newer envelope is never downgrade-rewritten
     */
    public MetadataDocument mutate(SequencedMap<String, SectionMutation> mutations) {
        if (newerThanKnown()) {
            throw new IllegalStateException("Refusing to mutate a format " + format
                    + " metadata document with a format " + FORMAT + " reader (an older node must not downgrade a "
                    + "newer writer's envelope); upgrade the node");
        }
        SequencedMap<String, JsonNode> next = new LinkedHashMap<>(sections);
        mutations.forEach((tag, mutation) -> {
            Section updated = mutation.apply(section(tag));
            if (updated == null) {
                next.remove(tag);
                return;
            }
            if (!updated.tag().equals(tag)) {
                throw new IllegalArgumentException("A mutation on section '" + tag + "' returned a section tagged '"
                        + updated.tag() + "'");
            }
            next.put(tag, serialize(updated));
        });
        return new MetadataDocument(FORMAT, next);
    }

    /** Serialise this document for its {@link MetadataKey#version} key: {@code format} plus {@code sections}, every
     *  section written from its raw node. */
    public byte[] serialize() {
        ObjectNode root = JSON.createObjectNode();
        root.put(FORMAT_FIELD, format);
        ObjectNode sectionsNode = root.putObject(SECTIONS_FIELD);
        sections.forEach(sectionsNode::set);
        return JSON.writeValueAsBytes(root);
    }

    private static JsonNode serialize(Section section) {
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put(SCHEMA_FIELD, section.schema());
        envelope.put(UPDATED_FIELD, section.updated().toString());
        envelope.put(STATE_FIELD, section.state().wire());
        if (section.error() != null) {
            ObjectNode errorNode = envelope.putObject(ERROR_FIELD);
            errorNode.put(KIND_FIELD, section.error().kind());
            errorNode.put(MESSAGE_FIELD, section.error().message());
        }
        if (!section.signal().neutral()) {
            envelope.putObject(SIGNAL_FIELD).put(SEVERITY_FIELD, section.signal().severity().name());
        }
        envelope.set(DATA_FIELD, section.data() == null ? JSON.createObjectNode() : section.data());
        return envelope;
    }

    private static build.jenesis.repository.compliance.Severity severity(String name) {
        if (name == null) {
            return null;
        }
        try {
            return build.jenesis.repository.compliance.Severity.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
