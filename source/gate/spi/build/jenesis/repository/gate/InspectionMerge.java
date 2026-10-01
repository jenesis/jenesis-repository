package build.jenesis.repository.gate;

import module java.base;
import build.jenesis.repository.compliance.ComplianceGate;

/**
 * Orders the subjects an artifact's claiming inspectors produced before the gate assesses them. Both screens run every
 * {@link build.jenesis.repository.compliance.QualityInspector} that claims the path, so a content scanner composes with
 * the one format inspector that claims it. A content-scan subject ({@link ComplianceGate.Subject#contentScan()}, with
 * no package identity) sorts after the package subjects, so the screens' reads of the first subject keep naming the
 * package. The order within each group is preserved.
 */
public final class InspectionMerge {

    private InspectionMerge() {
    }

    /**
     * Whether the inspectors produced no package subject, whatever content findings they produced: the question a
     * screen's truncated-artifact fallback asks. The fallback puts a licensable coordinate before the gate so the
     * licence dimensions still apply, and a content-scan subject is not one, so a list of only content-scan subjects
     * needs it as an empty list does.
     */
    public static boolean noPackageSubject(List<ComplianceGate.Subject> subjects) {
        return subjects.stream().allMatch(ComplianceGate.Subject::contentScan);
    }

    /** The subjects every claiming inspector produced, package subjects first and content-scan subjects last. */
    public static List<ComplianceGate.Subject> order(List<ComplianceGate.Subject> produced) {
        List<ComplianceGate.Subject> packages = new ArrayList<>();
        List<ComplianceGate.Subject> content = new ArrayList<>();
        for (ComplianceGate.Subject subject : produced) {
            (subject.contentScan() ? content : packages).add(subject);
        }
        packages.addAll(content);
        return packages;
    }
}
