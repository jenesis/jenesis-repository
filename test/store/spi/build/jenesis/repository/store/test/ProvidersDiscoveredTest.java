package build.jenesis.repository.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A home's providers are discovered once and held, so a repeated path does not walk the module graph again; and a
 * discovery that fails is not held, so it is made - and refused - again on the next call.
 */
class ProvidersDiscoveredTest {

    @Test
    void the_providers_are_discovered_once_and_the_same_instances_answer_every_call() {
        AtomicInteger discoveries = new AtomicInteger();
        Providers.Discovered<ArtifactStoreProvider> discovered = new Providers.Discovered<>(() -> {
            discoveries.incrementAndGet();
            return ServiceLoader.load(ArtifactStoreProvider.class);
        });

        List<ArtifactStoreProvider> first = new ArrayList<>();
        discovered.forEach(first::add);
        List<ArtifactStoreProvider> second = new ArrayList<>();
        discovered.forEach(second::add);

        assertThat(first).as("this module's provider among them").isNotEmpty();
        assertThat(second).as("the very instances, not a second discovery's").containsExactlyElementsOf(first);
        assertThat(discoveries).hasValue(1);
    }

    @Test
    void a_discovery_that_fails_is_not_held() {
        AtomicInteger discoveries = new AtomicInteger();
        Providers.Discovered<ArtifactStoreProvider> discovered = new Providers.Discovered<>(() -> {
            if (discoveries.incrementAndGet() == 1) {
                throw new ServiceConfigurationError("a provider that cannot be loaded");
            }
            return ServiceLoader.load(ArtifactStoreProvider.class);
        });

        assertThatThrownBy(discovered::iterator).isInstanceOf(ServiceConfigurationError.class);
        assertThat(discovered).as("asked again, the discovery is made again").isNotEmpty();
        assertThat(discoveries).hasValue(2);
    }
}
