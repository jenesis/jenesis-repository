package build.jenesis.repository.store.testkit;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublicationObserver;

/**
 * An observer that hands every leg to the one it wraps, so a mutant built on it changes exactly the legs it overrides.
 * A mutant written against the interface instead inherits a no-op for every leg it does not name, and removes
 * behaviour it never meant to remove.
 */
public abstract class ForwardingObserver implements PublicationObserver {

    private final PublicationObserver observer;

    protected ForwardingObserver(PublicationObserver observer) {
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    @Override
    public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        observer.onPublished(artifact, store);
    }

    @Override
    public void onDeleted(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        observer.onDeleted(artifact, store);
    }

    @Override
    public void onCached(ArtifactDescriptor artifact, URI upstream, ArtifactStore store) throws IOException {
        observer.onCached(artifact, upstream, store);
    }

    @Override
    public void onWithheld(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        observer.onWithheld(subject, store);
    }

    @Override
    public void onWithholdCleared(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        observer.onWithholdCleared(subject, store);
    }

    @Override
    public void onMarked(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
        observer.onMarked(subject, store);
    }
}
