/**
 * The observability SPI: the self-describing signals a plugin reports -
 * {@link build.jenesis.repository.observation.HealthCheck health checks},
 * {@link build.jenesis.repository.observation.Metric metrics} and
 * {@link build.jenesis.repository.observation.TaskStatus task status} - each with a {@code jenrepo.<feature>.<signal>}
 * name ({@link build.jenesis.repository.observation.Signals}) and a description, exposed through
 * {@link build.jenesis.repository.observation.ObservabilitySource} and discovered with {@link java.util.ServiceLoader}.
 * It also owns {@link build.jenesis.repository.observation.Contributions}, the containment every collected report folds
 * its contributors through.
 *
 * <p>{@code java.base}-only and registry-free, so every SPI can require it without Micrometer or Spring; the
 * distribution bridges the {@link build.jenesis.repository.observation.ObservabilityReport} onto Actuator, the console
 * and the reference docs.
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.observation {
    exports build.jenesis.repository.observation;
    uses build.jenesis.repository.observation.ObservabilitySource;
}
