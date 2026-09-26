package build.jenesis.repository.ui.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.ui.store.CacheConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A project's {@code cache.properties} read back for the sweeps. The console writes it through {@link CacheConfig#apply},
 * which refuses a bad value, so a bad value in the file was written by hand - and reading one as "unset" switched the
 * expiry or the cap off with nothing said. It is refused where it is read now, naming the value, and the sweep that
 * read it fails with that sentence as its reason.
 */
class CacheConfigTest {

    @Test
    void a_ttl_that_is_not_a_duration_is_refused_by_name_rather_than_read_as_unset() {
        assertThatThrownBy(() -> CacheConfig.ttlDuration(config("ttl", "30 days")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ttl=30 days");
        assertThatThrownBy(() -> CacheConfig.ttlDuration(config("ttl", "PT0S")))
                .as("a zero ttl is one apply refuses too").hasMessageContaining("ttl=PT0S");
    }

    @Test
    void a_size_that_is_not_a_number_is_refused_by_name_rather_than_read_as_no_cap() {
        assertThatThrownBy(() -> CacheConfig.sizeBytes(config("size", "2gb")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("size=2gb");
        assertThatThrownBy(() -> CacheConfig.sizeBytes(config("size", "-1"))).hasMessageContaining("size=-1");
    }

    @Test
    void well_formed_and_absent_values_read_as_before() {
        assertThat(CacheConfig.ttlDuration(config("ttl", "30d"))).isEqualTo(Duration.ofDays(30));
        assertThat(CacheConfig.ttlDuration(new Properties())).isNull();
        assertThat(CacheConfig.sizeBytes(config("size", "1048576"))).isEqualTo(1048576L);
        assertThat(CacheConfig.sizeBytes(new Properties())).isZero();
    }

    private static Properties config(String key, String value) {
        Properties properties = new Properties();
        properties.setProperty(key, value);
        return properties;
    }
}
