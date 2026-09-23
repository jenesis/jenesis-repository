package build.jenesis.repository.test;

import module java.base;
import module org.junit.jupiter.api;

import build.jenesis.repository.server.ArtifactStoreDecorator;
import build.jenesis.repository.server.RepositoryAutoConfiguration;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.store.ArtifactStore;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The store declaration applies contributed layers innermost first, and puts them under the wrappers it owns.
 *
 * <p>Order is the whole meaning of the seam rather than a detail of it: a composition layers a node memory above
 * a meter so that a read the memory answers is a read the meter does not count. Applied the other way round the
 * stack still works and the numbers are wrong, which is the kind of defect that reads as a cost regression in a
 * store bill months later.
 *
 * <p>So this drives the declaration with two layers whose order is declared and whose application is observable,
 * and fails if either the order or the position relative to the owned wrappers changes.
 */
class ArtifactStoreDecoratorOrderTest {

    @TempDir
    Path root;

    /** A layer that records the order it ran in, and hands the store on unchanged. */
    private record Recording(int order, String name, List<String> applied)
            implements ArtifactStoreDecorator, Ordered {

        @Override
        public ArtifactStore decorate(ArtifactStore store) {
            applied.add(name);
            return store;
        }

        @Override
        public int getOrder() {
            return order;
        }
    }

    @Test
    void contributed_layers_apply_innermost_first_and_under_the_wrappers_the_declaration_owns() {
        List<String> applied = new ArrayList<>();
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        // Registered in the reverse of the declared order, so passing proves the order was read rather than
        // the registration order followed.
        beans.registerBeanDefinition("outer", definition(new Recording(20, "outer", applied)));
        beans.registerBeanDefinition("inner", definition(new Recording(10, "inner", applied)));

        RepositoryProperties properties = new RepositoryProperties();
        properties.setStore("filesystem");
        properties.setReadOnly(true);
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test",
                Map.of("jenreg.filesystem.root", root.toString())));

        ArtifactStore store = new RepositoryAutoConfiguration(environment)
                .artifactStore(properties, environment, beans.getBeanProvider(ArtifactStoreDecorator.class));

        assertThat(applied)
                .as("the lowest order sits closest to the backend, so it is applied first")
                .containsExactly("inner", "outer");
        assertThat(store.getClass().getSimpleName())
                .as("read-only stays outermost: a layer cannot be contributed above the wrappers the declaration owns")
                .isEqualTo("ReadOnlyArtifactStore");
    }

    private static RootBeanDefinition definition(ArtifactStoreDecorator instance) {
        RootBeanDefinition definition = new RootBeanDefinition(ArtifactStoreDecorator.class);
        definition.setInstanceSupplier(() -> instance);
        return definition;
    }
}
