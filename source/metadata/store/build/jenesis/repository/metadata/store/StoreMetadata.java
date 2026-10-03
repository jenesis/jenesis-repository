package build.jenesis.repository.metadata.store;

import module java.base;
import build.jenesis.repository.metadata.MetadataDocument;
import build.jenesis.repository.metadata.DocumentTurns;
import build.jenesis.repository.metadata.MetadataKey;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.metadata.SectionMutation;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Retries;

/**
 * The metadata store over one repository's scoped store. Each coordinate version's metadata is one JSON document at
 * {@link MetadataKey#version}, so a version's record is a point lookup. A mutation re-reads, transforms only the
 * sections it names, carries every other section through verbatim and commits through the store's compare-and-set
 * under {@link Retries}, so writers of disjoint sections converge on the union instead of losing an update, and a
 * batch of sections commits in one cycle. This node's writers of one document land together ({@link DocumentTurns}).
 *
 * <p>The read is total - a torn or foreign object reads as an empty document - and the format guard is loud: mutating
 * a document a newer node wrote (a higher {@code format}) fails rather than rewriting it in the older shape. Document
 * size and retries are reported through {@link MetadataMetrics}.
 */
public final class StoreMetadata implements MetadataStore {


    private final ArtifactStore store;
    private final MetadataMetrics metrics;

    public StoreMetadata(ArtifactStore store) {
        this(store, MetadataMetrics.SHARED);
    }

    /** Binds the store to its own {@link MetadataMetrics} rather than {@link MetadataMetrics#SHARED}, for a test
     *  that asserts on the counts. */
    public StoreMetadata(ArtifactStore store, MetadataMetrics metrics) {
        this.store = store;
        this.metrics = metrics;
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

    /** The read-transform-compare-and-set loop the version and the {@link MetadataKey#COORDINATE} documents share. */
    private void mutateKey(String key, SequencedMap<String, SectionMutation> mutations) throws IOException {
        int[] asked = new int[1];
        try {
            DocumentTurns.decide(store, key, current -> {
                if (asked[0]++ > 0) {
                    metrics.recordRetry();                      // asked again: the previous write lost its token
                }
                MetadataDocument document = current.map(versioned -> MetadataDocument.read(versioned.content()))
                        .orElseGet(MetadataDocument::empty);
                // A newer node's format makes mutate throw; sections not named ride the commit verbatim.
                byte[] serialized = document.mutate(mutations).serialize();
                metrics.observeDocument(key, serialized.length);
                return Retries.Verdict.write(serialized, null);
            });
        } catch (IOException lost) {
            metrics.recordExhausted();
            throw lost;
        }
        metrics.recordMutation();
    }
}
