package build.jenesis.repository.test;

import module java.base;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublicationObserver;

/**
 * A test-only after-commit observer, discovered like a real one, that records the {@link ArtifactDescriptor} of every
 * accepted publish carrying the distinctive {@code publish-observed} token in its path - inert for every other path,
 * so the rest of the server suite publishes exactly as before. It exists so the import-edge test can prove that an
 * accepted import fires {@link build.jenesis.repository.store.Publication#published} (an observer sees the accepted
 * artifact's identity), the observable half of the edge's screen/layout/observe choreography - and so the pull-through
 * test can prove a fill announces itself as published and then as cached from its upstream, in that order.
 */
public final class RecordingObserver implements PublicationObserver {

    static final String MARKER = "publish-observed";

    private static final List<ArtifactDescriptor> PUBLISHED = new CopyOnWriteArrayList<>();

    static void reset() {
        PUBLISHED.clear();
        CACHED.clear();
        NOTICES.clear();
    }

    static List<ArtifactDescriptor> published() {
        return List.copyOf(PUBLISHED);
    }

    @Override
    public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) {
        if (artifact.path() != null && artifact.path().contains(MARKER)) {
            PUBLISHED.add(artifact);
            NOTICES.add("published " + artifact.path());
        }
    }

    /** A fill's cached notice, recorded beside the publish notices in the order the two arrived. */
    @Override
    public void onCached(ArtifactDescriptor artifact, URI upstream, ArtifactStore store) {
        if (artifact.path() != null && artifact.path().contains(MARKER)) {
            CACHED.add(artifact);
            NOTICES.add("cached " + artifact.path() + " from " + upstream);
        }
    }

    private static final List<ArtifactDescriptor> CACHED = new CopyOnWriteArrayList<>();

    private static final List<String> NOTICES = new CopyOnWriteArrayList<>();

    static List<ArtifactDescriptor> cached() {
        return List.copyOf(CACHED);
    }

    /** Every notice for a marked path, in arrival order. */
    static List<String> notices() {
        return List.copyOf(NOTICES);
    }
}
