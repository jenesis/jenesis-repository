package build.jenesis.repository.events.test;

import module java.base;
import build.jenesis.repository.events.EventSink;
import build.jenesis.repository.events.RepositoryEvent;
import build.jenesis.repository.store.ArtifactStore;

/**
 * A discovered {@link EventSink} that misbehaves in the three ways emit's old {@code catch (IOException |
 * RuntimeException)} could not see, each armed by a test and disarmed again so the sink is an inert no-op for
 * every other suite in this module. It is named {@code hostile} so name-sorted resolution places it after
 * {@link FailingSink} and before {@link RecordingSink}: a test can therefore state whether the sink after it was
 * reached, which is the difference between containment and propagation.
 *
 * <ul>
 * <li>{@link Mode#ERROR} throws a {@link NoClassDefFoundError} - an {@link Error} is the runtime or the module graph
 *     breaking, not a notification failing to queue, so emit must attribute it and rethrow rather than file it as
 *     this sink's answer.</li>
 * <li>{@link Mode#SNEAKY} throws a checked exception that is <em>not</em> an {@link IOException}, smuggled past the
 *     {@code throws} clause the way an unchecked cast does. Nothing in the compiler stops a sink from doing this, and
 *     the narrow two-arm catch let it escape the containment entirely.</li>
 * <li>{@link Mode#HOSTILE_NAME} throws from {@link #name()} instead of from {@link #accept}. That used to defeat the
 *     containment from inside its own handler - the WARN diagnostic asked the broken sink what it was called - and is
 *     now a packaging error refused at resolution, before any sink is called.</li>
 * </ul>
 */
public final class HostileSink implements EventSink {

    /** The name this sink answers to - between {@code failing} and {@code recording} in name order. */
    public static final String NAME = "hostile";

    /** How this sink misbehaves on the next fan-out; {@link Mode#OFF} until a test arms it. */
    enum Mode {
        OFF, ERROR, SNEAKY, HOSTILE_NAME
    }

    /** The checked exception {@link Mode#SNEAKY} smuggles past {@link #accept}'s {@code throws IOException}. */
    static final class Contrived extends Exception {

        private static final long serialVersionUID = 1L;

        Contrived(String message) {
            super(message);
        }
    }

    /** The armed misbehaviour, set by a test and always reset in a {@code finally}. */
    static final AtomicReference<Mode> MODE = new AtomicReference<>(Mode.OFF);

    @Override
    public void accept(ArtifactStore store, RepositoryEvent event) throws IOException {
        switch (MODE.get()) {
            case ERROR -> throw new NoClassDefFoundError("build.jenesis.repository.delivery.Missing");
            case SNEAKY -> sneak(new Contrived("a sink smuggled a checked exception past its throws clause"));
            case OFF, HOSTILE_NAME -> {
            }
        }
    }

    @Override
    public String name() {
        if (MODE.get() == Mode.HOSTILE_NAME) {
            throw new IllegalStateException("this sink cannot even say what it is called");
        }
        return NAME;
    }

    /** Throw a checked exception the compiler never saw declared - the one way a sink reaches emit with something
     *  that is neither an {@link IOException} nor a {@link RuntimeException} nor an {@link Error}. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneak(Throwable smuggled) throws T {
        throw (T) smuggled;
    }
}
