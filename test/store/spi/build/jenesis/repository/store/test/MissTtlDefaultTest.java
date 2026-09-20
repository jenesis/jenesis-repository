package build.jenesis.repository.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.MissMemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The dial's default has one definition, and this is the test that names it: what the memory does when nothing
 *  is set, and how a value an operator writes is read. */
class MissTtlDefaultTest {

    @Test
    void nothing_set_is_ten_seconds() {
        assertThat(MissMemory.ttl(null)).isEqualTo(Duration.ofSeconds(10));
        assertThat(MissMemory.ttl("")).isEqualTo(MissMemory.DEFAULT_TTL);
        assertThat(new MissMemory(MissMemory.DEFAULT_TTL).ttl()).isEqualTo(Duration.parse(MissMemory.DEFAULT_TTL_TEXT));
    }

    @Test
    void zero_is_off_and_a_written_duration_is_itself() {
        assertThat(MissMemory.ttl("0")).isZero();
        assertThat(MissMemory.ttl("30s")).isEqualTo(Duration.ofSeconds(30));
        assertThat(MissMemory.ttl("PT2M")).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void a_value_that_is_not_a_duration_is_refused_naming_the_key() {
        assertThatThrownBy(() -> MissMemory.ttl("soon"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jenreg.cache.miss-ttl");
    }
}
