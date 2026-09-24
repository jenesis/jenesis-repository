package build.jenesis.repository.findings.store;

import module java.base;
import module tools.jackson.databind;
import module org.slf4j;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.findings.Finding;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.metadata.SectionMutation;
import build.jenesis.repository.metadata.Signal;

/**
 * The {@code findings} section codec of the consolidated metadata document (§5.1): a coordinate version's
 * findings rows, keyed {@code (source, id)}, unioned across every writer exactly as the {@code findings/} sidecar this
 * section replaces did. This is the section-scoped form of {@link StoreFindings}' row model - the same
 * categorize-never-discard merge (a re-record refreshes a row's facts and {@code lastSeen} while keeping its
 * {@code firstSeen}, labels and any supersession mark; a sibling writer only ever ADDS or refreshes a row, never
 * drops one) and the same row-carry: a row this node cannot parse (a {@link Finding.Kind} or {@link Severity}
 * a newer node wrote, and its labels) is held as its raw {@link JsonNode} and re-serialised verbatim on the next CAS,
 * so an older node never eats a newer node's rows.
 *
 * <p>The {@code data} payload is exactly what the ledger serialised as a standalone sidecar - {@code {"findings":[...]}}
 * - so the sidecar bytes and this section's {@code data} node are the same shape, and the migration folds one into the
 * other by re-union rather than a re-encode. The envelope's {@code signal} summarises the section for the gate and the
 * generic renderer (§6): the highest active vulnerability/malware severity, or neutral. All methods are pure; a
 * mutation returns a fresh {@link Section} and never touches its argument (§11).
 */
public final class FindingsSection {

    /** The section tag - the short built-in name the findings ledger owns in the document. */
    public static final String TAG = "findings";

    /** The contributor-owned section schema version. */
    public static final int SCHEMA = 1;

    private static final String FINDINGS_FIELD = "findings";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Logger LOGGER = LoggerFactory.getLogger(FindingsSection.class);

    private FindingsSection() {
    }

    /** The findings rows carried by a section, in stored order; empty for an absent section. Only rows this node
     *  recognises are surfaced - a carried (forward-incompatible) row rides a mutate untouched but is never returned
     *  to a reader, exactly as the sidecar ledger's read was total. */
    public static List<Finding> rows(Optional<Section> section) {
        return document(section).recognised();
    }

    /** The section split into the rows this node understands and the raw rows it does not (the carried set), parsed
     *  tolerantly from the section's {@code data}; both empty for an absent or payload-less section. */
    static Document document(Optional<Section> section) {
        return section.flatMap(Section::payload).map(FindingsSection::parse).orElseGet(Document::empty);
    }

    /**
     * A section-scoped row mutation: apply {@code rowTransform} to the rows this node recognises, carry every row it
     * does not verbatim, and rebuild the section at {@code updated}. Pure and re-derivable each CAS attempt, as
     * {@link SectionMutation} requires - the transform sees only the recognised rows and must be a function of them.
     */
    static SectionMutation transform(UnaryOperator<List<Finding>> rowTransform, Instant updated) {
        return current -> {
            Document document = document(current);
            List<Finding> rows = rowTransform.apply(new ArrayList<>(document.recognised()));
            return section(rows, document.carried(), updated);
        };
    }

    /**
     * Merge one finding into a mutable row list under categorize-never-discard, in place: an existing row with the same
     * {@code (source, id)} keeps its {@code firstSeen}, labels and supersession mark while its mutable facts and
     * {@code lastSeen} refresh; a new one is appended beside its siblings. Returns whether the row was appended (a
     * genuinely new finding, the event-worthy case), so a caller emits the new-finding event only for a real addition.
     */
    static boolean merge(List<Finding> rows, Finding finding) {
        for (int index = 0; index < rows.size(); index++) {
            Finding existing = rows.get(index);
            if (existing.source().equals(finding.source()) && existing.id().equals(finding.id())) {
                rows.set(index, new Finding(finding.id(), finding.source(), finding.kind(), finding.category(),
                        finding.severity(), finding.confidence(), finding.description(), finding.references(),
                        finding.provenance(), finding.attributes(),
                        min(existing.firstSeen(), finding.firstSeen()), max(existing.lastSeen(), finding.lastSeen()),
                        existing.supersededBy(), existing.labels()));
                return false;
            }
        }
        rows.add(finding);
        return true;
    }

    /** The section for a full row set at {@code updated}: a {@link build.jenesis.repository.metadata.State#EMPTY}
     *  section when there is nothing at all to record (no recognised and no carried row), a derived one carrying the
     *  {@code {"findings":[...]}} payload otherwise. The signal is the highest active vulnerability/malware severity. */
    static Section section(List<Finding> rows, List<JsonNode> carried, Instant updated) {
        if (rows.isEmpty() && carried.isEmpty()) {
            return Section.empty(TAG, SCHEMA, updated);
        }
        return Section.derived(TAG, SCHEMA, updated, signal(rows), serialize(rows, carried));
    }

