package build.jenesis.repository.compliance.web;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Names;

/**
 * Reclaims provenance attestations whose artifact is gone, for what {@link ProvenanceAttestationReaper}'s
 * {@code onDeleted} path cannot reach: a delete that failed, a descriptor without a blob identity, an attestation
 * written before the reaper was installed.
 *
 * <p>An attestation is keyed {@code provenance-attestation/<sha256>/<digest-of-path>}, so the key cannot be read back
 * into its path; what it can answer is whether the content exists. With {@code blobs/<sha256>} gone, every attestation
 * under that hash is removed. One whose blob lives on but whose path no longer serves it is kept, since telling would
 * need a hash-to-paths index the store does not offer, and over-reclaiming destroys a live artifact's provenance.
 *
 * <p>Every probe is a point read, and a failed one skips rather than deletes.
 */
public final class ProvenanceAttestationSweep implements MaintenanceTask {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ProvenanceAttestationSweep.class);

    /** How many hashes one page holds; a pass pages through every hash, one point probe each. */
    static final int PAGE = 500;

    private final Duration interval;

    ProvenanceAttestationSweep(Duration interval) {
        this.interval = interval;
    }

    @Override
    public String name() {
        return "provenance-attestation-sweep";
    }

    @Override
    public Duration interval() {
        return interval;
    }

    @Override
    public void repository(RepositoryContext context) throws IOException {
        ArtifactStore store = context.store();
        int reclaimed = 0;
        int skipped = 0;
        Names hashes = Names.over(store, ProvenanceAttestationCache.PREFIX, PAGE);
        try {
            for (String hash = hashes.next(); hash != null; hash = hashes.next()) {
                Boolean live = blobLives(store, hash);
                if (live == null) {
                    skipped++;   // could not tell - keep every attestation under it
                    continue;
                }
                if (live) {
                    continue;    // the content still exists; this sweep does not judge individual paths (see the javadoc)
                }
                reclaimed += removeAll(store, hash);
            }
        } catch (RuntimeException unreadable) {
            // An enumeration failure is no evidence of orphans.
            LOGGER.warn("The provenance-attestation container did not enumerate to its end; the rest was not examined "
                    + "this pass.", unreadable);
        }
        context.gauge("jenrepo.provenance.attestations.reclaimed",
                "Provenance attestations removed because their content is gone",
                Map.of("repository", context.repository()), reclaimed);
        context.gauge("jenrepo.provenance.attestations.unreadable",
                "Attestation hashes this pass could not judge, and therefore kept",
                Map.of("repository", context.repository()), skipped);
    }

    /** Whether the content still exists, or {@code null} when the store could not say. */
    private static Boolean blobLives(ArtifactStore store, String hash) {
        try {
            return store.exists("blobs/" + hash);
        } catch (RuntimeException | Error unreadable) {
            return null;
        }
    }

    /** Removes every attestation under one dead hash, containing each entry's failure. */
    private static int removeAll(ArtifactStore store, String hash) {
        String container = ProvenanceAttestationCache.PREFIX + "/" + hash;
        int removed = 0;
        List<String> children;
        try {
            children = store.list(container);
        } catch (RuntimeException unreadable) {
            return 0;
        }
        for (String child : children) {
            try {
                store.delete(container + "/" + child);
                removed++;
            } catch (IOException | RuntimeException failed) {
                LOGGER.warn("Could not reclaim the attestation {}/{}; it stays and the next pass retries.",
                        container, child, failed);
            }
        }
        return removed;
    }
}
