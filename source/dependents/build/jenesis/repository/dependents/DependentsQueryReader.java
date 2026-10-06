package build.jenesis.repository.dependents;

import module java.base;
import build.jenesis.repository.dependents.spi.DependentsQuery;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Stamp;

/**
 * The read path of the declared-dependencies index, the {@link DependentsQuery} reached through
 * {@link DependentsIndexProvider}. It reads only what a pass stored - shards and the full-pass stamp - through
 * {@link DependentsStore}'s layout, never walking the tree and never writing, so it answers from a store another
 * node's pass filled. Stateless beyond its store, so one instance serves concurrent reads.
 */
public final class DependentsQueryReader implements DependentsQuery {

    private final ArtifactStore store;

    public DependentsQueryReader(ArtifactStore store) {
        this.store = store;
    }

    /**
     * The versions whose manifest declares a dependency on {@code dependency}, one bounded page in the entries'
     * sorted order - one read of the package's declared shard, decoding only its line. The cursor is the last entry
     * returned, so a page resumes strictly after it however the shard changed in between.
     */
    @Override
    public DeclarationPage declarations(String dependency, String cursor, int limit) throws IOException {
        if (limit <= 0) {
            return new DeclarationPage(List.of(), null);
        }
        Optional<ArtifactStore.Versioned> shard = store.readVersioned(DependentsStore.declaredShardKey(dependency));
        if (shard.isEmpty()) {
            return new DeclarationPage(List.of(), null);
        }
        List<Declaration> page = new ArrayList<>();
        List<String> entries = DependentsStore.dependentsOf(shard.get().content(), dependency);
        for (int index = 0; index < entries.size(); index++) {
            String entry = entries.get(index);
            if (cursor != null && !cursor.isEmpty() && entry.compareTo(cursor) <= 0) {
                continue;
            }
            Declaration declaration = DependentsStore.parseDeclaration(entry);
            if (declaration == null) {
                continue;                                       // a torn entry; the next pass over its version heals it
            }
            page.add(declaration);
            if (page.size() == limit) {
                return new DeclarationPage(page, index + 1 < entries.size() ? entry : null);
            }
        }
        return new DeclarationPage(page, null);
    }

    /** When the last full pass started - the one moment it can claim to have visited every
     *  published version. A single small-object read. */
    @Override
    public Optional<Instant> declarationsBuiltAt() throws IOException {
        return new Stamp(store, DependentsStore.DECLARED_FULL).read();
    }
}
