package build.jenesis.repository.inventory;

import module java.base;

import build.jenesis.repository.maintenance.StorageNamespace;

/**
 * The inventory module's storage manifest: it owns the per-repository derived rows it maintains beside the
 * pointers and the version documents - the {@code identity} rollup digest ({@link InventoryIdentity}, one small
 * accumulator per repository), the {@code sizes} subtree roll-ups ({@link SubtreeSizeRollUp}, one cached total per
 * browse folder), the repository's {@code retention} policy ({@link InventoryRetention#KEY}), the {@code pinned}
 * index and the {@code recent} releases index - so the orphan diagnostic and the explicit operator purge know the
 * key-spaces without a hardcoded table. The publish facts, the downloads and the declared licences are sections of the
 * version documents under {@code meta}, which the metadata store's own manifest declares.
 *
 * <p>The identity rollup rides the inventory lifecycle (maintained by {@code record}/{@code evict}, rebuilt by
 * {@code reconcile}); the {@code sizes} roll-ups are recomputed and compacted by the {@code rollUpSizes} sweep, which
 * deletes the cached total of every folder no longer in the live {@code publish/} tree, so the space tracks the browse
 * tree rather than growing per artifact. The {@code pinned/} markers and the {@code recent/} index are the bounded
 * faces the console lists pins and the newest releases through.
 *
 * <p><b>Every prefix is named from its composer's constant, never re-spelled.</b> A manifest that spells a space
 * itself is a second spelling of it: rename the root in the class that composes the keys and the declaration survives
 * naming a space nothing writes, so the operator purge composes a key the walk lists nothing under and the dry run
 * reports an empty blast radius while nothing is reclaimed.
 */
public final class InventoryStorageNamespace implements StorageNamespace {

    @Override
    public Set<String> repositoryPrefixes() {
        return Set.of(InventoryIdentity.ROOT, InventoryRetention.KEY, SubtreeSizeRollUp.ROOT,
                StoreRepositoryInventory.PINNED, RecentReleases.ROOT);
    }
}
