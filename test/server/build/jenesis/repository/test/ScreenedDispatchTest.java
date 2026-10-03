package build.jenesis.repository.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.server.EdgeHooks;
import build.jenesis.repository.server.FormatDispatcher;
import build.jenesis.repository.server.ScreenedDispatch;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The free ingress write edge ({@link ScreenedDispatch}) screens a claimed single-body write exactly once, before the
 * format lays it out, and leaves a {@code screened()==false} format's write unscreened. It drives the edge directly
 * over a real filesystem store with two spy formats - one a plain layout writer that does no screening of its own, one
 * that opts out of edge screening like OCI - and the discovered {@link CountingInterceptor} counts how often the chain
 * assesses a body, so "screened exactly once" and "bypassed" are asserted against a real count rather than inferred.
 */
public class ScreenedDispatchTest {

    @TempDir
    Path root;

    private ArtifactStore store;

    /** A spy format: a pure layout writer that records what its handle received and how often it ran, and does no
     *  screening itself - so any screening a test observes came from the edge. */
    private static final class SpyFormat implements RepositoryFormat {

        private final String name;
        private final String prefix;
        private final boolean screened;
        private int writes;
        private byte[] received;

        private SpyFormat(String name, String prefix, boolean screened) {
            this.name = name;
            this.prefix = prefix;
            this.screened = screened;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean handles(String path) {
            return path.startsWith(prefix);
        }

        @Override
        public boolean screened() {
            return screened;
        }

        @Override
        public void serve(FormatExchange exchange, ArtifactStore store) throws IOException {
            if ("PUT".equals(exchange.method()) || "POST".equals(exchange.method())
                    || "PATCH".equals(exchange.method())) {
                try (InputStream in = exchange.requestStream()) {
                    received = in.readAllBytes();
                }
                writes++;
                exchange.respond(201);
            } else {
                exchange.respond(404);
            }
        }
    }

    /** A minimal {@link FormatExchange}: a request of a method/path/body, capturing the status the edge or format set. */
    private static class FakeExchange implements FormatExchange {

        private final String method;
        private final String path;
        private final byte[] body;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final ByteArrayOutputStream responded = new ByteArrayOutputStream();
        private int status = -1;

        private FakeExchange(String method, String path, byte[] body) {
            this.method = method;
            this.path = path;
            this.body = body;
        }

        @Override
        public String method() {
            return method;
        }

        @Override
        public String path() {
            return path;
        }

        @Override
        public String queryParameter(String name) {
            return null;
        }

        @Override
        public String requestHeader(String name) {
            return null;
        }

        @Override
        public InputStream requestStream() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public void setResponseHeader(String name, String value) {
            headers.put(name, value);
        }

        @Override
        public OutputStream respond(int status, long contentLength) {
            this.status = status;
            return responded;
        }
    }

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        CountingInterceptor.reset();
    }

    private static ScreenedDispatch edge(RepositoryFormat... formats) {
        return new ScreenedDispatch(new FormatDispatcher(List.of(formats), Map.of(), ProxyFormat.Fetcher.NONE));
    }

    @Test
    void a_screened_put_is_screened_once_at_the_edge_then_laid_out() throws IOException {
        SpyFormat spy = new SpyFormat("spyscreened", "/spyscreened/", true);
        FakeExchange put = new FakeExchange("PUT", "/spyscreened/count-me/thing", "payload".getBytes(StandardCharsets.UTF_8));

        assertThat(edge(spy).dispatch("acme", put, store)).isTrue();

        assertThat(put.status).as("the format laid the accepted body out and set its own 201").isEqualTo(201);
        assertThat(spy.writes).as("the format's handle ran exactly once, over the restreamed blob").isEqualTo(1);
        assertThat(spy.received).as("the restreamed body is the screened bytes, byte for byte")
                .isEqualTo("payload".getBytes(StandardCharsets.UTF_8));
        assertThat(CountingInterceptor.count()).as("the edge screened the body exactly once (the format did not re-screen)")
                .isEqualTo(1);
    }

