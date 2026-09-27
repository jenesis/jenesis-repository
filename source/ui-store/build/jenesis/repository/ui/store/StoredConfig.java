package build.jenesis.repository.ui.store;

import module java.base;
import build.jenesis.repository.settings.SettingsDocuments;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The console's reads of the runtime settings documents, one JSON document per contributing module
 * ({@code config/settings/<module>.json}, {@link SettingsDocuments}) - the layout the repository server's
 * {@code Settings} reads and writes. A read merges every module's document into one view. Nothing here writes: every
 * change goes through the one settings editor ({@code SettingsEditor}).
 */
final class StoredConfig {


    private StoredConfig() {
    }

    /** Every stored override, merged across all module documents, as a properties view. */
    static Properties load(ArtifactStore root) throws IOException {
        Properties merged = new Properties();
        for (String child : root.list(SettingsDocuments.ROOT)) {
            if (!child.endsWith(".json")) {
                continue;
            }
            Optional<ArtifactStore.Versioned> object = root.readVersioned(SettingsDocuments.ROOT + "/" + child);
            if (object.isPresent()) {
                SettingsDocuments.parse(object.get().content()).forEach(merged::setProperty);
            }
        }
        return merged;
    }

    /** A tenant's own stored overrides, merged across its module documents under {@code <tenant>/config/settings} - the
     *  tenant layer a tenant admin edits, over the deployment-wide effective values. */
    static Properties load(ArtifactStore root, String tenant) throws IOException {
        return load(root.scope(tenant));
    }

    /** Every stored settings document, module name to that module's stored overrides, read straight from the store -
     *  the on-store shape the console's settings export dumps as one JSON bundle. Sorted (documents and keys) so an
     *  export of unchanged state is byte-identical; an empty document is omitted. */
    static SortedMap<String, SortedMap<String, String>> documents(ArtifactStore root) throws IOException {
        SortedMap<String, SortedMap<String, String>> documents = new TreeMap<>();
        for (String child : root.list(SettingsDocuments.ROOT)) {
            if (!child.endsWith(".json")) {
                continue;
            }
            Optional<ArtifactStore.Versioned> object = root.readVersioned(SettingsDocuments.ROOT + "/" + child);
            if (object.isEmpty()) {
                continue;
            }
            SortedMap<String, String> values = new TreeMap<>(SettingsDocuments.parse(object.get().content()));
            if (!values.isEmpty()) {
                documents.put(child.substring(0, child.length() - ".json".length()), values);
            }
        }
        return documents;
    }
}
