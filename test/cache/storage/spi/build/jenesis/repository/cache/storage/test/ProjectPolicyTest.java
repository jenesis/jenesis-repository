package build.jenesis.repository.cache.storage.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cache.storage.ProjectPolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A project's policy read back from its settings for a sweep. Every write path refuses a bad value through the
 * settings catalogue, so a bad value was written into the store by hand - and reading one as "unset" switched the
 * expiry or the cap off with nothing said. It is refused where it is parsed, naming the setting and the value, and
 * a caller that must keep serving (the build cache) says so out loud and fails open.
 */
class ProjectPolicyTest {

    @Test
    void a_lifetime_that_is_not_a_duration_is_refused_by_name_rather_than_read_as_unset() {
        assertThatThrownBy(() -> ProjectPolicy.ttl("30 days"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("project-ttl is '30 days'");
        assertThatThrownBy(() -> ProjectPolicy.ttl("PT0S"))
                .as("a zero lifetime is refused too").hasMessageContaining("project-ttl is 'PT0S'");
    }

    @Test
    void a_size_that_is_not_a_number_is_refused_by_name_rather_than_read_as_no_cap() {
        assertThatThrownBy(() -> ProjectPolicy.size("2gb"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("project-size is '2gb'");
        assertThatThrownBy(() -> ProjectPolicy.size("-1")).hasMessageContaining("project-size is '-1'");
    }

    @Test
    void well_formed_absent_and_switched_off_values_read_as_their_policy() {
        assertThat(ProjectPolicy.ttl("30d")).isEqualTo(Duration.ofDays(30));
        assertThat(ProjectPolicy.ttl(null)).isNull();
        assertThat(ProjectPolicy.ttl("none")).as("none keeps entries for ever, over any wider lifetime").isNull();
        assertThat(ProjectPolicy.size("1048576")).isEqualTo(1048576L);
        assertThat(ProjectPolicy.size(null)).isZero();
        assertThat(ProjectPolicy.lru(null)).isTrue();
        assertThat(ProjectPolicy.lru("false")).isFalse();
        assertThat(ProjectPolicy.of(Map.of(ProjectPolicy.SIZE, "10", ProjectPolicy.TTL, "P1D")::get))
                .isEqualTo(new ProjectPolicy(10, true, Duration.ofDays(1)));
    }
}
