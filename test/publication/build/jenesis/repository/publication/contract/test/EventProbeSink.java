package build.jenesis.repository.publication.contract.test;

import module java.base;
import build.jenesis.repository.events.EventSink;
import build.jenesis.repository.events.RepositoryEvent;
import build.jenesis.repository.store.ArtifactStore;

/**
 * An event sink that records every event it is handed into the store it is handed, one row per path - what makes the
 * event publication observer's fan-out an observable surface under the kit. It is this module's own, as the kit's
 * archetypes are: the product's one sink, the webhook outbox, is not on this graph, and its delivery is its own
 * contract's.
 */
public final class EventProbeSink implements EventSink {

    static final String SPACE = "events-probe";

    @Override
    public String name() {
        return "probe";
    }

    @Override
    public void accept(ArtifactStore store, RepositoryEvent event) throws IOException {
        Keys.upsert(store, SPACE + "/" + Keys.slug(event.path()), event.type().wire() + " " + event.path());
    }
}
