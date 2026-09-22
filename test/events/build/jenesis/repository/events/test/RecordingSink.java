package build.jenesis.repository.events.test;

import module java.base;
import build.jenesis.repository.events.EventSink;
import build.jenesis.repository.events.RepositoryEvent;
import build.jenesis.repository.store.ArtifactStore;

/**
 * A discovered {@link EventSink} that records every event it receives, so a test can prove {@link EventSink#emit}
 * keeps fanning out past an earlier failing sink - a throwing sink must not starve a sink discovered after it. The
 * module lists it after {@link FailingSink} in with-clause order, so it is the LATER sink the fan-out only reaches
 * by continuing past the failure. It can also be armed via {@link #FAIL_WITH_RUNTIME} to throw a
 * {@link RuntimeException} instead of recording, exercising the RuntimeException arm of emit's
 * {@code catch (IOException | RuntimeException)} - the companion of the IOException arm {@link FailingSink} covers.
 */
public final class RecordingSink implements EventSink {

    /** Events delivered to this sink, in arrival order; a test drains it first, then asserts the fan-out reached it. */
    static final List<RepositoryEvent> RECEIVED = new CopyOnWriteArrayList<>();

    /** When set, {@link #accept} throws a {@link RuntimeException} instead of recording, so a test can exercise
     *  emit's RuntimeException catch arm (the IOException arm is covered by {@link FailingSink}). */
    static final AtomicBoolean FAIL_WITH_RUNTIME = new AtomicBoolean();

    @Override
    public void accept(ArtifactStore store, RepositoryEvent event) {
        if (FAIL_WITH_RUNTIME.get()) {
            throw new IllegalStateException("recording sink is down");
        }
        RECEIVED.add(event);
    }

    @Override
    public String name() {
        return "recording";
    }
}
