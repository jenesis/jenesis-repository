package build.jenesis.repository.webhook.web.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.cleanup.RetentionProvider;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.events.EventType;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.staging.StagingProvider;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.webhook.WebhookOutbox;
import build.jenesis.repository.webhook.web.WebhookController;
import jakarta.servlet.http.HttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.anyInt;

/**
 * The event-webhook recovery controller ({@code /api/webhook}, {@code /api/webhook/retry}) directly over a real
 * filesystem artifact store and the framework-free {@link WebhookOutbox}, no server boot: a parked delivery is unparked
 * for another drain attempt, the retry is a history-preserving idempotent upsert (never a duplicate delivery, never a
 * lost delivered-endpoint set), and a retry that finds nothing parked answers a clean {@code 404} rather than a
 * {@code 500}. The write-role gate that fronts the mutation is the generic {@code /api/} {@code manage:write} of the
 * server's authorization chain, exercised at the server E2E level; here the controller contract is proven in isolation.
 */
public class WebhookRetryControllerTest {

    private static final String REPO = "releases";
    private static final String PATH = "/com/example/app/1.0.0/app-1.0.0.jar";
    private static final String DELIVERED_ENDPOINT = "already-took-it";

    @TempDir
    Path root;

    private Repositories repositories;
    private String tenant;
    private WebhookController controller;
    private RecordingAudit audit;

