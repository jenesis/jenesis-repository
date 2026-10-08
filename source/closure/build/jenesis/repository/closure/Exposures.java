package build.jenesis.repository.closure;

import module java.base;
import build.jenesis.repository.format.PackageNaming;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.closure.spi.ClosureSection;
import build.jenesis.repository.closure.spi.ClosureWalk;
import build.jenesis.repository.closure.spi.ExposureSection;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.findings.Finding;
import build.jenesis.repository.findings.Findings;
import build.jenesis.repository.findings.FindingsProvider;
import build.jenesis.repository.store.ArtifactStore;

/**
 * What a published version inherits from its closure: each version it reaches as it stands in the repository of the
 * walk holding it - held for review, and its findings at or above the risk band. A component is looked up where the
 * closure says it is held; a cut naming a version some repository of the walk holds for review is the held copy the
 * closure stopped at, and is looked up there. A package the version's bill names in another ecosystem is looked up in
 * every repository of the tenant that may hold one of its ecosystem, since any copy of it may be the one the build
 * installed: each copy held or carrying findings is reached, named by the repository holding it. Point reads only, a few per version reached - and per
 * repository of the tenant for such a package - so the work is bounded by the closure's own bound; nothing is asked of
 * a feed.
 */
final class Exposures {

    /** The most copies of one package a repository is asked for by name: one per repository a format's coordinate
     *  names it under, which a repository keeps a handful of. */
    private static final int COPIES_BY_NAME = 32;

    private Exposures() {
    }

    /** The tenant's repositories a closure's packages of other ecosystems are looked up in: those that may hold a
     *  package of an ecosystem, asked only of a closure naming such a package, and each one's store. */
    interface Tenant {

        /** No repository beyond the walk's: a closure's packages of other ecosystems are found nowhere. */
        Tenant NONE = new Tenant() {
            @Override
            public List<String> repositories(String ecosystem) {
                return List.of();
            }

            @Override
            public Optional<ArtifactStore> store(String repository) {
                return Optional.empty();
            }
        };

        /** The names of the repositories that may hold a package of {@code ecosystem}: those whose type serves it,
         *  and those whose type cannot be read here, which cannot be ruled out. */
        List<String> repositories(String ecosystem) throws IOException;

        Optional<ArtifactStore> store(String repository);
    }

    /** {@code closure}'s exposure over the repositories of {@code walk}, findings counted from {@code risk} up through
     *  {@code findings} - none where no ledger is installed - and its packages of other ecosystems over
     *  {@code tenant}'s, {@code own} being the name of the repository of the version - the walk's first. */
    static ExposureSection.Exposure derive(ClosureWalk walk, String ecosystem, ClosureSection.Closure closure,
                                           Severity risk, Instant now, String own, Tenant tenant,
                                           Optional<FindingsProvider> findings) throws IOException {
        List<ClosureWalk.Member> members = walk.members();
        List<ExposureSection.Reached> reached = new ArrayList<>();
        ClosureSection.Paths paths = ClosureSection.paths(closure);
        int examined = 0;
        for (ClosureSection.Component component : closure.components()) {
            Optional<ClosureWalk.Member> found = walk.holder(component.repository());
            if (found.isEmpty()) {
                continue;
            }
            ClosureWalk.Member holder = found.get();
            examined++;
            boolean held = Holdings.of(holder.store()).withheld(ecosystem, component.coordinate(),
                    component.version());
            add(reached, findings, holder.store(), ecosystem, component.coordinate(), component.version(),
                    component.repository(), held, risk, paths.path(component.coordinate(),
                            component.version()), "");
        }
        for (ClosureSection.Cut cut : closure.cuts()) {
            for (int i = 0; i < members.size(); i++) {
                ClosureWalk.Member member = members.get(i);
                if (!cut.requirement().isBlank() && Holdings.of(member.store()).standing(ecosystem,
                        cut.coordinate(), cut.requirement()).filter(standing -> !standing.served()).isPresent()) {
                    examined++;
                    add(reached, findings, member.store(), ecosystem, cut.coordinate(), cut.requirement(),
                            i == 0 ? "" : member.repository(), true, risk, List.of(), "");
                    break;
                }
            }
        }
        for (ClosureSection.Foreign foreign : closure.foreign()) {
            examined++;
            // A bill names a package by the name it goes by; where its format qualifies that further in the
            // coordinate - an RPM by the repository it was published into - the copies are found by name.
            boolean renamed = PackageNaming.renames(foreign.ecosystem());
            for (String repository : tenant.repositories(foreign.ecosystem())) {
                Optional<ArtifactStore> store = tenant.store(repository);
                if (store.isEmpty()) {
                    continue;
                }
                List<String> coordinates = renamed ? new StoreRepositoryInventory(store.get())
                        .named(foreign.ecosystem(), foreign.coordinate(), foreign.version(), COPIES_BY_NAME).stream()
                        .map(StoreRepositoryInventory.Coordinate::coordinate).toList()
                        : List.of(foreign.coordinate());
                for (String coordinate : coordinates) {
                    Optional<Holdings.Standing> standing = Holdings.of(store.get()).standing(foreign.ecosystem(),
                            coordinate, foreign.version());
                    if (standing.isPresent()) {
                        add(reached, findings, store.get(), foreign.ecosystem(), coordinate, foreign.version(),
                                repository.equals(own) ? "" : repository, !standing.get().served(), risk,
                                paths.foreign(foreign.ecosystem(), foreign.coordinate(), foreign.version()),
                                foreign.ecosystem());
                    }
                }
            }
        }
        return new ExposureSection.Exposure(reached, examined, now);
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
