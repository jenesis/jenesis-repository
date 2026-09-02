package build.jenesis.repository.publication.contract.test;

import module java.base;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublishInterceptor;

/**
 * The read-side archetype: a screen whose verdict lives on {@code withheld} rather than on {@code assess} - the
 * quarantine read side a retroactive hold uses to retract an artifact that has served for months.
 *
 * <p>It never diverts a fresh upload, which is the point: the retraction happens with no sweep and no pointer
 * rewrite, because {@code withheld} is re-consulted on every serve and every enumeration rather than latched at
 * publish time. The probe is a single keyed {@code readVersioned} - cheap enough to sit on the serve path, and
 * fail-closed, since a store outage propagates out of a read instead of silently reading as "serves".
 */
public final class WithholdingScreen implements PublishInterceptor {

    /** The key space this screen owns. */
    public static final String SPACE = "kitwithhold";

    /** One marker per retracted request path - what the read side probes. */
    public static final String HELD = SPACE + "/held";

    /** The outcome audit, so this screen has a durable record a replay must upsert rather than double. */
    public static final String AUDIT = SPACE + "/audit";

    @Override
    public int order() {
        return 20;
    }

    @Override
    public boolean withheld(String path, ArtifactStore store) throws IOException {
        return store.readVersioned(HELD + "/" + Keys.slug(path)).isPresent();
    }

    @Override
    public void committed(ArtifactDescriptor artifact, Disposition disposition, ArtifactStore store)
            throws IOException {
        Keys.upsert(store, AUDIT + "/" + Keys.slug(artifact.path()), disposition.name());
    }
}
