package build.jenesis.repository.gateway.contract.test;

import module java.base;
import build.jenesis.repository.metadata.MetadataDocument;
import build.jenesis.repository.metadata.MetadataKey;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.metadata.SectionMutation;
import build.jenesis.repository.store.ArtifactStore;

/**
 * An in-test {@link MetadataStore} over a real filesystem artifact store, using the real {@link MetadataDocument}
 * envelope and {@link MetadataKey} codec, so the hardened leg's digest-pinned verdict genuinely round-trips through
 * the consolidated-metadata read/section-scoped-CAS-mutate contract. It is deliberately injected into the screen
 * directly rather than discovered through {@code MetadataProvider}: putting the persistence module on the gateway
 * test path would install the provider for <em>every</em> gateway test and flip the inventory tests off their
 * sidecar path, so the verdict test carries its own store instead. Mirrors {@code StoreMetadata}'s read-transform-CAS
 * loop; it is not the production store, which {@code metadata.store.test} covers.
 */
final class TestMetadataStore implements MetadataStore {

    private static final int ATTEMPTS = 5;

    private final ArtifactStore store;

    TestMetadataStore(ArtifactStore store) {
        this.store = store;
    }

    @Override
    public Optional<MetadataDocument> read(String ecosystem, String coordinate, String version) throws IOException {
        return store.readVersioned(MetadataKey.version(ecosystem, coordinate, version))
                .map(versioned -> MetadataDocument.read(versioned.content()));
    }

    @Override
    public Optional<Section> section(String ecosystem, String coordinate, String version, String tag)
            throws IOException {
        return read(ecosystem, coordinate, version).flatMap(document -> document.section(tag));
    }

    @Override
    public void mutate(String ecosystem, String coordinate, String version, String tag, SectionMutation mutation)
            throws IOException {
        SequencedMap<String, SectionMutation> single = new LinkedHashMap<>();
        single.put(tag, mutation);
        mutate(ecosystem, coordinate, version, single);
    }

    @Override
    public void mutate(String ecosystem, String coordinate, String version,
                       SequencedMap<String, SectionMutation> mutations) throws IOException {
        mutateKey(MetadataKey.version(ecosystem, coordinate, version), mutations);
    }

    @Override
    public Optional<MetadataDocument> readCoordinate(String ecosystem, String coordinate) throws IOException {
        return store.readVersioned(MetadataKey.coordinate(ecosystem, coordinate))
                .map(versioned -> MetadataDocument.read(versioned.content()));
    }

    @Override
    public Optional<Section> coordinateSection(String ecosystem, String coordinate, String tag) throws IOException {
        return readCoordinate(ecosystem, coordinate).flatMap(document -> document.section(tag));
    }

    @Override
    public void mutateCoordinate(String ecosystem, String coordinate, String tag, SectionMutation mutation)
            throws IOException {
        SequencedMap<String, SectionMutation> single = new LinkedHashMap<>();
        single.put(tag, mutation);
        mutateKey(MetadataKey.coordinate(ecosystem, coordinate), single);
    }

    private void mutateKey(String key, SequencedMap<String, SectionMutation> mutations) throws IOException {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            Optional<ArtifactStore.Versioned> current = store.readVersioned(key);
            MetadataDocument document = current.map(versioned -> MetadataDocument.read(versioned.content()))
                    .orElseGet(MetadataDocument::empty);
            byte[] serialized = document.mutate(mutations).serialize();
            if (store.writeVersioned(key, serialized, current.map(ArtifactStore.Versioned::token).orElse(null))) {
                return;
            }
        }
        throw new IOException("Lost the metadata write race on " + key + " " + ATTEMPTS + " times");
    }
}
