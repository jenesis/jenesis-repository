package build.jenesis.repository.cli.test;

import module java.base;
import module org.junit.jupiter.api;
import module tools.jackson.databind;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import build.jenesis.repository.cli.Cli;
import build.jenesis.repository.cli.testkit.CliRequests.Case;
import build.jenesis.repository.cli.testkit.CliRequests.Sent;
import build.jenesis.repository.cli.Commands;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static build.jenesis.repository.cli.testkit.CliRequests.ACTIONS;
import static build.jenesis.repository.cli.testkit.CliRequests.LOCAL;
import static build.jenesis.repository.cli.testkit.CliRequests.PAGED;
import static build.jenesis.repository.cli.testkit.CliRequests.VARIANTS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every action sends the request its endpoint binds: the method, the path, exactly the query parameters the endpoint
 * reads, and a body only where the endpoint reads one - with exactly the fields it reads.
 *
 * <p><b>What this catches that {@code CliBindingTest} does not.</b> That suite asks whether a command reaches its
 * endpoint's path, and a request of the wrong shape reaches the right path just as well: a JSON document posted to a
 * route that binds only query parameters, an id put in the query of a route that reads it from the body, a
 * documented dry run whose argument order makes it the sweep itself. Each of those is a {@code 400} - or worse, a
 * write - from the real server behind a green binding census. A parameter the endpoint does not bind is the quiet
 * form of the same defect: sent, ignored, and read by the caller as a narrowing that never happened, which is why
 * build scans, test runs and VEX documents, the tenant's rather than a repository's, take no repository here.
 *
 * <p><b>Where the expected shapes come from.</b> Each case states the request as the endpoint's own declaration
 * binds it - its {@code @RequestParam} names, its {@code @RequestBody} record's fields, or the raw document it reads
 * from the request stream - so the API is the contract and the command line follows it. The stand-in server answers
 * everything alike and records what arrived; a command may fail afterwards on a reply it cannot parse, which is not
 * this suite's subject.
 *
 * <p><b>Why every action, not every noun.</b> The census below fails when an action form in {@code Commands} has no
 * case here. A noun's read and its writes are different requests to different routes, and a write behind a noun
 * whose read is covered is exactly the request nothing else looks at.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CliRequestShapeTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    private static Path home;

    private static WireMockServer server;

    private static Path payload;

    /** What the file an action forwards holds - told apart from a document the client composed by its content. */
    private static final String PAYLOAD = "{\"document\":true}";

    @BeforeAll
    public void setUp() throws Exception {
        server = new WireMockServer(WireMockConfiguration.options().bindAddress("127.0.0.1").dynamicPort());
        server.start();
        server.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{}")));
        // What the variants need answered for the client to go on: a deletion accepted and then gone, and a first page
        // of the paged repository naming the next.
        server.stubFor(WireMock.delete(urlPathEqualTo("/repository/releases/libs")).willReturn(aResponse()
                .withStatus(202).withHeader("Content-Type", "text/plain").withBody("Deleting repository 'libs'.")));
        server.stubFor(WireMock.get(urlPathEqualTo("/api/repository/deletion")).willReturn(json(
                "{\"repository\":\"libs\",\"state\":\"gone\"}")));
        server.stubFor(WireMock.delete(urlPathEqualTo("/api/admin/tenants/acme")).willReturn(aResponse()
                .withStatus(202).withHeader("Content-Type", "application/json")
                .withBody("{\"tenant\":\"acme\",\"started\":true,\"state\":\"running\"}")));
        server.stubFor(WireMock.get(urlPathEqualTo("/api/admin/tenants/acme/deletion")).willReturn(json(
                "{\"tenant\":\"acme\",\"state\":\"done\"}")));
        for (String listing : List.of("/api/vulnerabilities", "/api/search")) {
            server.stubFor(WireMock.get(urlPathEqualTo(listing)).withQueryParam("repo", equalTo(PAGED))
                    .withQueryParam("after", absent()).willReturn(json("{\"results\":[],\"next\":\"c\"}")));
            server.stubFor(WireMock.get(urlPathEqualTo(listing)).withQueryParam("repo", equalTo(PAGED))
                    .withQueryParam("after", equalTo("c")).willReturn(json("{\"results\":[]}")));
        }
        payload = home.resolve("payload.json");
        Files.writeString(payload, PAYLOAD);
        System.setProperty("JENREPO_CLI_HOME", home.toString());
        Cli.run(new String[] {"login", "http://127.0.0.1:" + server.port() + "/", "--key-file",
                Files.writeString(home.resolve("key"), "test-key").toString()});
    }

    @AfterAll
    public void tearDown() {
        System.clearProperty("JENREPO_CLI_HOME");
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void every_action_is_covered_by_this_test() {
        Set<String> declared = new TreeSet<>();
        for (Commands.Noun noun : Commands.BY_NAME.values()) {
            if (!LOCAL.contains(noun.name())) {
                noun.actions().forEach(action -> declared.add(action.form()));
            }
        }
        assertThat(new TreeSet<>(ACTIONS.keySet()))
                .as("every action form must state the request it sends - an action with no case is a request "
                        + "nobody has held to what its endpoint binds")
                .isEqualTo(declared);
    }

    @TestFactory
    Stream<DynamicTest> every_action_sends_what_its_endpoint_binds() {
        return ACTIONS.entrySet().stream().map(entry -> DynamicTest.dynamicTest(entry.getKey(),
                () -> sends(entry.getValue())));
    }

    @TestFactory
    Stream<DynamicTest> every_variant_sends_what_its_endpoints_bind() {
        return VARIANTS.stream().map(variant -> DynamicTest.dynamicTest(variant.line(), () -> sends(variant)));
    }

    private static ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }

    /** Run {@code action}'s command line against the stand-in and hold what arrived to the requests it states. */
    private static void sends(Case action) throws Exception {
        String[] args = Arrays.stream(action.line().split(" "))
                .map(arg -> arg.equals("@FILE") ? payload.toString()
                        : arg.equals("@OUT") ? payload.resolveSibling("written.out").toString() : arg)
                .toArray(String[]::new);
        server.resetRequests();
        try {
            Cli.run(args);
        } catch (Exception failedAfterTheCall) {
            // Expected for anything whose stub reply it cannot parse; the requests are what is under test.
        }
        List<String> sent = server.findAll(RequestPatternBuilder.allRequests()).stream()
                .map(CliRequestShapeTest::shape).toList();
        assertThat(sent).as("'%s' sends what its endpoint binds", action.line())
                .containsExactlyElementsOf(action.requests().stream().map(Sent::toString).toList());
    }

    /** What arrived, in the terms a case states it: method, path, query parameter names and the body's shape. */
    private static String shape(LoggedRequest request) {
        URI uri = URI.create(request.getUrl());
        Set<String> query = new TreeSet<>();
        if (uri.getRawQuery() != null) {
            for (String pair : uri.getRawQuery().split("&")) {
                query.add(URLDecoder.decode(pair.split("=", 2)[0], StandardCharsets.UTF_8));
            }
        }
        String body = request.getBodyAsString();
        Set<String> json = null;
        boolean raw = false;
        if (body != null && !body.isEmpty()) {
            if (body.equals(PAYLOAD)) {
                raw = true;
            } else {
                json = new TreeSet<>();
                JSON.readTree(body).propertyNames().forEach(json::add);
            }
        }
        return new Sent(request.getMethod().toString(), uri.getRawPath(), query, json, raw).toString();
    }
}
