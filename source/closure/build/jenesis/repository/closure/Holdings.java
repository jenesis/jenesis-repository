package build.jenesis.repository.closure;

import module java.base;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.HeldVersions;
import build.jenesis.repository.store.ServableNames;

/**
 * How one repository holds a version, the one answer every reader of a closure places a package by: as a release, as a
 * cached copy, or as a proxied copy the screen held at its fill and so no holding yet - and whether what it holds is
 * served. A holding is served unless it is withheld; a copy held at its fill never is. Point reads of the repository's
 * inventory only: the release and cached-copy pointers, the withheld marker, and the hold's subject and live review
 * pointer, in that order and only as far as the answer needs.
 */
final class Holdings {

    /** What a repository keeps of a version. */
    enum Form {
        /** A version published to it. */
        RELEASE,
        /** A copy it cached from an upstream. */
        CACHED,
        /** A proxied copy the screen held at its fill, kept for review rather than as a holding. */
        FILL
    }

    /** How a version stands in a repository: what it keeps of it, and whether that is served. */
    record Standing(Form form, boolean served) {

        /** Whether what is kept is a copy rather than a release. */
        boolean cached() {
            return form != Form.RELEASE;
        }
    }

    /** The first repository of a walk a version stands in, by its index in the walk, and how. */
    record Placed(int member, Standing standing) {
    }

    private final ArtifactStore store;

    private final StoreRepositoryInventory inventory;

    private Holdings(ArtifactStore store) {
        this.store = store;
        this.inventory = new StoreRepositoryInventory(store);
    }

    /** The holdings of the repository {@code store} is scoped to. */
    static Holdings of(ArtifactStore store) {
        return new Holdings(store);
    }

    /** Whether {@code version} of {@code coordinate} is withheld here - a holding the screen or a reviewer holds. */
    boolean withheld(String ecosystem, String coordinate, String version) throws IOException {
        return !inventory.disclosable(ecosystem, coordinate, version, ServableNames.Policy.HIDE_WITHHELD);
    }

    /** How this repository holds {@code version} of {@code coordinate} as a release or a cached copy, or empty where
     *  it holds neither. */
    Optional<Standing> holding(String ecosystem, String coordinate, String version) throws IOException {
        Form form;
        if (inventory.publishedAt(ecosystem, coordinate, version).isPresent()) {
            form = Form.RELEASE;
        } else if (inventory.cachedAt(ecosystem, coordinate, version).isPresent()) {
            form = Form.CACHED;
        } else {
            return Optional.empty();
        }
        return Optional.of(new Standing(form, !withheld(ecosystem, coordinate, version)));
    }

    /** How this repository holds {@code version} of {@code coordinate}: its holding, else the copy held at its fill,
     *  else empty. */
    Optional<Standing> standing(String ecosystem, String coordinate, String version) throws IOException {
        Optional<Standing> holding = holding(ecosystem, coordinate, version);
        if (holding.isPresent()) {
            return holding;
        }
        return heldAtFill(ecosystem, coordinate, version) ? Optional.of(new Standing(Form.FILL, false))
                : Optional.empty();
    }

    /** Where in {@code walk}, the holdings of a walk's repositories in its order, {@code version} of
     *  {@code coordinate} stands: the first repository holding it as a release or a cached copy, else the first
     *  holding it at its fill - a holding further along the walk being what a client is served before a copy kept for
     *  review. */
    static Optional<Placed> place(List<Holdings> walk, String ecosystem, String coordinate, String version)
            throws IOException {
        for (int i = 0; i < walk.size(); i++) {
            Optional<Standing> holding = walk.get(i).holding(ecosystem, coordinate, version);
            if (holding.isPresent()) {
                return Optional.of(new Placed(i, holding.get()));
            }
        }
        for (int i = 0; i < walk.size(); i++) {
            if (walk.get(i).heldAtFill(ecosystem, coordinate, version)) {
                return Optional.of(new Placed(i, new Standing(Form.FILL, false)));
            }
        }
        return Optional.empty();
    }

    private boolean heldAtFill(String ecosystem, String coordinate, String version) throws IOException {
        return HeldVersions.held(store, ecosystem, coordinate, version);
    }
}
