package build.jenesis.repository.metadata.store;

import module java.base;
import module tools.jackson.databind;
import build.jenesis.repository.blobs.VersionFiles;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.metadata.Signal;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Clocks;

/**
 * {@link VersionFiles} kept where everything else about a version is: a {@code files} section of the version's own
 * metadata document, {@code {"keys":[<pointer key>, ...]}} in key order, unioned under the document's
 * compare-and-set, so the record goes with the version - eviction and reconcile remove it with the document.
 */
public final class StoreVersionFiles implements VersionFiles {

    /** The section tag. */
    public static final String TAG = "files";

    private static final int SCHEMA = 1;
    private static final String KEYS = "keys";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public void record(ArtifactStore store, String ecosystem, String coordinate, String version, String key)
            throws IOException {
        Objects.requireNonNull(key, "key");
        StoreMetadata metadata = new StoreMetadata(store);
        if (keys(metadata.section(ecosystem, coordinate, version, TAG)).contains(key)) {
            return;   // listed already - a republish of the file, or one the gate refuses, rewrites nothing
        }
        metadata.mutate(ecosystem, coordinate, version, TAG, current -> {
            SortedSet<String> keys = new TreeSet<>(keys(current));
            keys.add(key);
            ObjectNode data = JSON.createObjectNode();
            ArrayNode list = data.putArray(KEYS);
            keys.forEach(list::add);
            return Section.derived(TAG, SCHEMA, Clocks.now(), Signal.NEUTRAL, data);
        });
    }

    @Override
    public Optional<List<String>> listed(ArtifactStore store, String ecosystem, String coordinate, String version)
            throws IOException {
        Optional<Section> section = new StoreMetadata(store).section(ecosystem, coordinate, version, TAG);
        return section.isEmpty() ? Optional.empty() : Optional.of(keys(section));
    }

    private static List<String> keys(Optional<Section> section) {
        List<String> keys = new ArrayList<>();
        section.flatMap(Section::payload).ifPresent(data -> data.path(KEYS)
                .forEach(key -> keys.add(key.asString())));
        return List.copyOf(keys);
    }
}
