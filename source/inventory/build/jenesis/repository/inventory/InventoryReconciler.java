package build.jenesis.repository.inventory;

import module java.base;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.metadata.DocumentTurns;
import build.jenesis.repository.metadata.MetadataDocument;
import build.jenesis.repository.metadata.MetadataKey;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.metadata.SectionMutation;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Known;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.walk.ArtifactWalk;

/**
 * The reconcile sweep beside {@link StoreRepositoryInventory}: the convergence backstop that rebuilds the
 * <em>publish facts</em> from the live {@code publish/} pointer tree in both directions and sweeps the derived
 * per-version key spaces of no-longer-published versions. The publish facts are the {@code published}
 * section of the consolidated metadata document (its presence is membership of the published set), so the forward leg
 * restores a missing section and the reverse leg removes a section whose pointers are gone. The inventory owns
 * the seam - {@link StoreRepositoryInventory#reconcile} delegates here - so this class carries only the sweep legs and
 * calls back into the inventory core for the format-driven {@code describe}/{@code record}, the membership check and the
 * shared key/layout helpers.
 *
 * <p>What it restores is a <em>holding</em> ({@link Holdings}), of whichever kind the version's document says: a live
 * pointer whose version nothing records is recorded as a cached copy where its origin trail shows it was fetched from
 * an upstream and as a release otherwise, a cached copy whose pointers are gone stops being held, and a release that
 * is really a copy recorded as a release becomes the cached copy it is.
 *
 * <p>Deployed, its legs run per key in {@link InventoryReconcileConsumer}, a listener of the one walk, which hands the
 * forward leg withheld pointers too - a held version is still a published one. {@link #reconcile} runs the same legs
 * as walks of its own: the reverse leg over the published root and the derived roots, the forward leg over every
 * {@code publish/} pointer.
 */
final class InventoryReconciler {

    private final StoreRepositoryInventory inventory;
    private final ArtifactStore store;
    private final MetadataStore metadata;

    InventoryReconciler(StoreRepositoryInventory inventory, ArtifactStore store, MetadataStore metadata) {
        this.inventory = inventory;
        this.store = store;
        this.metadata = metadata;
    }

    /**
     * Rebuild the publish facts from the {@code publish/} pointer tree in both directions, so a crash that left derived
     * state drifting converges on the next sweep. A linked, served artifact whose {@code published} section is missing -
     * the un-contained {@code committed()} leg of a publish failed after the pointer was linked, so it serves forever yet
     * is invisible to retention, garbage collection and the search/license index (all of which enumerate
     * {@link StoreRepositoryInventory#releases}) - gets one recreated from the owning format's {@link ArtifactLayout}
     * descriptor, timestamped at {@code now}: the conservative publish instant, which only ever delays an age-based
     * eviction, never accelerates it. The reverse orphan - a section whose coordinate has no live pointer left, the
     * residue of a crashed {@link StoreRepositoryInventory#evict} - is removed. The same orphan rule then sweeps the
     * derived per-version key spaces - the {@code overrides/<kind>/} hold-overrides and the {@code pinned/} markers -
     * deleting every row whose version is no longer published <em>and</em> whose absence an installed format can
     * positively confirm; a row nothing installed can judge is left alone. Reads only the tiny pointers and documents,
     * never an artifact blob, and commits through the store's compare-and-set, so it runs identically on filesystem
     * and every object store. Idempotent: a section that already exists is left untouched and a re-run over a
     * converged repository restores and removes nothing.
     *
     * <p>Each leg rides the shared {@link ArtifactWalk} as its own pass, inheriting the walk's resumable, segmented,
     * multi-node guarantees, and every leg is idempotent per row so a replayed visit after a crash-resume converges to
     * the same state. The returned counts are what <em>this</em> call restored and removed.
     */
    StoreRepositoryInventory.Reconciliation reconcile(ArtifactWalk walk, Instant now) throws IOException {
        int restored = restoreMissingPublished(walk, now);
        int removed = removeOrphanPublished(walk);
        // After the published set is converged, judge the derived rows against it: a row whose version is no longer a
        // published member MAY belong to an evicted release - for a publish/-namespace format the section of anything
        // that still serves was just restored above, and for a blobs-namespace format (whose lost section the forward
        // leg does not rebuild) removeOrphanDerived additionally spares any version still live under its format's
        // pointers, and any version whose format is not installed at all, so deleting a row can never orphan a live
        // version's bookkeeping (a human pin especially).
        return new StoreRepositoryInventory.Reconciliation(restored, removed, removeOrphanDerived(walk));
    }

