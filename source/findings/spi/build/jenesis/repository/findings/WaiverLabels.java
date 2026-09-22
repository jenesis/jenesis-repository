package build.jenesis.repository.findings;

import module java.base;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.Waiver;
import build.jenesis.repository.compliance.Waivers;

/**
 * The accept-risk waiver contract over the findings/annotations substrate: an operator's time-boxed decision to accept
 * a known vulnerability on a coordinate, recorded as a {@code (source="operator", name="accept-risk")} label on the
 * still-present finding - the same operator-confirmed-candidate model {@link ReviewLabels} uses, so the ledger stays
 * the single source of truth and the gate and the vulnerability ranking read a durable annotation rather than a side
 * store. The label's value is the ISO-8601 instant the acceptance {@linkplain #expiryOf expires}; an optional
 * {@link #NOTE} sibling carries the operator's justification, and re-applying refreshes the own value (the sanctioned
 * refresh), so extending or revoking a waiver is a relabel, never a deletion.
 *
 * <p>Categorize-never-discard holds: a waiver is a label on a finding that stays fully present and served, a revoke is a
 * relabel to {@link #REVOKED} rather than a removal, and the acceptance auto-lapses at its expiry with no sweep having
 * to retract it - a reader simply stops honouring it once {@code expires} is past. {@link #apply} refuses a row that is
 * not an advisory-derived finding (a vulnerability or malware kind): a license fact, a gate decision or an AI candidate
 * is not a risk an accept-risk waiver defers, and asking throws.
 */
public final class WaiverLabels {

    private WaiverLabels() {
    }

    /** The label source an operator's accept-risk decision writes under - the same attributed operator channel a
     *  review decision uses. */
    public static final String SOURCE = "operator";

    /** The label name carrying the acceptance's expiry instant (its value is the ISO-8601 {@code expires}). */
    public static final String NAME = "accept-risk";

    /** The label name carrying the operator's optional justification for accepting the risk. */
    public static final String NOTE = "accept-risk-note";

    /** The {@link #NAME} value a revoked waiver carries - a relabel, never a removal, so the acceptance stops being
     *  honoured at once while the still-present row records that it once was. */
    public static final String REVOKED = "revoked";

    /** The finding kinds an accept-risk waiver may be applied to - the advisory-derived ones the gate suppresses by
     *  advisory id (a vulnerability, or a malicious-package verdict). */
    public static final Set<Finding.Kind> WAIVABLE = Set.of(Finding.Kind.VULNERABILITY, Finding.Kind.MALWARE);

    /** The most characters of justification one waiver keeps. */
    private static final int NOTE_CEILING = 1000;

    /** Whether a finding of this kind takes an accept-risk waiver at all. */
    public static boolean waivable(Finding.Kind kind) {
        return WAIVABLE.contains(kind);
    }

    /** The instant this finding's accept-risk waiver expires, or empty when it carries no waiver, is revoked, or the
     *  recorded value does not parse as an instant (a corrupt label never over-accepts). */
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
     * The active accept-risk waivers among a coordinate's stored advisory findings, keyed by advisory id <em>and</em>
     * every CVE alias the finding carries - the same identifiers the vulnerability view de-duplicates rows by - each
     * key holding the ISO-8601 instant the acceptance stands until. A view badges a merged advisory row by looking up
     * its id, falling back to its CVE aliases; a revoked or expired waiver contributes nothing, so the badge shows only
     * a still-standing acceptance. Mirrors {@link ReachabilityLabels#verdicts} so the vulnerability report's waiver
     * badge keys exactly as its reachability and applicability badges do, without a second store walk.
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
     * Record an operator's accept-risk waiver on the advisory-derived finding identified by {@code (source, id)} on a
     * coordinate: the expiry label, and the note label when a non-blank justification was given. A repeated apply
     * refreshes its own labels (the sanctioned own-value refresh), so extending a waiver is a relabel.
     *
     * @throws IllegalArgumentException when no such finding exists on the coordinate, the finding is not
     *                                  advisory-derived, or the expiry is not in the future (a time-boxed exception must
     *                                  bound a future window)
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

    /** Revoke an operator's accept-risk waiver: relabel the expiry to {@link #REVOKED} so it stops being honoured at
     *  once, leaving the still-present finding to record that the risk was once accepted.
     *
     *  @throws IllegalArgumentException when no such finding exists on the coordinate */
    public static void revoke(Findings ledger, String ecosystem, String coordinate, String version,
                              String source, String id, Instant now) throws IOException {
        require(ledger, ecosystem, coordinate, version, source, id);
        ledger.label(ecosystem, coordinate, version, source, id,
                new Finding.Label(SOURCE, NAME, REVOKED, 1.0, now));
    }

    /**
     * Every active accept-risk waiver recorded in a repository's ledger, as the ecosystem-neutral {@link Waiver} the
     * compliance gate matches - projected from the {@code accept-risk} annotations on the advisory-derived findings, so
     * the gate reads the same durable ledger the review surfaces write. A revoked or expired waiver is skipped, so the
     * matcher only ever carries what is currently standing at {@code now}.
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
     * A repository's active accept-risk waivers as the {@link Waivers} overlay the compliance gate consults - the
     * ledger-backed mirror of {@code VexStore.asVex()}, so a deployment wires waiver suppression into the gate exactly
     * as it wires VEX: resolve this per tenant/repository and hand it to {@link build.jenesis.repository.compliance.ComplianceGate#waivers}.
     * Projects {@link #waivers(Findings, Instant)} through {@link Waivers#of}, so a revoked or expired waiver is already
     * dropped; a ledger with no standing waiver yields an empty matcher that suppresses nothing (the gate then behaves
     * exactly as before it was wired). The one bridge from the durable ledger to the gate's matcher, so no caller
     * re-implements the {@code waivers(...) -> Waivers.of(...)} step.
     */
    public static Waivers overlay(Findings ledger, Instant now) throws IOException {
        return Waivers.of(waivers(ledger, now));
    }

    /**
     * The active accept-risk waivers standing on just the coordinate versions about to be assessed, as the
     * {@link Waivers} overlay the compliance gate consults - the coordinate-scoped counterpart of {@link #overlay}.
     * Where {@code overlay} walks the whole repository ledger to project every standing waiver, this reads only each
     * inspected subject's own finding record (a point lookup per coordinate - {@link Findings#of}), so a publish into a
     * busy repository no longer pays an {@code O(ledger)} walk before the gate assesses. A waiver on any other
     * coordinate is irrelevant to this publish (the gate only produces findings for the subjects it is handed), so
     * scoping to the subjects is exactly the overlay the gate needs and no suppression is lost. Duplicate coordinates
     * among the subjects are read once; a subject whose coordinate carries no standing waiver contributes nothing.
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

    /** The instant the standing waiver was recorded (its {@link #NAME} label's {@code when}), or {@code null} when the
     *  finding carries no such label - the grant instant the matcher uses to let the newest waiver win. */
    private static Instant grantedAt(Finding finding) {
        for (Finding.Label label : finding.labels()) {
            if (SOURCE.equals(label.source()) && NAME.equals(label.name())) {
                return label.when();
            }
        }
        return null;
    }
}
