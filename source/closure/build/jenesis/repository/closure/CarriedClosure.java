package build.jenesis.repository.closure;

import module java.base;
import build.jenesis.repository.closure.spi.ClosureSection;
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
     * One package a carried document names: where it can be placed, its ecosystem, coordinate and version at
     * {@code depth} from the root, and the package {@code viaCoordinate} at {@code viaVersion} whose dependencies named
     * it - none where the root named it itself; where it cannot, {@code unplaced} says why and the rest is what the
     * document wrote.
     */
    public record Entry(String ecosystem, String coordinate, String version, int depth, String viaCoordinate,
                        String viaVersion, String unplaced) {

        /** A package the root names itself, placed at {@code depth}. */
        public static Entry placed(String ecosystem, String coordinate, String version, int depth) {
            return new Entry(ecosystem, coordinate, version, depth, "", "", null);
        }

        /** A package placed at {@code depth}, named by the dependencies of {@code viaCoordinate} at
         *  {@code viaVersion}. */
        public static Entry placed(String ecosystem, String coordinate, String version, int depth,
                                   String viaCoordinate, String viaVersion) {
            return new Entry(ecosystem, coordinate, version, depth, viaCoordinate, viaVersion, null);
        }

        /** A package named in a form no repository can place, for {@code reason}. */
        public static Entry unplaced(String coordinate, String version, String reason) {
            return new Entry(null, coordinate, version == null ? "" : version, 0, "", "", reason);
        }
    }

    /**
     * {@code entries}, which the release {@code coordinate} at {@code version} of {@code ecosystem} carries in what
     * {@code document} names ("the version's bill", "the version's lock file"), placed in {@code walk}'s repositories
     * as of {@code now} and attributed to {@code source}, under its own kind and name. A package named twice at one
     * version is placed once, where it is first named; at two versions it is placed twice, since a carried document
     * records what its build installed, and an npm or Cargo build installs two versions of one package side by side.
     */
    public static ClosureSection.Closure place(ClosureWalk walk, String ecosystem, String coordinate, String version,
                                               List<Entry> entries, String document, ClosureSource source,
                                               Instant now)
            throws IOException {
        List<ClosureWalk.Member> members = walk.members();
        List<Holdings> holdings = members.stream().map(member -> Holdings.of(member.store())).toList();
        List<ClosureSection.Component> components = new ArrayList<>();
        List<ClosureSection.Cut> cuts = new ArrayList<>();
        List<ClosureSection.Foreign> foreign = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(ecosystem + "@" + coordinate + "@" + version);
        boolean truncated = false;
        for (Entry entry : entries) {
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
                        entry.depth(), entry.viaCoordinate(), entry.viaVersion()));
                continue;
            }
            Optional<Holdings.Placed> placed = Holdings.place(holdings, ecosystem, entry.coordinate(),
                    entry.version());
            if (placed.isEmpty()) {
                cuts.add(new ClosureSection.Cut(entry.coordinate(), entry.version(), members.size() == 1
                        ? "named by " + document + ", not held by this repository"
                        : "named by " + document + ", not held by this repository or a repository its fallbacks "
                                + "name"));
            } else if (!placed.get().standing().served()) {
                cuts.add(new ClosureSection.Cut(entry.coordinate(), entry.version(), "held for review"));
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