    /** Delete every {@code overrides/<kind>/} and {@code pinned/} row whose coordinate version is <em>provably</em>
     *  gone: no published membership AND a format that can place the coordinate says it has no pointer left, the
     *  derived residue of an eviction that crashed between deletes. One shared-walk pass; a row that judges the same
     *  is deleted the same, and a replayed visit after a crash-resume finds the row already gone.
     *
     *  <p>The liveness half is three-valued, because it is answered through a format module that may not be
     *  installed: a row whose ecosystem <em>no installed format owns</em> is {@link Known.Unknown} and is left exactly
     *  where it is. A version whose format is gone is not dead; it is unreadable by that format right now, and "I
     *  cannot tell" must never share an outcome with "it is gone" - a human's force-keep or a human's clearance of a
     *  hold, deleted because a module was absent, is what the product's standing rule forbids. The genuine orphan is
     *  still reaped, because it is still provable: an installed format that can place the coordinate and finds no
     *  pointer for it. */
    private int removeOrphanDerived(ArtifactWalk walk) throws IOException {
        int[] removed = {0};
        walk.walk(store, "reconcile-derived", List.of(OverrideRecords.ROOT, StoreRepositoryInventory.PINNED),
                key -> {
            if (judgeDerived(key)) {
                removed[0]++;
            }
        });
        return removed[0];
    }

    /** The derived leg for one row under {@code overrides/} or {@code pinned/}: remove it when its version is provably
     *  unpublished and provably gone; {@code true} when it was removed. */
    boolean judgeDerived(String key) throws IOException {
        String[] parts = key.split("/");
        String ecosystem;
        String coordinate;
        String version;
        if (OverrideRecords.ROOT.equals(parts[0])) {
            // overrides/<kind>/<eco>/<coord>/<ver>, every segment encoded - parsed by the space's one owner
            // rather than re-spelled here, so the two spellings cannot drift.
            Optional<OverrideRecords.Row> row = OverrideRecords.parse(key);
            if (row.isEmpty()) {
                return false;
            }
            ecosystem = row.get().ecosystem();
            coordinate = row.get().coordinate();
            version = row.get().version();
        } else {
            if (parts.length != 4) {                         // pinned/<eco>/<coord>/<ver>
                return false;
            }
            ecosystem = parts[1];
            coordinate = StoreRepositoryInventory.decode(parts[2]);
            version = parts[3];
        }
        // The sweep deletes on a proven absence alone; a live or just-restored release keeps its rows.
        if (!(inventory.membership(ecosystem, coordinate, version) instanceof Known.Absent)) {
            return false;
        }
        boolean provablyGone = switch (liveness(ecosystem, coordinate, version)) {
            case Known.Absent<String> _ -> true;
            case Known.Present<String> _ -> false;                   // still serving under an installed format
            case Known.Unknown<String> _ -> false;                   // nothing installed can place it
        };
        if (!provablyGone) {
            return false;
        }
        store.delete(key);
        return true;
    }

    /** Whether a coordinate version still has a live pointer under an installed format - a {@code publish/} pointer for
     *  a Publication-namespace {@link ArtifactLayout}, or a blob pointer under a {@link BlobLayout blobs-namespace}
     *  format's own roots - <em>or whether that question could be put at all</em>. The liveness the reverse-repair
     *  judges a section by, three-valued so the derived-row sweep can tell an evicted release from one whose format is
     *  not installed: {@link Known.Present} carries the pointer prefix that proved it live, {@link Known.Absent} is a
     *  format that CAN place the coordinate reporting no pointer (the version really is gone - the only answer this
     *  sweep deletes on), and {@link Known.Unknown} is nothing installed being able to ask. A layout that resolves no
     *  path for the coordinate leaves the question unasked exactly as an absent layout does
     *  ({@code removeOrphanPublished} draws the same line): a roots-only format whose version pointers are not
     *  derivable from the coordinate reports nothing, and nothing is not evidence of absence. */
    private Known<String> liveness(String ecosystem, String coordinate, String version) throws IOException {
        boolean asked = false;
        for (ArtifactLayout layout : StoreRepositoryInventory.layoutsFor(ecosystem)) {
            for (String prefix : layout.paths(coordinate, version, store)) {
                asked = true;
                if (!store.isEmpty("publish" + prefix)) {
                    return Known.known("publish" + prefix);
                }
            }
        }
        for (BlobLayout blobLayout : StoreRepositoryInventory.blobLayoutsFor(ecosystem)) {
            asked = true;
            List<String> blobKeys = blobLayout.blobKeys(coordinate, version, store);
            if (!blobKeys.isEmpty()) {
                return Known.known(blobKeys.getFirst());
            }
        }
        return asked
                ? Known.absent()
                : Known.uninstalled("no installed format can place " + ecosystem + " " + coordinate + ":" + version
                        + ", so whether it still has a live pointer was never asked");
    }

