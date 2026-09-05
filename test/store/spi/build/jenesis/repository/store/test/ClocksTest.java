package build.jenesis.repository.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.Clocks;

import static org.assertj.core.api.Assertions.assertThat;

/** The installed clock is what a stamp reads: the system clock until one is installed, the installed one after. */
class ClocksTest {

    @AfterEach
    void systemClockAgain() {
        Clocks.install(Clock.systemUTC());
    }

    @Test
    void a_stamp_reads_the_installed_clock() {
        Instant before = Instant.now();
        assertThat(Clocks.now()).as("the system clock by default").isBetween(before, Instant.now().plusMillis(1));

        Clocks.install(Clock.offset(Clock.systemUTC(), Duration.ofHours(3)));

        assertThat(Clocks.now()).as("a stamp made now runs ahead by the installed offset")
                .isAfter(Instant.now().plus(Duration.ofHours(2)));
        assertThat(Clocks.clock()).as("the clock itself is the installed one").isNotEqualTo(Clock.systemUTC());
    }
}
