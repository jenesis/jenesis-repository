package build.jenesis.repository.ui.test;

import build.jenesis.repository.ui.ConsistencyCard;
import build.jenesis.repository.ui.ConsoleCard;
import build.jenesis.repository.ui.CredentialsCard;
import build.jenesis.repository.ui.LogCard;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three cards whose subject is live process state rather than store state: the log tail, the fleet's consistency
 * and the deployment's credentials. Each is a key-gated read of an {@code /api} surface the <em>browser</em> makes
 * after the page is delivered - the card contract's read-purity clause - so each declares itself, names its fragment
 * and reads nothing at all.
 *
 * <p>What these tests used to assert is worth recording, because the change removed the need for it rather than the
 * coverage. Each card built its whole body - markup, controls and a {@code <script>} - as a Java text block, so its
 * script interpolated API values into {@code innerHTML} and carried a hand-written {@code esc()} to make that safe.
 * The tests therefore substring-matched the script: that {@code esc} mapped the three metacharacters, and that every
 * attacker-influenced field appeared wrapped in it. That is a test of a string's spelling, and it would have passed
 * over a fourth field nobody remembered to wrap.
 *
 * <p>The bodies are Thymeleaf fragments now and the behaviour is {@code /js/cards.js}, which reaches the DOM only
 * through {@code textContent} and created elements. There is nothing left to escape, so the claim that replaces
 * those assertions is the one that keeps it that way.
 */
class LiveApiCardTest {

    /**
     * The cards' shipped behaviour, read from the module's own resources.
     *
     * <p>This is a text check, and the contract for writing one is to say why neither better option carries the
     * claim. An executable test would have to run the script: no script engine is on the module path in this JDK, so
     * the only way to execute it is a browser, and a browser suite is exclusive across checkouts and far too coarse
     * an instrument for one property of one asset. A descriptor-decidable check cannot see inside a resource at all.
     * What is left is this - and unlike the assertions it replaces it is a single, exception-free property over a
     * file this module ships, rather than a list of field names that has to be kept in step with the API.
     */
    private static String cardsScript() throws IOException {
        try (InputStream stream = ConsoleCard.class.getModule().getResourceAsStream("META-INF/resources/js/cards.js")) {
            assertThat(stream).as("the console ships the script its cards bind through").isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * The script with its comments removed, because a claim about what the code does must not be answerable by what
     * the code <em>says</em>. The file explains at its head that no value reaches the page through {@code innerHTML};
     * read whole, that sentence is the only occurrence and it made the check pass for the wrong reason - a check that
     * a comment can satisfy is a check a comment can also break.
     */
    private static String cardsCode() throws IOException {
        String withoutBlocks = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(cardsScript()).replaceAll("");
        return withoutBlocks.lines().filter(line -> !line.strip().startsWith("//"))
                .collect(Collectors.joining("\n"));
    }

    @Test
    void the_log_card_declares_itself_and_reads_nothing() {
        LogCard card = new LogCard();

        assertThat(card.id()).isEqualTo("logs");
        assertThat(card.title()).isEqualTo("Logs");
        assertThat(card.fragment()).isEqualTo("console/cards :: logs");
        assertThat(card.model(null)).as("the tail is the browser's read, so there is no value to prepare").isNull();
    }

    @Test
    void the_consistency_card_declares_itself_and_reads_nothing() {
        ConsistencyCard card = new ConsistencyCard();

        assertThat(card.id()).isEqualTo("consistency");
        assertThat(card.title()).isEqualTo("Consistency");
        assertThat(card.fragment()).isEqualTo("console/cards :: consistency");
        assertThat(card.model(null)).as("the fleet view is the browser's read").isNull();
    }

    @Test
    void the_credentials_card_declares_itself_and_reads_nothing() {
        CredentialsCard card = new CredentialsCard();

        assertThat(card.id()).isEqualTo("credentials");
        assertThat(card.title()).isEqualTo("Credentials");
        assertThat(card.fragment()).isEqualTo("console/cards :: credentials");
        assertThat(card.model(null))
                .as("rendering keys here would duplicate the authorization the API already applies").isNull();
    }

    @Test
    void no_api_value_reaches_the_page_through_innerHTML() throws IOException {
        // The property that makes an escaper unnecessary rather than merely present. A log message, a node id, a
        // divergence reason and a credential label are all attacker-influenced, and every one of them reaches the
        // page as text: through `textContent` or through a created element. Reintroducing `innerHTML` reintroduces
        // the obligation to escape, and the obligation is what drifts - so the check is on the construct, not on a
        // list of the fields that happen to exist today.
        assertThat(cardsCode()).doesNotContain("innerHTML");
    }

    @Test
    void each_card_reads_its_own_api_and_carries_the_key_the_operator_pasted() throws IOException {
        // The three reads, and the header that gates them. The free console authenticates the human by session, but
        // the server's /api surfaces are key-gated like every other one, so a card that stopped sending the header
        // would render an empty state on a healthy deployment and say nothing about why.
        String script = cardsCode();

        assertThat(script).contains("'/api/logs?'").contains("'/api/consistency'").contains("'/api/credentials'");
        assertThat(script).contains("'Jenesis-Repository-Key'");
    }
}