    /** For every live {@code publish/} pointer that a descriptive format claims as a coordinate version, recreate its
     *  {@code published} section when it is missing - the forward-repair direction, one shared-walk pass. Idempotent per
     *  pointer (membership is checked before the record), so the at-least-once replay after a crash-resume restores
     *  nothing twice. Covers Publication-namespace formats only (Maven, the raw layout): it walks {@code publish/}, so a
     *  blobs-namespace format's pointers are not visited and such a release's lost section is not rebuilt here.
     *
     *  <p>The blobs-namespace formats are repaired by {@link InventoryBackfillConsumer}, which reads the coordinate
     *  back out of each stored pointer through {@code BlobLayout.describePointer} - so a format that can name its own
     *  keys is repaired and one that cannot is not. For one that cannot, a crash between writing a serving pointer and
     *  writing its {@code published} section leaves a row nothing repairs. A missing row does not make the artifact
     *  disappear - it stays enumerable through its own format and the derived-row sweep spares its live rows - but a
     *  retroactive sweep does not see it, so it can keep serving a later-listed CVE while the held gauge reads
     *  clean. */
    private int restoreMissingPublished(ArtifactWalk walk, Instant now) throws IOException {
        int[] restored = {0};
        walk.walk(store, "reconcile-publish", List.of("publish"), pointer -> {
            if (restore(pointer, now)) {
                restored[0]++;
            }
        });
        return restored[0];
    }

    /** The forward leg for one served pointer ({@code publish/...}): restore its missing published section from the
     *  owning format's descriptor, timestamped at {@code now}; {@code true} when one was restored. Idempotent per
     *  pointer - membership is checked before the record - so a replay restores nothing twice. */
    boolean restore(String pointer, Instant now) throws IOException {
        String requestPath = pointer.substring("publish".length());     // keeps the leading '/'
        if (requestPath.startsWith("/quarantine/")) {
            return false;                                                // a held artifact is not a release
        }
        Optional<ArtifactDescriptor> descriptor = inventory.describe(requestPath);
        if (descriptor.isEmpty()) {
            return false;
        }
        ArtifactDescriptor artifact = descriptor.get();
        if (artifact.coordinate() == null || artifact.version() == null) {
            return false;                                                // a checksum or generated file - no section
        }
        MetadataDocument document = document(artifact.ecosystem(), artifact.coordinate(), artifact.version());
        switch (Holdings.kind(document)) {
            case NONE -> {
                // reconcile-time: the conservative instant
                return holdMissing(artifact.ecosystem(), artifact.coordinate(), artifact.version(),
                        artifact.prerelease(), document, now);
            }
            case RELEASE -> {
                // The facts just read also backfill the two bounded listing faces for a release that lacks them:
                // the newest-first index row and the pinned/ marker. No further read: both are
                // idempotent writes guarded by a presence probe of their own small key. A release the reverse leg
                // is about to turn into a cached copy is left to it, so the pass does not write a row it removes.
                PublishedSection.Facts facts = PublishedSection.facts(document.section(PublishedSection.TAG))
                        .orElseThrow();
                if (!copyRecordedAsRelease(document, facts)) {
                    backfillFaces(artifact.ecosystem(), artifact.coordinate(), artifact.version(), facts);
                }
                return false;
            }
            case CACHED -> {
                backfillCachedFace(artifact.ecosystem(), artifact.coordinate(), artifact.version(), document);
                return false;
            }
        }
        return false;
    }

    /** The version document of a coordinate version as it stands, or an empty one where none is stored. */
    private MetadataDocument document(String ecosystem, String coordinate, String version) throws IOException {
        return store.readVersioned(MetadataKey.version(ecosystem, coordinate, version))
                .map(versioned -> MetadataDocument.read(versioned.content()))
                .orElseGet(MetadataDocument::empty);
    }

