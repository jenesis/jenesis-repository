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
 * The consolidated metadata store over one repository's scoped store. Each coordinate version's metadata lives in
 * one JSON document at {@link MetadataKey#version} - a version's whole record is a point lookup, and a mutation
 * re-reads, transforms only the sections it owns and commits through the store's compare-and-set with a bounded
 * retry, so disjoint-section writers (a publish, an advisory sweep, an AI labeler) converge on the union of their
 * sections instead of losing an update. This generalises {@code StoreFindings.mutate}/{@code Document} to the
 * section level: the mutation sees only the sections it names, every other section is carried through the CAS
 * commit verbatim (§5.2), and a whole batch of sections commits in one CAS cycle (§4a).
 *
 * <p>The retry bound is 5 (raised from the findings ledger's 3, §5.2), since one CAS token now covers every
 * subsystem writing the coordinate. The read is total - a torn or foreign object reads as an empty document,
 * never throwing - and the format guard is loud: a mutation of a document a newer node wrote (a higher
 * {@code format}) fails rather than downgrade-rewriting the envelope. Doc size and CAS-retry counts are reported
 * through {@link MetadataMetrics} (registry-free), including the §7.3 soft-size WARNING for a pathologically large
 * document.
 */
public final class StoreMetadata implements MetadataStore {


    private final ArtifactStore store;
    private final MetadataMetrics metrics;

    public StoreMetadata(ArtifactStore store) {
        this(store, MetadataMetrics.SHARED);
    }

    /** Bind the store to a caller-supplied {@link MetadataMetrics} rather than the process-wide
     *  {@link MetadataMetrics#SHARED} sink - for a distribution that wants a scoped accumulator, or a test that
     *  asserts on the counts deterministically. */
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

    /** The one read-transform-compare-and-set loop both document scopes share: the {@link MetadataKey#version} and the
     *  {@link MetadataKey#COORDINATE} documents differ only in their key, never in their reader-tolerance, format guard
     *  or bounded-retry mutate. */
    private void mutateKey(String key, SequencedMap<String, SectionMutation> mutations) throws IOException {
        DocumentTurns.take(store, key, () -> {
            mutateTurn(key, mutations);
            return null;
        });
    }

    private void mutateTurn(String key, SequencedMap<String, SectionMutation> mutations) throws IOException {
        int[] asked = new int[1];
        try {
            Retries.update(store, key, current -> {
                if (asked[0]++ > 0) {
                    metrics.recordRetry();                      // asked again: the previous write lost its token
                }
                MetadataDocument document = current.map(versioned -> MetadataDocument.read(versioned.content()))
                        .orElseGet(MetadataDocument::empty);
                // A newer node's format makes doc.mutate throw loudly - never a silent downgrade-rewrite. Every other
                // section this node does not name rides the CAS commit verbatim, so a batch of section transforms
                // lands as one atomic document write against one token.
                byte[] serialized = document.mutate(mutations).serialize();
                metrics.observeDocument(key, serialized.length);
                return serialized;
            });
        } catch (IOException lost) {
            metrics.recordExhausted();
            throw lost;
        }
        metrics.recordMutation();
    }
}
