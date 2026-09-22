package build.jenesis.repository.inventory;

import module java.base;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.metadata.MetadataDocument;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Known;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.walk.ArtifactWalk;

/**
 * The reconcile sweep extracted from {@link StoreRepositoryInventory}: the §4/§5 convergence backstop that rebuilds the
 * <em>publish facts</em> from the live {@code publish/} pointer tree in both directions and sweeps the derived
 * per-version key spaces of no-longer-published versions. As of the publish facts are the {@code published}
 * section of the consolidated metadata document (its presence is membership of the published set), so the forward leg
 * restores a missing section and the reverse leg removes a section whose pointers are gone; with no metadata store
 * installed the legs fall back to the legacy {@code published/} sidecars, the pre-cutover behaviour. The inventory owns
 * the seam - {@link StoreRepositoryInventory#reconcile} delegates here - so this class carries only the sweep legs and
 * calls back into the inventory core for the format-driven {@code describe}/{@code record}, the membership check and the
 * shared key/layout helpers.
 *
 * <p>It opens walks of its own rather than riding the shared rebuild pass: the reverse leg walks the derived roots,
 * which that pass never visits, and the forward leg must see every {@code publish/} pointer including the withheld
 * ones that pass screens out - a held version is still a published one.
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
     * derived per-version key spaces - the {@code downloaded/} markers, the {@code overrides/<kind>/} hold-overrides and
     * the {@code licenses/}/{@code pinned/} sidecars of a graceful-absence deployment - deleting every row whose
     * version is no longer published <em>and</em> whose absence an installed format can positively confirm; a row
     * nothing installed can judge is left alone. Reads only the tiny pointers and documents, never an artifact blob, and commits
     * through the store's compare-and-set, so it runs identically on filesystem and every object store. Idempotent: a
     * section that already exists is left untouched and a re-run over a converged repository restores and removes nothing.
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
        // leg cannot yet rebuild) removeOrphanDerived additionally spares any version still live under its format's
        // pointers, and any version whose format is not installed at all, so deleting a row can never orphan a live
        // version's bookkeeping (a human pin especially).
        return new StoreRepositoryInventory.Reconciliation(restored, removed, removeOrphanDerived(walk));
    }

    /** Delete every {@code downloaded/} and {@code overrides/<kind>/} row - and every {@code licenses/}/
     *  {@code pinned/} sidecar a graceful-absence deployment wrote - whose coordinate version is <em>provably</em>
     *  gone: no published membership AND a format that can place the coordinate says it has no pointer left, the
     *  derived residue of an eviction that crashed between deletes. One shared-walk pass; a row that judges the same
     *  is deleted the same, and a replayed visit after a crash-resume finds the row already gone.
     *
     *  <p>Both halves of the judgment are three-valued, and for one reason: each is answered through a module that may
     *  not be installed. an earlier change made the liveness half so; made the membership half so, because which plane
     *  carries the publish facts - the consolidated {@code meta} document or the legacy {@code published/} sidecar -
     *  is chosen by the installed metadata persistence, so removing that module (or installing it over a store written
     *  without one) flipped every version in the repository to "not a published member" and left this sweep judging
     *  the whole store by liveness alone.
     *
     *  <p>The third state is the one that matters: a row whose ecosystem <em>no installed format owns</em> is
     *  {@link Known.Unknown} and is left exactly where it is. Uninstalling a format module used to make
     *  {@code isLive} answer {@code false} for every one of its versions, and this sweep then deleted their
     *  {@code pinned/}, {@code overrides/}, {@code licenses/} and {@code downloaded/} rows - a human's force-keep and
     *  a human's clearance of a hold, deleted because a module was absent, which this product's standing rule forbids
     *  and which had just closed one family over for {@code holds/}. A version whose format is gone is not dead;
     *  it is unreadable by that format right now, and "I cannot tell" must never share an outcome with "it is gone".
     *  The genuine orphan is still reaped, because it is still provable: an installed format that can place the
     *  coordinate and finds no pointer for it. */
    private int removeOrphanDerived(ArtifactWalk walk) throws IOException {
        int[] removed = {0};
        walk.walk(store, "reconcile-derived", List.of(StoreRepositoryInventory.DOWNLOADED, LicenseInventory.ROOT,
                        OverrideRecords.ROOT, StoreRepositoryInventory.PINNED),
                key -> {
            if (judgeDerived(key)) {
                removed[0]++;
            }
        });
        return removed[0];
    }

    /** The derived leg for one row under {@code downloaded/}, {@code licenses/}, {@code overrides/} or
     *  {@code pinned/}: remove it when its version is provably unpublished and provably gone; {@code true} when it
     *  was removed. */
    boolean judgeDerived(String key) throws IOException {
        {
            String[] parts = key.split("/");
            String ecosystem;
            String coordinate;
            String version;
            if (OverrideRecords.ROOT.equals(parts[0])) {
                // overrides/<kind>/<eco>/<coord>/<ver>, every segment encoded - parsed by the space's one owner
                // rather than re-spelled here, which is exactly the drift closed.
                Optional<OverrideRecords.Row> row = OverrideRecords.parse(key);
                if (row.isEmpty()) {
                    return false;
                }
                ecosystem = row.get().ecosystem();
                coordinate = row.get().coordinate();
                version = row.get().version();
            } else {
                if (parts.length != 4) {                         // downloaded|licenses|pinned/<eco>/<coord>/<ver>
                    return false;
                }
                ecosystem = parts[1];
                coordinate = StoreRepositoryInventory.decode(parts[2]);
                version = parts[3];
            }
            // Membership is three-valued for the same reason liveness is: which plane carries the publish
            // facts is chosen by the installed metadata persistence, so an absent (or newly-installed) module flips
            // every version to "not a member" - and this sweep deletes on that answer. Both arms below are written
            // out: the sweep deletes on Absent alone, and Present and Unknown share an outcome only because BOTH are
            // reasons to keep the row - not because the third state was folded into either of them.
            boolean provablyUnpublished = switch (inventory.membership(ecosystem, coordinate, version)) {
                case Known.Absent<PublishedSection.Facts> _ -> true;
                case Known.Present<PublishedSection.Facts> _ -> false;   // a live or just-restored release
                case Known.Unknown<PublishedSection.Facts> _ -> false;   // a membership nothing here can read
            };
            if (!provablyUnpublished) {
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
     *  <p>Stated precisely, because "a known gap" understated it. The exposure is generic - a crash between writing
     *  a serving pointer and writing its {@code published/} section leaves a row nothing repairs - and it applies to
     *  every blobs-namespace format, of which the product installs around twenty. Only {@code oci} is covered, by
     *  {@code InventoryBackfillConsumer}, which reads the coordinate back out of each stored pointer through
     *  {@code BlobLayout.describePointer} - so a format that can name its own keys is repaired and one that cannot
     *  is not. A missing row does not make the artifact disappear - it stays enumerable through its own format
     *  and the derived-row sweep spares its live rows - but a retroactive sweep does not see it, so it can keep
     *  serving a later-listed CVE while the held gauge reads clean.
     *
     *  <p>The reason it is not simply generalised is that the SPI has no reverse mapping. {@code BlobLayout} maps a
     *  coordinate forward ({@code blobKeys}, {@code blobHashes}, {@code servedPaths}) and a request path to a store
     *  key ({@code servingKey}); nothing maps a stored pointer back to the coordinate and version it belongs to,
     *  and the {@code by/} indexes are per-format answers to other questions rather than a general one. Closing it
     *  therefore means a new defaulted clause on {@code BlobLayout} - which coordinate version is this pointer -
     *  plus an implementation per format, on a path that writes the rows retention ages by. That is its own change
     *  with its own tests, not a line added here. */
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
        // Only where the version's absence from the published set is an answer rather than an unread plane
        //: re-recording a version whose facts stand in the plane this deployment does not read would
        // re-stamp its publish instant at now on every pass, silently resetting the age retention evicts by.
        Known<PublishedSection.Facts> membership = inventory.membership(
                artifact.ecosystem(), artifact.coordinate(), artifact.version());
        boolean restorable = switch (membership) {
            case Known.Absent<PublishedSection.Facts> _ -> true;
            case Known.Present<PublishedSection.Facts> _ -> false;      // already recorded; idempotent per pointer
            case Known.Unknown<PublishedSection.Facts> _ -> false;      // its facts stand on the unread plane
        };
        if (restorable) {
            inventory.record(artifact, now);                            // reconcile-time: the conservative instant
            return true;
        }
        if (membership instanceof Known.Present<PublishedSection.Facts> present) {
            // The facts just read also backfill the two bounded listing faces for a release recorded before
            // they existed: the newest-first index row and the pinned/ marker. No further read: both are
            // idempotent writes guarded by a presence probe of their own small key.
            backfillFaces(artifact.ecosystem(), artifact.coordinate(), artifact.version(), present.value());
        }
        return false;
    }

    /** For every published member whose coordinate has no surviving pointer - {@code publish/} for a
     *  Publication-namespace layout, the format's own {@link BlobLayout#blobKeys blob keys} for a blobs-namespace one -
     *  remove its publish facts: the reverse-repair direction, a crashed eviction's residue. When the metadata store is
     *  installed the {@code published} section is dropped from the document (the licenses and any sibling sections stay);
     *  otherwise the legacy {@code published/} sidecar is deleted. A member nothing can judge is kept: an ecosystem with
     *  no installed format, or a roots-only format whose version pointers are not enumerable from the coordinate -
     *  removing on "found nothing" there would wipe the retention books of every live release. One shared-walk pass over
     *  the {@link StoreRepositoryInventory#publishedRoot} tree; a replayed visit re-judges the row and a removed one is
     *  no longer a member. */
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
        boolean consolidated = metadata != null;
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
            Optional<PublishedSection.Facts> facts = Optional.empty();
            if (consolidated) {
                // Membership is the document's published section; a licenses-only document is not a member and is left
                // untouched. A legacy published/ key is a member by its existence - judged from the key alone, no read.
                Optional<ArtifactStore.Versioned> document = store.readVersioned(key);
                Section section = document
                        .map(versioned -> MetadataDocument.read(versioned.content())
                                .section(PublishedSection.TAG).orElse(null))
                        .orElse(null);
                if (!PublishedSection.published(Optional.ofNullable(section))) {
                    return false;
                }
                facts = PublishedSection.facts(Optional.ofNullable(section));
            }
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
            if (live && facts.isPresent()) {
                // The document already read backfills the two bounded listing faces for a live release recorded
                // before they existed (a legacy published/ row is judged from its key alone and is not read here;
                // its publish-namespace releases are backfilled by the pointer leg above).
                backfillFaces(ecosystem, coordinate, version, facts.get());
            }
            if (!judgeable || live) {
                return false;
            }
            if (metadata != null) {
                metadata.mutate(ecosystem, coordinate, version, PublishedSection.TAG, current -> null);
            } else {
                store.delete(key);
            }
            return true;
        }
    }

    /** Write the newest-first index row and, for a pinned release, the {@code pinned/} marker, unless each is already
     *  there - the idempotent backfill from facts a leg has already read. */
    private void backfillFaces(String ecosystem, String coordinate, String version, PublishedSection.Facts facts)
            throws IOException {
        if (facts.at() != null) {
            RecentReleases.ensure(store, ecosystem, coordinate, version, facts.at());
        }
        if (facts.pinned()) {
            String marker = StoreRepositoryInventory.pinnedKey(ecosystem, coordinate, version);
            if (store.readVersioned(marker).isEmpty()) {
                inventory.writeVersioned(marker, new byte[]{'1'});
            }
        }
    }
}
