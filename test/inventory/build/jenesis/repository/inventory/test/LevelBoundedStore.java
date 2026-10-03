package build.jenesis.repository.inventory.test;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ForwardingArtifactStore;

/**
 * An {@link ArtifactStore} decorator that <b>refuses to materialise a wide level</b>: {@link #list} throws once a
 * container holds more than {@link #MAX_LEVEL} immediate children, while {@link #page} pages it exactly as the
 * delegate does. Everything else delegates unchanged.
 *
 * <p>It is not an artificial cruelty - it is the free store SPI's own documented behaviour made observable. The
 * inherited {@code ArtifactStore.page} fallback ({@code pageByListing}) refuses past
 * {@code MAX_INHERITED_CHILDREN} rather than "quietly turning one page request into an unbounded heap allocation",
 * and this decorator holds a whole-level {@code list()} to the same standard from the other side: a level that is
 * too wide to hold in heap is a level a traversal must page, not list.
 *
 * <p>So it is the fixture that makes the defect fail rather than merely cost memory. A sweep that descends a
 * subtree by calling {@code list(level)} once per level is refused here by name; a sweep that drives the shared
 * bounded tree walk ({@code PagedTreeWalk}, which consumes the store exclusively through {@code page}) is not. A test
 * that seeds a container past {@link #MAX_LEVEL} therefore fails <em>before</em> the migration and passes after it.
 */
final class LevelBoundedStore extends ForwardingArtifactStore {
    /** The widest level this store will hand back as one {@code List}. Small on purpose: a test seeds just past it. */
    static final int MAX_LEVEL = 8;

    LevelBoundedStore(ArtifactStore delegate) {
        super(delegate);
    }

    @Override
    public List<String> list(String prefix) {
        List<String> children = delegate.list(prefix);
        if (children.size() > MAX_LEVEL) {
            throw new IllegalStateException("Refusing to materialise the " + children.size() + " children of '"
                    + prefix + "' as one list: a level wider than " + MAX_LEVEL + " must be paged through "
                    + "ArtifactStore.page, not listed whole");
        }
        return children;
    }

    @Override
    public ArtifactStore scope(String tenant) {
        return new LevelBoundedStore(delegate.scope(tenant));
    }
}
