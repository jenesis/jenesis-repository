package build.jenesis.repository.metadata;

import module java.base;
import module tools.jackson.databind;
import module org.slf4j;

/**
 * The consolidated, versioned, tagged per-coordinate metadata document (§5): a top-level {@code format} version and
 * a {@code sections} object mapping each contributor's tag to its {@link Section} envelope. This value type owns
 * the reader-tolerance and round-trip-fidelity contract - the generalisation of {@code StoreFindings}' carried-rows
 * model to the <em>section</em> level.
 *
 * <p><strong>Total, section-carrying read.</strong> {@link #read} never throws: a torn or foreign object (non-JSON
 * bytes, or something another module placed under {@code meta/}) reads as an empty document with a WARNING, never
 * an exception - one stray object must not blank a coordinate's whole trail. Every section is held as its raw
 * envelope {@link JsonNode}; a reader parses only the sections it asks for ({@link #section}), and a mutator
 * ({@link #mutate}) re-parses only the tags it transforms and re-serialises every other section's raw node
 * verbatim. Unknown and newer-tagged sections therefore survive every writer <em>by construction</em>, so an older
 * node never eats a newer node's section (the row-carry property, now format-wide).
 *
 * <p><strong>Format guard.</strong> A reader whose known {@link #FORMAT} is older than the document's renders what
 * it recognises but {@link #mutate} refuses to write (fails loudly), never downgrade-rewriting the envelope - the
 * lossless-downgrade half of the versioning rules.
 *
 * <p>Instances are immutable; {@link #mutate} returns a new document. Two writers on <em>different</em> sections
 * conflict only on the store's CAS token and converge on retry (each re-reads and re-applies its own section);
 * two writers on the <em>same</em> section keep that section owner's merge semantics.
 */
public final class MetadataDocument {

    /** This reader's known envelope format version. A document whose {@code format} exceeds this is rendered but
     *  not mutated ({@link #mutate} fails loudly). Bumped only on a breaking <em>envelope</em> change. */
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

    // Tag -> raw section envelope node, in document order. Every section (recognised or not) is held raw; the typed
    // view (section) parses on demand and a mutate re-parses only the tags it touches, so an unmutated section
    // round-trips through serialize byte-for-structure verbatim - the section-level carry.
    private final SequencedMap<String, JsonNode> sections;

    private MetadataDocument(int format, SequencedMap<String, JsonNode> sections) {
        this.format = format;
        this.sections = sections;
    }

    /** An empty document at this reader's {@link #FORMAT} - the starting point for a coordinate version never
     *  written before. */
    public static MetadataDocument empty() {
        return new MetadataDocument(FORMAT, new LinkedHashMap<>());
    }

    /**
     * Read a document from its stored bytes, totally: a torn/foreign object reads as {@link #empty} with a WARNING
     * rather than throwing, and every section is held as its raw envelope node (parsed lazily and tolerantly by
     * {@link #section}), so an unreadable individual section is carried, not dropped.
     */
    public static MetadataDocument read(byte[] content) {
        JsonNode root;
        try {
            root = JSON.readTree(content);
        } catch (RuntimeException e) {
            LOGGER.warn("Skipping an unreadable metadata document", e);
            return empty();
        }
        if (root == null || !root.isObject()) {
            // A non-object under meta/ (a foreign file, an empty body) is not this document - read as empty rather
            // than letting one stray object blank the coordinate's trail, exactly as StoreFindings' read is total.
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

    /** This document's envelope format version - the reader's {@link #FORMAT} for one it wrote or read at its own
     *  version, or a higher number for one a newer node wrote. */
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

    /** The raw section envelope node for a tag, or {@code null} when absent - the verbatim node a carry preserves,
     *  for a generic renderer or a diagnostic that inspects an unrecognised section. */
    public JsonNode raw(String tag) {
        return sections.get(tag);
    }

    /**
     * The typed view of one section, parsed tolerantly from its raw envelope; empty when the tag is absent or its
     * envelope cannot be parsed as a {@link Section} (in which case the raw node is still carried by a
     * {@link #mutate} - the typed view simply does not surface it).
     */
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
            // An error envelope round-trips through the Section invariant (error present iff state==error); a raw
            // node that violates it is carried but not surfaced typed.
            return Optional.of(new Section(tag, schema, updated, state,
                    state == State.ERROR ? (error == null ? new SectionError("unknown", "") : error) : null,
                    signal, data.isMissingNode() ? null : data));
        } catch (RuntimeException e) {
            LOGGER.warn("Carrying an unparsable metadata section '" + tag + "' through untouched", e);
            return Optional.empty();
        }
    }

    /**
     * Apply each section transform in one logical step and return the resulting document; sections not named in
     * {@code mutations} are carried verbatim. Refuses (throws {@link IllegalStateException}) when this document's
     * {@code format} is newer than this reader knows - never downgrade-rewrites a newer envelope.
     *
     * @throws IllegalStateException when {@link #newerThanKnown()} - the loud format guard
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

    /** Serialise this document to the bytes stored under its {@link MetadataKey#version} key: {@code format} plus
     *  the {@code sections} object, every section written from its raw node so a carried section is byte-preserved. */
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
