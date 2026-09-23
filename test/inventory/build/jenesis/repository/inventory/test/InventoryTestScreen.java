package build.jenesis.repository.inventory.test;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublishInterceptor;

/**
 * A minimal discovered publication screen for the inventory suite - the store-truth read side of a screening
 * screen in miniature, so the facade's own {@code Publication} (which the servable-name seam composes)
 * has a real withheld chain to consult. A request path is withheld exactly when a {@code publish/quarantine<path>}
 * review pointer exists for it (the review-pointer convention every hold writer uses), so a test can withhold a
 * {@code publish/}-namespace leaf by linking that pointer and see the screened {@code children} page and the
 * {@code state}-based disclosure faces omit it. Inert otherwise: it withholds nothing when no quarantine pointer is
 * present, so it does not gate any other inventory test. Provided as a {@code PublicationObserver} (a
 * {@link PublishInterceptor} is one), the single {@code uses} clause the free store SPI discovers both through.
 */
public final class InventoryTestScreen implements PublishInterceptor {

    @Override
    public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) {
        // no after-commit work; this screen only reads the quarantine-pointer withhold state
    }

    @Override
    public boolean withheld(String path, ArtifactStore store) throws IOException {
        return store.readVersioned("publish/quarantine" + path).isPresent();
    }
}
