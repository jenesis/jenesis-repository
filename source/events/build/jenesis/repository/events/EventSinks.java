package build.jenesis.repository.events;

import module java.base;
import build.jenesis.repository.store.Providers;

/** The event sinks on the module path, held once discovered: every event is emitted to them. */
final class EventSinks {

    static final Providers.Discovered<EventSink> DISCOVERED =
            new Providers.Discovered<>(() -> ServiceLoader.load(EventSink.class));

    private EventSinks() {
    }
}
