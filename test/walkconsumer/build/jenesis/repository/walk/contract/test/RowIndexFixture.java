package build.jenesis.repository.walk.contract.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.walk.testkit.WalkConsumerFixture;

import module java.base;

/**
 * The shared half of the two row-per-artifact archetypes: both hold one small object per retained pointer, keyed by
 * the path they were handed, so both declare the same projection - the path-to-hash map read straight back out of
 * their own key space. What differs is <em>when</em> those rows become durable, which is exactly the
 * {@link WalkConsumerFixture.Delivery} class each subclass declares, and the only thing the kit treats them
 * differently on.
 *
 * <p>Reading the rows back rather than remembering what was written is deliberate: the projection has to be a
 * statement about the store, or a consumer that never wrote anything would converge by agreeing with itself.
 */
abstract class RowIndexFixture implements WalkConsumerFixture {

    /** The key prefix this consumer's rows live under. */
    abstract String space();

    @Override
    public List<String> pointerRoots() {
        return List.of(KitCorpus.ROOT);
    }

    @Override
    public List<String> namespaces() {
        return List.of(space());
    }

    @Override
    public Corpus seed(ArtifactStore store, int artifacts) throws IOException {
        return KitCorpus.seed(store, artifacts);
    }

    @Override
    public Map<String, String> projection(ArtifactStore store) throws IOException {
        Map<String, String> rows = new HashMap<>();
        for (String row : store.list(space())) {
            rows.put(KitCorpus.decode(row), KitCorpus.text(store, space() + "/" + row));
        }
        return rows;
    }
}
