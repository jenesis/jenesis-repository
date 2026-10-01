package build.jenesis.repository.cli.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cli.Cli;
import build.jenesis.repository.cli.Session;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Where {@code login} takes its key from: a file, standard input, the environment or the terminal - never an
 * argument, which the process list shows every user of the machine and the shell history keeps. The environment leg
 * needs a process of its own and is driven where the CLI runs as one, by the operator suite.
 */
public class CliLoginTest {

    /** A port nothing listens on, so the licence probe that follows a login fails at once. */
    private static final String URL = "http://127.0.0.1:1/";

    @TempDir
    private Path home;

    @BeforeEach
    void isolate() {
        System.setProperty("JENREPO_CLI_HOME", home.toString());
    }

    @AfterEach
    void restore() {
        System.clearProperty("JENREPO_CLI_HOME");
    }

    @Test
    void a_key_on_the_command_line_is_refused_and_nothing_is_stored() throws Exception {
        assertThatThrownBy(() -> Cli.run(new String[] {"login", URL, "--key", "jenk_acme.secret"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--key-file").hasMessageContaining("--key-stdin")
                .hasMessageContaining("JENREPO_KEY")
                .hasMessageNotContaining("jenk_acme.secret");
        assertThat(Session.load(home)).as("a refused login stores no session").isNull();
    }

    @Test
    void the_key_is_read_from_the_file_named() throws Exception {
        Path file = Files.writeString(home.resolve("key"), "jenk_acme.secret\n");

        assertThat(Cli.run(new String[] {"login", URL, "--key-file", file.toString()})).isZero();

        assertThat(Session.load(home).key()).as("the file's content, without its line break")
                .isEqualTo("jenk_acme.secret");
    }

    @Test
    void the_key_is_read_from_standard_input() throws Exception {
        InputStream real = System.in;
        System.setIn(new ByteArrayInputStream("jenk_acme.piped\n".getBytes(StandardCharsets.UTF_8)));
        try {
            assertThat(Cli.run(new String[] {"login", URL, "--key-stdin"})).isZero();
        } finally {
            System.setIn(real);
        }

        assertThat(Session.load(home).key()).isEqualTo("jenk_acme.piped");
    }

    @Test
    void naming_two_sources_is_a_usage_error() throws Exception {
        Path file = Files.writeString(home.resolve("key"), "jenk_acme.secret");

        assertThatThrownBy(() -> Cli.run(new String[] {"login", URL, "--key-file", file.toString(), "--key-stdin"}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Usage: login");
        assertThat(Session.load(home)).isNull();
    }

    @Test
    void an_empty_key_file_logs_in_anonymously() throws Exception {
        Path file = Files.writeString(home.resolve("key"), "\n");

        assertThat(Cli.run(new String[] {"login", URL, "--key-file", file.toString()})).isZero();

        assertThat(Session.load(home).key()).isNull();
    }
}
