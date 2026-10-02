package build.jenesis.repository.observation.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.observation.Metric;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A metric reads in its unit as a person reads it: a quota of four gibibytes is "4.0 GiB" rather than 4.294967296E9,
 * and a count is grouped and whole rather than "12.0".
 */
class MetricDisplayTest {

    @Test
    void bytes_read_in_binary_units_and_counts_as_grouped_whole_numbers() {
        Metric quota = Metric.bounded("jenrepo.quota.used", "Stored bytes against the quota.", 0, 4_294_967_296.0,
                "bytes");
        assertThat(quota.displayValue()).isEqualTo("0 B");
        assertThat(quota.displayLimit()).isEqualTo("4.0 GiB");
        assertThat(quota.display(1536)).isEqualTo("1.5 KiB");

        Metric requests = Metric.counter("jenrepo.requests.served", "Requests served.", 1_234_567, "");
        assertThat(requests.displayValue()).isEqualTo("1,234,567");
        assertThat(requests.displayLimit()).isEmpty();
        assertThat(Metric.gauge("jenrepo.walk.duration", "Seconds.", 2.5, "s").displayValue()).isEqualTo("2.50 s");
    }
}
