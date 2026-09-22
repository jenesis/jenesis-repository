package build.jenesis.repository.cli.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cli.Session;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stored login round-trips through its home directory and is forgotten on clear, so a later invocation reuses
 * the URL and key without re-entering them.
 */
public class SessionTest {

    @TempDir
    Path home;

    @Test
    void a_session_round_trips_and_clears() throws IOException {
        assertThat(Session.load(home)).as("no session before login").isNull();

        new Session(URI.create("https://repo.example.com"), "jenk_acme.secret").save(home);

        Session loaded = Session.load(home);
        assertThat(loaded).isNotNull();
        assertThat(loaded.url()).isEqualTo(URI.create("https://repo.example.com"));
        assertThat(loaded.key()).isEqualTo("jenk_acme.secret");

        Session.clear(home);
        assertThat(Session.load(home)).as("forgotten after logout").isNull();
    }

    @Test
    void an_anonymous_session_keeps_no_key() throws IOException {
        new Session(URI.create("https://repo.example.com"), null).save(home);

        Session loaded = Session.load(home);
        assertThat(loaded).isNotNull();
        assertThat(loaded.key()).isNull();
    }
}
