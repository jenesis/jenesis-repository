package build.jenesis.repository.compliance;

import module java.base;

/**
 * An inspection that read what it could and could not read the rest - a dependency closure it could not resolve, say -
 * carrying the verdict the deployment gives such an artifact. The screen decides on it: the artifact is held or refused
 * with the reason, under the subjects that were read, so a review names what was screened and what was not. An
 * inspector whose deployment admits such an artifact returns what it read instead of throwing this.
 */
public final class IncompleteScreenException extends RuntimeException {

    private final Verdict verdict;
    private final List<ComplianceGate.Subject> subjects;

    /** @param verdict  what the deployment does with it: {@link Verdict#QUARANTINE} or {@link Verdict#REJECT}
     *  @param subjects what the inspection read before it stopped */
    public IncompleteScreenException(String reason, Verdict verdict, List<ComplianceGate.Subject> subjects) {
        super(reason);
        if (verdict != Verdict.QUARANTINE && verdict != Verdict.REJECT) {
            throw new IllegalArgumentException("an incomplete screen holds or refuses, not " + verdict);
        }
        this.verdict = verdict;
        this.subjects = List.copyOf(subjects);
    }

    public Verdict verdict() {
        return verdict;
    }

    public List<ComplianceGate.Subject> subjects() {
        return subjects;
    }
}
