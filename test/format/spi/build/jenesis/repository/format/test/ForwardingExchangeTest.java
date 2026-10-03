package build.jenesis.repository.format.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ForwardingExchange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A decorator built on {@link ForwardingExchange} hands every call it does not change to the exchange it wraps, the
 * defaulted ones included: a range's offset, the caller's rights, an audit line, a setting. Every method of the
 * interface is called, so one added later is held to it the day it lands.
 */
class ForwardingExchangeTest {

    /** The response conveniences an interface default builds from the forwarded primitives, so their call reaches
     *  the wrapped exchange through {@code respond(int, long)}. */
    private static final Set<String> COMPOSED = Set.of("respond(int)", "answer(byte[])");

    @Test
    void every_call_a_decorator_does_not_change_reaches_the_wrapped_exchange() throws Exception {
        List<String> reached = new ArrayList<>();
        FormatExchange wrapped = (FormatExchange) java.lang.reflect.Proxy.newProxyInstance(
                FormatExchange.class.getClassLoader(), new Class<?>[]{FormatExchange.class}, (_, method, _) -> {
                    reached.add(signature(method));
                    return answer(method.getReturnType());
                });
        FormatExchange decorator = new ForwardingExchange(wrapped) {
        };

        List<String> missing = new ArrayList<>();
        for (Method method : FormatExchange.class.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            reached.clear();
            Object[] arguments = Arrays.stream(method.getParameterTypes()).map(ForwardingExchangeTest::argument)
                    .toArray();
            Object result = method.invoke(decorator, arguments);
            if (result instanceof Closeable closeable) {
                closeable.close();
            }
            String signature = signature(method);
            boolean forwarded = COMPOSED.contains(signature) ? !reached.isEmpty() : reached.contains(signature);
            if (!forwarded) {
                missing.add(signature);
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
        } else if (type == int.class) {
            return 7;
        } else if (type == String.class) {
            return "wrapped";
        } else if (type == Optional.class) {
            return Optional.empty();
        } else if (type == InputStream.class) {
            return InputStream.nullInputStream();
        } else if (type == OutputStream.class) {
            return OutputStream.nullOutputStream();
        }
        return null;
    }

    private static Object argument(Class<?> type) {
        if (type == int.class) {
            return 200;
        } else if (type == long.class) {
            return 10L;
        } else if (type == byte[].class) {
            return new byte[]{1};
        } else if (type == String.class) {
            return "/path";
        }
        return null;
    }
}
