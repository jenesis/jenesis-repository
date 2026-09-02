package build.jenesis.repository.walk.contract.test;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.walk.testkit.WalkConsumerFixture;

/**
 * The {@link PassSnapshotConsumer} fixture. Its declared projection is one committed document parsed back into a
 * path-to-hash map - a completely different layout from its two row-per-artifact peers, holding the same view, which
 * is precisely why the kit compares a fixture-declared projection instead of stored bytes.
 *
 * <p>It declares {@link Delivery#PASS_SNAPSHOT}, and the kit therefore holds it to the weaker post-crash claim its
 * commit protocol actually supports: after a resumed pass it must be converged <em>or</em> visibly degraded, and never
 * a fragment published as a whole view. {@link #degradation} is that say-so.
 */
final class PassSnapshotFixture implements WalkConsumerFixture {

    @Override
    public String consumer() {
        return PassSnapshotConsumer.NAME;
    }

    @Override
    public String providerClass() {
        return PassSnapshotConsumer.class.getName();
    }

    @Override
    public List<String> pointerRoots() {
        return List.of(KitCorpus.ROOT);
    }

    @Override
    public List<String> namespaces() {
        return List.of(PassSnapshotConsumer.SPACE);
    }

    @Override
    public Corpus seed(ArtifactStore store, int artifacts) throws IOException {
        return KitCorpus.seed(store, artifacts);
    }

    @Override
    public Map<String, String> projection(ArtifactStore store) throws IOException {
        String snapshot = KitCorpus.text(store, PassSnapshotConsumer.SNAPSHOT);
        if (snapshot == null || snapshot.isBlank()) {
            return Map.of();
        }
        Map<String, String> view = new HashMap<>();
        for (String line : snapshot.split("\n")) {
            if (!line.isBlank()) {
                int tab = line.indexOf('\t');
                view.put(line.substring(0, tab), line.substring(tab + 1));
            }
        }
        return view;
    }

    @Override
    public Optional<String> degradation(ArtifactStore store) throws IOException {
        return Optional.ofNullable(KitCorpus.text(store, PassSnapshotConsumer.DEGRADED));
    }

    @Override
    public Delivery delivery() {
        return Delivery.PASS_SNAPSHOT;
    }
}
