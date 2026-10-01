package build.jenesis.repository.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.DocumentMemory;
import build.jenesis.repository.store.MissMemory;
import build.jenesis.repository.store.NodeMemoStore;
import build.jenesis.repository.store.QuotaArtifactStore;
import build.jenesis.repository.store.ReadMemo;
import build.jenesis.repository.store.ReadOnlyArtifactStore;
import build.jenesis.repository.store.StoreBindings;
import build.jenesis.repository.store.testkit.FaultInjectingStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A deployment's bindings reach every view of the store it bound: every scope, and every decorator this module ships
 * layered over it in any order - which is what a plug-in handed a repository's view relies on to find its
 * deployment's values. A decorator that forgot to forward {@link ArtifactStore#bindings()} would answer
 * {@link StoreBindings#NONE} here.
 */
class StoreBindingsTest {

    /** A value of a type nothing else binds, standing in for a deployment's own. */
    private record Marker(String deployment) {
    }

    @TempDir
    Path root;

    @Test
    void a_binding_is_found_by_its_type_and_a_type_nothing_bound_is_absent() {
        StoreBindings bindings = StoreBindings.of(Marker.class, new Marker("a"));
        assertThat(bindings.get(Marker.class)).contains(new Marker("a"));
        assertThat(bindings.get(String.class)).isEmpty();
        assertThat(StoreBindings.NONE.get(Marker.class)).isEmpty();
        assertThat(bindings.with(Marker.class, new Marker("b")).get(Marker.class))
                .as("binding a type again replaces it").contains(new Marker("b"));
        assertThat(bindings.and(StoreBindings.of(String.class, "x")).get(Marker.class))
                .as("bindings combine by type").contains(new Marker("a"));
    }

    @Test
    void a_backend_carries_nothing_and_binding_nothing_answers_the_store_itself() {
        ArtifactStore store = filesystem();
        assertThat(store.bindings().isEmpty()).isTrue();
        assertThat(StoreBindings.NONE.over(store)).isSameAs(store);
    }

    @Test
    void every_scope_of_a_bound_store_carries_its_bindings_and_its_identity_is_the_stores() {
        ArtifactStore backend = filesystem();
        ArtifactStore bound = StoreBindings.of(Marker.class, new Marker("a")).over(backend);
        assertThat(marker(bound.scope("tenant").scope("repository"))).contains(new Marker("a"));
        assertThat(bound.identity()).isEqualTo(backend.identity());
        assertThat(bound.scope("tenant").identity()).isEqualTo(backend.scope("tenant").identity());
    }

    @Test
    void a_second_binding_layered_over_a_bound_store_adds_to_its_bindings() {
        ArtifactStore bound = StoreBindings.of(String.class, "inner")
                .over(StoreBindings.of(Marker.class, new Marker("a")).over(filesystem()));
        ArtifactStore view = bound.scope("tenant");
        assertThat(marker(view)).contains(new Marker("a"));
        assertThat(view.bindings().get(String.class)).contains("inner");
    }

    /** Every decorator this module ships, each over a bound store, at the root and two scopes down. */
    @TestFactory
    Stream<DynamicTest> every_decorator_forwards_the_bindings_of_its_delegate() {
        Map<String, UnaryOperator<ArtifactStore>> decorators = new LinkedHashMap<>();
        decorators.put("read-only", ReadOnlyArtifactStore::new);
        decorators.put("quota", store -> new QuotaArtifactStore(store, Long.MAX_VALUE));
        decorators.put("read memo", ReadMemo::over);
        decorators.put("node memo", store -> NodeMemoStore.over(store,
                new MissMemory(Duration.ofSeconds(10)), new DocumentMemory(Duration.ofSeconds(10))));
        decorators.put("fault injection", FaultInjectingStore::wrap);
        return decorators.entrySet().stream().map(decorator -> DynamicTest.dynamicTest(decorator.getKey(), () -> {
            ArtifactStore bound = StoreBindings.of(Marker.class, new Marker("a")).over(filesystem());
            ArtifactStore decorated = decorator.getValue().apply(bound);
            assertThat(marker(decorated)).as(decorator.getKey() + " at the root").contains(new Marker("a"));
            assertThat(marker(decorated.scope("tenant").scope("repository")))
                    .as(decorator.getKey() + " two scopes down").contains(new Marker("a"));
            assertThat(marker(decorator.getValue().apply(bound.scope("tenant")).scope("repository")))
                    .as(decorator.getKey() + " over a scope").contains(new Marker("a"));
        }));
    }

    private static Optional<Marker> marker(ArtifactStore store) {
        return store.bindings().get(Marker.class);
    }

    private ArtifactStore filesystem() {
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
    }
}
