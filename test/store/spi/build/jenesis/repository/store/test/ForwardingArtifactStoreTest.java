package build.jenesis.repository.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ForwardingArtifactStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A decorator built on {@link ForwardingArtifactStore} hands every call it does not change to the store it wraps, the
 * defaulted ones included. Every method of the interface is called, so one added later is held to it the day it
 * lands.
 */
class ForwardingArtifactStoreTest {

    @Test
    void every_call_a_decorator_does_not_change_reaches_the_wrapped_store() throws Exception {
        List<String> reached = new ArrayList<>();
        ArtifactStore wrapped = (ArtifactStore) java.lang.reflect.Proxy.newProxyInstance(
                ArtifactStore.class.getClassLoader(), new Class<?>[]{ArtifactStore.class}, (_, method, _) -> {
                    reached.add(signature(method));
                    return answer(method.getReturnType());
                });
        ArtifactStore decorator = new ForwardingArtifactStore(wrapped) {
            @Override
            public ArtifactStore scope(String tenant) {
                return delegate.scope(tenant);
            }
        };

        List<String> missing = new ArrayList<>();
        for (Method method : ArtifactStore.class.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            reached.clear();
            Object[] arguments = Arrays.stream(method.getParameterTypes())
                    .map(ForwardingArtifactStoreTest::argument).toArray();
            Object result = method.invoke(decorator, arguments);
            if (result instanceof Closeable closeable) {
                closeable.close();
            }
            if (!reached.contains(signature(method))) {
                missing.add(signature(method));
            }
        }

        assertThat(missing).as("calls a decorator answered itself, with the interface's default").isEmpty();
    }

    private static String signature(Method method) {
        return method.getName() + Arrays.stream(method.getParameterTypes()).map(Class::getSimpleName)
                .collect(Collectors.joining(",", "(", ")"));
    }

    private static Object answer(Class<?> type) {
        if (type == boolean.class) {
            return true;
        } else if (type == long.class) {
            return 7L;
        } else if (type == String.class) {
            return "wrapped";
        } else if (type == Optional.class) {
            return Optional.empty();
        } else if (type == List.class) {
            return List.of();
        } else if (type == InputStream.class) {
            return InputStream.nullInputStream();
        } else if (type == ArtifactStore.Scan.class) {
            return new ArtifactStore.Scan(0, 0, Optional.empty());
        }
        return null;
    }

    private static Object argument(Class<?> type) {
        if (type == int.class) {
            return 1;
        } else if (type == long.class) {
            return 0L;
        } else if (type == String.class) {
            return "key";
        } else if (type == byte[].class) {
            return new byte[]{1};
        } else if (type == InputStream.class) {
            return InputStream.nullInputStream();
        } else if (type == OutputStream.class) {
            return OutputStream.nullOutputStream();
        } else if (type == Consumer.class) {
            return (Consumer<Object>) _ -> {
            };
        } else if (type == Duration.class) {
            return Duration.ofMinutes(1);
        }
        return null;
    }
}
