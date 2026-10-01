package build.jenesis.repository.findings;

import module java.base;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.Waiver;
import build.jenesis.repository.compliance.Waivers;

/**
 * The accept-risk waiver contract: an operator's time-boxed acceptance of a known vulnerability on a coordinate,
 * recorded as a {@code (source="operator", name="accept-risk")} label on the still-present finding, so the gate and the
 * ranking read the ledger rather than a side store. The value is the ISO-8601 instant the acceptance
 * {@linkplain #expiryOf expires}, with an optional {@link #NOTE} justification; extending is a relabel and revoking a
 * relabel to {@link #REVOKED}. A waiver lapses at its expiry with no sweep, since readers stop honouring it. Only
 * advisory-derived findings take one ({@link #WAIVABLE}).
 */
public final class WaiverLabels {

    private WaiverLabels() {
    }

    /** The label source an operator's accept-risk decision writes under, shared with review decisions. */
    public static final String SOURCE = "operator";

    /** The label name carrying the acceptance's expiry instant (its value is the ISO-8601 {@code expires}). */
    public static final String NAME = "accept-risk";

    /** The label name carrying the operator's optional justification for accepting the risk. */
    public static final String NOTE = "accept-risk-note";

    /** The {@link #NAME} value a revoked waiver carries, so the row still records that the risk was once accepted. */
    public static final String REVOKED = "revoked";

    /** The finding kinds a waiver applies to: the advisory-derived ones the gate suppresses by advisory id. */
    public static final Set<Finding.Kind> WAIVABLE = Set.of(Finding.Kind.VULNERABILITY, Finding.Kind.MALWARE);

    /** The most characters of justification one waiver keeps. */
    private static final int NOTE_CEILING = 1000;

    /** Whether a finding of this kind takes an accept-risk waiver at all. */
    public static boolean waivable(Finding.Kind kind) {
        return WAIVABLE.contains(kind);
    }

