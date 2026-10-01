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
 * The {@code findings} section codec of the consolidated metadata document: a coordinate version's rows, keyed
 * {@code (source, id)}, unioned across every writer. The merge is categorize-never-discard - a re-record refreshes a
 * row's facts and {@code lastSeen} while keeping its {@code firstSeen}, labels and supersession mark, and a writer only
 * adds or refreshes rows - and a row this node cannot parse (a newer {@link Finding.Kind} or {@link Severity}) is held
 * as raw {@link JsonNode} and re-serialised verbatim, so an older node never eats a newer node's rows.
 *
 * <p>The payload is {@code {"findings":[...]}}; the envelope's {@code signal} is the highest active vulnerability or
 * malware severity, or neutral. All methods are pure; a mutation returns a fresh {@link Section}.
 */
public final class FindingsSection {

    /** The section tag the findings ledger owns in the document. */
    public static final String TAG = "findings";

    /** The contributor-owned section schema version. */
    public static final int SCHEMA = 1;

    private static final String FINDINGS_FIELD = "findings";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Logger LOGGER = LoggerFactory.getLogger(FindingsSection.class);

    private FindingsSection() {
    }

    /** The recognised rows of a section, in stored order; empty for an absent section. Carried rows are never returned,
     *  so a read is total. */
    public static List<Finding> rows(Optional<Section> section) {
        return document(section).recognised();
    }

    /** The section split into recognised and carried rows; both empty for an absent or payload-less section. */
    static Document document(Optional<Section> section) {
        return section.flatMap(Section::payload).map(FindingsSection::parse).orElseGet(Document::empty);
    }

    /** A section-scoped row mutation: apply {@code rowTransform} to the recognised rows, carry the rest verbatim, and
     *  rebuild the section at {@code updated}. Re-derivable on every CAS attempt, as {@link SectionMutation}
     *  requires. */
    static SectionMutation transform(UnaryOperator<List<Finding>> rowTransform, Instant updated) {
        return current -> {
            Document document = document(current);
            List<Finding> rows = rowTransform.apply(new ArrayList<>(document.recognised()));
            return section(rows, document.carried(), updated);
        };
    }

    /** Merge one finding into a mutable row list, in place: a row with the same {@code (source, id)} keeps its
     *  {@code firstSeen}, labels and supersession mark while its facts and {@code lastSeen} refresh; otherwise it is
     *  appended. Returns whether it was appended, so the new-finding event fires only for a real addition. */
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

    /** The section for a full row set: {@link build.jenesis.repository.metadata.State#EMPTY} when there is no row at
     *  all, otherwise a derived section with the payload and the {@link #signal}. */
    static Section section(List<Finding> rows, List<JsonNode> carried, Instant updated) {
        if (rows.isEmpty() && carried.isEmpty()) {
            return Section.empty(TAG, SCHEMA, updated);
        }
        return Section.derived(TAG, SCHEMA, updated, signal(rows), serialize(rows, carried));
    }

    /** The signal of a row set: the highest severity among active vulnerability and malware rows, or neutral - the
     *  summary the gate and the generic renderer read without parsing {@code data}. */
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

    /** Serialise recognised rows followed by the carried raw rows, verbatim, so a mutate stays lossless. */
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

    /** Parse a payload into recognised and carried rows. Total: a row that cannot be parsed is carried, not dropped, so
     *  one garbled row never blanks the coordinate's trail. */
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

    /** A findings payload split into the rows this node understands and the raw rows it carries. */
    record Document(List<Finding> recognised, List<JsonNode> carried) {

        static Document empty() {
            return new Document(new ArrayList<>(), new ArrayList<>());
        }
    }
}
