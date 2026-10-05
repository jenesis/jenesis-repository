package build.jenesis.repository.closure;

import module java.base;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.findings.Finding;
import build.jenesis.repository.findings.Findings;
import build.jenesis.repository.findings.FindingsProvider;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.HeldVersions;
import build.jenesis.repository.store.ServableNames;

/**
 * What a published version inherits from its closure: each version it reaches as it stands in the repository of the
 * walk holding it - held for review, and its findings at or above the risk band. A component is looked up where the
 * closure says it is held; a cut naming a version some repository of the walk holds for review is the held copy the
 * closure stopped at, and is looked up there. Point reads only, a few per version reached, so the work is bounded by
 * the closure's own bound; nothing is asked of a feed.
 */
final class Exposures {

    private Exposures() {
    }

    /** {@code closure}'s exposure over the repositories of {@code walk}, findings counted from {@code risk} up. */
    static ExposureSection.Exposure derive(ClosureWalk walk, String ecosystem, ClosureSection.Closure closure,
                                           Severity risk, Instant now) throws IOException {
        List<ClosureWalk.Member> members = walk.members();
        Map<String, ClosureWalk.Member> byRepository = new HashMap<>();
        for (int i = 1; i < members.size(); i++) {
            byRepository.putIfAbsent(members.get(i).repository(), members.get(i));
        }
        List<ExposureSection.Reached> reached = new ArrayList<>();
        int examined = 0;
        for (ClosureSection.Component component : closure.components()) {
            ClosureWalk.Member holder = component.elsewhere() ? byRepository.get(component.repository())
                    : members.getFirst();
            if (holder == null) {
                continue;
            }
            examined++;
            boolean held = !new StoreRepositoryInventory(holder.store()).disclosable(ecosystem,
                    component.coordinate(), component.version(), ServableNames.Policy.HIDE_WITHHELD);
            add(reached, holder, ecosystem, component.coordinate(), component.version(), component.repository(), held,
                    risk);
        }
        for (ClosureSection.Cut cut : closure.cuts()) {
            for (int i = 0; i < members.size(); i++) {
                ClosureWalk.Member member = members.get(i);
                if (heldForReview(member, ecosystem, cut.coordinate(), cut.requirement())) {
                    examined++;
                    add(reached, member, ecosystem, cut.coordinate(), cut.requirement(),
                            i == 0 ? "" : member.repository(), true, risk);
                    break;
                }
            }
        }
        return new ExposureSection.Exposure(reached, examined, now);
    }

    /** Whether {@code member} holds {@code version} of {@code coordinate} for review: a holding it withholds, or a
     *  proxied copy the screen held at its fill, whose review pointer is still in place. */
    private static boolean heldForReview(ClosureWalk.Member member, String ecosystem, String coordinate,
                                         String version) throws IOException {
        if (version.isBlank()) {
            return false;
        }
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(member.store());
        if (inventory.publishedAt(ecosystem, coordinate, version).isPresent()
                || inventory.cachedAt(ecosystem, coordinate, version).isPresent()) {
            return !inventory.disclosable(ecosystem, coordinate, version, ServableNames.Policy.HIDE_WITHHELD);
        }
        return HeldVersions.held(member.store(), ecosystem, coordinate, version);
    }

    /** Add the version to {@code reached} where it is held or carries findings at or above {@code risk}. */
    private static void add(List<ExposureSection.Reached> reached, ClosureWalk.Member holder, String ecosystem,
                            String coordinate, String version, String repository, boolean held, Severity risk)
            throws IOException {
        int count = 0;
        Severity worst = null;
        Optional<Findings> ledger = FindingsProvider.installed().map(provider -> provider.over(holder.store()));
        if (ledger.isPresent()) {
            for (Finding finding : ledger.get().of(ecosystem, coordinate, version)) {
                if (finding.active() && finding.severity().compareTo(risk) >= 0) {
                    count++;
                    if (worst == null || finding.severity().compareTo(worst) > 0) {
                        worst = finding.severity();
                    }
                }
            }
        }
        if (held || count > 0) {
            reached.add(new ExposureSection.Reached(coordinate, version, repository, held, count,
                    worst == null ? "" : worst.name()));
        }
    }
}
