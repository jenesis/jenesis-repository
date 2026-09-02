package build.jenesis.repository.ui.test;

import module java.base;

import build.jenesis.repository.ui.PostureSource;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the security-posture screen reads from.
 *
 * <p>The screen used to compute its own counts from a report it discovered itself, which made the screen and the
 * header badge two readings of one thing - and two readings drift. Both now go through this seam, so a deployment
 * that layers stored settings over its environment changes what both of them see, together, by contributing one
 * implementation.
 */
class PostureSourceTest {

    @Test
    void the_environment_source_reports_what_the_process_was_started_with() throws IOException {
        PostureSource source = PostureSource.ofEnvironment(key -> switch (key) {
            case "jenreg.auth" -> "false";
            case "jenreg.rate-limit" -> "600";
            default -> null;
        });

        assertThat(source.collect("default").report().count())
                .as("disabling authorization is an unsafe configuration and is reported as one")
                .isPositive();
    }

    @Test
    void a_hardened_configuration_reports_nothing_rather_than_failing_to_read() throws IOException {
        PostureSource source = PostureSource.ofEnvironment(key -> switch (key) {
            case "jenreg.auth" -> "true";
            case "jenreg.rate-limit" -> "600";
            default -> null;
        });

        assertThat(source.collect("default").report().count()).isZero();
    }

    @Test
    void every_collection_says_when_it_was_taken() throws IOException {
        Instant before = Instant.now();

        PostureSource.Collected collected = PostureSource.ofEnvironment(_ -> null).collect("default");

        assertThat(collected.collectedAt())
                .as("a posture read is a snapshot of configuration; one presented without a time reads as current "
                        + "when it may not be")
                .isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
    }
}
