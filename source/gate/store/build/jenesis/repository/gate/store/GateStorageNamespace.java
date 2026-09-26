package build.jenesis.repository.gate.store;

import module java.base;

import build.jenesis.repository.inventory.HeldSubjects;
import build.jenesis.repository.inventory.HoldMarkers;
import build.jenesis.repository.inventory.OverrideRecords;
import build.jenesis.repository.maintenance.StorageNamespace;
import build.jenesis.repository.gate.QuarantineLog;

/**
 * The gate's storage manifest: it owns the per-repository quarantine record ({@code audit/quarantine} rows and
 * the {@code audit/quarantine-index} paging index the {@link QuarantineLog} keeps) and the hold bookkeeping
 * ({@code holds} for the open holds, {@code overrides} for the release decisions, {@code subjects} for what each
 * held path is a path to), so the orphan diagnostic and the explicit operator purge know the key-spaces without a
 * hardcoded table.
 *
 * <p>{@code subjects} is declared here rather than by the inventory module for the same reason {@code overrides} is
 *: a declaration is ownership <em>for reclamation</em>, and what the space belongs to is the hold, which is
 * the gate's. Its key spelling has one owner one module down - {@code HeldSubjects} in the inventory, beside the
 * sibling per-version spaces and reachable from the name-enumeration screen that reads it.
 *
 * <p><b>Every prefix is named from its composer's constant, never re-spelled.</b> Three of these five spaces
 * are composed in a different module from the one that declares them, which is exactly the split that rots: a manifest
 * entry re-spelling the literal survives a rename in the owner, so the operator purge composes a key nothing writes,
 * the walk lists nothing under it, and the dry run reports an empty blast radius while the purge reclaims nothing -
 * the failure once found in the well-formedness direction and again in the reaper.
 */
public final class GateStorageNamespace implements StorageNamespace {

    @Override
    public Set<String> repositoryPrefixes() {
        return Set.of(QuarantineLog.ROOT, QuarantineLog.INDEX_ROOT, HoldMarkers.ROOT, OverrideRecords.ROOT,
                HeldSubjects.ROOT);
    }
}
