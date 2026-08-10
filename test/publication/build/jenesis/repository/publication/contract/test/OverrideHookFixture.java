package build.jenesis.repository.publication.contract.test;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.testkit.PublicationHookFixture;

import module java.base;

/**
 * The {@link OverrideHook} fixture, plus the synthetic release surface it runs on.
 *
 * <p>Downstream owns the real surfaces ({@code GatedRepository.release}, {@code ComplianceReview.releaseQuarantined}),
 * so the free side models the same choreography over the one hold convention the core does own: a
 * {@code publish/quarantine<path>} review pointer, which {@code Publication} links and unlinks and whose transitions
 * drive the withhold-change feed. The ordering is the whole contract - <b>every hook first, the visibility mutation
 * last</b> - and it is written that way here so a hook that fails leaves the pointer exactly where a reviewer will
 * find it again.
 *
 * <p>The hook is constructed rather than discovered because there is no core service to discover it through, and
 * inventing one would be inventing a coupling the product does not have. The census records the four downstream
 * providers as out-of-graph instead, which is the honest statement of where the real fixtures belong.
 */
final class OverrideHookFixture implements PublicationHookFixture.Release {

    @Override
    public String hook() {
        return "kit-override-hook";
    }

    @Override
    public String providerClass() {
        return OverrideHook.class.getName();
    }

    @Override
    public PublicationHookFixture.ReleaseHook create() {
        return new OverrideHook();
    }

    @Override
    public List<String> namespaces() {
        return List.of(OverrideHook.SPACE);
    }

    @Override
    public Map<String, String> projection(ArtifactStore store) throws IOException {
        return Keys.rows(store, OverrideHook.OVERRIDES);
    }

    @Override
    public void hold(ArtifactStore store, String path, byte[] body) throws IOException {
        // Stored content-addressed and diverted straight to the review view: a retroactive hold is written by a sweep
        // long after the publish, so it has no screen to run and no visibility to declare.
        Publication publication = publication(store);
        String hash = publication.storeBlob(new ByteArrayInputStream(body));
        publication.link("/quarantine" + path, hash);            // stored, diverted, not served
        Keys.upsert(store, OverrideHook.RECORDS + "/" + Keys.slug(path), hash);
    }

    @Override
    public boolean held(ArtifactStore store, String path) throws IOException {
        return store.readVersioned("publish/quarantine" + path).isPresent();
    }

    @Override
    public boolean visible(ArtifactStore store, String path) throws IOException {
        return publication(store).located(path).isPresent();
    }

    @Override
    public boolean records(ArtifactStore store, String path) throws IOException {
        return store.readVersioned(OverrideHook.RECORDS + "/" + Keys.slug(path)).isPresent();
    }

    @Override
    public Optional<String> override(ArtifactStore store, String path) throws IOException {
        return Keys.read(store, OverrideHook.OVERRIDES + "/" + Keys.slug(path));
    }

    @Override
    public void release(ArtifactStore store, String path, List<PublicationHookFixture.ReleaseHook> hooks)
            throws IOException {
        // Every hook first, and the first failure propagates. Nothing below runs until all of them have succeeded,
        // which is what makes a failed fan-out leave the hold exactly where a reviewer will find it again.
        for (PublicationHookFixture.ReleaseHook hook : hooks) {
            hook.onReleased(store, path);
        }
        Optional<String> hash = Keys.read(store, "publish/quarantine" + path).map(String::trim);
        if (hash.isEmpty()) {
            return;    // nothing was held here: the release is a no-op, not a failure
        }
        Publication publication = publication(store);
        publication.link(path, hash.get());                      // the visibility mutation, last
        publication.unpublish("/quarantine" + path);             // and the review pointer is cleared
    }

    @Override
    public void discard(ArtifactStore store, String path, List<PublicationHookFixture.ReleaseHook> hooks)
            throws IOException {
        for (PublicationHookFixture.ReleaseHook hook : hooks) {
            hook.onDiscarded(store, path);
        }
        publication(store).unpublish("/quarantine" + path);      // thrown away: no pointer is ever linked at the path
    }

    /** A publication with explicitly empty hook lists, so the module's own discovered observers never ride a release
     *  check and write rows the fixture's projection would then have to explain away. */
    private static Publication publication(ArtifactStore store) {
        return new Publication(store, List.of(), List.of());
    }
}
