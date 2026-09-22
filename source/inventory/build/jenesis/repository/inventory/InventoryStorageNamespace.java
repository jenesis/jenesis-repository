package build.jenesis.repository.inventory;

import module java.base;

import build.jenesis.repository.maintenance.StorageNamespace;
import build.jenesis.repository.metadata.MetadataProvider;

/**
 * The inventory module's storage manifest: it owns the per-repository derived rows it maintains beside the
 * pointers - the {@code downloaded} markers, the {@code identity} rollup digest ({@link InventoryIdentity}, one small
 * accumulator per repository), and the {@code sizes} subtree roll-ups ({@link SubtreeSizeRollUp}, one cached total per
 * browse folder) - so the orphan diagnostic and the explicit operator purge know the key-spaces without a hardcoded
 * table. The identity rollup rides the inventory lifecycle (maintained by {@code record}/{@code evict}, rebuilt by
 * {@code reconcile}); the {@code sizes} roll-ups are recomputed and compacted by the {@code rollUpSizes} sweep, which
 * deletes the cached total of every folder no longer in the live {@code publish/} tree - so the space tracks the browse
 * tree rather than growing per artifact, and was declared here (with {@code identity}) so it stops being
 * purge-invisible.
 *
 * <p><strong>{@code retention} joined it.</strong> A repository's {@link build.jenesis.repository.cleanup.RetentionPolicy}
 * used to be stored at {@code config/retention} - per-repository data spelled with the name of the deployment-global
 * reserved root - which no manifest entry could describe at all, so purging this module left every repository's
 * deletion policy behind. The key moved to the repository-scoped {@code retention} ({@link InventoryRetention#KEY}) and
 * is declared here, which is the same purge-invisibility {@code identity} and {@code sizes} had closed for them.
 *
 * <p><strong>{@code published} is claimed on both layouts, and only {@code licenses} is not.</strong> The three
 * cut-over spaces were un-claimed together on the installed layout, on the reasoning that the facts had moved into
 * the coordinate documents and nothing could still read the sidecar. That held for {@code licenses}, whose
 * {@code LicenseInventory.record} takes the sidecar branch only when no metadata store is bound. It did not hold
 * for {@code published}: {@code InventoryRecording.membership} still probes it, to tell "no record anywhere" from
 * "a record on the plane this deployment does not read" - and the reconcile sweep deletes on the first answer, so
 * that probe is what stands between an un-migrated store and the reaping of an operator's explicit force-keep and
 * its KEV overrides. Offering the prefix as an orphan while membership depended on it invited a purge of exactly
 * that. The read stays and the claim widens, which trades an irreversible loss for a recoverable one: a genuinely
 * dead sidecar is no longer surfaced for reclamation, and an explicit operator purge still reaches it.
 *
 * <p><strong>{@code licenses} removed (§5.4), and {@code published}/{@code pinned} before it.</strong> The
 * declared-license facts were cut over into the {@code licenses} section of the consolidated metadata document
 * ({@code meta}, owned by {@code MetadataStorageNamespace}), as the publish facts were into its {@code published}
 * section (the pin a field of that section), so this manifest declares none of those three prefixes <em>while
 * the metadata module is installed</em>: a sidecar left behind after the cutover surfaces as an <em>orphan</em> under
 * the reconcile completeness sweep rather than hiding under a manifest entry. Nothing folds it in and nothing reads
 * it; reclaiming it is the operator's explicit purge - the "absence never deletes" posture surfacing what is
 * unreachable.
 *
 * <p><b>that reasoning does not survive the deployment which never installs the module at all.</b> This module
 * is the widest case of the shape, because all three of its cut-over spaces stay live together on the
 * graceful-absence layout: {@link StoreRepositoryInventory#publishedRoot()} <em>is</em> {@code published/} there and
 * the whole published-set enumeration walks it, {@link InventoryPins} keeps every pin - a human's explicit force-keep
 * - on the {@code pinned/} sidecar, and {@link LicenseInventory} keeps both its record and its read on
 * {@code licenses/}. Those rows are not residue but the deployment's entire membership ledger; reported as orphans
 * they read as reclaimable, which is an invitation to purge the record of what is published. The manifest therefore
 * claims all three exactly when {@link MetadataProvider#installed()} is empty, switching on the same seam the
 * inventory switches its own layout on, so the two can never disagree about which layout is in force. The
 * distinction that matters is not who wrote the key but whether anything can still read it.
 *
 * <p><b>Every prefix is named from its composer's constant, never re-spelled.</b> A manifest that spells a
 * space itself is a second spelling of it: rename the root in the class that composes the keys and the declaration
 * survives naming a space nothing writes, so the operator purge composes a key the walk lists nothing under and the
 * dry run reports an empty blast radius while nothing is reclaimed.
 */
public final class InventoryStorageNamespace implements StorageNamespace {

    @Override
    public Set<String> repositoryPrefixes() {
        // The pinned/ markers and the recent/ index are written in both layouts: they are the bounded faces the
        // console lists pins and the newest releases through, whichever document the facts themselves live in.
        if (MetadataProvider.installed().isPresent()) {
            // published/ joins them, and for a different reason than they did. With the module installed the
            // publish facts live in the coordinate documents and the sidecar is residue - but
            // InventoryRecording.membership still PROBES it, to answer Known.Unknown rather than Known.Absent for
            // a version whose record stands on the plane this deployment does not read, and the reconcile sweep
            // DELETES on the second answer. Un-claiming a prefix that something still reads made the completeness
            // sweep offer an operator the very key that stops their force-keeps and KEV overrides being reaped.
            return Set.of(StoreRepositoryInventory.DOWNLOADED, InventoryIdentity.ROOT, InventoryRetention.KEY,
                    SubtreeSizeRollUp.ROOT, StoreRepositoryInventory.PINNED, RecentReleases.ROOT,
                    StoreRepositoryInventory.PUBLISHED);
        }
        // with no metadata persistence module installed the inventory takes its graceful-absence layout, and there
        // all three cut-over spaces are read as well as written - publishedRoot() resolves to published/ and every
        // membership question, enumeration and eviction goes through it, InventoryPins lists and clears pins out of
        // pinned/, and LicenseInventory.read answers from licenses/. That is this deployment's live publish ledger,
        // so the manifest has to claim the three roots or the completeness sweep reports every published version,
        // every operator force-keep and every license record as an orphan., so this claims that root too -
        // conditional for the reason StorageNamespace states.
        return Set.of(StoreRepositoryInventory.DOWNLOADED, InventoryIdentity.ROOT, InventoryRetention.KEY,
                SubtreeSizeRollUp.ROOT, StoreRepositoryInventory.PUBLISHED, StoreRepositoryInventory.PINNED,
                LicenseInventory.ROOT, RecentReleases.ROOT);
    }
}
