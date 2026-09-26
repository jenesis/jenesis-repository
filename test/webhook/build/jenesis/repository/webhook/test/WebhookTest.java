package build.jenesis.repository.webhook.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.events.EventPublicationObserver;
import build.jenesis.repository.events.EventSink;
import build.jenesis.repository.events.EventType;
import build.jenesis.repository.events.RepositoryEvent;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.maintenance.UnitFailures;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsSecrets;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.webhook.WebhookDelivery;
import build.jenesis.repository.webhook.WebhookDeliveryTask;
import build.jenesis.repository.webhook.WebhookEndpoint;
import build.jenesis.repository.webhook.WebhookOutbox;
import build.jenesis.repository.webhook.WebhookSettingsContributor;
import build.jenesis.repository.webhook.WebhookSink;
import build.jenesis.repository.webhook.Webhooks;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Event webhooks end to end over a filesystem store and a real loopback HTTP receiver - the sink's
 * enabled-gated outbox recording (reached through the seam's own emit, publish and unpublish included),
 * the drain's HTTP delivery with signature, event filter, retry-with-backoff and
 * terminal park, and the tenant/repository the pass stamps onto the payload - all with no network beyond loopback
 * and no framework. The event payload is small metadata; every bit of state is a store object.
 */
public class WebhookTest {

    private static final String PATH = "/com/example/app/1.0.0/app-1.0.0.jar";

    @TempDir
    Path root;

    private ArtifactStore store;

    /** Every gauge the drain published on the last pass, so a reading the pass is supposed to report can be asserted
     *  rather than assumed - the only channel the unsigned-endpoint condition has. */
    private final Map<String, Double> gauges = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                        key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null)
                .scope("acme").scope("releases");
        gauges.clear();
        Webhooks.configure(false);
    }

    @AfterEach
    void tearDown() {
        Webhooks.configure(false);
    }

    // ---- the sink and the observer -------------------------------------------------------------------------------

    @Test
    void the_sink_queues_an_event_when_enabled() throws IOException {
        Webhooks.configure(true);
        new WebhookSink().accept(store, RepositoryEvent.publish("maven", "pkg:maven/com.example/app@1.0.0",
                "1.0.0", PATH, Instant.parse("2026-07-05T00:00:00Z")));

        assertThat(new WebhookOutbox(store).entries()).singleElement().satisfies(entry -> {
            assertThat(entry.type()).isEqualTo("publish");
            assertThat(entry.coordinate()).isEqualTo("pkg:maven/com.example/app@1.0.0");
            assertThat(entry.path()).isEqualTo(PATH);
            assertThat(entry.delivered()).isEmpty();
            assertThat(entry.parked()).isFalse();
        });
    }

    @Test
    void the_sink_records_nothing_when_disabled() throws IOException {
        Webhooks.configure(false);
        new WebhookSink().accept(store, RepositoryEvent.publish("maven", "c", "1.0.0", PATH, Instant.now()));
        assertThat(new WebhookOutbox(store).entries()).isEmpty();
    }

    @Test
    void the_discovered_sink_fans_an_emit_out() throws IOException {
        Webhooks.configure(true);
        EventSink.emit(store, RepositoryEvent.quarantine("maven", "c", PATH, "QUARANTINE",
                List.of("CVE-2026-0001"), Instant.now()));
        assertThat(EventSink.installed()).contains("webhook");
        assertThat(new WebhookOutbox(store).entries()).singleElement()
                .satisfies(entry -> assertThat(entry.type()).isEqualTo("quarantine"));
    }

    /** The runtime leg for PUBLISH: the producer no longer writes into this module's outbox, it emits, and the
     *  note appears here only because this graph's discovered sink put it there. A second sink would see the publish
     *  too, which is the whole point of moving the producer beside the seam. */
    @Test
    void the_seam_queues_a_committed_publish_and_skips_a_checksum() throws IOException {
        Webhooks.configure(true);
        new EventPublicationObserver().onPublished(descriptor("pkg:maven/com.example/app@1.0.0", PATH), store);
        new EventPublicationObserver().onPublished(
                ArtifactDescriptor.at("maven", PATH + ".sha256"), store);                    // no coordinate

        assertThat(new WebhookOutbox(store).entries()).singleElement()
                .satisfies(entry -> assertThat(entry.path()).isEqualTo(PATH));
    }

    /** And for UNPUBLISH, the other constant that never travelled the seam before. */
    @Test
    void the_seam_queues_an_unpublish_on_delete_and_skips_a_checksum() throws IOException {
        Webhooks.configure(true);
        new EventPublicationObserver().onDeleted(descriptor("pkg:maven/com.example/app@1.0.0", PATH), store);
        new EventPublicationObserver().onDeleted(
                ArtifactDescriptor.at("maven", PATH + ".sha256"), store);                    // no coordinate

        assertThat(new WebhookOutbox(store).entries()).singleElement().satisfies(entry -> {
            assertThat(entry.type()).isEqualTo("unpublish");
            assertThat(entry.coordinate()).isEqualTo("pkg:maven/com.example/app@1.0.0");
            assertThat(entry.path()).isEqualTo(PATH);
        });
    }

    /** The feature dial now bites in the sink rather than in the producer: emit still fans out, and this module
     *  declines. A deployment with another sink installed keeps getting its unpublish events. */
    @Test
    void the_sink_records_no_unpublish_when_disabled() throws IOException {
        Webhooks.configure(false);
        new EventPublicationObserver().onDeleted(descriptor("pkg:maven/com.example/app@1.0.0", PATH), store);
        assertThat(new WebhookOutbox(store).entries()).isEmpty();
    }

    // ---- the drain -----------------------------------------------------------------------------------------------

    @Test
    void the_drain_delivers_a_queued_event_over_http_with_a_signature() throws Exception {
        StubEndpoint endpoint = StubEndpoint.start();
        try {
            RepositoryEvent event = RepositoryEvent.finding("maven", "pkg:maven/com.example/app@1.0.0", "1.0.0",
                    "osv", "CVE-2026-0001", "HIGH", "vulnerability", Instant.parse("2026-07-05T00:00:00Z"));
            new WebhookOutbox(store).record(event);
            String secret = "s3cr3t";
            String spec = endpoint.base() + "/hook *";                       // 2-field: url + events, no secret here
            String secrets = endpoint.base() + "/hook=" + secret;            // the secret lives in webhook-secrets, keyed by URL
            drain(WebhookDelivery.live())
                    .repository(context(spec, secrets, Instant.parse("2026-07-06T00:00:00Z"), true));

            assertThat(endpoint.received()).hasSize(1);
            StubEndpoint.Recorded got = endpoint.received().get(0);
            assertThat(got.event()).isEqualTo("finding");
            assertThat(got.contentType()).isEqualTo("application/json");
            assertThat(got.body())
                    .contains("\"type\":\"finding\"")
                    .contains("\"tenant\":\"acme\"")
                    .contains("\"repository\":\"releases\"")
                    .contains("\"coordinate\":\"pkg:maven/com.example/app@1.0.0\"")
                    .contains("\"source\":\"osv\"")
                    .contains("\"id\":\"CVE-2026-0001\"");
            assertThat(got.signature())
                    .isEqualTo("sha256=" + WebhookDelivery.sign(secret, got.body().getBytes(StandardCharsets.UTF_8)));
            assertThat(new WebhookOutbox(store).entries()).as("delivered entry is dropped").isEmpty();
        } finally {
            endpoint.stop();
        }
    }

    @Test
    void an_event_only_goes_to_an_endpoint_that_subscribes_to_its_type() throws Exception {
        StubEndpoint publishOnly = StubEndpoint.start();
        try {
            new WebhookOutbox(store).record(RepositoryEvent.finding("maven", "c", "1.0.0", "osv", "CVE-1",
                    "LOW", "vulnerability", Instant.parse("2026-07-05T00:00:00Z")));
            String spec = publishOnly.base() + "/hook publish";     // subscribes to publish only, not finding
            drain(WebhookDelivery.live()).repository(context(spec, Instant.parse("2026-07-06T00:00:00Z")));

            assertThat(publishOnly.received()).as("a finding is not delivered to a publish-only endpoint").isEmpty();
            assertThat(new WebhookOutbox(store).entries()).as("no endpoint subscribes, so the entry is dropped")
                    .isEmpty();
        } finally {
            publishOnly.stop();
        }
    }

    @Test
    void a_failing_delivery_retries_with_backoff_then_parks() throws IOException {
        new WebhookOutbox(store).record(RepositoryEvent.publish("maven", "c", "1.0.0", PATH,
                Instant.parse("2026-07-05T00:00:00Z")));
        WebhookDeliveryTask task = new WebhookDeliveryTask(Duration.ofMinutes(1), 3,
                Duration.ofSeconds(1), Duration.ofMinutes(1), new WebhookDelivery((_, _, _) -> 500));
        String spec = "http://localhost:1/hook";
        Instant now = Instant.parse("2026-07-06T00:00:00Z");

        task.repository(context(spec, now));
        WebhookOutbox.Entry first = only();
        assertThat(first.attempts()).isEqualTo(1);
        assertThat(first.parked()).isFalse();
        assertThat(first.nextAttemptMillis()).isGreaterThan(now.toEpochMilli());   // backed off into the future

        task.repository(context(spec, now));                        // before the window elapses: untouched
        assertThat(only().attempts()).isEqualTo(1);

        task.repository(context(spec, now.plus(Duration.ofHours(1))));
        assertThat(only().attempts()).isEqualTo(2);
        task.repository(context(spec, now.plus(Duration.ofHours(2))));   // third and terminal attempt
        WebhookOutbox.Entry terminal = only();
        assertThat(terminal.parked()).as("a terminal failure parks the entry (kept for the status surface)").isTrue();
        assertThat(terminal.attempts()).isEqualTo(3);

        task.repository(context(spec, now.plus(Duration.ofDays(1))));   // a parked entry is skipped, not re-attempted
        assertThat(only().attempts()).as("parked, so retained and not retried").isEqualTo(3);
    }

    /**
     *, the write-back leg: the drain commits its progress with the compare-and-set token it read the entry at,
     * so a rival that replaced the entry mid-pass - a concurrent unpark from {@code POST /api/webhook/retry}, which
     * runs off the drain's lease, or a producer's re-emit - is never overwritten by this pass's stale bookkeeping.
     *
     * <p>The loss this prevents is the wrong-direction one for an at-least-once seam. The rival writes a fresh,
     * immediately-eligible entry; the drain, still holding the copy it read before that, writes back an attempt bump
     * and a backoff window over it. The delivery that was just re-queued is silently deferred - and at the attempt cap
     * immediately re-parked - so the event is <em>lost</em> rather than duplicated. The rival writes inside the sender,
     * which is exactly when the window is open: after the pass read the entry, before it commits.
     */
    @Test
    void a_rival_that_requeues_an_entry_is_not_clobbered_by_the_drains_stale_progress() throws IOException {
        WebhookOutbox outbox = new WebhookOutbox(store);
        RepositoryEvent event = RepositoryEvent.publish("maven", "c", "1.0.0", PATH,
                Instant.parse("2026-07-05T00:00:00Z"));
        outbox.record(event);

        // The rival re-queues the entry with fresh bookkeeping (no attempts, no backoff) while the pass delivers -
        // the shape both an /api/webhook/retry unpark and a producer's re-emit leave behind.
        WebhookDelivery racing = new WebhookDelivery((_, _, _) -> {
            outbox.record(event);
            throw new IOException("receiver is down");
        });

        new WebhookDeliveryTask(Duration.ofMinutes(1), 3, Duration.ofSeconds(1), Duration.ofMinutes(1), racing)
                .repository(context("http://localhost:1/hook", Instant.parse("2026-07-06T00:00:00Z")));

        WebhookOutbox.Entry survivor = only();
        assertThat(survivor.attempts()).as("the rival's reset survives - the drain's stale attempt bump lost its "
                + "compare-and-set rather than overwriting a delivery that had just been re-queued").isZero();
        assertThat(survivor.nextAttemptMillis()).as("and no backoff window was re-imposed on it").isZero();
        assertThat(survivor.parked()).isFalse();
    }

    /**
     *, the drop leg - the same discipline where losing it deletes rather than defers. On a full delivery the
     * drain drops the entry; if a rival re-queued it mid-pass, an untokened delete removes an event that has been
     * delivered to nobody. Compare-and-set leaves it for the next drain instead.
     */
    @Test
    void a_rival_that_requeues_an_entry_is_not_deleted_by_the_drains_full_delivery_drop() throws IOException {
        WebhookOutbox outbox = new WebhookOutbox(store);
        RepositoryEvent event = RepositoryEvent.publish("maven", "c", "1.0.0", PATH,
                Instant.parse("2026-07-05T00:00:00Z"));
        outbox.record(event);

        // The rival re-queues while this pass's one and only endpoint is taking the event, so the pass goes on to
        // consider the entry fully delivered and to drop it.
        WebhookDelivery racing = new WebhookDelivery((_, _, _) -> {
            outbox.record(event);
            return 200;
        });

        new WebhookDeliveryTask(Duration.ofMinutes(1), 3, Duration.ofSeconds(1), Duration.ofMinutes(1), racing)
                .repository(context("http://localhost:1/hook", Instant.parse("2026-07-06T00:00:00Z")));

        assertThat(new WebhookOutbox(store).entries()).as("the re-queued event is not deleted along with the copy "
                + "this pass delivered - it is left for the next drain").hasSize(1);
    }

    @Test
    void an_endpoint_to_an_internal_host_is_classified_internal_and_filtered() {
        // The SSRF guard: a per-tenant endpoint must not reach a loopback, cloud-metadata, private or IPv6
        // unique-local target, but a public host is fine. Literals resolve to themselves, so no DNS is needed.
        for (String internal : List.of("http://127.0.0.1/hook", "http://localhost/hook",
                "http://169.254.169.254/latest/meta-data", "http://10.1.2.3/hook", "http://192.168.0.5/hook",
                "http://[::1]/hook", "http://[fc00::1]/hook")) {
            assertThat(WebhookEndpoint.parse(internal))
                    .as("%s parses", internal).singleElement()
                    .satisfies(endpoint -> assertThat(endpoint.internal()).as("%s is internal", internal).isTrue());
        }
        assertThat(new WebhookEndpoint(URI.create("http://93.184.216.34/hook"), Set.of(), null).internal())
                .as("a public address is deliverable").isFalse();

        // The delivery task drops internal endpoints unless webhook-allow-internal is set (default off).
        List<WebhookEndpoint> kept = WebhookEndpoint.parse("http://127.0.0.1/hook\nhttp://93.184.216.34/hook")
                .stream().filter(endpoint -> !endpoint.internal()).toList();
        assertThat(kept).singleElement().satisfies(endpoint ->
                assertThat(endpoint.url().getHost()).isEqualTo("93.184.216.34"));
    }

    @Test
    void a_repository_with_no_configured_endpoint_accumulates_no_outbox() throws IOException {
        new WebhookOutbox(store).record(RepositoryEvent.publish("maven", "c", "1.0.0", PATH, Instant.now()));
        WebhookDeliveryTask task = drain(new WebhookDelivery((_, _, _) -> 200));

        task.repository(context("", Instant.now()));
        assertThat(new WebhookOutbox(store).entries()).as("no endpoint, so the queue is drained").isEmpty();
    }

    @Test
    void configured_endpoints_that_all_filter_out_as_internal_retain_the_queue() throws IOException {
        // An endpoint IS configured, but it resolves internal and allow-internal is off, so the filter removes every
        // one - the same shape a transient DNS failure (unresolvable, so internal()) produces. That must NOT be
        // mistaken for "no endpoint configured" and delete the queue; the events must survive to deliver once
        // resolution recovers or the block is lifted.
        new WebhookOutbox(store).record(RepositoryEvent.publish("maven", "c", "1.0.0", PATH,
                Instant.parse("2026-07-05T00:00:00Z")));
        WebhookDeliveryTask task = drain(new WebhookDelivery((_, _, _) -> 200));

        task.repository(context("http://127.0.0.1/hook", Instant.parse("2026-07-06T00:00:00Z"), false));
        assertThat(new WebhookOutbox(store).entries())
                .as("configured-but-all-filtered endpoints retain the queue, they do not delete it").hasSize(1);

        // The existing contract still holds: genuinely no endpoint configured at all drains the queue.
        task.repository(context("", Instant.parse("2026-07-06T00:00:00Z"), false));
        assertThat(new WebhookOutbox(store).entries()).as("no endpoint configured at all - drained").isEmpty();
    }

    @Test
    void a_configured_but_wholly_unparseable_endpoint_spec_retains_the_queue() throws IOException {
        // The setting HAS content, but every line is unparseable (no scheme, then a non-HTTP scheme), so parse()
        // returns no endpoints. That is a misconfiguration, not "no endpoints configured": purging the queue would
        // silently drop deliverable events over a typo. Retain them; a corrected config delivers them on a later pass.
        new WebhookOutbox(store).record(RepositoryEvent.publish("maven", "c", "1.0.0", PATH,
                Instant.parse("2026-07-05T00:00:00Z")));
        WebhookDeliveryTask task = drain(new WebhookDelivery((_, _, _) -> 200));

        task.repository(context("not-a-url\nftp://nope/hook", Instant.parse("2026-07-06T00:00:00Z")));
        assertThat(new WebhookOutbox(store).entries())
                .as("a non-blank but unparseable endpoints spec retains the queue, it does not nuke it").hasSize(1);

        // The existing contract still holds: a genuinely blank spec drains the queue.
        task.repository(context("", Instant.parse("2026-07-06T00:00:00Z")));
        assertThat(new WebhookOutbox(store).entries()).as("a blank spec - no endpoints configured - drains").isEmpty();
    }

    @Test
    void a_terminally_failed_entry_is_retained_parked_not_deleted() throws IOException {
        new WebhookOutbox(store).record(RepositoryEvent.publish("maven", "c", "1.0.0", PATH,
                Instant.parse("2026-07-05T00:00:00Z")));
        // A public host (not filtered) whose delivery always fails: the single-attempt cap parks it on the first pass.
        WebhookDeliveryTask task = new WebhookDeliveryTask(Duration.ofMinutes(1), 1,
                Duration.ofSeconds(1), Duration.ofMinutes(1), new WebhookDelivery((_, _, _) -> 500));
        String spec = "http://93.184.216.34/hook";
        Instant now = Instant.parse("2026-07-06T00:00:00Z");

        task.repository(context(spec, now));
        WebhookOutbox.Entry parked = only();
        assertThat(parked.parked()).as("a terminal failure parks the entry, kept for retry not deleted").isTrue();
        assertThat(parked.attempts()).isEqualTo(1);

        // A later pass leaves the parked entry untouched (skipped by eligible()), never re-attempting or dropping it.
        task.repository(context(spec, now.plus(Duration.ofDays(1))));
        assertThat(only().parked()).as("still parked and retained").isTrue();
    }

    @Test
    void unpark_resets_a_parked_entry_but_keeps_its_delivered_set() throws IOException {
        // The recovery surface mirroring forwarding's /api/forwarding/retry -> Outbox.unpark: a terminally-parked
        // entry is reset for another drain, keeping which endpoints already took it so a retry re-sends only to those
        // that never did.
        WebhookOutbox outbox = new WebhookOutbox(store);
        WebhookOutbox.Entry parked = new WebhookOutbox.Entry("1720137600000-abc", "publish", "maven",
                "pkg:maven/x@1", "1", PATH, "{}", "2026-07-05T00:00:00Z",
                3, 999999L, true, "webhook endpoint answered 500", Set.of("aa", "bb"), 0L);
        outbox.record(parked);

        assertThat(outbox.unpark(parked.id())).as("a parked entry unparks").isTrue();
        WebhookOutbox.Entry reset = only();
        assertThat(reset.parked()).as("park lifted").isFalse();
        assertThat(reset.attempts()).as("attempts reset").isZero();
        assertThat(reset.nextAttemptMillis()).as("backoff cleared").isZero();
        assertThat(reset.lastError()).as("error cleared").isEmpty();
        assertThat(reset.delivered()).as("delivered set kept for a targeted retry")
                .containsExactlyInAnyOrder("aa", "bb");

        assertThat(outbox.unpark(parked.id())).as("no longer parked").isFalse();
        assertThat(outbox.unpark("nothing-queued-here")).as("nothing queued there").isFalse();
    }

    @Test
    void a_terminal_failure_moves_the_entry_out_of_the_live_drain_scan_into_the_parked_backlog() throws IOException {
        WebhookOutbox outbox = new WebhookOutbox(store);
        outbox.record(RepositoryEvent.publish("maven", "c", "1.0.0", PATH, Instant.parse("2026-07-05T00:00:00Z")));
        // A public host (not filtered) whose delivery always fails: the single-attempt cap parks it on the first pass.
        WebhookDeliveryTask task = new WebhookDeliveryTask(Duration.ofMinutes(1), 1,
                Duration.ofSeconds(1), Duration.ofMinutes(1), new WebhookDelivery((_, _, _) -> 500));
        String spec = "http://93.184.216.34/hook";
        Instant now = Instant.parse("2026-07-06T00:00:00Z");

        task.repository(context(spec, now));

        // The parked entry is out of the hot drain scan (active()) but moved into the parked backlog, and still
        // visible on the status surface (entries()) - so a terminal failure is retained and recoverable, not deleted,
        // yet never re-listed by the per-pass scan again.
        assertThat(outbox.active()).as("a parked entry is no longer walked by the live drain scan").isEmpty();
        assertThat(outbox.parkedCount()).as("it moved to the parked backlog").isEqualTo(1);
        assertThat(outbox.entries()).as("still visible on the status surface").singleElement().satisfies(entry -> {
            assertThat(entry.parked()).isTrue();
            assertThat(entry.attempts()).isEqualTo(1);
            // Stamped at the moment it parked, which is what the retention window is measured from. The id's own
            // millis prefix is the event's occurrence and can be arbitrarily older, so the two must not be confused.
            assertThat(entry.parkedAtMillis()).as("the park transition is stamped")
                    .isEqualTo(now.toEpochMilli());
        });

        // A later pass never re-scans the parked entry: the active scan is empty, so nothing is re-attempted.
        task.repository(context(spec, now.plus(Duration.ofDays(1))));
        assertThat(outbox.active()).as("parked, so out of the scan and not re-attempted").isEmpty();
        assertThat(outbox.parkedCount()).isEqualTo(1);
        // And the stamp is carried rather than refreshed. Restamping on every pass would push the retention window
        // forward for as long as the entry kept being written, so no backlog would ever age out.
        assertThat(outbox.entries()).singleElement().satisfies(entry ->
                assertThat(entry.parkedAtMillis()).as("carried across a later pass, not restamped")
                        .isEqualTo(now.toEpochMilli()));
    }

    @Test
    void unpark_moves_a_parked_entry_from_the_backlog_back_into_the_live_scan() throws IOException {
        // The dead-letter replay: a parked entry is moved back from the parked backlog into the active queue so the
        // next drain retries it, keeping which endpoints already took it (a targeted re-send).
        WebhookOutbox outbox = new WebhookOutbox(store);
        WebhookOutbox.Entry parked = new WebhookOutbox.Entry("1720137600000-abc", "publish", "maven",
                "pkg:maven/x@1", "1", PATH, "{}", "2026-07-05T00:00:00Z",
                3, 999999L, true, "webhook endpoint answered 500", Set.of("aa"), 0L);
        outbox.park(parked);
        assertThat(outbox.active()).as("parked, so out of the live scan").isEmpty();
        assertThat(outbox.parkedCount()).isEqualTo(1);

        assertThat(outbox.unpark(parked.id())).as("a parked entry in the backlog unparks").isTrue();
        assertThat(outbox.parkedCount()).as("moved out of the backlog").isZero();
        assertThat(outbox.active()).as("back in the live scan for the next drain").singleElement().satisfies(entry -> {
            assertThat(entry.parked()).as("park lifted").isFalse();
            assertThat(entry.attempts()).as("attempts reset").isZero();
            assertThat(entry.delivered()).as("delivered set kept for a targeted retry").containsExactly("aa");
        });

        assertThat(outbox.unpark(parked.id())).as("no longer parked anywhere").isFalse();
    }

    @Test
    void a_park_at_a_stale_token_does_not_clobber_a_concurrent_unparks_fresh_active_copy() throws IOException {
        // The drain-vs-retry race: the drain reads an entry at token K0 and then decides to park it, but meanwhile the
        // retry endpoint (off the drain's lease) replaces the active copy (unpark / same-milli re-emit), advancing its
        // token to K1. The drain's park must NOT delete that fresh copy - it guards the active removal on K0, sees the
        // token moved, and undoes its own park, so the queued delivery is retried rather than lost forever.
        WebhookOutbox outbox = new WebhookOutbox(store);
        outbox.record(RepositoryEvent.publish("maven", "x", "1.0.0", PATH, Instant.parse("2026-07-05T00:00:00Z")));
        WebhookOutbox.Queued<WebhookOutbox.Entry> read = outbox.queued().getFirst();          // the drain's read: entry + token K0
        WebhookOutbox.Entry entry = read.entry();
        Object staleToken = read.token();

        // A concurrent unpark / re-emit rewrites the active object since the drain read it, moving its token to K1
        // (a different delivered set changes the serialised bytes, so the store advances the version).
        outbox.update(new WebhookOutbox.Entry(entry.id(), entry.type(), entry.ecosystem(), entry.coordinate(),
                entry.version(), entry.path(), entry.detailJson(), entry.occurredAt(), entry.attempts(),
                entry.nextAttemptMillis(), entry.parked(), entry.lastError(), Set.of("aa"), 0L));
        assertThat(outbox.queued().getFirst().token()).as("the active copy's token has moved").isNotEqualTo(staleToken);

        outbox.park(read.entry(), staleToken);                          // the drain parks at the now-stale token

        assertThat(outbox.active()).as("the fresh active copy survives - the queued delivery is not lost").hasSize(1);
        assertThat(outbox.parkedCount()).as("the stale park undid itself, leaving no parked shadow").isZero();
    }

    /** A parked entry, stamped as parked at {@code parkedAt}. */
    private static WebhookOutbox.Entry parkedAt(String id, long parkedAt) {
        return new WebhookOutbox.Entry(id, "publish", "maven", "pkg:maven/x@1", "1", PATH, "{}",
                "2026-07-05T00:00:00Z", 3, 999999L, true, "dead endpoint", Set.of(), parkedAt);
    }

    @Test
    void the_retention_window_runs_from_when_an_entry_parked_not_from_when_its_event_occurred()
            throws IOException {
        // The two instants are far apart on purpose, and that gap is the whole defect this pins. An id's millis
        // prefix is the event's OCCURRENCE; an entry can retry for weeks before it gives up and parks. Reading the
        // id as a park time reclaimed an entry on the very first pass after it parked - a retention window that
        // retained nothing, and a maintenance pass that changed durable state on a repeat run.
        Instant now = Instant.parse("2026-07-05T00:00:00Z");
        long occurredLongAgo = now.minus(Duration.ofDays(200)).toEpochMilli();
        WebhookOutbox outbox = new WebhookOutbox(store);
        outbox.park(parkedAt(occurredLongAgo + "-fresh", now.minus(Duration.ofDays(1)).toEpochMilli()));

        assertThat(outbox.prunePark(now, Duration.ofDays(30), 0))
                .as("an entry parked yesterday is kept, however old the event behind it was").isZero();
        assertThat(outbox.parkedCount()).isEqualTo(1);
    }

    @Test
    void the_retention_window_reclaims_an_entry_that_has_been_parked_longer_than_it() throws IOException {
        Instant now = Instant.parse("2026-07-05T00:00:00Z");
        WebhookOutbox outbox = new WebhookOutbox(store);
        outbox.park(parkedAt("1720137600000-stale", now.minus(Duration.ofDays(31)).toEpochMilli()));
        outbox.park(parkedAt("1720137600001-recent", now.minus(Duration.ofDays(29)).toEpochMilli()));

        assertThat(outbox.prunePark(now, Duration.ofDays(30), 0))
                .as("only the one past the window").isEqualTo(1);
        assertThat(outbox.parkedCount()).isEqualTo(1);
        assertThat(outbox.entries().stream().map(WebhookOutbox.Entry::id))
                .as("and it is the one still inside the window that survives")
                .containsExactly("1720137600001-recent");
    }

    @Test
    void a_repeated_prune_over_unchanged_state_changes_nothing() throws IOException {
        // The maintenance contract's convergence clause, at the level of the primitive: this is the shape the
        // WebhookDeliveryTask contract caught, so it is pinned here where it is cheap to see.
        Instant now = Instant.parse("2026-07-05T00:00:00Z");
        WebhookOutbox outbox = new WebhookOutbox(store);
        outbox.park(parkedAt("1720137600000-kept", now.minus(Duration.ofDays(2)).toEpochMilli()));

        assertThat(outbox.prunePark(now, Duration.ofDays(30), 10)).isZero();
        assertThat(outbox.prunePark(now, Duration.ofDays(30), 10)).as("and again").isZero();
        assertThat(outbox.parkedCount()).isEqualTo(1);
    }

    @Test
    void an_unstamped_parked_entry_is_never_aged_out_but_the_cap_still_bounds_it() throws IOException {
        // 0 means "nothing recorded when this parked" - never delete what cannot be judged. The cap is what keeps
        // a backlog of them from growing without limit, so both halves are asserted together.
        Instant now = Instant.parse("2026-07-05T00:00:00Z");
        WebhookOutbox outbox = new WebhookOutbox(store);
        for (int i = 0; i < 5; i++) {
            outbox.park(parkedAt("1720137600000-u" + i, 0L));
        }
        assertThat(outbox.prunePark(now, Duration.ofDays(1), 0))
                .as("age alone reclaims none of them").isZero();
        assertThat(outbox.prunePark(now, Duration.ofDays(1), 2))
                .as("but the cap does").isEqualTo(3);
        assertThat(outbox.parkedCount()).isEqualTo(2);
    }

    @Test
    void the_live_drain_scan_stays_bounded_as_parked_entries_accumulate() throws IOException {
        // The unbounded-scan fix: dead-endpoint entries pile up as parked over time, but the hot drain scan set does
        // not grow with them - it walks only the active queue, so a drain pass stays O(live), not O(live + parked).
        WebhookOutbox outbox = new WebhookOutbox(store);
        for (int i = 0; i < 25; i++) {
            outbox.park(new WebhookOutbox.Entry("1720137600000-p" + i, "publish", "maven",
                    "pkg:maven/x@" + i, Integer.toString(i), PATH, "{}", "2026-07-05T00:00:00Z",
                    3, 999999L, true, "dead endpoint", Set.of(), 0L));
        }
        assertThat(outbox.parkedCount()).as("the parked backlog grows").isEqualTo(25);
        assertThat(outbox.active()).as("the drain scan does not grow with the parked backlog").isEmpty();

        // One live entry is still scanned; the 25 parked ones are not in that scan.
        outbox.record(RepositoryEvent.publish("maven", "live", "1.0.0", PATH, Instant.parse("2026-07-05T00:00:00Z")));
        assertThat(outbox.active()).as("only the one live entry is walked by the drain").hasSize(1);
        assertThat(outbox.entries()).as("the status surface still shows every entry").hasSize(26);
    }

    @Test
    void delivery_refuses_an_internal_host_at_send_time_even_if_it_passed_an_earlier_filter() {
        // TOCTOU / DNS-rebinding SSRF: a plain HttpClient re-resolves the host at connect time, so an endpoint that
        // passed the drain's filter can still point at an internal address by the time bytes go out. The live sender
        // re-screens immediately before sending and refuses. Deterministic via a loopback literal (resolves to itself).
        // The URL is https so it is the HOST half of the screen under test here and not the transport half - the two
        // halves are separately asserted, and a shared message would let one cover for the other's absence.
        assertThat(WebhookEndpoint.internal(URI.create("https://127.0.0.1/hook"))).as("vetting helper").isTrue();
        assertThat(WebhookEndpoint.internal(URI.create("https://93.184.216.34/hook"))).as("public is fine").isFalse();

        boolean[] sent = {false};
        WebhookDelivery delivery = new WebhookDelivery((_, _, _) -> {
            sent[0] = true;
            return 200;
        });
        WebhookEndpoint internal = new WebhookEndpoint(URI.create("https://127.0.0.1/hook"), Set.of(), null);
        WebhookOutbox.Entry entry = WebhookOutbox.Entry.fresh(RepositoryEvent.publish("maven", "c", "1.0.0", PATH,
                Instant.parse("2026-07-05T00:00:00Z")));

        assertThatThrownBy(() -> delivery.deliver(internal, entry, "acme", "releases", false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("internal or non-public address")
                .as("the host half refused it, not the transport half - the URL is already https")
                .hasMessageNotContaining("not https");
        assertThat(sent[0]).as("refused before the socket is touched").isFalse();

        // The intended allow-internal bypass still reaches an internal target - the re-screen is only for the default.
        assertThatCode(() -> delivery.deliver(internal, entry, "acme", "releases", true))
                .doesNotThrowAnyException();
        assertThat(sent[0]).as("allow-internal delivers to the internal host").isTrue();
    }

    @Test
    void a_plaintext_endpoint_is_refused_and_the_refusal_is_recorded_where_an_operator_reads_it() throws IOException {
        //, footgun one. An http:// endpoint is a per-tenant dial a tenant admin can set alone, and it puts the
        // event's tenant, repository, coordinate, version and path in front of any network observer - and lets an
        // active intermediary rewrite what the receiver acts on. The transport is stated by the URL, so this side can
        // judge it, and it is REFUSED: the same screen the forwarding peer already applies to its own
        // operator-supplied targets (PrivateHostGuard.refusalReason), under the same deployment-global opt-out.
        //
        // A public literal, so the host half of the screen admits it and only the transport half can refuse. If this
        // test ever goes green with no refusal, cleartext delivery has come back.
        String spec = "http://93.184.216.34/hook";
        new WebhookOutbox(store).record(RepositoryEvent.publish("maven", "c", "1.0.0", PATH,
                Instant.parse("2026-07-05T00:00:00Z")));
        List<URI> sent = new ArrayList<>();
        WebhookDeliveryTask task = new WebhookDeliveryTask(Duration.ofMinutes(1), 1, Duration.ofSeconds(1),
                Duration.ofMinutes(1), new WebhookDelivery((url, _, _) -> {
                    sent.add(url);
                    return 200;
                }));

        task.repository(context(spec, "", Instant.parse("2026-07-06T00:00:00Z"), false));

        assertThat(sent).as("nothing leaves for a plaintext endpoint - refused before the socket is touched").isEmpty();
        WebhookOutbox.Entry refused = only();
        assertThat(refused.lastError())
                .as("""
                        the refusal is recorded against the queued event, so it reaches an operator on the webhook \
                        status surface (GET /api/webhook) naming the endpoint, the reason and the dial that permits \
                        it - a silent filter here is what left a tenant watching events never arrive""")
                .contains("93.184.216.34")
                .contains("not https")
                .contains("webhook-allow-internal");
        assertThat(refused.parked()).as("and it is kept, parked at the attempt cap, not deleted").isTrue();

        // The opt-out is real: a deployment that has said it runs a trusted plain-HTTP receiver still delivers. It is
        // deployment-global on purpose - endpoints are per-tenant, so a tenant-scoped opt-out would let a tenant admin
        // alone put that tenant's event metadata on the wire in cleartext.
        new WebhookOutbox(store).unpark(refused.id());
        task.repository(context(spec, "", Instant.parse("2026-07-07T00:00:00Z"), true));
        assertThat(sent).as("webhook-allow-internal permits the plaintext receiver explicitly")
                .containsExactly(URI.create(spec));
    }

    @Test
    void an_endpoint_with_no_matching_secret_is_delivered_and_reported_rather_than_refused() throws IOException {
        //, footgun two - and the one that is NOT refused, deliberately. An unsigned delivery is the worse
        // failure (a receiver acting on a forged POST beats an eavesdropper reading metadata), but severity is not
        // what decides the mechanism: judgeability is. A secretless https URL is very often itself the bearer
        // credential - chat and CI incoming-webhook URLs are exactly that - and this side cannot tell such a URL from
        // a public one, so refusing would be refusing on a guess and would break the commonest receivers there are.
        // It is reported instead, on the one channel that can see it per tenant without enumerating anything.
        new WebhookOutbox(store).record(RepositoryEvent.publish("maven", "c", "1.0.0", PATH,
                Instant.parse("2026-07-05T00:00:00Z")));
        List<String> signatures = new ArrayList<>();
        WebhookDeliveryTask task = drain(new WebhookDelivery((_, _, headers) -> {
            signatures.add(headers.get(WebhookDelivery.SIGNATURE_HEADER));
            return 200;
        }));
        String spec = "https://93.184.216.34/one *\nhttps://93.184.216.35/two *";
        String oneSecret = "https://93.184.216.34/one=s3cr3t";

        task.repository(context(spec, oneSecret, Instant.parse("2026-07-06T00:00:00Z"), false));

        assertThat(gauges).as("""
                        the pass publishes the reading the refusal deliberately does not raise: how many of this \
                        tenant's configured endpoints have no webhook-secrets entry and therefore leave their \
                        receiver unable to tell a genuine event from a forged POST""")
                .containsEntry("jenreg.webhook.unsigned", 1.0d);
        assertThat(signatures).as("and both endpoints were delivered to - the unsigned one is not refused")
                .hasSize(2);
        assertThat(signatures.get(0)).as("the endpoint keyed in webhook-secrets is signed").startsWith("sha256=");
        assertThat(signatures.get(1)).as("the one with no entry carries no signature header at all").isNull();

        // Configuring the missing secret is the fix, and the reading says so - so an operator can tell "fixed" from
        // "never had one", which is the whole point of reporting rather than staying silent.
        new WebhookOutbox(store).record(RepositoryEvent.publish("maven", "d", "1.0.0", PATH,
                Instant.parse("2026-07-07T00:00:00Z")));
        task.repository(context(spec, oneSecret + "\nhttps://93.184.216.35/two=other",
                Instant.parse("2026-07-08T00:00:00Z"), false));
        assertThat(gauges).as("with every endpoint keyed in webhook-secrets the reading falls to zero")
                .containsEntry("jenreg.webhook.unsigned", 0.0d);
    }

    @Test
    void a_retry_re_sends_only_to_the_endpoint_that_never_took_it() throws Exception {
        StubEndpoint good = StubEndpoint.start();
        try {
            new WebhookOutbox(store).record(RepositoryEvent.publish("maven", "c", "1.0.0", PATH,
                    Instant.parse("2026-07-05T00:00:00Z")));
            // Two endpoints; one is dead. The first pass delivers to the good one and fails the dead one.
            String spec = good.base() + "/hook\nhttp://localhost:1/dead";
            WebhookDeliveryTask task = drain(WebhookDelivery.live());
            task.repository(context(spec, Instant.parse("2026-07-06T00:00:00Z")));
            assertThat(good.received()).hasSize(1);
            WebhookOutbox.Entry entry = only();
            assertThat(entry.delivered()).containsExactly(new WebhookEndpoint(
                    URI.create(good.base() + "/hook"), Set.of(), null).key());

            // A later pass past the backoff retries: the good endpoint is not re-sent, the dead one is retried.
            task.repository(context(spec, Instant.parse("2026-07-06T06:00:00Z")));
            assertThat(good.received()).as("the endpoint that already took it is not re-sent").hasSize(1);
        } finally {
            good.stop();
        }
    }

    @Test
    void the_body_signature_matches_an_independent_known_answer_hmac_sha256() {
        // The drain test verifies the delivered signature by calling the PRODUCTION sign() on both sides - self-
        // agreeing, so a wrong HMAC construction inside sign() (a truncated key, upper-case hex, the wrong algorithm)
        // would still match itself. Pin it to an INDEPENDENT known answer instead: a fixed secret and body whose
        // HMAC-SHA256 is both computed here with a plain javax.crypto Mac and wired in as a pre-computed literal vector,
        // so a regression in sign() is caught by a disagreement rather than a mutual agreement.
        String secret = "s3cr3t";
        byte[] body = "{\"type\":\"finding\",\"id\":\"CVE-2026-0001\"}".getBytes(StandardCharsets.UTF_8);

        // The ground-truth vector: HMAC-SHA256("s3cr3t", body) as lower-case hex, computed offline and pinned here.
        String knownAnswer = "72d289e87eb77e84d90e783f9c40c53900db3d68b63f723f66f3413ef71cf2df";
        // And the same value recomputed in-test with a plain Mac, deliberately NOT via WebhookDelivery.sign.
        assertThat(independentHmacSha256(secret, body))
                .as("the in-test Mac agrees with the pre-computed known-answer vector").isEqualTo(knownAnswer);

        assertThat(WebhookDelivery.sign(secret, body))
                .as("production sign() reproduces the independent known-answer HMAC (lower-case hex), byte for byte")
                .isEqualTo(knownAnswer);
        assertThat("sha256=" + WebhookDelivery.sign(secret, body))
                .as("the sha256= framed signature header equals the independent known answer")
                .isEqualTo("sha256=" + knownAnswer);
    }

    /** HMAC-SHA256 of {@code body} under {@code secret} as lower-case hex, computed with a plain {@code javax.crypto}
     *  {@link Mac} - deliberately NOT {@link WebhookDelivery#sign}, so it is an independent check of that method rather
     *  than a self-agreeing one. */
    private static String independentHmacSha256(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- config + payload + outbox parsing -----------------------------------------------------------------------

    @Test
    void endpoints_are_parsed_from_the_two_field_spec_and_bad_lines_skipped() {
        // The endpoints spec is 2-field now: '<url> [events]', no secret. parse() carries no secret at all - the secret
        // is attached separately from webhook-secrets - so every parsed endpoint is unsigned until one is attached.
        String spec = "https://a/hook publish,finding\n bad-line ; http://b/hook ; ftp://c/x ; http://d/hook *";
        List<WebhookEndpoint> endpoints = WebhookEndpoint.parse(spec);
        assertThat(endpoints).extracting(e -> e.url().toString())
                .containsExactly("https://a/hook", "http://b/hook", "http://d/hook");   // ftp + bad-line skipped
        assertThat(endpoints.get(0).subscribes(EventType.PUBLISH)).isTrue();
        assertThat(endpoints.get(0).subscribes(EventType.QUARANTINE)).isFalse();
        assertThat(endpoints.get(0).signed()).as("parse carries no secret - the endpoints line has none").isFalse();
        assertThat(endpoints.get(1).subscribes(EventType.PROMOTION)).as("no filter means all").isTrue();
        assertThat(endpoints.get(1).signed()).isFalse();
    }

    @Test
    void a_secret_attached_from_the_webhook_secrets_map_signs_by_url_and_a_missing_one_stays_unsigned() {
        // webhook-secrets maps '<url>=<secret>' per line, keyed by the endpoint URL; withSecret attaches it.
        Map<String, String> secrets = WebhookEndpoint.secrets("https://a/hook=top-secret\nhttps://b/hook=other=with=eq");
        assertThat(secrets).containsEntry("https://a/hook", "top-secret")
                .containsEntry("https://b/hook", "other=with=eq");   // only the first '=' splits url from secret

        WebhookEndpoint signed = new WebhookEndpoint(URI.create("https://a/hook"), Set.of(), null)
                .withSecret(secrets.get("https://a/hook"));
        assertThat(signed.signed()).as("a matching secret signs").isTrue();
        assertThat(signed.secret()).isEqualTo("top-secret");

        WebhookEndpoint unsigned = new WebhookEndpoint(URI.create("https://c/hook"), Set.of(), null)
                .withSecret(secrets.get("https://c/hook"));   // no entry -> null
        assertThat(unsigned.signed()).as("no secret entry -> unsigned").isFalse();

        assertThat(WebhookEndpoint.secrets("")).as("a blank secrets spec is empty").isEmpty();
        assertThat(WebhookEndpoint.secrets("no-equals-line\n=no-url\nhttps://d/hook=")).as("malformed lines skipped")
                .isEmpty();
    }

    @Test
    void an_endpoint_without_a_secret_entry_is_delivered_unsigned() throws Exception {
        StubEndpoint endpoint = StubEndpoint.start();
        try {
            new WebhookOutbox(store).record(RepositoryEvent.publish("maven", "pkg:maven/x@1", "1.0.0", PATH,
                    Instant.parse("2026-07-05T00:00:00Z")));
            String spec = endpoint.base() + "/hook *";
            // A secrets map that keys a DIFFERENT URL - this endpoint has no entry, so its delivery must be unsigned.
            String secrets = "https://elsewhere/hook=unused";
            drain(WebhookDelivery.live())
                    .repository(context(spec, secrets, Instant.parse("2026-07-06T00:00:00Z"), true));

            assertThat(endpoint.received()).hasSize(1);
            assertThat(endpoint.received().get(0).signature()).as("no matching secret, so no signature header").isNull();
        } finally {
            endpoint.stop();
        }
    }

    @Test
    void the_webhook_secrets_key_is_classified_secret_so_it_is_redacted_and_kept_out_of_export() {
        // webhook-secrets is a Kind.SECRET setting, so SettingsSecrets redacts it on read-back and drops it from the
        // export bundle by construction - the whole point of moving the HMAC secret off the STRING endpoints line.
        assertThat(new WebhookSettingsContributor().settings())
                .filteredOn(setting -> setting.key().equals("webhook-secrets"))
                .singleElement()
                .satisfies(setting -> assertThat(setting.kind()).isEqualTo(Setting.Kind.SECRET));
        assertThat(new WebhookSettingsContributor().settings())
                .filteredOn(setting -> setting.key().equals("webhook-endpoints"))
                .singleElement()
                .satisfies(setting -> assertThat(setting.kind())
                        .as("the endpoints spec is a plain STRING, no secret rides it").isEqualTo(Setting.Kind.STRING));
        assertThat(SettingsSecrets.secret("webhook-secrets")).as("classified SECRET - redacted / kept out of export")
                .isTrue();
        assertThat(SettingsSecrets.secret("webhook-endpoints")).as("the endpoints line is not a secret").isFalse();
    }

    @Test
    void a_payload_stamps_the_pass_tenant_and_repository() {
        WebhookOutbox.Entry entry = WebhookOutbox.Entry.fresh(RepositoryEvent.promotion("stg-1", 3,
                Instant.parse("2026-07-05T00:00:00Z")));
        String payload = WebhookDelivery.payload(entry, "acme", "releases");
        assertThat(payload)
                .contains("\"type\":\"promotion\"")
                .contains("\"tenant\":\"acme\"")
                .contains("\"repository\":\"releases\"")
                .contains("\"staging\":\"stg-1\"")
                .contains("\"artifacts\":\"3\"");
    }

    @Test
    void an_outbox_entry_round_trips_through_the_store() throws IOException {
        WebhookOutbox outbox = new WebhookOutbox(store);
        WebhookOutbox.Entry entry = new WebhookOutbox.Entry("1720137600000-abc", "quarantine", "maven",
                "pkg:maven/x@1", null, "/x", "{\"verdict\":\"REJECT\"}", "2026-07-05T00:00:00Z",
                2, 12345L, true, "endpoint answered 500", Set.of("aa", "bb"), 0L);
        outbox.record(entry);
        assertThat(outbox.entries()).singleElement().isEqualTo(entry);
    }

    // ---- the bounded window the status surface reads --------------------------------------------------------------

    /** An entry under one id, either queued or parked, with everything else fixed - the paging claims below care about
     *  identity and namespace, nothing else. */
    private WebhookOutbox.Entry entry(String id, boolean parked) {
        return new WebhookOutbox.Entry(id, "publish", "maven", "pkg:maven/com.example/app@1.0.0", "1.0.0", PATH,
                "{}", "2026-07-05T00:00:00Z", parked ? 5 : 0, 0L, parked, parked ? "connection refused" : "",
                Set.of(), 0L);
    }

    @Test
    void a_window_is_capped_and_says_that_more_remain() throws IOException {
        WebhookOutbox outbox = new WebhookOutbox(store);
        for (int index = 0; index < 12; index++) {
            outbox.record(entry(String.format("evt-%02d", index), false));
        }

        WebhookOutbox.Window<WebhookOutbox.Entry> window = outbox.entries(null, 5);

        assertThat(window.entries()).as("the window holds what was asked for, not the whole outbox").hasSize(5);
        assertThat(window.more()).as("and reports that more remain, so a capped answer cannot read as complete")
                .isTrue();
        assertThat(window.next()).as("the cursor to resume from is the last id in the window").isEqualTo("evt-04");
    }

    @Test
    void the_cursor_walks_every_entry_exactly_once_across_the_queue_and_the_parked_backlog() throws IOException {
        WebhookOutbox outbox = new WebhookOutbox(store);
        List<String> expected = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            String id = String.format("evt-%02d", index);
            expected.add(id);
            // Alternating, so every page spans both namespaces: the merge of two independently paged prefixes is
            // exactly where a paging bug drops or repeats an entry, and one namespace at a time would not show it.
            if (index % 2 == 0) {
                outbox.record(entry(id, false));
            } else {
                outbox.park(entry(id, true));
            }
        }

        List<String> walked = new ArrayList<>();
        String after = null;
        for (int page = 0; page < 20; page++) {
            WebhookOutbox.Window<WebhookOutbox.Entry> window = outbox.entries(after, 5);
            window.entries().forEach(entry -> walked.add(entry.id()));
            if (!window.more()) {
                break;
            }
            after = window.next();
        }

        assertThat(walked).as("every entry seen exactly once, in id order, with no gap and no repeat")
                .containsExactlyElementsOf(expected);
    }

    @Test
    void a_crash_left_duplicate_is_deduped_in_a_window_too_with_the_parked_copy_winning() throws IOException {
        WebhookOutbox outbox = new WebhookOutbox(store);
        outbox.record(entry("evt-01", false));          // the active copy a crash mid-park left behind
        outbox.park(entry("evt-01", true));             // and the terminal copy that was already written

        WebhookOutbox.Window<WebhookOutbox.Entry> window = outbox.entries(null, 10);

        assertThat(window.entries()).as("one row, not two - the same dedupe the unbounded read applies")
                .singleElement()
                .satisfies(entry -> assertThat(entry.parked()).as("the parked copy wins as the terminal one").isTrue());
        assertThat(window.more()).as("a deduped pair does not fake a further page").isFalse();
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private WebhookOutbox.Entry only() throws IOException {
        List<WebhookOutbox.Entry> entries = new WebhookOutbox(store).entries();
        assertThat(entries).hasSize(1);
        return entries.get(0);
    }

    private WebhookDeliveryTask drain(WebhookDelivery delivery) {
        return new WebhookDeliveryTask(Duration.ofMinutes(1), 5,
                Duration.ofSeconds(1), Duration.ofMinutes(1), delivery);
    }

    private static ArtifactDescriptor descriptor(String coordinate, String path) {
        return new ArtifactDescriptor("maven", coordinate, "1.0.0", path,
                "application/java-archive", false, "deadbeef", 17L);
    }

    private RepositoryContext context(String endpoints, Instant now) {
        return context(endpoints, "", now, true);   // the test receivers are loopback servers, so internal is allowed
    }

    private RepositoryContext context(String endpoints, Instant now, boolean allowInternal) {
        return context(endpoints, "", now, allowInternal);
    }

    private RepositoryContext context(String endpoints, String secrets, Instant now, boolean allowInternal) {
        return new RepositoryContext() {
            @Override
            public UnitFailures failures(String work, String consequence) {
                return new UnitFailures(work, consequence);
            }

            @Override
            public String tenant() {
                return "acme";
            }

            @Override
            public String repository() {
                return "releases";
            }

            @Override
            public ArtifactStore store() {
                return store;
            }

            @Override
            public UnaryOperator<String> config() {
                return key -> switch (key) {
                    case "webhook-endpoints" -> endpoints;
                    case "webhook-secrets" -> secrets;
                    case "webhook-allow-internal" -> Boolean.toString(allowInternal);
                    default -> null;
                };
            }

            @Override
            public Instant now() {
                return now;
            }

            @Override
            public void gauge(String name, String description, Map<String, String> tags, double value) {
                gauges.put(name, value);
            }
        };
    }

    /** A loopback HTTP receiver standing in for a webhook consumer: it records every POST's body and the webhook
     *  headers and answers a configurable status, so the delivery's wire behaviour (payload, signature, filter) is
     *  asserted without a live receiver. */
    private static final class StubEndpoint {

        record Recorded(String body, String event, String id, String signature, String contentType) {
        }

        private final WireMockServer server;

        private StubEndpoint(WireMockServer server) {
            this.server = server;
        }

        static StubEndpoint start() {
            WireMockServer server = new WireMockServer(
                    WireMockConfiguration.options().bindAddress("localhost").dynamicPort());
            server.start();
            server.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)));
            return new StubEndpoint(server);
        }

        String base() {
            return "http://localhost:" + server.port();
        }

        /** Every POST WireMock recorded, in arrival order (the journal is newest-first, so it is reversed). */
        List<Recorded> received() {
            return server.getAllServeEvents().reversed().stream()
                    .map(event -> {
                        LoggedRequest request = event.getRequest();
                        return new Recorded(request.getBodyAsString(),
                                request.getHeader(WebhookDelivery.EVENT_HEADER),
                                request.getHeader(WebhookDelivery.ID_HEADER),
                                request.getHeader(WebhookDelivery.SIGNATURE_HEADER),
                                request.getHeader("Content-Type"));
                    })
                    .toList();
        }

        void stop() {
            server.stop();
        }
    }
}
