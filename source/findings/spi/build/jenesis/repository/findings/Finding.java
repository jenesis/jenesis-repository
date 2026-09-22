package build.jenesis.repository.findings;

import module java.base;
import build.jenesis.repository.compliance.Severity;

/**
 * One durable finding against a coordinate: what was found, by which module or feed, and how it is categorized.
 * A finding is identified within its coordinate by {@code (source, id)} - the same advisory reported by two feeds
 * is two findings, each attributed, so the ledger never loses who said what (the de-duplicated union is a display
 * concern). Re-recording an existing finding refreshes its mutable facts and {@code lastSeen} while keeping
 * {@code firstSeen}, its labels and any supersession mark, so a scheduled re-scan converges instead of duplicating.
 *
 * <p>The row obeys categorize-never-discard: a finding that stops being reported keeps its row (its {@code lastSeen}
 * simply ages), a wrong or replaced finding is {@linkplain #supersededBy marked} rather than erased, and later
 * classifiers (reachability, applicability, AI) attach {@linkplain Label labels} rather than rewriting the finding.
 * Kind-specific structured facts that no shared field carries - a vulnerability's fixed version, a reachability
 * verdict's call path - ride the small {@code attributes} map, so a new finding kind needs no schema change.
 */
public record Finding(String id, String source, Kind kind, String category, Severity severity, double confidence,
                      String description, List<String> references, String provenance,
                      Map<String, String> attributes, Instant firstSeen, Instant lastSeen, String supersededBy,
                      List<Label> labels) {

    /** What family of statement a finding makes; open-ended by design - a future engine adds a constant here, and
     *  the ledger stores the name, so old rows survive new kinds. */
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
        /** A statement about a publisher's signature on an artifact: that it verified, did not verify, was made by a
         *  signer the deployment has no reason to believe, was absent where the format expects one, or was present
         *  but unreadable. Distinct from {@link #GATE} because a signature fact outlives the verdict taken on it -
         *  the same signature is a finding whether or not a dimension is installed to act on it - and distinct from
         *  the provenance an attestation carries, which is a claim about how an artifact was BUILT rather than about
         *  who vouched for these bytes. */
        SIGNATURE,
        /** A quality-inspection failure: the artifact claimed a format but could not be parsed by its inspector
         *  (truncated, corrupt, not the archive it names, a decompression bomb), so it was screened only from its path
         *  coordinate. Recorded so an unparseable artifact renders as "could not derive - not fully screened" rather
         *  than a silent clean, distinct from a well-formed artifact that genuinely declares nothing. */
        INSPECTION,
        /** A negative scan result: the advisory feeds were consulted for this coordinate and reported nothing. Recorded
         *  so a known-clean coordinate is served from the ledger instead of re-querying every feed on every read, until
         *  the marker ages past its freshness window. Never rendered as a vulnerability. */
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

    /** An annotation a module attaches to an existing finding - a separately attributed opinion (an AI reachability
     *  judgement beside the static one, an applicability rationale) that adds to the finding without replacing
     *  anything on it. */
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