    /** The instant this finding's waiver expires, or empty when it has none, is revoked, or does not parse. */
    public static Optional<Instant> expiryOf(Finding finding) {
        for (Finding.Label label : finding.labels()) {
            if (SOURCE.equals(label.source()) && NAME.equals(label.name())) {
                if (REVOKED.equalsIgnoreCase(label.value())) {
                    return Optional.empty();
                }
                try {
                    return Optional.of(Instant.parse(label.value()));
                } catch (RuntimeException _) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    /** Whether this finding carries an accept-risk waiver still standing at {@code now}. */
    public static boolean active(Finding finding, Instant now) {
        return expiryOf(finding).filter(expiry -> expiry.isAfter(now)).isPresent();
    }

    /**
     * The waivers standing at {@code now} among a coordinate's advisory findings, keyed as
     * {@link ReachabilityLabels#verdicts} keys them, each holding the ISO-8601 instant the acceptance stands until.
     */
    public static Map<String, String> expiries(List<Finding> findings, Instant now) {
        Map<String, String> expiries = new HashMap<>();
        for (Finding finding : findings) {
            Optional<Instant> expiry = expiryOf(finding);
            if (expiry.isEmpty() || !expiry.get().isAfter(now)) {
                continue;
            }
            String value = expiry.get().toString();
            expiries.put(finding.id(), value);
            for (String reference : finding.references()) {
                if (reference.startsWith("CVE-")) {
                    expiries.put(reference, value);
                }
            }
        }
        return expiries;
    }

    /** The operator's justification recorded with the waiver, or empty when none was given. */
    public static Optional<String> noteOf(Finding finding) {
        for (Finding.Label label : finding.labels()) {
            if (SOURCE.equals(label.source()) && NOTE.equals(label.name())
                    && label.value() != null && !label.value().isBlank()) {
                return Optional.of(label.value());
            }
        }
        return Optional.empty();
    }

    /**
     * Records a waiver on the advisory-derived finding {@code (source, id)}: the expiry label, and the note label for a
     * non-blank justification. A repeated apply refreshes its own labels.
     *
     * @throws IllegalArgumentException when no such finding exists, it is not advisory-derived, or the expiry is not
     *                                  in the future
     */
    public static void apply(Findings ledger, String ecosystem, String coordinate, String version,
                             String source, String id, Instant expires, String note, Instant now)
            throws IOException {
        Finding target = require(ledger, ecosystem, coordinate, version, source, id);
        if (!waivable(target.kind())) {
            throw new IllegalArgumentException("Only an advisory-derived finding takes an accept-risk waiver; "
                    + source + ":" + id + " is " + target.kind().wire());
        }
        if (expires == null || !expires.isAfter(now)) {
            throw new IllegalArgumentException("An accept-risk waiver's expiry must be in the future.");
        }
        ledger.label(ecosystem, coordinate, version, source, id,
                new Finding.Label(SOURCE, NAME, expires.toString(), 1.0, now));
        if (note != null && !note.isBlank()) {
            String trimmed = note.trim();
            ledger.label(ecosystem, coordinate, version, source, id, new Finding.Label(SOURCE, NOTE,
                    trimmed.length() > NOTE_CEILING ? trimmed.substring(0, NOTE_CEILING) : trimmed, 1.0, now));
        }
    }

    /** Revokes a waiver by relabelling its expiry to {@link #REVOKED}.
     *
     *  @throws IllegalArgumentException when no such finding exists on the coordinate */
    public static void revoke(Findings ledger, String ecosystem, String coordinate, String version,
                              String source, String id, Instant now) throws IOException {
        require(ledger, ecosystem, coordinate, version, source, id);
        ledger.label(ecosystem, coordinate, version, source, id,
                new Finding.Label(SOURCE, NAME, REVOKED, 1.0, now));
    }

    /**
     * Every waiver standing at {@code now} in a repository's ledger, as the {@link Waiver} the gate matches.
     */
    public static List<Waiver> waivers(Findings ledger, Instant now) throws IOException {
        List<Waiver> waivers = new ArrayList<>();
        for (Findings.Located located : ledger.all(Findings.Filter.none())) {
            Finding finding = located.finding();
            if (!waivable(finding.kind())) {
                continue;
            }
            Optional<Instant> expiry = expiryOf(finding);
            if (expiry.isEmpty() || !expiry.get().isAfter(now)) {
                continue;
            }
            waivers.add(new Waiver(finding.id(), finding.references(), located.ecosystem(), located.coordinate(),
                    located.version(), grantedAt(finding), expiry.get(), noteOf(finding).orElse(null)));
        }
        return waivers;
    }

    /**
     * A repository's standing waivers as the {@link Waivers} overlay handed to
     * {@link build.jenesis.repository.compliance.ComplianceGate#waivers}, as VEX is; empty when none stands.
     */
    public static Waivers overlay(Findings ledger, Instant now) throws IOException {
        return Waivers.of(waivers(ledger, now));
    }

    /**
     * The {@link #overlay} restricted to the subjects about to be assessed: one {@link Findings#of} point read per
     * distinct coordinate rather than a ledger walk, losing nothing, since the gate only judges the subjects it is
     * handed.
     */
    public static Waivers overlayFor(Findings ledger, List<ComplianceGate.Subject> subjects, Instant now)
            throws IOException {
        List<Waiver> waivers = new ArrayList<>();
        Set<String> read = new HashSet<>();
        for (ComplianceGate.Subject subject : subjects) {
            String scope = subject.ecosystem() + '\0' + subject.coordinate() + '\0' + subject.version();
            if (!read.add(scope)) {
                continue;                                       // a coordinate two subjects share is read once
            }
            for (Finding finding : ledger.of(subject.ecosystem(), subject.coordinate(), subject.version())) {
                if (!waivable(finding.kind())) {
                    continue;
                }
                Optional<Instant> expiry = expiryOf(finding);
                if (expiry.isEmpty() || !expiry.get().isAfter(now)) {
                    continue;
                }
                waivers.add(new Waiver(finding.id(), finding.references(), subject.ecosystem(), subject.coordinate(),
                        subject.version(), grantedAt(finding), expiry.get(), noteOf(finding).orElse(null)));
            }
        }
        return Waivers.of(waivers);
    }

    private static Finding require(Findings ledger, String ecosystem, String coordinate, String version,
                                   String source, String id) throws IOException {
        for (Finding finding : ledger.of(ecosystem, coordinate, version)) {
            if (finding.source().equals(source) && finding.id().equals(id)) {
                return finding;
            }
        }
        throw new IllegalArgumentException("No finding " + source + ":" + id + " on " + coordinate + ":" + version);
    }

    /** When the waiver was granted, or {@code null} without one; the newest waiver wins. */
    private static Instant grantedAt(Finding finding) {
        for (Finding.Label label : finding.labels()) {
            if (SOURCE.equals(label.source()) && NAME.equals(label.name())) {
                return label.when();
            }
        }
        return null;
    }
}
