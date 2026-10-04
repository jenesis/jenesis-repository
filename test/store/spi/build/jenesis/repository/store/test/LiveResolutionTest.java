package build.jenesis.repository.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.LiveResolution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A resolution over the live settings is held while the settings it read stand, resolved again when one of them
 * moves, and closes what it replaces and what it holds when it is closed.
 */
class LiveResolutionTest {

    private final Map<String, String> settings = new ConcurrentHashMap<>();
    private final AtomicInteger resolutions = new AtomicInteger();

    /** A client the resolution builds from the {@code feed} and {@code feed-endpoint} settings. */
    private static final class Client implements AutoCloseable {

        final String endpoint;
        boolean closed;

        Client(String endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private LiveResolution<Optional<Client>> resolution() {
        return new LiveResolution<>(settings::get, config -> {
            resolutions.incrementAndGet();
            return "true".equals(config.apply("feed"))
                    ? Optional.of(new Client(config.apply("feed-endpoint")))
                    : Optional.empty();
        }, value -> value.<Collection<?>>map(List::of).orElse(List.of()));
    }

    @Test
    void a_setting_switched_on_is_resolved_at_the_next_ask_and_an_unrelated_write_keeps_what_was_resolved() {
        LiveResolution<Optional<Client>> resolution = resolution();
        assertThat(resolution.get()).as("off").isEmpty();

        settings.put("feed", "true");
        settings.put("feed-endpoint", "https://one.example");
        Client first = resolution.get().orElseThrow();
        assertThat(first.endpoint).isEqualTo("https://one.example");

        settings.put("unrelated", "anything");
        assertThat(resolution.get()).as("a write the resolution never read keeps the client").containsSame(first);
        assertThat(resolutions).hasValue(2);
    }

    @Test
    void a_setting_it_read_moving_resolves_it_again_and_closes_what_it_replaced() {
        settings.put("feed", "true");
        settings.put("feed-endpoint", "https://one.example");
        LiveResolution<Optional<Client>> resolution = resolution();
        Client first = resolution.get().orElseThrow();

        settings.put("feed-endpoint", "https://two.example");
        Client second = resolution.get().orElseThrow();

        assertThat(second.endpoint).isEqualTo("https://two.example");
        assertThat(first.closed).as("the client the new resolution replaced").isTrue();
        assertThat(second.closed).isFalse();

        settings.put("feed", "false");
        assertThat(resolution.get()).as("switched off").isEmpty();
        assertThat(second.closed).as("and its client closed").isTrue();
    }

    @Test
    void an_object_the_new_resolution_still_holds_stays_open() {
        Client shared = new Client("https://shared.example");
        LiveResolution<List<Client>> resolution = new LiveResolution<>(settings::get,
                config -> "true".equals(config.apply("second"))
                        ? List.of(shared, new Client("https://second.example")) : List.of(shared),
                list -> list);
        resolution.get();

        settings.put("second", "true");
        resolution.get();

        assertThat(shared.closed).isFalse();
    }

    @Test
    void closing_it_closes_what_it_holds_once_and_it_answers_nothing_after() {
        settings.put("feed", "true");
        LiveResolution<Optional<Client>> resolution = resolution();
        Client client = resolution.get().orElseThrow();

        resolution.close();
        resolution.close();

        assertThat(client.closed).isTrue();
        assertThatThrownBy(resolution::get).isInstanceOf(IllegalStateException.class);
    }
}