    @BeforeEach
    void setUp() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        RepositoryProperties properties = new RepositoryProperties();
        properties.setProxyEnabled(false);
        LiveConfig live = new LiveConfig(new Settings(store), properties, AdvisorySource.none(), _ -> null);
        repositories = new Repositories(store, Authorization.anonymous(), live,
                StagingProvider.resolve(_ -> null), RetentionProvider.resolve(_ -> null));
        tenant = repositories.tenant(null);            // the default tenant an anonymous request resolves to
        audit = new RecordingAudit();
        controller = new WebhookController(repositories, audit);
    }

    private WebhookOutbox outbox() {
        return new WebhookOutbox(repositories.store(tenant, REPO));
    }

    /** A parked entry that has already been taken by one endpoint - the state a terminal delivery failure leaves. */
    private WebhookOutbox.Entry parkedEntry(String id) {
        return new WebhookOutbox.Entry(id, "publish", "maven", "pkg:maven/com.example/app@1.0.0", "1.0.0", PATH,
                "{}", "2026-07-05T00:00:00Z", 5, 0L, true, "connection refused", Set.of(DELIVERED_ENDPOINT), 0L);
    }

    @Test
    void a_retry_unparks_a_parked_entry_preserving_its_delivered_history_and_audits() throws IOException {
        outbox().record(parkedEntry("evt-1"));

        CapturingResponse response = new CapturingResponse();
        controller.retry(REPO, null, new WebhookController.RetryRequest("evt-1"), response.proxy());

        assertThat(response.status()).as("a parked entry unparks with 200").isEqualTo(200);
        assertThat(outbox().entries()).singleElement().satisfies(entry -> {
            assertThat(entry.parked()).as("the park is lifted").isFalse();
            assertThat(entry.attempts()).as("attempts reset so the next drain retries it").isZero();
            assertThat(entry.delivered())
                    .as("history preserved - the endpoint that already took it is not re-delivered")
                    .containsExactly(DELIVERED_ENDPOINT);
        });
        assertThat(audit.events).singleElement().satisfies(event -> {
            assertThat(event.action()).isEqualTo("webhook.retry");
            assertThat(event.target()).isEqualTo(REPO + "/evt-1");
            assertThat(event.actor()).isEqualTo("anonymous");
        });
    }

    @Test
    void a_retry_is_idempotent_a_second_retry_of_an_unparked_entry_is_a_clean_404() throws IOException {
        outbox().record(parkedEntry("evt-1"));

        CapturingResponse first = new CapturingResponse();
        controller.retry(REPO, null, new WebhookController.RetryRequest("evt-1"), first.proxy());
        assertThat(first.status()).isEqualTo(200);

        CapturingResponse second = new CapturingResponse();
        assertThatCode(() ->
                controller.retry(REPO, null, new WebhookController.RetryRequest("evt-1"), second.proxy()))
                .doesNotThrowAnyException();

        assertThat(second.status()).as("a re-retry of an already-unparked entry is 404, not a duplicate or a 500")
                .isEqualTo(404);
        assertThat(outbox().entries()).as("no duplicate row from the second retry").hasSize(1);
        assertThat(audit.events).as("only the first, effective retry is audited").hasSize(1);
    }

    @Test
    void a_retry_of_a_missing_entry_is_a_404_not_a_500() throws IOException {
        CapturingResponse response = new CapturingResponse();
        assertThatCode(() ->
                controller.retry(REPO, null, new WebhookController.RetryRequest("no-such-id"), response.proxy()))
                .doesNotThrowAnyException();

        assertThat(response.status()).isEqualTo(404);
        assertThat(audit.events).isEmpty();
    }

    @Test
    void a_retry_of_a_not_yet_parked_entry_is_a_404() throws IOException {
        // A pending (not parked) entry must not be resettable through the recovery surface - only a terminal park is.
        outbox().record(new WebhookOutbox.Entry("evt-pending", "publish", "maven",
                "pkg:maven/com.example/app@1.0.0", "1.0.0", PATH, "{}", "2026-07-05T00:00:00Z",
                1, 0L, false, "", Set.of(), 0L));

        CapturingResponse response = new CapturingResponse();
        controller.retry(REPO, null, new WebhookController.RetryRequest("evt-pending"), response.proxy());

        assertThat(response.status()).isEqualTo(404);
        assertThat(outbox().entries()).singleElement()
                .satisfies(entry -> assertThat(entry.parked()).isFalse());
    }

    @Test
    void a_blank_id_is_rejected_with_400() throws IOException {
        CapturingResponse response = new CapturingResponse();
        controller.retry(REPO, null, new WebhookController.RetryRequest("  "), response.proxy());
        assertThat(response.status()).isEqualTo(400);
    }

    @Test
    void the_status_read_renders_the_stored_entries() throws IOException {
        outbox().record(parkedEntry("evt-1"));

        CapturingResponse response = new CapturingResponse();
        WebhookController.WebhookView view = controller.webhook(REPO, null, null, null, response.proxy());

        assertThat(view.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.id()).isEqualTo("evt-1");
            assertThat(entry.parked()).isTrue();
            assertThat(entry.status()).isEqualTo("parked");
            assertThat(entry.delivered()).isEqualTo(1);
            assertThat(entry.occurredAt()).isEqualTo("2026-07-05T00:00:00Z");
        });
    }

    /**
     * The runtime half of the reconciliation route: an operator asking this surface why an event never arrived is told
     * what to poll instead. The static half - that every {@link build.jenesis.repository.events.EventType} has a route
     * at all - is asserted separately in {@code test/events}; this asserts the separate thing, that the route reaches a
     * surface somebody reads. A route nothing renders would be a dead leg, and the webhook module cannot reach the
     * console's posture screen, so this endpoint is the surface it owns.
     */
    @Test
    void the_status_read_carries_the_reconciliation_route_for_every_event_type() throws IOException {
        CapturingResponse response = new CapturingResponse();
        WebhookController.WebhookView view = controller.webhook(REPO, null, null, null, response.proxy());

        assertThat(view.reconciliation()).as("the route is rendered even on an empty queue - an integrator asking "
                + "'where did my event go?' is looking at a queue that does NOT contain it").isNotNull();
        assertThat(view.reconciliation().delivery())
                .as("and it states the gap it is a route around, not just the reads")
                .containsIgnoringCase("at-least-once")
                .containsIgnoringCase("lossy");
        assertThat(view.reconciliation().routes())
                .as("every event type an endpoint can subscribe to has a row")
                .hasSize(EventType.values().length)
                .allSatisfy(route -> {
                    assertThat(route.type()).isNotBlank();
                    assertThat(route.ledger()).isNotBlank();
                    assertThat(route.route()).startsWith("GET /api/");
                    assertThat(route.caveat()).isNotBlank();
                });
        assertThat(view.reconciliation().routes()).extracting(WebhookController.ReconciliationRoute::type)
                .as("named by the same wire tokens webhook-endpoints filters on, so the two line up")
                .containsExactly(Arrays.stream(EventType.values()).map(EventType::wire).toArray(String[]::new));
    }

    /** A recording {@link AuditTrail} - captures the events the controller writes, so a retry's audit is asserted. */
    private static final class RecordingAudit implements AuditTrail {
        private final List<Event> events = new ArrayList<>();

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public void record(String tenant, String actor, String action, String target) {
            events.add(new Event(Instant.now(), actor, action, target));
        }

        @Override
        public List<Event> query(String tenant, Instant from, Instant to, String action) {
            return List.copyOf(events);
        }
    }

    /** A minimal {@link HttpServletResponse} mock that captures only {@code setStatus} - all the controller touches -
     *  so the contract is tested without a servlet container. Every un-stubbed method returns Mockito's
     *  type-appropriate default (0 / false / null). */
    private static final class CapturingResponse {
        private int status = 200;

        HttpServletResponse proxy() {
            HttpServletResponse response = mock(HttpServletResponse.class);
            doAnswer(invocation -> {
                status = invocation.getArgument(0);
                return null;
            }).when(response).setStatus(anyInt());
            when(response.getStatus()).thenAnswer(invocation -> status);
            return response;
        }

        int status() {
            return status;
        }
    }
}
