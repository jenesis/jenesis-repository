package build.jenesis.repository.compliance.spi.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.FeedCache;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a per-version feed cache drops when a feed's change list names packages: every held answer about a version of
 * each package named, and nothing else - not another ecosystem's package of the same name, and not a package whose
 * name only begins with the one named.
 */
class FeedCacheForgetTest {

    @Test
    void forgetting_a_package_drops_every_version_of_it_and_nothing_else() throws IOException {
        Map<String, Integer> loads = new HashMap<>();
        FeedCache<String> cache = FeedCache.failClosed("test", key -> key + "#" + loads.merge(key, 1, Integer::sum),
                Duration.ofHours(6), Clock.systemUTC());
        List<String> keys = List.of(FeedCache.versionKey("npm", "lodash", "4.17.20"),
                FeedCache.versionKey("npm", "lodash", "4.17.21"), FeedCache.versionKey("npm", "lodash-es", "4.17.21"),
                FeedCache.versionKey("PyPI", "lodash", "1.0"));
        for (String key : keys) {
            cache.get(key);
        }

        cache.forgetVersions(Set.of(new AdvisorySource.Package("npm", "lodash")));

        for (String key : keys) {
            cache.get(key);
        }
        assertThat(loads).as("the two versions of the package named are drawn again, the rest are held")
                .containsExactlyInAnyOrderEntriesOf(Map.of(keys.get(0), 2, keys.get(1), 2, keys.get(2), 1,
                        keys.get(3), 1));
    }
}
