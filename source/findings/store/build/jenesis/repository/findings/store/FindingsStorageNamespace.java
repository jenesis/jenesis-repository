package build.jenesis.repository.findings.store;

import module java.base;

import build.jenesis.repository.findings.Findings;
import build.jenesis.repository.maintenance.StorageNamespace;

/**
 * The findings module's storage manifest. The per-coordinate findings rows are the {@code findings} section of the
 * consolidated metadata document ({@code meta}, owned by {@code MetadataStorageNamespace}), so this manifest declares
 * only the repository-level {@code findings/scanned} freshness stamp and {@code findings/evicted} eviction epoch -
 * repo-level singletons kept outside the document (§8) - and the durable findings-filter index. Per-coordinate
 * reclamation rides the document: the inventory's {@code evict} deletes the whole {@code meta} document with the
 * artifact, and a discarded quarantine hold drops just its findings section through the
 * {@link DiscardedHoldFindingsObserver}.
 */
public final class FindingsStorageNamespace implements StorageNamespace {

    @Override
    public Set<String> repositoryPrefixes() {
        // Two repo-level singletons kept outside the document (§8): the findings scan-freshness stamp and the eviction
        // epoch the vulnerability rank index folds into its rebuild stamp (bumped when a version's findings are
        // reclaimed by eviction, so an evicted line drops on the next rank-index pass, not the next scan); plus the
        // durable findings-filter index, a derived, bounded, generation-reclaimed view (like healthrank/vulnrank) that
        // lets a selective /api/findings query seek a facet bucket rather than scan the whole plane. The index is
        // rebuilt from the findings on the maintenance cadence and reclaimed generation-by-generation on each rebuild -
        // it never outlives the findings it derives from.
        return Set.of(Findings.SCANNED, Findings.EVICTED, FindingsFilterIndex.PREFIX);
    }
}