    /**
     * The client hears of an accepted write only after the edge's commit has returned, which is after every
     * after-commit observer has run. The format answers inside the layout, and the servlet exchange commits a
     * response as soon as the format closes its stream - so without the edge holding the answer, a client was
     * acknowledged while the publish's consequences were still running and its next request raced them. The probe
     * is the outer exchange's status as seen from inside the format's own layout: still unanswered there, answered
     * once the dispatch returns.
     */
    @Test
    void an_accepted_write_is_answered_only_after_its_commit_has_returned() throws IOException {
        FakeExchange put = new FakeExchange("PUT", "/heldanswer/thing", "payload".getBytes(StandardCharsets.UTF_8));
        int[] seenInsideLayout = {Integer.MIN_VALUE};
        RepositoryFormat format = new RepositoryFormat() {

            @Override
            public String name() {
                return "heldanswer";
            }

            @Override
            public boolean handles(String path) {
                return path.startsWith("/heldanswer/");
            }

            @Override
            public void serve(FormatExchange exchange, ArtifactStore store) throws IOException {
                try (InputStream in = exchange.requestStream()) {
                    in.readAllBytes();
                }
                exchange.setResponseHeader("Location", exchange.path());
                exchange.respond(201, "laid out".getBytes(StandardCharsets.UTF_8));
                seenInsideLayout[0] = put.status;
            }
        };

        assertThat(edge(format).dispatch("acme", put, store)).isTrue();

        assertThat(seenInsideLayout[0]).as("the format's answer had not reached the client while its layout ran")
                .isEqualTo(-1);
        assertThat(put.status).as("the answer reached the client once the dispatch returned").isEqualTo(201);
        assertThat(put.responded.toByteArray()).as("the body the format wrote, byte for byte")
                .isEqualTo("laid out".getBytes(StandardCharsets.UTF_8));
        assertThat(put.headers).containsEntry("Location", "/heldanswer/thing");
    }

    @Test
    void a_rejected_put_answers_422_and_never_reaches_the_format() throws IOException {
        SpyFormat spy = new SpyFormat("spyscreened", "/spyscreened/", true);
        FakeExchange put = new FakeExchange("PUT", "/spyscreened/count-me/gate-reject/x",
                "bad".getBytes(StandardCharsets.UTF_8));

        assertThat(edge(spy).dispatch("acme", put, store)).isTrue();

        assertThat(put.status).as("a rejected body is 422 at the edge").isEqualTo(422);
        assertThat(put.responded.toString(StandardCharsets.UTF_8))
                .as("and the publisher is told why, by the screen whose verdict refused it, and what happened")
                .isEqualTo("Refused by the compliance gate: The test gate refuses a marked path. Nothing was "
                        + "published; the refusal and its findings are listed on the repository's Refused screen.");
        assertThat(put.headers).containsEntry("Content-Type", "text/plain; charset=utf-8");
        assertThat(spy.writes).as("the format is never handed a rejected body - the edge screened before layout")
                .isZero();
    }

    @Test
    void a_quarantined_put_answers_202_and_is_not_laid_out() throws IOException {
        SpyFormat spy = new SpyFormat("spyscreened", "/spyscreened/", true);
        FakeExchange put = new FakeExchange("PUT", "/spyscreened/count-me/gate-quarantine/y",
                "hold".getBytes(StandardCharsets.UTF_8));

        assertThat(edge(spy).dispatch("acme", put, store)).isTrue();

        assertThat(put.status).as("a quarantined body is 202 at the edge").isEqualTo(202);
        assertThat(put.responded.toString(StandardCharsets.UTF_8))
                .as("told why it is held and where it is decided, never what an accepting screen said")
                .isEqualTo("Held for review: The test gate holds a marked path. It is stored but not served until a "
                        + "reviewer releases it on the repository's Quarantine screen.");
        assertThat(spy.writes).as("a quarantined body is held, never laid out").isZero();
    }

    @Test
    void an_unscreened_format_write_bypasses_the_edge_screen() throws IOException {
        SpyFormat oci = new SpyFormat("spybypass", "/spybypass/", false);
        FakeExchange put = new FakeExchange("PUT", "/spybypass/count-me/thing",
                "layer".getBytes(StandardCharsets.UTF_8));

        assertThat(edge(oci).dispatch("acme", put, store)).isTrue();

        assertThat(put.status).as("the format handled its own write").isEqualTo(201);
        assertThat(oci.writes).as("the format handled the write directly, unscreened").isEqualTo(1);
        assertThat(CountingInterceptor.count()).as("a screened()==false format's write never touches the edge screen")
                .isZero();
    }

