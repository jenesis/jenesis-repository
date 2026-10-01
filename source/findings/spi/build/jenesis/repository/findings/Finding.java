package build.jenesis.repository.findings;

import module java.base;
import build.jenesis.repository.compliance.Severity;

/**
 * One durable finding against a coordinate: what was found, by which module or feed, and how it is categorized. It is
 * identified within its coordinate by {@code (source, id)}, so the same advisory from two feeds is two attributed
 * findings. Re-recording refreshes the mutable facts and {@code lastSeen} and keeps {@code firstSeen}, the labels and
 * any supersession mark, so a re-scan converges.
 *
 * <p>A finding no longer reported keeps its row with an ageing {@code lastSeen}, a wrong one is
 * {@linkplain #supersededBy marked} rather than erased, and later classifiers attach {@linkplain Label labels}.
 * Kind-specific facts (a fixed version, a call path) ride {@code attributes}.
 */
public record Finding(String id, String source, Kind kind, String category, Severity severity, double confidence,
                      String description, List<String> references, String provenance,
                      Map<String, String> attributes, Instant firstSeen, Instant lastSeen, String supersededBy,
                      List<Label> labels) {

    /** What family of statement a finding makes. The ledger stores the name, so stored rows survive new kinds. */
    public enum Kind {
        /** A known vulnerability reported by an advisory feed. */
        VULNERABILITY,
        /** A license fact or policy statement. */
        LICENSE,
        /** A malicious-package verdict. */
        MALWARE,
        /** A reachability categorization (reachable / not reachable / unknown) of another finding's subject. */
        REACHABILITY,
        /** An applicability judgement - whether a reported finding applies in this artifact's context. */
        APPLICABILITY,
        /** An AI-produced candidate finding awaiting human review - labeled, never authoritative. */
        AI_CANDIDATE,
        /** A structured gate decision - the reasons the compliance gate withheld an artifact. */
        GATE,
        /** A statement about a publisher's signature: verified, not verified, by an untrusted signer, absent where the
         *  format expects one, or unreadable. Not {@link #GATE}, since the fact stands whether or not a dimension acts
         *  on it, and not provenance, which says how an artifact was built rather than who vouched for its bytes. */
        SIGNATURE,
        /** A quality-inspection failure: the artifact could not be parsed by its format's inspector (truncated,
         *  corrupt, a decompression bomb) and was screened from its path alone, so it renders as not fully screened
         *  rather than clean. */
        INSPECTION,
        /** A negative scan result ({@link CleanScanMarker}): the feeds reported nothing for this coordinate. */
        CLEAN;

        /** The wire spelling ({@code ai-candidate}), the form the HTTP API and the CLI accept and emit. */
        public String wire() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }

        /** The kind a wire spelling names, case-insensitively; empty for an unknown spelling. */
        public static Optional<Kind> ofWire(String value) {
            for (Kind kind : values()) {
                if (kind.wire().equalsIgnoreCase(value) || kind.name().equalsIgnoreCase(value)) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }
    }

    /** A separately attributed annotation on an existing finding, adding without replacing anything. */
    public record Label(String source, String name, String value, double confidence, Instant when) {
    }

    public Finding {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(kind, "kind");
        category = category == null ? "" : category;
        severity = severity == null ? Severity.NONE : severity;
        description = description == null ? "" : description;
        references = references == null ? List.of() : List.copyOf(references);
        provenance = provenance == null ? "" : provenance;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        Objects.requireNonNull(firstSeen, "firstSeen");
        Objects.requireNonNull(lastSeen, "lastSeen");
        labels = labels == null ? List.of() : List.copyOf(labels);
    }

    /** A fresh finding with the shared facts, seen now on both ends; the writer refines it with the withers. */
    public static Finding of(String id, String source, Kind kind, String category, Severity severity,
                             String description, Instant seen) {
        return new Finding(id, source, kind, category, severity, 1.0, description, List.of(), "", Map.of(),
                seen, seen, null, List.of());
    }

    public Finding withConfidence(double confidence) {
        return new Finding(id, source, kind, category, severity, confidence, description, references, provenance,
                attributes, firstSeen, lastSeen, supersededBy, labels);
    }

    public Finding withReferences(List<String> references) {
        return new Finding(id, source, kind, category, severity, confidence, description, references, provenance,
                attributes, firstSeen, lastSeen, supersededBy, labels);
    }

    public Finding withProvenance(String provenance) {
        return new Finding(id, source, kind, category, severity, confidence, description, references, provenance,
                attributes, firstSeen, lastSeen, supersededBy, labels);
    }

    public Finding withAttribute(String key, String value) {
        Map<String, String> extended = new LinkedHashMap<>(attributes);
        extended.put(key, value);
        return new Finding(id, source, kind, category, severity, confidence, description, references, provenance,
                extended, firstSeen, lastSeen, supersededBy, labels);
    }

    /** Whether this finding is still standing - not marked as superseded by a later or better one. */
    public boolean active() {
        return supersededBy == null;
    }
}
