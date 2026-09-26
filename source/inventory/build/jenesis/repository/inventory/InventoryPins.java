package build.jenesis.repository.inventory;

import module java.base;
import build.jenesis.repository.store.Clocks;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.metadata.MetadataDocument;
import build.jenesis.repository.metadata.MetadataKey;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.metadata.Section;

/**
 * The pin subsystem extracted from {@link StoreRepositoryInventory}: force-keep markers that make a coordinate version
 * immune to every retention rule. A pin is a field of the document's {@code published} section (preserving
 * the publish instant/prerelease), so it evicts with the version's document instead of dangling as its own
 * {@code pinned/} sidecar; with no metadata store installed it stays the legacy {@code pinned/} sidecar. The facade owns
 * the seam - {@code pin}/{@code unpin}/{@code pins}/{@code pinned} delegate here - and this class shares the facade's
 * subtree {@code walk} and its store-key/codec helpers rather than duplicating them.
 */
final class InventoryPins {

    private final StoreRepositoryInventory inventory;
    private final ArtifactStore store;
    private final MetadataStore metadata;

    InventoryPins(StoreRepositoryInventory inventory, ArtifactStore store, MetadataStore metadata) {
        this.inventory = inventory;
        this.store = store;
        this.metadata = metadata;
    }

    /** Pin a coordinate version - mark it force-kept, immune to every retention rule. */
    void pin(String ecosystem, String coordinate, String version) throws IOException {
        if (metadata != null) {
            metadata.mutate(ecosystem, coordinate, version, PublishedSection.TAG,
                    PublishedSection.pinned(true, Clocks.now()));
        }
        // The pinned/ marker is written in both modes: the consolidated document is the truth the retention rule
        // reads per release, but the set of pins is enumerated from this small namespace, never by walking every
        // document to read one flag out of each.
        inventory.writeVersioned(StoreRepositoryInventory.pinnedKey(ecosystem, coordinate, version), new byte[]{'1'});
    }

    /** Remove a coordinate version's pin, returning it to the retention rules. */
    void unpin(String ecosystem, String coordinate, String version) throws IOException {
        if (metadata != null && metadata.section(ecosystem, coordinate, version, PublishedSection.TAG).isPresent()) {
            metadata.mutate(ecosystem, coordinate, version, PublishedSection.TAG,
                    PublishedSection.pinned(false, Clocks.now()));
        }
        String key = StoreRepositoryInventory.pinnedKey(ecosystem, coordinate, version);
        if (store.readVersioned(key).isPresent()) {
            store.delete(key);
        }
    }

    /** The pinned coordinate versions, as {@code ecosystem:coordinate:version}. */
    List<String> pins() {
        List<String> pins = new ArrayList<>();
        for (StoreRepositoryInventory.Pin pin : pinned()) {
            pins.add(pin.ecosystem() + ":" + pin.coordinate() + ":" + pin.version());
        }
        return pins;
    }

    /** The pinned coordinate versions, decoded into their parts. */
    List<StoreRepositoryInventory.Pin> pinned() {
        List<StoreRepositoryInventory.Pin> pins = new ArrayList<>();
        try {
            // The marker namespace holds one key per pin in either mode (the reconcile pass backfills a pin that
            // predates the marker), so this is a walk of the pins, never of the published set.
            inventory.walk(StoreRepositoryInventory.PINNED, key -> {
                String[] segments = key.substring(StoreRepositoryInventory.PINNED.length() + 1).split("/");
                if (segments.length == 3) {
                    pins.add(new StoreRepositoryInventory.Pin(
                            segments[0], StoreRepositoryInventory.decode(segments[1]), segments[2]));
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return pins;
    }
}
