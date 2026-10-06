package build.jenesis.repository.closure;

import module java.base;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.HeldVersions;
import build.jenesis.repository.store.ServableNames;

/**
 * A closure a release carries written out - a bill of materials, a lock file - placed in the repositories of a walk:
 * each package it names the holding the walk keeps of it, a release or a cached copy, at the distance the document
 * records; one the walk does not hold, holds for review, or that is named in another ecosystem, a cut saying so. What
 * the sources that read such a document share, so a bill and a lock file read the same.
 *
 * <p>Bounded at {@link ClosureResolver#MAX_COMPONENTS} components, a closure stopped there saying so. Nothing is
 * fetched: a lookup is a point read of each repository's inventory, in the walk's order.
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
     * as of {@code now} and attributed to {@code source}. A package named twice at one version is placed once, where it is first named; at
     * two versions it is placed twice, since a carried document records what its build installed, and an npm or Cargo
     * build installs two versions of one package side by side.
     */
    public static ClosureSection.Closure place(ClosureWalk walk, String ecosystem, String coordinate, String version,
                                               List<Entry> entries, String document, String source, Instant now)
            throws IOException {
        List<Holder> holders = walk.members().stream().map(Holder::new).toList();
        List<ClosureSection.Component> components = new ArrayList<>();
        List<ClosureSection.Cut> cuts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(coordinate + "@" + version);
        boolean truncated = false;
        for (Entry entry : entries) {
            if (entry.unplaced() != null) {
                cuts.add(new ClosureSection.Cut(entry.coordinate(), entry.version(), entry.unplaced()));
                continue;
            }
            if (!seen.add(entry.coordinate() + "@" + entry.version())) {
                continue;
            }
            if (!ecosystem.equals(entry.ecosystem())) {
                cuts.add(new ClosureSection.Cut(entry.coordinate(), entry.version(),
                        "named by " + document + " in " + entry.ecosystem() + ", another ecosystem"));
                continue;
            }
            if (components.size() >= ClosureResolver.MAX_COMPONENTS) {
                truncated = true;
                break;
            }
            Optional<Held> held = held(holders, ecosystem, entry.coordinate(), entry.version());
            if (held.isEmpty()) {
                cuts.add(new ClosureSection.Cut(entry.coordinate(), entry.version(), holders.size() == 1
                        ? "named by " + document + ", not held by this repository"
                        : "named by " + document + ", not held by this repository or a repository its fallbacks "
                                + "name"));
            } else if (!held.get().served()) {
                cuts.add(new ClosureSection.Cut(entry.coordinate(), entry.version(), "held for review"));
            } else {
                components.add(new ClosureSection.Component(entry.coordinate(), entry.version(), held.get().cached(),
                        entry.depth(), held.get().repository(), entry.viaCoordinate(), entry.viaVersion()));
            }
        }
        return new ClosureSection.Closure(cuts.isEmpty() && !truncated ? ClosureSection.Status.RESOLVED
                : ClosureSection.Status.PARTIAL, components, cuts, truncated, now, ClosureSource.Kind.BILL, source);
    }

    /** One repository of the walk, as a carried document's packages are looked up in it. */
    private record Holder(String repository, ArtifactStore store, StoreRepositoryInventory inventory) {

        Holder(ClosureWalk.Member member) {
            this(member.repository(), member.store(), new StoreRepositoryInventory(member.store()));
        }

        /** Whether this repository holds {@code version} of {@code coordinate} for review as no holding yet: a
         *  proxied copy the screen held at its fill, found through its hold's subject and live review pointer. */
        boolean heldAtFill(String ecosystem, String coordinate, String version) throws IOException {
            return HeldVersions.held(store, ecosystem, coordinate, version);
        }
    }

    /** Where a package is held: the repository, as a release or a cached copy, and whether it is served. */
    private record Held(String repository, boolean cached, boolean served) {
    }

    /** The first repository of the walk holding {@code coordinate} at {@code version}, as a release or a cached copy,
     *  else the first holding it for review since its fill. */
    private static Optional<Held> held(List<Holder> holders, String ecosystem, String coordinate, String version)
            throws IOException {
        for (int i = 0; i < holders.size(); i++) {
            Holder holder = holders.get(i);
            boolean released = holder.inventory().publishedAt(ecosystem, coordinate, version).isPresent();
            boolean cached = !released && holder.inventory().cachedAt(ecosystem, coordinate, version).isPresent();
            if (released || cached) {
                return Optional.of(new Held(i == 0 ? "" : holder.repository(), cached,
                        holder.inventory().disclosable(ecosystem, coordinate, version,
                                ServableNames.Policy.HIDE_WITHHELD)));
            }
        }
        for (int i = 0; i < holders.size(); i++) {
            if (holders.get(i).heldAtFill(ecosystem, coordinate, version)) {
                return Optional.of(new Held(i == 0 ? "" : holders.get(i).repository(), true, false));
            }
        }
        return Optional.empty();
    }
}