    /**
     * Record a live pointer's version that nothing records as held, from what its document already says: a version
     * whose origin trail shows it was fetched from an upstream is a cached copy whose fill's notice was lost or never
     * sent, and anything else is a release whose record was lost, recorded at {@code now}, the conservative publish
     * instant. Returns {@code true} when a record was written. Shared by the forward leg over {@code publish/} and the
     * inventory back-fill over the blobs-namespace pointers, so the two decide it the same way.
     */
    boolean holdMissing(String ecosystem, String coordinate, String version, boolean prerelease,
                        MetadataDocument document, Instant now) throws IOException {
        if (Holdings.fetched(document)) {
            return inventory.cache(ecosystem, coordinate, version, Holdings.upstream(document), now);
        }
        inventory.record(ecosystem, coordinate, version, prerelease, now);
        return true;
    }

    /**
     * Whether a release is really a copy of an upstream's artifact that a repair recorded as a release: its origin
     * trail names fetches through a fallback and no hand upload, and nobody pinned it. While such a row stands,
     * retention ages the copy as if it had been published here and every reader of the published set counts it. A
     * pinned one is left as it is, since a pin is an operator's decision about that release and turning it into a copy
     * would drop it.
     */
    private static boolean copyRecordedAsRelease(MetadataDocument document, PublishedSection.Facts facts) {
        return Holdings.fetched(document) && !facts.pinned();
    }

    /**
     * Turn a release that is really a copy of an upstream's artifact into the cached copy it is: in one
     * compare-and-set the {@code published} section goes and a {@code cached} section takes its place, dated when
     * the release was, then the release's newest-first row gives way to a cached one. Retention stops ageing it and
     * the scans keep seeing it. The rollup identity, which folds the published set, is recomputed at the end of the
     * pass that did this, as it is after every reconcile.
     */
    private void recordAsCopy(String ecosystem, String coordinate, String version, PublishedSection.Facts facts,
                              String upstream) throws IOException {
        String key = MetadataKey.version(ecosystem, coordinate, version);
        boolean turned = DocumentTurns.decide(store, key, current -> {
            if (current.isEmpty()) {
                return Retries.Verdict.keep(false);
            }
            MetadataDocument document = MetadataDocument.read(current.get().content());
            Optional<PublishedSection.Facts> now = PublishedSection.facts(document.section(PublishedSection.TAG));
            if (Holdings.kind(document) != Holdings.Kind.RELEASE || now.isEmpty()
                    || !copyRecordedAsRelease(document, now.get())) {
                return Retries.Verdict.keep(false);              // a publish or a pin landed in between
            }
            SequencedMap<String, SectionMutation> mutations = new LinkedHashMap<>();
            mutations.put(PublishedSection.TAG, _ -> null);
            mutations.put(CachedSection.TAG, _ -> CachedSection.section(now.get().at(), upstream));
            return Retries.Verdict.write(document.mutate(mutations).serialize(), true);
        });
        if (turned) {
            NewestFirst.RELEASES.forget(store, ecosystem, coordinate, version, facts.at());
            NewestFirst.CACHED.ensure(store, ecosystem, coordinate, version, facts.at());
        }
    }

    /** Write a cached copy's newest-first row unless it is already there - the idempotent backfill from a document a
     *  leg has already read. */
    private void backfillCachedFace(String ecosystem, String coordinate, String version, MetadataDocument document)
            throws IOException {
        Optional<CachedSection.Facts> facts = CachedSection.facts(document.section(CachedSection.TAG));
        if (facts.isPresent()) {
            NewestFirst.CACHED.ensure(store, ecosystem, coordinate, version, facts.get().at());
        }
    }

    /** For every published member whose coordinate has no surviving pointer - {@code publish/} for a
     *  Publication-namespace layout, the format's own {@link BlobLayout#blobKeys blob keys} for a blobs-namespace one -
     *  remove its publish facts: the reverse-repair direction, a crashed eviction's residue. The {@code published}
     *  section is dropped from the document (the licenses and any sibling sections stay). A member nothing can judge is
     *  kept: an ecosystem with no installed format, or a roots-only format whose version pointers are not enumerable
     *  from the coordinate - removing on "found nothing" there would wipe the retention books of every live release.
     *  One shared-walk pass over the {@link StoreRepositoryInventory#publishedRoot} tree; a replayed visit re-judges
     *  the row and a removed one is no longer a member. */
    private int removeOrphanPublished(ArtifactWalk walk) throws IOException {
        int[] removed = {0};
        walk.walk(store, "reconcile-published", List.of(inventory.publishedRoot()), key -> {
            if (judgePublished(key)) {
                removed[0]++;
            }
        });
        return removed[0];
    }

