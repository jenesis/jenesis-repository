package build.jenesis.repository.inventory;

import module java.base;
import build.jenesis.repository.store.Clocks;
import build.jenesis.repository.metadata.MetadataDocument;
import build.jenesis.repository.metadata.DocumentTurns;
import build.jenesis.repository.metadata.MetadataKey;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.metadata.SectionMutation;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The per-coordinate declared-license facts: the licenses an artifact <em>declares</em> (a name and/or URL, exactly as
 * the publishing gate's quality inspector read them out of the artifact's own metadata), consolidated into the
 * {@code licenses} section of the unified per-coordinate metadata document ({@link MetadataKey#version}). It records
 * the <em>declared</em> form, not a resolved SPDX id, so the one place that categorises (the search sweep's
 * {@code License.identify}) stays authoritative and a later categorisation-table change re-derives cleanly from stored
 * truth rather than from a frozen verdict.
 *
 * <p>The distinction between a <em>present but empty</em> record (the gate inspected the artifact and it declared no
 * license) and an <em>absent</em> one (never inspected) is load-bearing: the sweep indexes an empty record as the
 * unknown-license bucket and does not re-parse, but backfills an absent one from stored metadata. The section envelope
 * carries this as {@link build.jenesis.repository.metadata.State#EMPTY} versus a section absent from the document.
 */
public final class LicenseInventory {

    private final ArtifactStore store;

    /** The consolidated metadata store this repository's document lives in. */
    private final MetadataStore metadata;

    public LicenseInventory(ArtifactStore store) {
        this.store = store;
        this.metadata = MetadataProvider.installed().over(store);
    }

    /** A license as declared by the artifact: a name and/or URL, either of which may be {@code null}, but not both. */
    public record Declared(String name, String url) {
    }

    /**
     * Record the licenses a coordinate version declares, <em>unioned</em> into any already recorded for the version: a
     * version's license set is the union of what its artifacts declare, so a sibling publish (or a re-record) only ever
     * ADDS to the set, never replaces it. An empty declaration therefore contributes nothing - it can neither erase a
     * license a sibling already recorded nor un-mark the version. A version first seen with no license still writes the
     * present-but-empty record ("inspected, none declared"), distinct from an absent one; a later real declaration then
     * backfills it. Idempotent and crash-safe: the union converges to the same set on re-record.
     *
     * <p>The union folds into the document's {@code licenses} section and, for a published member, the
     * {@code identity/rollup} is re-folded once that document write has committed: from the fingerprint of the section
     * the write replaced to the one it wrote - the transition the compare-and-set made linear, so two records of one
     * version telescope (old to a, a to b) rather than cancel. The re-fold used to be grouped into the document's batch
     * as one unretried compare-and-set on the first attempt only, and was dropped whenever the document or the rollup
     * conflicted, which under concurrent publishers was most of the time.
     */
    public void record(String ecosystem, String coordinate, String version, List<Declared> licenses)
            throws IOException {
        Instant now = Clocks.now();
        // The transition the landing try made, or null when the union added nothing already recorded.
        String key = MetadataKey.version(ecosystem, coordinate, version);
        Transition made = DocumentTurns.take(store, key, () -> Retries.decide(store, key, current -> {
            MetadataDocument document = current.map(versioned -> MetadataDocument.read(versioned.content()))
                    .orElseGet(MetadataDocument::empty);
            Optional<Section> before = document.section(LicenseSection.TAG);
            List<Declared> beforeDeclared = LicenseSection.declared(before);
            // doc.mutate applies the union and carries every other section verbatim; it throws loudly on a
            // newer-format document rather than downgrade-rewriting it, exactly as the store's mutate would.
            MetadataDocument next = document.mutate(single(LicenseSection.union(licenses, now)));
            List<Declared> afterDeclared = LicenseSection.declared(next.section(LicenseSection.TAG));
            if (before.isPresent() && new LinkedHashSet<>(beforeDeclared).equals(new LinkedHashSet<>(afterDeclared))) {
                return Retries.Verdict.keep(null);          // the union added nothing already recorded - a no-op
            }
            return Retries.Verdict.write(next.serialize(), new Transition(before.map(_ -> beforeDeclared),
                    afterDeclared, PublishedSection.facts(next.section(PublishedSection.TAG))));
        }));
        if (made != null) {
            refold(ecosystem, coordinate, version, made);
        }
    }

    /** What a license record changed: the declared set the document carried (absent when it had no section), the
     *  set it carries now, and the member's publish facts as that document states them. */
    private record Transition(Optional<List<Declared>> before, List<Declared> after,
                              Optional<PublishedSection.Facts> facts) {
    }

    /** Re-fold a published member's identity contribution from the set it was folded with to the set just recorded.
     *  Only a published member is folded: an unpublished coordinate is folded when it publishes, with the section its
     *  publish commits. The set the rollup reflects for the member is the one the document carried - absent means it
     *  was folded with no license fingerprint - which is what makes the out-member correct. */
    private void refold(String ecosystem, String coordinate, String version, Transition made) throws IOException {
        if (made.facts().isPresent() && made.facts().get().at() != null) {
            new InventoryIdentity(store).refold(
                    InventoryIdentity.member(ecosystem, coordinate, version, LicenseSection.fingerprintOf(made.before())),
                    InventoryIdentity.member(ecosystem, coordinate, version,
                            LicenseSection.fingerprintOf(Optional.of(made.after()))),
                    made.facts().get().at());
        }
    }

    private static SequencedMap<String, SectionMutation> single(SectionMutation mutation) {
        SequencedMap<String, SectionMutation> one = new LinkedHashMap<>();
        one.put(LicenseSection.TAG, mutation);
        return one;
    }

    /**
     * The licenses a coordinate version declares, or empty when none has been recorded for it yet (so the sweep knows
     * to backfill from stored metadata rather than treat the artifact as license-free): the document's
     * {@code licenses} section. A read never writes (§10).
     */
    public Optional<List<Declared>> read(String ecosystem, String coordinate, String version) throws IOException {
        return metadata.read(ecosystem, coordinate, version)
                .filter(document -> document.has(LicenseSection.TAG))
                .map(document -> LicenseSection.declared(document.section(LicenseSection.TAG)));
    }
}
