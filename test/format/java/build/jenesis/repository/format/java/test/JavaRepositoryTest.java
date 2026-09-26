package build.jenesis.repository.format.java.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryType;
import build.jenesis.repository.store.ArtifactStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code java} repository holds Maven and the Jenesis module layout, and takes publishes from Maven alone: its module
 * layout is Maven's view of what Maven published, so a write the Jenesis format claims there is one the repository
 * refuses. A repository of one format takes that format's publishes.
 */
class JavaRepositoryTest {

    @Test
    void a_java_repository_takes_maven_publishes_and_not_jenesis_ones() {
        RepositoryFormat maven = format("maven"), jenesis = format("jenesis");

        RepositoryType java = RepositoryType.of("java", List.of(maven, jenesis)).orElseThrow();
        assertThat(java.publishes(maven)).isTrue();
        assertThat(java.publishes(jenesis)).isFalse();

        assertThat(RepositoryType.of("jenesis", List.of(maven, jenesis)).orElseThrow().publishes(jenesis)).isTrue();
    }

    private static RepositoryFormat format(String name) {
        return new RepositoryFormat() {

            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean handles(String path) {
                return path.startsWith("/" + name + "/");
            }

            @Override
            public void serve(FormatExchange exchange, ArtifactStore store) {
            }
        };
    }
}