    /** The gate-and-GUI signal of a row set: the highest severity among active (not superseded) vulnerability and
     *  malware rows, or neutral when none contributes a band - the "adds to a score or is neutral" summary the gate
     *  and the generic renderer read without parsing {@code data} (§6). */
    static Signal signal(List<Finding> rows) {
        Severity highest = null;
        for (Finding row : rows) {
            if (!row.active() || (row.kind() != Finding.Kind.VULNERABILITY && row.kind() != Finding.Kind.MALWARE)) {
                continue;
            }
            if (row.severity() != Severity.NONE && (highest == null || row.severity().ordinal() > highest.ordinal())) {
                highest = row.severity();
            }
        }
        return Signal.of(highest);
    }

    /** Serialise a row set plus its carried raw rows to the {@code {"findings":[...]}} data node - the same shape the
     *  standalone sidecar wrote, so a sidecar's bytes and this node are interchangeable across the migration. Carried
     *  rows ride after the recognised ones, verbatim, so a mutate by a node that cannot parse them stays lossless. */
    static JsonNode serialize(List<Finding> rows, List<JsonNode> carried) {
        ObjectNode data = JSON.createObjectNode();
        ArrayNode findings = data.putArray(FINDINGS_FIELD);
        for (Finding finding : rows) {
            ObjectNode row = findings.addObject();
            row.put("id", finding.id());
            row.put("source", finding.source());
            row.put("kind", finding.kind().name());
            row.put("category", finding.category());
            row.put("severity", finding.severity().name());
            row.put("confidence", finding.confidence());
            row.put("description", finding.description());
            ArrayNode references = row.putArray("references");
            finding.references().forEach(references::add);
            row.put("provenance", finding.provenance());
            ObjectNode attributes = row.putObject("attributes");
            finding.attributes().forEach(attributes::put);
            row.put("firstSeen", finding.firstSeen().toString());
            row.put("lastSeen", finding.lastSeen().toString());
            if (finding.supersededBy() != null) {
                row.put("supersededBy", finding.supersededBy());
            }
            ArrayNode labels = row.putArray("labels");
            for (Finding.Label label : finding.labels()) {
                ObjectNode mark = labels.addObject();
                mark.put("source", label.source());
                mark.put("name", label.name());
                mark.put("value", label.value());
                mark.put("confidence", label.confidence());
                mark.put("when", label.when().toString());
            }
        }
        for (JsonNode unrecognised : carried) {
            findings.add(unrecognised);
        }
        return data;
    }

    /** Parse a {@code {"findings":[...]}} data node into what this node understands and what it carries. Total: a row
     *  it cannot parse (a newer node's kind/severity and labels, an absent field, an unparseable instant) is carried,
     *  not dropped, while its siblings parse - one garbled row never blanks the coordinate's whole trail. */
    static Document parse(JsonNode data) {
        Document parsed = Document.empty();
        for (JsonNode row : data.path(FINDINGS_FIELD)) {
            try {
                List<String> references = new ArrayList<>();
                for (JsonNode reference : row.path("references")) {
                    references.add(reference.asString());
                }
                Map<String, String> attributes = new LinkedHashMap<>();
                row.path("attributes").properties().forEach(entry ->
                        attributes.put(entry.getKey(), entry.getValue().asString()));
                List<Finding.Label> labels = new ArrayList<>();
                for (JsonNode mark : row.path("labels")) {
                    labels.add(new Finding.Label(mark.path("source").asString(), mark.path("name").asString(),
                            mark.path("value").asString(), mark.path("confidence").asDouble(),
                            Instant.parse(mark.path("when").asString())));
                }
                parsed.recognised().add(new Finding(row.path("id").asString(), row.path("source").asString(),
                        Finding.Kind.valueOf(row.path("kind").asString()), row.path("category").asString(),
                        Severity.valueOf(row.path("severity").asString()), row.path("confidence").asDouble(),
                        row.path("description").asString(), references, row.path("provenance").asString(),
                        attributes, Instant.parse(row.path("firstSeen").asString()),
                        Instant.parse(row.path("lastSeen").asString()), row.path("supersededBy").asString(null),
                        labels));
            } catch (RuntimeException e) {
                LOGGER.warn("Carrying an unrecognised findings row through unparsed", e);
                parsed.carried().add(row);
            }
        }
        return parsed;
    }

    private static Instant min(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private static Instant max(Instant left, Instant right) {
        return left.isAfter(right) ? left : right;
    }

    /** A findings payload split into the rows this node understands and the raw rows it does not - the section-level
     *  form of {@code StoreFindings.Document}, so the carried-row fidelity survives the move into the document. */
    record Document(List<Finding> recognised, List<JsonNode> carried) {

        static Document empty() {
            return new Document(new ArrayList<>(), new ArrayList<>());
        }
    }
}
