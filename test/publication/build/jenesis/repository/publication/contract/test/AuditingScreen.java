package build.jenesis.repository.publication.contract.test;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublishInterceptor;

import module java.base;

/**
 * The one-class-two-failure-modes archetype: a screen that also opts into the inherited after-commit observer leg.
 *
 * <p>This is the shape the {@code PublishInterceptor} contract's clause 7 is written for. Its {@code committed} leg
 * is a <b>verdict</b> leg - a throw there fails the publish - while the {@code onPublished} it overrides is an
 * <b>observer</b> leg whose throw is logged and swallowed, and the exchange it makes for that containment is the only
 * way to learn that the artifact really serves: {@code committed} fires before the layout and before the commit
 * point, so its {@code ACCEPT} is not a visibility claim. A screen that overrides neither would never be double
 * counted as an observer of its own verdict, which is what the inherited no-op default buys.
 */
public final class AuditingScreen implements PublishInterceptor {

    /** The key space this screen owns. */
    public static final String SPACE = "kitaudit";

    /** What the chain decided - written from the propagating verdict leg. */
    public static final String COMMITTED = SPACE + "/committed";

    /** What really became visible - written from the contained observer leg. */
    public static final String OBSERVED = SPACE + "/observed";

    @Override
    public int order() {
        return -20;
    }

    @Override
    public void committed(ArtifactDescriptor artifact, Disposition disposition, ArtifactStore store)
            throws IOException {
        Keys.upsert(store, COMMITTED + "/" + Keys.slug(artifact.path()), disposition.name());
    }

    @Override
    public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        Keys.upsert(store, OBSERVED + "/" + Keys.slug(artifact.path()), IndexObserver.row(artifact));
    }
}
