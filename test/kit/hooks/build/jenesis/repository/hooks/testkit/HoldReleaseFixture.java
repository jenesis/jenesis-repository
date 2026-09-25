package build.jenesis.repository.hooks.testkit;

import module java.base;

import build.jenesis.repository.gate.HoldReleaseObserver;
import build.jenesis.repository.gate.store.GatedRepository;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.store.testkit.PublicationHookFixture;

/**
 * The shared shape of every {@code HoldReleaseObserver} fixture: the <b>real</b> release surface, driven through
 * {@code GatedRepository}, over the retroactive-hold state a sweep actually leaves.
 *
 * <p><b>The hold is the retroactive shape, not a diverted upload.</b> These hooks exist for the sweeps - the
 * known-exploited and licence sweeps among them - which hold a version that is <em>already serving</em>: they link a
 * {@code publish/quarantine<path>} review pointer over a live release pointer and write their own
 * {@code holds/<kind>/...} record. {@link #hold} reproduces exactly that, and it is what makes
 * {@code HoldLifecycle.release} take the branch it takes in production (a present pointer is deliberately left alone,
 * so no phantom re-link and no publish-time sidecar are synthesised).
 *
 * <p><b>The hooks the kit hands over are the ones the surface fans out to.</b> The kit passes its own poison hook
 * among the real ones and, on the falsification leg, a mutated copy of the hook under test; {@code GatedRepository}
 * takes the hooks it fans out to, so the real choreography runs with exactly those in it. It used to discover its
 * fan-out itself, which left a fixture two bad choices: hand-fan the kit's hooks and then call a surface that ran the
 * genuine hook again - so a mutant that omitted work was answered by the discovered copy and no omission ever
 * bit - or skip the surface.
 *
 * <p><b>The surface answers "nothing quarantined" by throwing.</b> {@code HoldLifecycle.release} raises
 * {@code IllegalStateException} for a path it never held, and {@code GatedRepository.discard} synthesises the same for
 * a refused discard. The kit's "a hook is a no-op for a path it never held" is a claim about the <em>hook</em>, so the
 * fixture treats that answer as the surface's no-op and lets the hook's own behaviour be what is asserted.
 */
public abstract class HoldReleaseFixture implements PublicationHookFixture.Release {

    /** The screen a release is read back through: the withhold face the product reads a served path by. */
    private static final String SCREEN = "build.jenesis.repository.gate.store.ComplianceScreen";

    /**
     * What a review release or discard writes, which is what the kit attributes to the hook because the surface is
     * what its namespace check drives.
     *
     * <p>The first four are the gate's own declared spaces. The last two are a measured fact worth stating rather
     * than hiding: {@code HoldLifecycle} links and unpublishes through {@code new Publication(store)} - the
     * <em>discovered</em> chain - so a reviewer's release fans out to the after-commit observers as well. The
     * published index raises its retraction flag (correctly: a cleared hold has to re-enter the immutable chain) and
     * the subtree-size roll-up folds the review pointer's bytes into the browse totals, which is how a
     * {@code sizes/quarantine} row comes to exist for a path that never served. That row is transient - the delete
     * leg floors it and the next {@code rollUpSizes()} reconcile drops it - but it is a real, if small, consequence
     * of the release path that no fixture would have surfaced without this check.
     */
    public static final List<String> GATE_SPACES = List.of(
            "holds", "overrides", "audit/quarantine", "audit/quarantine-index",
            "index/publish", "sizes");

    /** The discovered hook, answering the kit's release role - named, so a census can read which hook it carries. */
    public record Adapter(HoldReleaseObserver observer) implements ReleaseHook {

        @Override
        public void onReleased(ArtifactStore store, String path) throws IOException {
            observer.onReleased(store, path);
        }

        @Override
        public void onDiscarded(ArtifactStore store, String path) throws IOException {
            observer.onDiscarded(store, path);
        }
    }

    @Override
    public ReleaseHook create() {
        return new Adapter(Discovered.release(providerClass()));
    }

    /** Record whatever this hook keys on for {@code path} - its {@code holds/<kind>/...} row, or the state its own
     *  leg consults. Called by {@link #hold} once the review pointer is in place. */
    protected abstract void record(ArtifactStore store, String path) throws IOException;

    @Override
    public void hold(ArtifactStore store, String path, byte[] body) throws IOException {
        Publication publication = new Publication(store, List.of(), List.of());
        String hash = publication.storeBlob(new ByteArrayInputStream(body));
        publication.link(path, hash);                  // the version is serving ...
        publication.link("/quarantine" + path, hash);  // ... and a sweep has just retracted it for review
        record(store, path);
    }

    @Override
    public boolean held(ArtifactStore store, String path) throws IOException {
        return store.readVersioned("publish/quarantine" + path).isPresent();
    }

    @Override
    public boolean visible(ArtifactStore store, String path) throws IOException {
        // Read through the screen the product reads through: the withhold face is store truth, so a live review
        // pointer retracts an already-linked path and a cleared one restores it, with no pointer rewrite either way.
        return new Publication(store,
                List.of((PublishInterceptor) Discovered.hook(SCREEN)), List.of())
                .located(path).isPresent();
    }

    @Override
    public void release(ArtifactStore store, String path, List<ReleaseHook> hooks) throws IOException {
        try {
            new GatedRepository(store, observers(hooks)).release(path);
        } catch (IllegalStateException nothingQuarantined) {
            // The surface's own answer for a path it never held. Not a failure of the hook, which is what is under
            // test - and never reached when a hold really is standing.
        }
    }

    @Override
    public void discard(ArtifactStore store, String path, List<ReleaseHook> hooks) throws IOException {
        try {
            new GatedRepository(store, observers(hooks)).discard(path);
        } catch (IllegalStateException nothingQuarantined) {
            // As above: a refused discard is the surface reporting there was nothing to destroy.
        }
    }

    /** The kit's hooks as the service the surface fans out to: each carried hook is itself, and any other - the kit's
     *  poison hook, a mutant - answers the release and discard legs it was handed. */
    private static List<HoldReleaseObserver> observers(List<ReleaseHook> hooks) {
        return hooks.stream().map(hook -> hook instanceof Adapter adapter ? adapter.observer()
                : (HoldReleaseObserver) new HoldReleaseObserver() {
                    @Override
                    public void onReleased(ArtifactStore store, String path) throws IOException {
                        hook.onReleased(store, path);
                    }

                    @Override
                    public void onDiscarded(ArtifactStore store, String path) throws IOException {
                        hook.onDiscarded(store, path);
                    }
                }).toList();
    }
}