    /**
     * An unscreened format commits its own publish and links a released file through {@code Blobs.linkRelease} as a
     * screened one does, so the tenant's {@code allow-redeploy} has to reach its write through the edge just as it
     * reaches a screened layout - otherwise the opt-out silently binds only the formats the edge screens.
     */
    @Test
    void an_unscreened_write_runs_under_the_tenant_s_allow_redeploy() throws IOException {
        List<Boolean> seen = new ArrayList<>();
        RepositoryFormat unscreened = new RepositoryFormat() {

            @Override
            public String name() {
                return "redeploying";
            }

            @Override
            public boolean handles(String path) {
                return path.startsWith("/redeploying/");
            }

            @Override
            public boolean screened() {
                return false;
            }

            @Override
            public void serve(FormatExchange exchange, ArtifactStore store) throws IOException {
                seen.add(Publication.redeployAllowed());
                exchange.respond(201);
            }
        };
        FormatDispatcher dispatcher = new FormatDispatcher(List.of(unscreened), Map.of(), ProxyFormat.Fetcher.NONE);
        EdgeHooks allowing = new EdgeHooks() {

            @Override
            public boolean redeploys(RepositoryFormat format, ArtifactStore store) {
                return true;
            }
        };

        new ScreenedDispatch(dispatcher, allowing).dispatch("acme",
                new FakeExchange("PUT", "/redeploying/a", new byte[0]), store);
        new ScreenedDispatch(dispatcher).dispatch("acme",
                new FakeExchange("PUT", "/redeploying/a", new byte[0]), store);
        new ScreenedDispatch(dispatcher, allowing).dispatch("acme",
                new FakeExchange("GET", "/redeploying/a", new byte[0]), store);

        assertThat(seen).as("a write of a tenant that allows redeploys may replace a release, one of a tenant that "
                + "does not may not, and a read is no publish at all").containsExactly(true, false, false);
    }

    /**
     * A request the format declares as administering the repository is the operator's: refused without the right to
     * administer it whatever the caller may publish, and otherwise handed to the format with nothing screened and
     * nothing announced, since it is no artifact.
     */
    @Test
    void an_administration_request_takes_the_operator_s_right_and_is_no_publish() throws IOException {
        List<String> handled = new ArrayList<>();
        RepositoryFormat signing = new RepositoryFormat() {

            @Override
            public String name() {
                return "keyed";
            }

            @Override
            public boolean handles(String path) {
                return path.startsWith("/keyed/");
            }

            @Override
            public boolean administers(String method, String path) {
                return method.equals("POST") && path.equals("/keyed/keyring");
            }

            @Override
            public void serve(FormatExchange exchange, ArtifactStore store) throws IOException {
                handled.add(exchange.path());
                exchange.respond(200, 0L);
            }
        };
        FakeExchange publisher = new FakeExchange("POST", "/keyed/keyring", new byte[0]);
        FakeExchange operator = new FakeExchange("POST", "/keyed/keyring", new byte[0]) {
            @Override
            public boolean administers() {
                return true;
            }
        };

        edge(signing).dispatch("acme", publisher, store);
        edge(signing).dispatch("acme", operator, store);

        assertThat(publisher.status).as("a caller that may not administer the repository is refused").isEqualTo(403);
        assertThat(operator.status).isEqualTo(200);
        assertThat(handled).as("only the operator's request reached the format").containsExactly("/keyed/keyring");
        assertThat(CountingInterceptor.count()).as("and nothing of either was screened as an artifact").isZero();
    }

    @Test
    void a_read_dispatches_unscreened() throws IOException {
        SpyFormat spy = new SpyFormat("spyscreened", "/spyscreened/", true);
        FakeExchange get = new FakeExchange("GET", "/spyscreened/count-me/thing", new byte[0]);

        assertThat(edge(spy).dispatch("acme", get, store)).isTrue();

        assertThat(CountingInterceptor.count()).as("a read carries no body to screen").isZero();
        assertThat(spy.writes).isZero();
    }

    @Test
    void an_unclaimed_path_is_not_dispatched() throws IOException {
        SpyFormat spy = new SpyFormat("spyscreened", "/spyscreened/", true);
        FakeExchange put = new FakeExchange("PUT", "/nobody/claims/this", "x".getBytes(StandardCharsets.UTF_8));

        assertThat(edge(spy).dispatch("acme", put, store)).as("no format claimed the path, so the caller answers 404")
                .isFalse();
        assertThat(spy.writes).isZero();
    }

    @Test
    void the_default_is_edge_screened_and_oci_opts_out() {
        RepositoryFormat plain = new RepositoryFormat() {
            @Override
            public String name() {
                return "plain";
            }

            @Override
            public boolean handles(String path) {
                return false;
            }

            @Override
            public void serve(FormatExchange exchange, ArtifactStore store) {
            }
        };
        assertThat(plain.screened()).as("a format is edge-screened by default").isTrue();
        assertThat(RepositoryFormat.installed("oci").orElseThrow().screened())
                .as("OCI, whose push is split across requests, opts out of the edge screen").isFalse();
    }
}
