package build.jenesis.repository.gate;

import module java.base;

import build.jenesis.repository.inventory.OverrideRecords;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Retries;

/**
 * One retroactive hold kind's two per-release records: what a sweep is holding a coordinate version <em>for</em>
 * (its subjects - the CVEs, the licence reason tokens, the advisory ids), and what a human has released it for, so
 * the sweep never re-holds a release for a subject an operator has already cleared. Both are keyed by the same
 * ecosystem/coordinate/version the inventory keys its sidecars by, through the two layouts {@link HoldRecords}
 * ({@code holds/<kind>/...}) and {@link OverrideRecords} ({@code overrides/<kind>/...}) own, and both store the
 * subject set as a space-separated list.
 *
 * <p>The protocol every kind shares. {@link #hold} writes the record <em>before</em> the sweep links its hold
 * pointers, so a crash never leaves a pointer whose subject set is unknown, and it is a union into any existing
 * record, never a replacement: a feed that transiently drops a recorded subject while another appears must not
 * erase the subject that justified the hold, or the re-analysis pass loses its re-confirmation subject and
 * auto-releases a still-affected version. A record only shrinks on an explicit release, discard or clear.
 * {@link #onReleased} promotes the record into the override (unioned with any prior override) and drops the
 * consumed record; {@link #onDiscarded} drops the record and writes no override, because no human cleared anything
 * and a re-publish is simply re-screened; and both are per <em>version</em>, so discarding one path of a
 * multi-path hold leaves the state the remaining held paths are reviewed against and the last discard reaps it.
 *
 * <p>Three kinds - the KEV, licence and reachability sweeps - used to carry this class each, character for character
 * apart from the kind's name: three codecs for one document, three compare-and-set loops with two retry policies,
 * and one kind without the multi-path guard the other two had grown. A kind is a name now; what differs between
 * kinds is how its subjects are found and explained, and that stays with the kind.
 *
 * <p>Every write is a compare-and-set under {@link Retries}, and a lost override throws rather than returning: it
 * would durably re-expose a released artifact to a re-hold. Nothing here reads an artifact blob - only the tiny
 * markers and, on release or discard, the path's format-neutral descriptor.
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

    /** Record that a sweep is (retroactively) holding a coordinate version for {@code subjects}, unioned into any
     *  existing record. Written before the hold pointers are linked. */
    public void hold(ArtifactStore store, String ecosystem, String coordinate, String version,
                     Collection<String> subjects) throws IOException {
        String key = holdKey(ecosystem, coordinate, version);
        Set<String> merged = new LinkedHashSet<>(readSet(store, key).orElseGet(Set::of));
        merged.addAll(subjects);
        writeSet(store, key, merged);
    }

    /** The subjects the sweep recorded for a currently-held coordinate version, or empty when this kind holds no
     *  record there (an un-held release, or one held only by another kind or the publish-time gate). */
    public Optional<Set<String>> held(ArtifactStore store, String ecosystem, String coordinate, String version)
            throws IOException {
        return readSet(store, holdKey(ecosystem, coordinate, version));
    }

    /** The subjects a human has released for a coordinate version - the set the sweep must not re-hold on. Empty
     *  when the release has never been operator-released. */
    public Set<String> overridden(ArtifactStore store, String ecosystem, String coordinate, String version)
            throws IOException {
        return readSet(store, overrideKey(ecosystem, coordinate, version)).orElseGet(Set::of);
    }

    /** Whether this kind holds a record for the coordinate version {@code path} maps to - {@code false} for a path
     *  no installed format maps to a coordinate. The per-path read a {@link HoldReleaseObserver#holds} fan-out
     *  serves. */
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
     * The review-release hook: promote this kind's hold record for the released path's coordinate into the override
     * (unioned with any prior override) and drop the consumed record, so the sweep never re-holds the release for a
     * subject a human has already cleared. When no record exists, {@code recovered} is asked for the subjects the
     * release should still override - a kind whose publish-time gate holds write no record recovers them from
     * elsewhere - and a release that recovers nothing is a no-op, so another kind's release is left untouched.
     * Called by every review release surface <em>before</em> it links the release pointer or clears the
     * {@code /quarantine} pointer, through {@link HoldReleaseObserver#released}.
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
     * The review-discard hook: drop this kind's hold record for the discarded path's coordinate, so a thrown-away
     * version's row never dangles (nothing evicts a discarded version's rows: it has no published sidecar for the
     * reconcile sweep to judge). No override is written - no human cleared anything, and a re-publish of the same
     * version is simply re-screened. The record is per version, so while another path of the same version is still
     * held it stays; the last discard reaps it.
     */
    public void onDiscarded(ArtifactStore store, String path) throws IOException {
        ArtifactDescriptor artifact = describe(store, path);
        if (artifact == null || HeldElsewhere.othersStillHeld(store, artifact, path)) {
            return;
        }
        cleared(store, artifact.ecosystem(), artifact.coordinate(), artifact.version());
    }

    /**
     * Drop a coordinate version's hold record without writing an override - what a re-analysis pass does after it
     * auto-releases a hold whose intelligence has cleared. An operator's release is a deliberate acceptance the sweep
     * must never re-hold, but an intel-driven release is only as good as the current intel: if the subject is
     * re-listed the release must hold again, so the record is simply removed and a later re-listing writes a fresh
     * one. A no-op when no record is present, so a crash mid-pass re-runs cleanly.
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
