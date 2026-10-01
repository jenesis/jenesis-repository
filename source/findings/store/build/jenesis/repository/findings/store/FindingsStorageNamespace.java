package build.jenesis.repository.findings.store;

import module java.base;

import build.jenesis.repository.findings.Findings;
import build.jenesis.repository.maintenance.StorageNamespace;

/**
 * The findings module's storage manifest. The per-coordinate rows are a section of the metadata document, owned by
 * {@code MetadataStorageNamespace}, so this declares only the repository-level {@code findings/scanned} stamp and
 * {@code findings/evicted} epoch and the findings-filter index. Per-coordinate reclamation rides the document: eviction
 * deletes it with the artifact, and {@link DiscardedHoldFindingsObserver} drops a discarded hold's section.
 */
public final class FindingsStorageNamespace implements StorageNamespace {

    @Override
    public Set<String> repositoryPrefixes() {
        // The scan-freshness stamp, the eviction epoch the rank index folds into its rebuild stamp, and the filter
        // index, whose generations are reclaimed on each rebuild.
        return Set.of(Findings.SCANNED, Findings.EVICTED, FindingsFilterIndex.PREFIX);
    }
}