    /** The reverse leg for one row under the published root: remove its publish facts when its coordinate has no
     *  surviving pointer under an installed format; {@code true} when they were removed. A row nothing installed can
     *  judge is kept. */
    boolean judgePublished(String key) throws IOException {
        String root = inventory.publishedRoot();
        if (!key.startsWith(root + "/")) {
            return false;
        }
        {
            String[] segments = key.substring(root.length() + 1).split("/");
            if (segments.length != 3 || segments[2].startsWith("@")) {
                return false;                                    // not a version release row (or the @coordinate document)
            }
            String ecosystem = segments[0];
            String coordinate = StoreRepositoryInventory.decode(segments[1]);
            String version = segments[2];
            // Membership is the document's published or cached section; a licenses-only document holds nothing and
            // is left untouched.
            Optional<ArtifactStore.Versioned> stored = store.readVersioned(key);
            if (stored.isEmpty()) {
                return false;
            }
            MetadataDocument document = MetadataDocument.read(stored.get().content());
            Holdings.Kind kind = Holdings.kind(document);
            if (kind == Holdings.Kind.NONE) {
                return false;
            }
            Optional<PublishedSection.Facts> facts = PublishedSection.facts(document.section(PublishedSection.TAG));
            boolean judgeable = false;
            boolean live = false;
            for (ArtifactLayout layout : StoreRepositoryInventory.layoutsFor(ecosystem)) {
                for (String prefix : layout.paths(coordinate, version, store)) {
                    judgeable = true;
                    if (!store.isEmpty("publish" + prefix)) {
                        live = true;
                        break;
                    }
                }
            }
            // Only ask the blobs-namespace layout when the answer can still change the outcome: a member no installed
            // ArtifactLayout can place is unjudgeable and is kept whatever this probe says (below), and blobKeys is an
            // eviction handle rather than a cheap liveness question - OCI's, for one, proves a manifest unaliased by
            // descending the tag space. Spending that per published row, on a pass that then discards the
            // answer, is the O(rows x tags) reconcile this ordering avoids; the outcome is unchanged either way.
            if (judgeable && !live) {
                for (BlobLayout blobLayout : StoreRepositoryInventory.blobLayoutsFor(ecosystem)) {
                    if (!blobLayout.blobKeys(coordinate, version, store).isEmpty()) {
                        live = true;
                        break;
                    }
                }
            }
            if (kind == Holdings.Kind.CACHED) {
                // A cached copy is judged by the same liveness as a release: a live one gets its newest-first row
                // back if it lacks one, and one whose pointers are gone - reclaimed, removed, or a crash between
                // the two - stops being held. Nothing else about the version is touched: its origin trail and
                // verdict stay, as a reclaim leaves them.
                if (live) {
                    backfillCachedFace(ecosystem, coordinate, version, document);
                    return false;
                }
                if (!judgeable) {
                    return false;
                }
                metadata.mutate(ecosystem, coordinate, version, CachedSection.TAG, current -> null);
                NewestFirst.CACHED.forget(store, ecosystem, coordinate, version,
                        CachedSection.facts(document.section(CachedSection.TAG)).map(CachedSection.Facts::at)
                                .orElse(null));
                AdvisedSection.forget(store, AdvisedSection.advised(document.section(AdvisedSection.TAG)), ecosystem,
                        coordinate, version);
                ChangedVersions.forget(store, ecosystem, coordinate, version);
                return true;
            }
            if (live && facts.isPresent()) {
                if (copyRecordedAsRelease(document, facts.get())) {
                    recordAsCopy(ecosystem, coordinate, version, facts.get(), Holdings.upstream(document));
                    return false;
                }
                // The document already read backfills the two bounded listing faces for a live release that lacks
                // them.
                backfillFaces(ecosystem, coordinate, version, facts.get());
            }
            if (!judgeable || live) {
                return false;
            }
            metadata.mutate(ecosystem, coordinate, version, PublishedSection.TAG, current -> null);
            return true;
        }
    }

    /** Write the newest-first index row and, for a pinned release, the {@code pinned/} marker, unless each is already
     *  there - the idempotent backfill from facts a leg has already read. */
    private void backfillFaces(String ecosystem, String coordinate, String version, PublishedSection.Facts facts)
            throws IOException {
        if (facts.at() != null) {
            NewestFirst.RELEASES.ensure(store, ecosystem, coordinate, version, facts.at());
        }
        if (facts.pinned()) {
            String marker = StoreRepositoryInventory.pinnedKey(ecosystem, coordinate, version);
            if (store.readVersioned(marker).isEmpty()) {
                inventory.writeVersioned(marker, new byte[]{'1'});
            }
        }
    }
}
