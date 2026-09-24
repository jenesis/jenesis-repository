package build.jenesis.repository.gate.store;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.gate.QuarantineDispatch;

/**
 * The listener that records an edge-held OCI manifest's replay context, the OCI analog of the deploy edge's
 * {@code DeployEdgeHooks.held} and the import edge's held-import record. The OCI manifest choke point opts
 * out of the single-body ingress screen ({@code ScreenedDispatch}, whose {@code EdgeHooks.held} the deploy path records
 * from) and runs its own {@code Publication.screen} over the manifest, mapping a {@code QUARANTINE} verdict onto the
 * native {@code withheld/<hex>} marker rather than a {@code publish/} pointer. That choke point surfaces no seam of its
 * own to record the hold context on, so this module observes the screen where every screened publish is observed -
 * as a discovered {@link PublishInterceptor} on the same chain the gate rides - and, purely on an OCI manifest
 * {@code QUARANTINE}, writes the {@link QuarantineDispatch} descriptor beside the hold.
 *
 * <p>It has no say in the verdict ({@code assess} stays the default {@code ACCEPT}) and holds nothing back
 * ({@code withheld} stays {@code false}); it is a commit-time audit that captures exactly what a later review release
 * needs to complete the deferred OCI layout: the {@code oci} format, the {@link QuarantineDispatch#OCI OCI} method, the
 * stored manifest hash the descriptor already carries ({@link ArtifactDescriptor#hash}), and the manifest media type on
 * the context map's {@code Content-Type} key so {@link HoldLifecycle#release} reproduces the pushed manifest's type in
 * the {@code oci/types/<hex>} sidecar. The image name and tag are read back off the request path at release, so no
 * further context is captured. Every non-OCI publish, and an OCI {@code ACCEPT}/{@code REJECT}, is a no-op - a
 * quarantined deploy/import already records its own dispatch through its own edge, and an accepted or rejected manifest
 * has no hold to release.
 */
public final class OciHoldRecorder implements PublishInterceptor {

    /** The neutral OCI ecosystem the manifest choke point stamps on its descriptor, and the manifest path marker the
     *  Distribution API pins at the host root - the two facts that identify a held OCI manifest without re-parsing a
     *  layout. */
    private static final String OCI_ECOSYSTEM = "oci";

    /** Record the OCI manifest hold's replay context on a manifest {@code QUARANTINE}, and nothing otherwise. The
     *  descriptor already carries the stored blob hash and the manifest media type (its {@code contentType}), so the
     *  record needs no store read of its own; the write is {@link QuarantineDispatch#record}'s bounded compare-and-set,
     *  so a re-screen of the same manifest overwrites rather than duplicating. */
    @Override
    public void committed(ArtifactDescriptor artifact, Disposition disposition, ArtifactStore store)
            throws IOException {
        if (disposition != Disposition.QUARANTINE || !OCI_ECOSYSTEM.equals(artifact.ecosystem())) {
            return;
        }
        String path = artifact.path();
        if (path == null || !path.startsWith("/v2/") || !path.contains("/manifests/")) {
            return;                                          // not an OCI manifest push - nothing to complete on release
        }
        Map<String, String> context = new LinkedHashMap<>();
        if (artifact.contentType() != null && !artifact.contentType().isBlank()) {
            // The one datum a release needs beyond the path and hash: the pushed manifest's media type, reproduced into
            // the oci/types/<hex> sidecar. Carried on the Content-Type key the deploy dispatch also frames from, so the
            // stored descriptor stays the same shape; absent, the release defaults to the OCI image-manifest type.
            context.put("Content-Type", artifact.contentType());
        }
        QuarantineDispatch.record(store, path, OCI_ECOSYSTEM, QuarantineDispatch.OCI, artifact.hash(), context);
    }
}
