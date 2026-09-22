package build.jenesis.repository.gate;

import module java.base;
import build.jenesis.repository.compliance.ComplianceGate;

/**
 * Orders the subjects an artifact's claiming inspectors produced before the gate assesses them. Both screens - the
 * publish-path {@code ComplianceScreen} and the proxy-path {@code ProxyScreen} - run <em>every</em> {@link
 * build.jenesis.repository.compliance.QualityInspector} whose {@code handles} claims the path, not just the first, so
 * a content scanner (the embedded-secret dimension) composes alongside the format inspector rather than shadowing it
 * or being shadowed. A path is claimed by at most one <em>format</em> inspector (they are prefix-namespaced), so this
 * merge does not change what a format contributes; it only appends any content-scan subjects.
 *
 * <p>A content-scan subject ({@link ComplianceGate.Subject#contentScan()} - one an inspector derived from an
 * artifact's bytes, an embedded-secret detection or an inbound attestation, with no package coordinate identity) is
 * sorted <em>after</em> the format's subjects, so the screens' "the first subject is the artifact itself" reads - the
 * licenses recorded on an accepted publish, the coordinate named in a quarantine log line - keep naming the package,
 * not a content finding. The order within each group is preserved (a stable partition).
 */
public final class InspectionMerge {

    private InspectionMerge() {
    }

    /**
     * Whether the inspectors produced no <em>package</em> subject - nothing carrying a licensable coordinate identity,
     * whether or not they produced content findings.
     *
     * <p>This is the question a screen's truncated-artifact fallback actually asks, and asking "is the list empty"
     * instead is subtly wrong in a way that only shows up once two inspectors claim one path. The fallback exists to
     * put a licensable coordinate in front of the gate when no inspector could read one, so that the deny-list and
     * unknown-license dimensions still bite; a content-scan subject is by construction not that - the license
     * dimension skips it - so a list holding only content-scan subjects needs the fallback exactly as an empty one
     * does. Reading it as "not empty" means any content inspector that happens to find something in a truncated head
     * silently disables the fallback for that artifact.
     *
     * <p>Found when an always-claiming signature inspector was installed and a padded archive that had been held
     * started streaming through; the same hole was already reachable through the embedded-secret scanner, which had
     * simply never found anything in a truncated head during a test.
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
