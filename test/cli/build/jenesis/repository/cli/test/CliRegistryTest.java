package build.jenesis.repository.cli.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cli.Cli;
import build.jenesis.repository.cli.Commands;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registry is the single description of what the CLI offers, and this holds it to that.
 *
 * <p>An earlier version of this suite kept its own list of the verbs and checked the dispatcher against it. That is
 * the shape that drifts: the list was a third copy of a fact already written twice - once in the dispatch map, once
 * in the printed usage - and a verb could be added to any one of the three and missed by the others without
 * anything failing. Now there is one declaration in {@code Commands}, the help is rendered from it, and this reads
 * it rather than restating it. What is left to check is that the declaration is honest: every command dispatches,
 * every command is documented, and the forms in the help are the forms that work.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CliRegistryTest {

    private static final String OVERVIEW = "jenesis-repo - operate a Jenesis repository";

    @TempDir
    private static Path home;

    @BeforeAll
    public void setUp() {
        // A home with no saved session: a command that needs one fails at the login check and a command that
        // validates its own arguments throws - either way it is routed, which is what is being asked.
        System.setProperty("JENREG_CLI_HOME", home.toString());
    }

    @AfterAll
    public void tearDown() {
        System.clearProperty("JENREG_CLI_HOME");
    }

    @Test
    void every_command_dispatches_rather_than_falling_through_to_the_overview() throws Exception {
        for (String command : Commands.BY_NAME.keySet()) {
            assertThat(capture(() -> run(command)))
                    .as("'%s' must reach its handler; printing the overview is the tell of a command that is "
                            + "declared but not dispatched", command)
                    .doesNotContain(OVERVIEW);
        }
    }

    @Test
    void every_command_is_documented() {
        for (Commands.Noun noun : Commands.BY_NAME.values()) {
            assertThat(noun.summary()).as("'%s' has no summary", noun.name()).isNotBlank();
            assertThat(noun.actions()).as("'%s' declares no action, so 'help %s' would print nothing",
                    noun.name(), noun.name()).isNotEmpty();
            for (Commands.Action action : noun.actions()) {
                assertThat(action.summary()).as("an action of '%s' has no summary", noun.name()).isNotBlank();
                assertThat(action.form())
                        .as("the form '%s' must begin with the command it belongs to, or a reader cannot type it",
                                action.form())
                        .startsWith(noun.name());
            }
        }
    }

    @Test
    void the_overview_lists_every_command() throws Exception {
        String out = capture(() -> assertThat(Cli.run(new String[] {"help"})).isZero());
        assertThat(out).contains(OVERVIEW);
        for (Commands.Noun noun : Commands.BY_NAME.values()) {
            assertThat(out).as("'%s' is missing from the overview", noun.name()).contains(noun.name());
        }
        for (Commands.Section section : Commands.SECTIONS) {
            assertThat(out).contains(section.title());
        }
    }

    @Test
    void help_for_one_command_prints_its_actions() throws Exception {
        for (Commands.Noun noun : Commands.BY_NAME.values()) {
            String out = capture(() -> assertThat(Cli.run(new String[] {"help", noun.name()})).isZero());
            for (Commands.Action action : noun.actions()) {
                assertThat(out).as("'help %s' omits '%s'", noun.name(), action.form()).contains(action.form());
            }
        }
    }

    @Test
    void a_command_answers_its_own_help_flag() throws Exception {
        String out = capture(() -> assertThat(Cli.run(new String[] {"quarantine", "--help"})).isZero());
        assertThat(out).contains("quarantine release <repo> <path>");
    }

    @Test
    void an_unknown_command_is_a_usage_error_and_suggests_the_nearest() throws Exception {
        String err = captureErr(() -> assertThat(Cli.run(new String[] {"quarentine"})).isEqualTo(2));
        assertThat(err).contains("unknown command 'quarentine'").contains("did you mean 'quarantine'?");
    }

    @Test
    void an_empty_command_line_prints_the_overview() throws Exception {
        String out = capture(() -> assertThat(Cli.run(new String[] {})).isEqualTo(1));
        assertThat(out).contains(OVERVIEW);
    }

    @Test
    void the_skill_briefing_states_what_a_program_needs_to_know() throws Exception {
        String out = capture(() -> assertThat(Cli.run(new String[] {"skill"})).isZero());
        assertThat(out).as("a program has to be able to tell 'not installed' from 'you asked wrongly'")
                .contains("EXIT CODES").contains("3");
        assertThat(out).as("and has to know where the irreversible operations are").contains("purge --delete");
        assertThat(out).contains("capabilities");
    }

    private static void run(String command) throws Exception {
        try {
            Cli.run(new String[] {command});
        } catch (Exception argumentsOrSession) {
            // A routed handler validating its own arguments, or refusing without a session. Both mean routed.
        }
    }

    private interface Body {
        void run() throws Exception;
    }

    private static String capture(Body body) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            body.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static String captureErr(Body body) throws Exception {
        PrintStream original = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            body.run();
        } finally {
            System.setErr(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
