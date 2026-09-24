package build.jenesis.repository.findings.store;

import module java.base;

import build.jenesis.repository.findings.Findings;
import build.jenesis.repository.maintenance.StorageNamespace;
import build.jenesis.repository.metadata.MetadataProvider;

/**
 * The findings module's storage manifest. As of (§5.4) the per-coordinate findings rows were cut over into the
 * {@code findings} section of the consolidated metadata document ({@code meta}, owned by
 * {@code MetadataStorageNamespace}), so this manifest no longer declares the whole {@code findings/} prefix - only the
 * repository-level {@code findings/scanned} freshness stamp and {@code findings/evicted} eviction epoch, repo-level
 * singletons the consolidation deliberately keeps outside the document (§8), plus the durable findings-filter index. Dropping the ledger's key-space from this manifest is deliberate <em>while the
 * metadata module is installed</em>: a {@code findings/<eco>/<coord>/<version>} sidecar left behind after the cutover
 * surfaces as an <em>orphan</em> under the reconcile completeness sweep rather than hiding under a manifest entry.
 * Nothing folds it into the document and nothing reads it: reclaiming it is the operator's explicit purge, the
 * "absence never deletes" posture surfacing what is unreachable.
 *
 * <p><b>that reasoning does not survive the deployment which never installs the module at all.</b> There the
 * same prefix is not residue - {@link StoreFindings} takes its graceful-absence layout and keeps both reads and writes
 * on the sidecar, with a documented point read and a repository-wide walk over it - so the rows are the deployment's
 * live findings ledger. Reported as orphans they read as reclaimable, which is an invitation to purge live data. The
 * manifest therefore claims the prefix exactly when {@link MetadataProvider#installed()} is empty, switching on the
 * same seam {@code StoreFindings} switches its own layout on, so the two can never disagree about which layout is in
 * force. The distinction that matters is not who wrote the key but whether anything can still read it. Per-coordinate reclamation now rides the document: the inventory's
 * {@code evict} deletes the whole {@code meta} document with the artifact, and a discarded quarantine hold drops just
 * its findings section through the {@link DiscardedHoldFindingsObserver}.
 */
public final class FindingsStorageNamespace implements StorageNamespace {

    @Override
    public Set<String> repositoryPrefixes() {
        // Two repo-level singletons the consolidation keeps outside the document (§8): the findings scan-freshness stamp
        // and the eviction epoch the vulnerability rank index folds into its rebuild stamp (bumped when a version's
        // findings are reclaimed by eviction, so an evicted line drops on the next rank-index pass, not the next scan);
        // plus the durable findings-filter index, a derived, bounded, generation-reclaimed view (like healthrank/
        // vulnrank) that lets a selective /api/findings query seek a facet bucket rather than scan the whole plane. The
        // index is rebuilt from the findings on the maintenance cadence and reclaimed generation-by-generation on each
        // rebuild - it never outlives the findings it derives from.
        if (MetadataProvider.installed().isPresent()) {
            return Set.of(Findings.SCANNED, Findings.EVICTED, FindingsFilterIndex.PREFIX);
        }
        // with no metadata persistence module installed, StoreFindings takes its graceful-absence layout and both
        // reads AND writes stay on the findings/<eco>/<coord>/<version> sidecar - there is a documented read for it
        // ("the graceful-absence read") and a repository-wide walk over the same prefix, so this claims that root too
        // - conditional for the reason StorageNamespace states.
        return Set.of(Findings.SCANNED, Findings.EVICTED, FindingsFilterIndex.PREFIX, Findings.PREFIX);
    }
}
