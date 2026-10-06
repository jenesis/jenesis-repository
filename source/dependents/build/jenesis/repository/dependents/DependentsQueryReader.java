package build.jenesis.repository.dependents;

import module java.base;
import build.jenesis.repository.dependents.spi.DependentsQuery;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Stamp;

/**
 * The read path of the reverse-dependency index, the {@link DependentsQuery} reached through
 * {@link DependentsIndexProvider}. It reads only what a sweep stored - shards and the built marker - through
 * {@link DependentsStore}'s layout, never walking the tree and never writing, so it answers from a store another
 * node's sweep filled. Stateless beyond its store, so one instance serves concurrent reads.
 */
public final class DependentsQueryReader implements DependentsQuery {

    private final ArtifactStore store;

    public DependentsQueryReader(ArtifactStore store) {
        this.store = store;
    }

    /**
     * The coordinates that depend on {@code coordinate} - every artifact whose SBOM names it anywhere in its
     * resolved tree - sorted, or empty when nothing recorded depends on it. A single shard fetch and parse.
     */
    @Override
    public List<String> dependents(String coordinate) throws IOException {
        Optional<ArtifactStore.Versioned> shard = store.readVersioned(DependentsStore.shardKey(coordinate));
        if (shard.isEmpty()) {
            return List.of();
        }
        return DependentsStore.dependentsOf(shard.get().content(), coordinate);
    }

    /** Every coordinate the index holds a dependent for, sorted. It reads every shard and holds the whole key set, so
     *  its cost grows with the graph; {@link #coordinates(String, int)} is the bounded form. */
    @Override
    public List<String> coordinates() throws IOException {
        List<String> all = new ArrayList<>();
        for (String child : store.list(DependentsStore.PREFIX)) {
            if (!DependentsStore.isShard(child)) {
                continue;                                       // the built marker (or any non-shard object) is not a shard
            }
            Optional<ArtifactStore.Versioned> stored = store.readVersioned(DependentsStore.PREFIX + "/" + child);
            if (stored.isPresent()) {
                DependentsStore.collectKeys(stored.get().content(), all);
            }
        }
        Collections.sort(all);
        return all;
    }

    /**
     * One bounded page of {@link #coordinates()}: the shards in name order, one held at a time, resumed strictly after
     * the opaque {@code "<shard> <coordinate>"} cursor. The order is shard then coordinate, stable across pages; a
     * shard a sweep rewrites between pages is tolerated, the index being a derived view.
     */
    @Override
    public CoordinatePage coordinates(String cursor, int limit) throws IOException {
        if (limit <= 0) {
            return new CoordinatePage(List.of(), null);
        }
        String cursorShard = null;
        String cursorCoordinate = null;
        if (cursor != null && cursor.length() >= 3 && cursor.charAt(2) == ' ') {
            cursorShard = cursor.substring(0, 2);           // the two-hex shard name the last page ended in
            cursorCoordinate = cursor.substring(3);         // the last coordinate returned from that shard
        }
        List<String> shards = new ArrayList<>();
        for (String child : store.list(DependentsStore.PREFIX)) {
            if (DependentsStore.isShard(child)) {
                shards.add(child);
            }
        }
        Collections.sort(shards);                           // a deterministic 00..ff order the cursor resumes within
        List<String> page = new ArrayList<>();
        for (String shardName : shards) {
            if (cursorShard != null && shardName.compareTo(cursorShard) < 0) {
                continue;                                   // fully returned on an earlier page
            }
            Optional<ArtifactStore.Versioned> stored = store.readVersioned(DependentsStore.PREFIX + "/" + shardName);
            if (stored.isEmpty()) {
                continue;                                   // a shard a concurrent sweep removed since the listing
            }
            List<String> keys = new ArrayList<>();
            DependentsStore.collectKeys(stored.get().content(), keys);   // one shard's keys, never the whole graph's
            Collections.sort(keys);                         // the serialised lines are already sorted; robust to a torn shard
            for (String coordinate : keys) {
                if (cursorShard != null && shardName.equals(cursorShard) && cursorCoordinate != null
                        && coordinate.compareTo(cursorCoordinate) <= 0) {
                    continue;                               // already returned from this shard on the cursor's page
                }
                page.add(coordinate);
                if (page.size() == limit) {
                    return new CoordinatePage(page, shardName + ' ' + coordinate);   // more may remain; resume after here
                }
            }
        }
        return new CoordinatePage(page, null);              // every shard walked to the end
    }

    /**
     * The subset of {@code coordinates} the index holds a dependent for, each an ecosystem-neutral
     * {@code group:name:version} - the reachability probe the vulnerability report makes on a page render. It reads
     * only the shards the query addresses ({@code min(k, 256)} reads), because {@link DependentsStore#shard} hashes the
     * neutral spelling, so a recorded purl and the report's coordinate land in one shard.
     *
     * <p>A torn key is skipped, and a coordinate whose stored spelling neutralises differently is a miss, not an error.
     */
    @Override
    public Set<String> reachable(Collection<String> coordinates) throws IOException {
        Map<String, Set<String>> wanted = new TreeMap<>();       // shard byte -> the queried coordinates it can hold
        for (String coordinate : coordinates) {
            String neutral = DependentsQuery.neutralise(coordinate);
            wanted.computeIfAbsent(DependentsStore.shard(neutral), _ -> new HashSet<>()).add(neutral);
        }
        Set<String> hit = new HashSet<>();
        for (Map.Entry<String, Set<String>> shard : wanted.entrySet()) {
            Optional<ArtifactStore.Versioned> stored =
                    store.readVersioned(DependentsStore.PREFIX + "/" + shard.getKey());
            if (stored.isEmpty()) {
                continue;                                       // nothing in the index hashes here - a proven miss
            }
            List<String> keys = new ArrayList<>();
            DependentsStore.collectKeys(stored.get().content(), keys);   // one shard's keys, not the whole graph's
            for (String key : keys) {
                String neutral = DependentsQuery.neutralise(key);
                if (shard.getValue().contains(neutral)) {
                    hit.add(neutral);
                }
            }
        }
        return hit;
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

    /** When the declared tier's last full pass started - the one moment it can claim to have visited every
     *  published version. A single small-object read. */
    @Override
    public Optional<Instant> declarationsBuiltAt() throws IOException {
        return new Stamp(store, DependentsStore.DECLARED_FULL).read();
    }

    /**
     * Whether a sweep has committed this index <em>in the current shard layout</em> at least once - the
     * {@link DependentsStore#BUILT} marker is present and its body names {@link DependentsStore#LAYOUT}.
     * {@code false} before the first sweep, so a surface answers "not yet built" rather than an empty result, and false
     * for shards another layout placed, which this reader cannot address; the incremental apply defers to the full
     * rebuild on that signal, which heals the layout. One read of the marker: shards prove edges were recorded, not
     * that a sweep committed.
     */
    @Override
    public boolean built() throws IOException {
        Optional<ArtifactStore.Versioned> stored = store.readVersioned(DependentsStore.BUILT);
        return stored.isPresent() && DependentsStore.builtHere(stored.get().content());
    }

    /**
     * The instant the last completing sweep rebuilt this index, so a view shows how fresh its blast radius is. Empty
     * when not {@link #built()} or when the marker's instant does not parse - unknown rather than a fabricated
     * freshness. One read.
     */
    @Override
    public Optional<Instant> builtAt() throws IOException {
        return store.readVersioned(DependentsStore.BUILT)
                .flatMap(stored -> DependentsStore.builtAt(stored.content()));
    }
}
