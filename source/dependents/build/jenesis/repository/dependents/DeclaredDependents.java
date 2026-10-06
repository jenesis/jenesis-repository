package build.jenesis.repository.dependents;

import module java.base;
import build.jenesis.repository.inventory.DependencySection;
import build.jenesis.repository.inventory.IncrementalPasses;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Names;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.store.Stamp;

/**
 * The declared-dependencies index: for each package name, the versions whose manifest declares a dependency on it and
 * the requirement each states, read from the dependencies the format's inspector recorded at publish - what "who
 * declares a dependency on X" answers from. A declaration is a requirement, and which version satisfies it is a
 * client's decision, so it is keyed by package name, carries the requirement with every entry, and never counts as
 * reaching a version; the versions a published version relies on are its closure's, indexed by the closure.
 *
 * <p>Fed by the inventory on the cadence of {@link IncrementalPasses}: every Nth pass visits every published version,
 * the passes between the versions published since. A visit reads the version's dependencies section and its record -
 * two point reads - and writes only when they differ. Changes are applied {@link #BATCH} versions at a time, each
 * touched shard rewritten once by compare-and-set, shards before records, so a crash between re-applies the same
 * difference idempotently. A full pass also takes out every version no longer published; until then a deleted version
 * stays listed, and a surface screens each row against the inventory.
 *
 * <p>Safe on several nodes at once: two nodes visiting one version compute the same difference and apply it
 * idempotently.
 */
public final class DeclaredDependents {

    /** The task name the cadence counts under and a standing full-pass request names. */
    static final String TASK = "dependents-declared";

    /** Versions re-decided per shard-rewrite batch: their differences are grouped by shard in heap, so this bounds a
     *  batch's footprint however many versions a pass visits. */
    static final int BATCH = 10_000;

    private final ArtifactStore store;

    private final StoreRepositoryInventory inventory;

    public DeclaredDependents(ArtifactStore store) {
        this.store = store;
        this.inventory = new StoreRepositoryInventory(store);
    }

    /** What one pass did: whether it visited every published version, how many it visited, how many had changed,
     *  and how many records a full pass took out because their version is no longer published. */
    public record Pass(boolean full, long visited, long changed, long removed) {
    }

    /** Run one pass under the deployment's cadence dials. */
    public Pass pass(UnaryOperator<String> config) throws IOException {
        Instant started = Instant.now();
        IncrementalPasses cadence = IncrementalPasses.over(store, TASK, DependentsStore.DECLARED_PASSES,
                new Stamp(store, DependentsStore.DECLARED_FULL), config);
        Batch batch = new Batch();
        long[] visited = {0};
        cadence.coordinates(inventory, coordinate -> {
            visited[0]++;
            Set<DependentsStore.Declares> declares = new TreeSet<>();
            for (DependencySection.Declared declared : inventory.dependencies(coordinate.ecosystem(),
                    coordinate.coordinate(), coordinate.version()).orElse(List.of())) {
                declares.add(new DependentsStore.Declares(declared.coordinate(), declared.requirement()));
            }
            batch.decide(new DependentsStore.DeclaredRecord(coordinate.ecosystem(), coordinate.coordinate(),
                    coordinate.version(), declares));
        });
        batch.apply();
        long removed = cadence.full() ? sweep() : 0;
        cadence.completed(started, true);
        return new Pass(cadence.full(), visited[0], batch.changed, removed);
    }

    /** Drain the records and take out every version the inventory no longer holds as published: one point read per
     *  record, and a write only for what went. */
    private long sweep() throws IOException {
        Batch batch = new Batch();
        Names names = Names.over(store, DependentsStore.DECLARED_BY, ArtifactStore.DRAIN_PAGE);
        for (String name = names.next(); name != null; name = names.next()) {
            String key = DependentsStore.DECLARED_BY + "/" + name;
            Optional<ArtifactStore.Versioned> stored = store.readVersioned(key);
            if (stored.isEmpty()) {
                continue;
            }
            DependentsStore.DeclaredRecord record = DependentsStore.DeclaredRecord.parse(stored.get().content());
            if (record == null) {
                batch.forget(key);                  // torn: the visit that wrote it rewrites it on the next full pass
            } else if (inventory.publishedAt(record.ecosystem(), record.coordinate(), record.version()).isEmpty()) {
                batch.decide(new DependentsStore.DeclaredRecord(record.ecosystem(), record.coordinate(),
                        record.version(), Set.of()), record);
            }
        }
        batch.apply();
        return batch.changed;
    }

    /** One batch of re-decided versions: the shard differences they add up to, and the records to write after. */
    private final class Batch {

        private final Map<String, Map<String, Set<String>>> additions = new TreeMap<>();
        private final Map<String, Map<String, Set<String>>> removals = new TreeMap<>();
        private final Map<String, byte[]> records = new LinkedHashMap<>();
        private final List<String> forgotten = new ArrayList<>();
        private int pending;
        private long changed;

        /** Re-decide one version against what its record says it contributed. */
        void decide(DependentsStore.DeclaredRecord now) throws IOException {
            String key = DependentsStore.declaredRecordKey(now.ecosystem(), now.coordinate(), now.version());
            DependentsStore.DeclaredRecord before = store.readVersioned(key)
                    .map(stored -> DependentsStore.DeclaredRecord.parse(stored.content()))
                    .orElse(null);
            decide(now, before);
        }

        void decide(DependentsStore.DeclaredRecord now, DependentsStore.DeclaredRecord before) throws IOException {
            Set<DependentsStore.Declares> previous = before == null ? Set.of() : before.declares();
            if (previous.equals(now.declares())) {
                return;                                             // unchanged, or nothing either side: no write
            }
            for (DependentsStore.Declares gone : previous) {
                if (!now.declares().contains(gone)) {
                    collect(removals, gone, now);
                }
            }
            for (DependentsStore.Declares added : now.declares()) {
                if (!previous.contains(added)) {
                    collect(additions, added, now);
                }
            }
            String key = DependentsStore.declaredRecordKey(now.ecosystem(), now.coordinate(), now.version());
            if (now.declares().isEmpty()) {
                forgotten.add(key);
            } else {
                records.put(key, now.serialise());
            }
            changed++;
            if (++pending >= BATCH) {
                apply();
            }
        }

        void forget(String key) {
            forgotten.add(key);
        }

        private void collect(Map<String, Map<String, Set<String>>> into, DependentsStore.Declares declared,
                             DependentsStore.DeclaredRecord version) {
            into.computeIfAbsent(DependentsStore.declaredShardKey(declared.dependency()), _ -> new TreeMap<>())
                    .computeIfAbsent(declared.dependency(), _ -> new TreeSet<>())
                    .add(DependentsStore.declaration(version.ecosystem(), version.coordinate(), version.version(),
                            declared.requirement()));
        }

        /** Rewrite each touched shard once, then the records - so a crash between the two re-applies the same
         *  difference on the next visit rather than losing it. */
        void apply() throws IOException {
            Set<String> shards = new TreeSet<>(removals.keySet());
            shards.addAll(additions.keySet());
            for (String shard : shards) {
                DependentsStore.rewriteShard(store, shard, removals.getOrDefault(shard, Map.of()),
                        additions.getOrDefault(shard, Map.of()));
            }
            for (Map.Entry<String, byte[]> record : records.entrySet()) {
                Retries.update(store, record.getKey(), _ -> record.getValue());
            }
            for (String key : forgotten) {
                store.delete(key);
            }
            additions.clear();
            removals.clear();
            records.clear();
            forgotten.clear();
            pending = 0;
        }
    }
}
