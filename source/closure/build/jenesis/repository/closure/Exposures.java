package build.jenesis.repository.closure;

import module java.base;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.findings.Finding;
import build.jenesis.repository.findings.Findings;
import build.jenesis.repository.findings.FindingsProvider;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.HeldVersions;
import build.jenesis.repository.store.ServableNames;

/**
 * What a published version inherits from its closure: each version it reaches as it stands in the repository of the
 * walk holding it - held for review, and its findings at or above the risk band. A component is looked up where the
 * closure says it is held; a cut naming a version some repository of the walk holds for review is the held copy the
 * closure stopped at, and is looked up there. A package the version's bill names in another ecosystem is looked up in
 * every repository of the tenant, since any copy of it may be the one the build installed: each copy held or carrying
 * findings is reached, named by the repository holding it. Point reads only, a few per version reached - and per
 * repository of the tenant for such a package - so the work is bounded by the closure's own bound; nothing is asked of
 * a feed.
 */
final class Exposures {

    private Exposures() {
    }

    /** The tenant's repositories a closure's packages of other ecosystems are looked up in: their names, asked only
     *  of a closure naming such a package, and each one's store. */
    interface Tenant {

        /** No repository beyond the walk's: a closure's packages of other ecosystems are found nowhere. */
        Tenant NONE = new Tenant() {
            @Override
            public List<String> repositories() {
                return List.of();
            }

            @Override
            public Optional<ArtifactStore> store(String repository) {
                return Optional.empty();
            }
        };

        List<String> repositories() throws IOException;

        Optional<ArtifactStore> store(String repository);
    }

    /** {@code closure}'s exposure over the repositories of {@code walk}, findings counted from {@code risk} up through
     *  {@code findings} - none where no ledger is installed - and its packages of other ecosystems over
     *  {@code tenant}'s, {@code own} being the name of the repository of the version - the walk's first. */
    static ExposureSection.Exposure derive(ClosureWalk walk, String ecosystem, ClosureSection.Closure closure,
                                           Severity risk, Instant now, String own, Tenant tenant,
                                           Optional<FindingsProvider> findings) throws IOException {
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
            add(reached, findings, holder.store(), ecosystem, component.coordinate(), component.version(),
                    component.repository(), held, risk, ClosureSection.path(closure, component.coordinate(),
                            component.version()), "");
        }
        for (ClosureSection.Cut cut : closure.cuts()) {
            for (int i = 0; i < members.size(); i++) {
                ClosureWalk.Member member = members.get(i);
                if (heldForReview(member, ecosystem, cut.coordinate(), cut.requirement())) {
                    examined++;
                    add(reached, findings, member.store(), ecosystem, cut.coordinate(), cut.requirement(),
                            i == 0 ? "" : member.repository(), true, risk, List.of(), "");
                    break;
                }
            }
        }
        List<String> repositories = closure.foreign().isEmpty() ? List.of() : tenant.repositories();
        for (ClosureSection.Foreign foreign : closure.foreign()) {
            examined++;
            for (String repository : repositories) {
                Optional<ArtifactStore> store = tenant.store(repository);
                if (store.isEmpty()) {
                    continue;
                }
                StoreRepositoryInventory inventory = new StoreRepositoryInventory(store.get());
                boolean holds = inventory.publishedAt(foreign.ecosystem(), foreign.coordinate(), foreign.version())
                        .isPresent() || inventory.cachedAt(foreign.ecosystem(), foreign.coordinate(),
                        foreign.version()).isPresent();
                boolean held = holds ? !inventory.disclosable(foreign.ecosystem(), foreign.coordinate(),
                        foreign.version(), ServableNames.Policy.HIDE_WITHHELD)
                        : HeldVersions.held(store.get(), foreign.ecosystem(), foreign.coordinate(), foreign.version());
                if (holds || held) {
                    add(reached, findings, store.get(), foreign.ecosystem(), foreign.coordinate(), foreign.version(),
                            repository.equals(own) ? "" : repository, held, risk, ClosureSection.foreignPath(closure,
                                    foreign.ecosystem(), foreign.coordinate(), foreign.version()),
                            foreign.ecosystem());
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

    /** Add the version, reached along {@code path}, to {@code reached} where it is held or carries findings at or
     *  above {@code risk}, {@code foreign} naming its ecosystem where that is not the closure's own. */
    private static void add(List<ExposureSection.Reached> reached, Optional<FindingsProvider> findings,
                            ArtifactStore holder, String ecosystem, String coordinate, String version,
                            String repository, boolean held, Severity risk, List<ClosureSection.Hop> path,
                            String foreign) throws IOException {
        int count = 0;
        Severity worst = null;
        Optional<Findings> ledger = findings.map(provider -> provider.over(holder));
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
                    worst == null ? "" : worst.name(), path, foreign));
        }
    }
}
