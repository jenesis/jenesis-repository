package build.jenesis.repository.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.store.testkit.ForwardingInterceptor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The store kit's forwarding hooks hand every method on to the hook they wrap - each observer leg and each question a
 * screen answers, including any the interfaces grow later - so a mutant built on them changes only what it overrides.
 */
class ForwardingHooksTest {

    @Test
    void every_method_a_screen_answers_reaches_the_screen_it_wraps() throws Exception {
        Set<String> reached = new TreeSet<>();
        PublishInterceptor wrapped = (PublishInterceptor) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {PublishInterceptor.class}, (_, method, _) -> {
                    reached.add(signature(method));
                    return answer(method.getReturnType());
                });
        PublishInterceptor forwarding = new ForwardingInterceptor(wrapped) {
        };

        Set<String> asked = new TreeSet<>();
        for (Method method : PublishInterceptor.class.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            asked.add(signature(method));
            method.invoke(forwarding, new Object[method.getParameterCount()]);
        }

        assertThat(asked).as("the interfaces answer something").isNotEmpty();
        assertThat(reached).as("every method was handed on rather than answered by a default").isEqualTo(asked);
    }

    private static String signature(Method method) {
        return method.getName() + Arrays.toString(method.getParameterTypes());
    }

    private static Object answer(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == List.class) {
            return List.of();
        }
        if (type == PublishInterceptor.Disposition.class) {
            return PublishInterceptor.Disposition.ACCEPT;
        }
        return null;
    }
}
