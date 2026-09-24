package build.jenesis.repository.gate.store;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.walk.WalkConsumer;
import build.jenesis.repository.walk.WalkPass;

/**
 * The repair of the stored listings - the packuments, Simple pages, Packages files, repodata, sparse-index files,
 * tag lists and search documents a client fetches - at the end of a walk: every listing document of the repository
 * the pass walked is regenerated from the store through the format that owns it, so any drift an interrupted
 * write could have left is corrected. A listing is on demand otherwise: materialised on first read, maintained by
 * every write that changes it, so this repair is the only time one is regenerated that nothing asked for, and it
 * rides the walk instead of a daily pass of its own. Listens on the pointer stream only to be told which store's
 * pass it is riding.
 */
public final class ListingRebuildConsumer implements WalkConsumer {

    /** The consumer's name: its toggle ({@code jenreg.listing-rebuild}), its scenario, its settings row. */
    public static final String NAME = "listing-rebuild";

    private static final Logger LOGGER = LoggerFactory.getLogger(ListingRebuildConsumer.class);

    private final List<StoredListing.Rebuilder> rebuilders;
    private final Set<Object> riding = ConcurrentHashMap.newKeySet();

    public ListingRebuildConsumer() {
        this.rebuilders = StoredListing.Rebuilder.installed();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Regenerates every stored listing - packuments, Simple pages, Packages files, tag lists - in place at the end "
                + "of the walk; reads every pointer of every format that keeps one.";
    }

    @Override
    public void onRetained(ArtifactDescriptor artifact, ArtifactStore store) {
        riding.add(store.identity());
    }

    @Override
    public void onPassStarted(WalkPass pass, ArtifactStore store) {
        riding.add(store.identity());
    }

    @Override
    public void onPassCompleted(WalkPass pass, ArtifactStore store) {
        if (!riding.remove(store.identity()) || rebuilders.isEmpty()) {
            return;
        }
        try {
            int rebuilt = StoredListing.rebuildAll(store, rebuilders);
            if (rebuilt > 0) {
                LOGGER.info("listing-rebuild regenerated {} stored listing(s) after the walk", rebuilt);
            }
        } catch (IOException unrebuilt) {
            throw new UncheckedIOException(unrebuilt);
        }
    }
}
