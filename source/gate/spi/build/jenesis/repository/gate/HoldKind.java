package build.jenesis.repository.gate;

import module java.base;

import build.jenesis.repository.inventory.OverrideRecords;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Retries;

/**
 * One retroactive hold kind's two per-version records: the subjects a sweep holds a coordinate version for (CVEs,
 * licence reason tokens, advisory ids), and the subjects a human released it for, so the sweep never re-holds for a
 * cleared subject. Keyed through {@link HoldRecords} ({@code holds/<kind>/...}) and {@link OverrideRecords}
 * ({@code overrides/<kind>/...}), each a space-separated subject set.
 *
 * <p>{@link #hold} writes before the sweep links its hold pointers, so no pointer has an unknown subject set, and
 * unions into the existing record: a feed that drops a subject transiently must not erase the subject the re-analysis
 * pass re-confirms against. A record shrinks only on a release, discard or clear. {@link #onReleased} promotes the
 * record into the override and drops it; {@link #onDiscarded} drops it and writes no override, since no human cleared
 * anything. Both are per version, so the last discard of a multi-path hold reaps the state.
 *
 * <p>A kind is a name; how its subjects are found and explained stays with the kind. Every write is compare-and-set
 * under {@link Retries}, and a lost override throws, since it would re-expose a released artifact to a re-hold.
 * Nothing here reads an artifact blob.
 */
public final class HoldKind {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final String kind;

    private HoldKind(String kind) {
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    /** The records of the kind called {@code kind} - the segment under {@code holds/} and {@code overrides/}. */
    public static HoldKind of(String kind) {
        return new HoldKind(kind);
    }

    /** The kind's name, as the {@code holds/<kind>/} layout spells it. */
    public String name() {
        return kind;
    }

    /** Records that a sweep holds a coordinate version for {@code subjects}, unioned into any existing record. */
    public void hold(ArtifactStore store, String ecosystem, String coordinate, String version,
                     Collection<String> subjects) throws IOException {
        String key = holdKey(ecosystem, coordinate, version);
        Set<String> merged = new LinkedHashSet<>(readSet(store, key).orElseGet(Set::of));
        merged.addAll(subjects);
        writeSet(store, key, merged);
    }

    /** The subjects recorded for a held coordinate version, or empty when this kind holds no record there. */
    public Optional<Set<String>> held(ArtifactStore store, String ecosystem, String coordinate, String version)
            throws IOException {
        return readSet(store, holdKey(ecosystem, coordinate, version));
    }

    /** The subjects a human released a coordinate version for, which the sweep must not re-hold on. */
    public Set<String> overridden(ArtifactStore store, String ecosystem, String coordinate, String version)
            throws IOException {
        return readSet(store, overrideKey(ecosystem, coordinate, version)).orElseGet(Set::of);
    }

    /** Whether this kind holds a record for the coordinate version {@code path} maps to; {@code false} for a path no
     *  installed format maps. Serves {@link HoldReleaseObserver#holds}. */
    public boolean holds(ArtifactStore store, String path) throws IOException {
        ArtifactDescriptor artifact = describe(store, path);
        return artifact != null
                && held(store, artifact.ecosystem(), artifact.coordinate(), artifact.version()).isPresent();
    }

    /** {@link #onReleased(ArtifactStore, String, Function)} with nothing to recover: a path this kind never held
     *  writes no override. */
    public void onReleased(ArtifactStore store, String path) throws IOException {
        onReleased(store, path, released -> Set.of());
    }

    /**
     * The review-release hook: promotes this kind's record for the released path's coordinate into the override and
     * drops the record. Without a record, {@code recovered} supplies the subjects to override (for a publish-time hold
     * that wrote none), and recovering nothing is a no-op. Runs through {@link HoldReleaseObserver#released}, before
     * the release surface mutates anything.
     */
    public void onReleased(ArtifactStore store, String path, Function<String, Set<String>> recovered)
            throws IOException {
        ArtifactDescriptor artifact = describe(store, path);
        if (artifact == null) {
            return;
        }
        String key = holdKey(artifact.ecosystem(), artifact.coordinate(), artifact.version());
        Optional<Set<String>> record = readSet(store, key);
        Set<String> releasing = new LinkedHashSet<>(record.isPresent() ? record.get() : recovered.apply(path));
        if (releasing.isEmpty()) {
            return;
        }
        Set<String> merged = new LinkedHashSet<>(overridden(store, artifact.ecosystem(), artifact.coordinate(),
                artifact.version()));
        merged.addAll(releasing);
        writeSet(store, overrideKey(artifact.ecosystem(), artifact.coordinate(), artifact.version()), merged);
        if (record.isPresent()) {
            store.delete(key);
        }
    }

    /**
     * The review-discard hook: drops this kind's record for the discarded path's coordinate, since no sweep reaches a
     * discarded version. No override is written. The record stays while another path of the version is held.
     */
    public void onDiscarded(ArtifactStore store, String path) throws IOException {
        ArtifactDescriptor artifact = describe(store, path);
        if (artifact == null || HeldElsewhere.othersStillHeld(store, artifact, path)) {
            return;
        }
        cleared(store, artifact.ecosystem(), artifact.coordinate(), artifact.version());
    }

    /**
     * Drops a coordinate version's record without writing an override, as a re-analysis pass does when the
     * intelligence behind a hold has cleared: a re-listed subject must hold again. A no-op when absent.
     */
    public void cleared(ArtifactStore store, String ecosystem, String coordinate, String version)
            throws IOException {
        String key = holdKey(ecosystem, coordinate, version);
        if (store.readVersioned(key).isPresent()) {
            store.delete(key);
        }
    }

    private static ArtifactDescriptor describe(ArtifactStore store, String path) throws IOException {
        Optional<ArtifactDescriptor> descriptor = new StoreRepositoryInventory(store).describe(path);
        if (descriptor.isEmpty()) {
            return null;
        }
        ArtifactDescriptor artifact = descriptor.get();
        return artifact.coordinate() == null || artifact.version() == null ? null : artifact;
    }

    private static Optional<Set<String>> readSet(ArtifactStore store, String key) throws IOException {
        return store.readVersioned(key).map(versioned -> {
            Set<String> set = new LinkedHashSet<>();
            for (String token : WHITESPACE.split(new String(versioned.content(), StandardCharsets.UTF_8).trim())) {
                if (!token.isEmpty()) {
                    set.add(token);
                }
            }
            return set;
        });
    }

    private static void writeSet(ArtifactStore store, String key, Set<String> set) throws IOException {
        byte[] value = String.join(" ", set).getBytes(StandardCharsets.UTF_8);
        Retries.update(store, key, current -> value);
    }

    private String holdKey(String ecosystem, String coordinate, String version) {
        return HoldRecords.key(kind, ecosystem, coordinate, version);
    }

    private String overrideKey(String ecosystem, String coordinate, String version) {
        return OverrideRecords.key(kind, ecosystem, coordinate, version);
    }
}
