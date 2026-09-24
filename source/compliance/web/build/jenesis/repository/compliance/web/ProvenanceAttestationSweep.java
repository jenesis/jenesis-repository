package build.jenesis.repository.compliance.web;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Names;

/**
 * Reclaims provenance attestations whose artifact is gone - the sweep half of a reaper that only ever had an
 * event-driven half.
 *
 * <p>{@link ProvenanceAttestationReaper} deletes an attestation off the {@code onDeleted} notification, which is
 * sound and covers the ordinary case. What it cannot reach is everything the notification does not carry, and its own
 * javadoc names each one: a delete that failed on a transient store error (logged, moved past), a descriptor whose
 * blob identity could not be completed (skipped - without the content half there is no key), and every attestation
 * written before the reaper was installed. That residue is exactly the population a converging pass exists for, and
 * there was none.
 *
 * <p><b>The orphan test, and the thing it deliberately does not attempt.</b> An attestation is keyed
 * {@code provenance-attestation/<sha256>/<digest-of-path>} - the path is hashed, so the key cannot be read backwards
 * into the path it describes. What it CAN answer is whether the content still exists: if {@code blobs/<sha256>} is
 * gone, nothing can serve those bytes under any name, so every attestation beneath that hash is dead and is removed.
 *
 * <p>An attestation whose blob is still live but whose particular path no longer serves it is <b>not</b> reclaimed
 * here. Deciding that needs a hash-to-paths reverse index the store does not offer ({@code BlobLayout.servedPaths} is
 * keyed by coordinate, not by content), and the alternative - inferring it from an enumeration that might be
 * incomplete - is the failure this plan keeps returning to: absence read as evidence. Under-reclaiming leaves a small
 * derived record; over-reclaiming destroys the provenance of a live artifact. The sweep errs the first way and says
 * so rather than guessing.
 *
 * <p><b>A store that cannot answer keeps its attestations.</b> Every probe is a point read, and a failure is a
 * <em>skip</em>, never a delete: on a store having a bad minute the honest outcome is that nothing is reclaimed this
 * pass, not that everything looks orphaned at once.
 */
public final class ProvenanceAttestationSweep implements MaintenanceTask {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ProvenanceAttestationSweep.class);

    /** How many hashes one page holds. Every pass examines every hash, a page at a time, so a pass holds one page
     *  and nothing else: the unpaged listing this replaced held every hash, and the first {@code 500} of it was all a
     *  pass ever examined - "resumable by construction" said the javadoc, and there was no cursor, so an orphan behind
     *  five hundred live attestations was never reached. The healing suite proves it with six hundred. A pass is
     *  one point probe per hash, which is what a daily walk may cost (&sect;1). */
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
            // The container itself did not enumerate. Nothing is orphaned on that evidence - it is the absence of
            // evidence - so the pass reports what it has and returns.
            LOGGER.warn("The provenance-attestation container did not enumerate to its end; the rest was not examined "
                    + "this pass.", unreadable);
        }
        context.gauge("jenreg.provenance.attestations.reclaimed",
                "Provenance attestations removed because their content is gone",
                Map.of("repository", context.repository()), reclaimed);
        context.gauge("jenreg.provenance.attestations.unreadable",
                "Attestation hashes this pass could not judge, and therefore kept",
                Map.of("repository", context.repository()), skipped);
    }

    /** Whether the content still exists, or null when the store could not say - the third answer that keeps this
     *  sweep from reading a bad minute as a mass eviction. */
    private static Boolean blobLives(ArtifactStore store, String hash) {
        try {
            return store.exists("blobs/" + hash);
        } catch (RuntimeException | Error unreadable) {
            return null;
        }
    }

    /** Every attestation recorded under one dead hash. Contained per entry: one unreadable child does not abandon
     *  the rest, and does not turn into a delete either. */
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
