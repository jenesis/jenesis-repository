package build.jenesis.repository.closure;

import module java.base;
import build.jenesis.repository.closure.spi.ClosureSection;
import build.jenesis.repository.compliance.PackageUrls;
import build.jenesis.repository.closure.spi.ClosureSource;
import build.jenesis.repository.closure.spi.ClosureWalk;

/**
 * A closure a release carries written out - a bill of materials, a lock file - placed in the repositories of a walk:
 * each package it names the holding the walk keeps of it, a release or a cached copy, at the distance the document
 * records; one the walk does not hold, or holds for review, a cut saying so. A package named in another ecosystem - a
 * jar or a distribution package inside an image - is no repository of the walk's to hold, and is recorded as a
 * {@link ClosureSection.Foreign} entry, indexed by its coordinate across the tenant. What the sources that read such a
 * document share, so a bill and a lock file read the same.
 *
 * <p>Bounded at {@link ClosureSource#MAX_COMPONENTS} entries, components and foreign entries together, a closure
 * stopped there saying so. Nothing is fetched: a lookup is a point read of each repository's inventory, in the walk's
 * order.
 */
public final class CarriedClosure {

    private CarriedClosure() {
    }

    /**
     * The closure {@code source} reads out of what {@code coordinate} at {@code version} of {@code ecosystem} carries,
     * placed in {@code walk}'s repositories as of {@code now} and attributed to {@code source}; empty where it carries
     * nothing {@code source} reads. The release is the first repository of the walk's, and the source is handed its
     * store alone.
     */
    public static Optional<ClosureSection.Closure> resolve(ClosureSource.Carried source, ClosureWalk walk,
                                                           String ecosystem, String coordinate, String version,
                                                           Instant now) throws IOException {
        Optional<ClosureSource.Carriage> carriage = source.read(walk.members().getFirst().store(), ecosystem,
                coordinate, version);
        if (carriage.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(place(walk, ecosystem, coordinate, version, carriage.get(), source, now));
    }

    /**
     * {@code carriage}, what the release {@code coordinate} at {@code version} of {@code ecosystem} carries, placed in
     * {@code walk}'s repositories as of {@code now} and attributed to {@code source}, under its own kind and name. A package named twice at one
     * version is placed once, where it is first named; at two versions it is placed twice, since a carried document
     * records what its build installed, and an npm or Cargo build installs two versions of one package side by side.
     */
    static ClosureSection.Closure place(ClosureWalk walk, String ecosystem, String coordinate, String version,
                                        ClosureSource.Carriage carriage, ClosureSource source, Instant now)
            throws IOException {
        String document = carriage.document();
        List<ClosureWalk.Member> members = walk.members();
        List<Holdings> holdings = members.stream().map(member -> Holdings.of(member.store())).toList();
        List<ClosureSection.Component> components = new ArrayList<>();
        List<ClosureSection.Cut> cuts = new ArrayList<>();
        List<ClosureSection.Foreign> foreign = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(ecosystem + "@" + coordinate + "@" + version);
        boolean truncated = false;
        for (ClosureSource.Entry entry : carriage.entries()) {
            if (entry.unplaced() != null) {
                cuts.add(new ClosureSection.Cut(entry.coordinate(), entry.version(), entry.unplaced()));
                continue;
            }
            if (!seen.add(entry.ecosystem() + "@" + entry.coordinate() + "@" + entry.version())) {
                continue;
            }
            if (components.size() + foreign.size() >= ClosureSource.MAX_COMPONENTS) {
                truncated = true;
                break;
            }
            if (!ecosystem.equals(entry.ecosystem())) {
                foreign.add(new ClosureSection.Foreign(entry.ecosystem(), entry.coordinate(), entry.version(),
                        entry.depth(), entry.viaCoordinate(), entry.viaVersion(), entry.purl(),
                        PackageUrls.qualifiers(entry.purl())));
                continue;
            }
            Optional<Holdings.Placed> placed = Holdings.place(holdings, ecosystem, entry.coordinate(),
                    entry.version());
            if (placed.isEmpty()) {
                cuts.add(new ClosureSection.Cut(entry.coordinate(), entry.version(),
                        "named by " + document + ", " + ClosureSection.Cut.notHeld(members.size() == 1)));
            } else if (!placed.get().standing().served()) {
                cuts.add(new ClosureSection.Cut(entry.coordinate(), entry.version(),
                        ClosureSection.Cut.HELD_FOR_REVIEW));
            } else {
                int member = placed.get().member();
                components.add(new ClosureSection.Component(entry.coordinate(), entry.version(),
                        placed.get().standing().cached(), entry.depth(),
                        member == 0 ? "" : members.get(member).repository(), entry.viaCoordinate(),
                        entry.viaVersion()));
            }
        }
        return new ClosureSection.Closure(cuts.isEmpty() && !truncated ? ClosureSection.Status.RESOLVED
                : ClosureSection.Status.PARTIAL, components, cuts, truncated, now, source.kind(), source.name(),
                foreign);
    }
}
