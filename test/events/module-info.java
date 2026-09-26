/**
 * Tests of the event SPI's {@link build.jenesis.repository.events.EventSink#emit} fan-out in isolation: a
 * best-effort emit contains a failing discovered sink so a notification never fails the publish, quarantine,
 * finding or promotion it observes - but §9 forbids a silent fail-soft, so the drop is surfaced as a
 * WARNING naming the lost event (its kind and coordinate/path). A capturing {@code System.LoggerFinder} and a
 * deliberately failing discovered {@link build.jenesis.repository.events.EventSink} pin both halves of the
 * contract at once: the caller sees no exception, and a diagnostic is emitted rather than the event vanishing. A
 * second discovered sink - a recording one sorted after the failing one - additionally pins the fan-out's
 * <em>continuation</em>: a throwing sink must not starve a sink discovered after it, and both the IOException and
 * the RuntimeException shapes are exercised (the failing sink throws an IOException, the recording sink an armed
 * RuntimeException).
 *
 * <p>A third, armable {@code HostileSink} carries three rulings on a guest's failures, which are the ones a two-arm
 * {@code catch (IOException | RuntimeException)} could not express: a checked exception smuggled past the
 * {@code throws} clause is contained like any other delivery failure, an {@link java.lang.Error} is attributed and
 * <em>rethrown</em> because it is the runtime breaking rather than a notification failing to queue, and a sink whose
 * {@code name()} throws is refused at resolution instead of defeating the containment from inside its own diagnostic.
 * It sorts between the failing and the recording sink, so every leg can also state whether the sink after the
 * misbehaving one was reached - the observable difference between containment and propagation. The duplicate-name
 * refusal needs a module graph that really declares a clash and lives in {@code test/events-discovery}.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.events
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.events.test {
    requires build.jenesis.repository.events;
    requires org.slf4j;
    requires org.junit.jupiter;
    requires org.assertj.core;
    provides build.jenesis.repository.events.EventSink
            with build.jenesis.repository.events.test.FailingSink,
                 build.jenesis.repository.events.test.HostileSink,
                 build.jenesis.repository.events.test.RecordingSink;
    provides org.slf4j.spi.SLF4JServiceProvider
            with build.jenesis.repository.events.test.CapturingLoggerFinder;
}
