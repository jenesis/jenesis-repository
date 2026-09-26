package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.server.kernel.LiveUpstreams;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shipped posture: with pull-through switched on and nothing named, no format fetches its misses from anywhere -
 * not even one whose public registry it knows. That registry is what the console offers to name; a miss is fetched
 * from it only once somebody does.
 */
class NoUpstreamUnlessNamedTest {

    @TempDir
    Path root;

    @Test
    void a_format_that_knows_its_public_registry_fetches_from_nowhere_until_an_upstream_is_named() throws IOException {
        Settings settings = new Settings(ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null));
        RepositoryProperties defaults = new RepositoryProperties();
        assertThat(defaults.isProxyEnabled()).as("pull-through is on by default").isTrue();
        LiveConfig live = new LiveConfig(settings, defaults, AdvisorySource.none(), _ -> null);
        List<RepositoryFormat> formats = RepositoryFormat.installed();
        LiveUpstreams upstreams = new LiveUpstreams(live, formats);

        assertThat(formats).filteredOn(format -> format.name().equals("maven"))
                .singleElement()
                .satisfies(maven -> assertThat(((ProxyFormat) maven).defaultUpstream())
                        .as("Maven knows its public registry, to offer it").isPresent());
        assertThat(upstreams.get("maven")).as("and fetches from nowhere until one is named").isNull();
        assertThat(upstreams.entrySet()).as("no format does").isEmpty();

        settings.set("format-upstream.maven", "https://repo1.maven.org/maven2/");
        assertThat(upstreams.get("maven")).as("naming one is what switches it on")
                .isEqualTo(URI.create("https://repo1.maven.org/maven2/"));
    }
}
