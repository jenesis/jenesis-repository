package build.jenesis.repository.events.test;

import module java.base;
import build.jenesis.repository.events.EventSink;
import build.jenesis.repository.events.RepositoryEvent;
import build.jenesis.repository.store.ArtifactStore;

/**
 * A discovered {@link EventSink} that always fails, so a test can prove {@link EventSink#emit} swallows the
 * failure - never propagating it to the observed operation - yet still emits a diagnostic naming the lost event.
 */
public final class FailingSink implements EventSink {

    @Override
    public void accept(ArtifactStore store, RepositoryEvent event) throws IOException {
        throw new IOException("sink is down");
    }

    @Override
    public String name() {
        return "failing";
    }
}
