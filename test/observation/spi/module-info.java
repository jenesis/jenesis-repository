/**
 * Focused unit tests for the observability SPI - the self-describing signal contract every plugin reports through,
 * exercised without the server, Micrometer or any network: the {@link build.jenesis.repository.observation.Signals}
 * naming grammar (the checkable {@code jenreg.<feature>.<signal>} form of the observability naming grammar), the three signal
 * descriptors ({@link build.jenesis.repository.observation.HealthCheck}, {@link
 * build.jenesis.repository.observation.Metric} with its used-vs-available limit fraction, {@link
 * build.jenesis.repository.observation.TaskStatus}) and their construction-time name validation, the {@link
 * build.jenesis.repository.observation.Health} severity collapse, and the {@link
 * build.jenesis.repository.observation.ObservabilityReport} aggregation - both from an explicit set of sources and
 * through the {@link java.util.ServiceLoader} discovery a {@code provides}-declared test source proves.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.observation
 * @jenesis.bom pin-repository.properties
 */
open module build.jenesis.repository.observation.test {
    requires build.jenesis.repository.observation;
    requires org.junit.jupiter;
    requires org.assertj.core;
    provides build.jenesis.repository.observation.ObservabilitySource
            with build.jenesis.repository.observation.test.SampleObservabilitySource;
}
