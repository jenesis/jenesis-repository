/**
 * Focused unit tests for the token-bucket rate limiter's observability adoption - that it is an {@link
 * build.jenesis.repository.observation.ObservabilitySource} reporting {@code jenreg.ratelimit.buckets} (the number
 * of keys it is currently tracking, one bucket each - the memory-exhaustion vector the shared {@code anonymous}
 * bucket bounds) as a gauge and a {@code jenreg.ratelimit.limiter} health check, both collected into the single
 * {@link build.jenesis.repository.observation.ObservabilityReport} view, exercised without the server, Micrometer or
 * any network. The limiter's metering behaviour itself is covered by the server test module.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.ratelimit
 * @jenesis.bom pin-repository.properties
 */
open module build.jenesis.repository.ratelimit.test {
    requires build.jenesis.repository.ratelimit;
    requires build.jenesis.repository.observation;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
