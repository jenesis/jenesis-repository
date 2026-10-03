package build.jenesis.repository.store.testkit;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublishInterceptor;

/**
 * A screen that hands every question and every leg to the one it wraps - its order, its verdict, its reasons, its
 * hold probe and its commit as well as the observer legs - so a mutant built on it changes exactly what it overrides.
 */
public abstract class ForwardingInterceptor extends ForwardingObserver implements PublishInterceptor {

    private final PublishInterceptor screen;

    protected ForwardingInterceptor(PublishInterceptor screen) {
        super(screen);
        this.screen = screen;
    }

    @Override
    public int order() {
        return screen.order();
    }

    @Override
    public Disposition assess(ArtifactDescriptor artifact, Content content) throws IOException {
        return screen.assess(artifact, content);
    }

    @Override
    public List<String> reasons(ArtifactDescriptor artifact) {
        return screen.reasons(artifact);
    }

    @Override
    public boolean withheld(String path, ArtifactStore store) throws IOException {
        return screen.withheld(path, store);
    }

    @Override
    public void committed(ArtifactDescriptor artifact, Disposition disposition, ArtifactStore store)
            throws IOException {
        screen.committed(artifact, disposition, store);
    }
}
