package build.jenesis.repository.demo.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.demo.Demo;
import build.jenesis.repository.demo.DemoContributor;
import build.jenesis.repository.demo.web.DemoOffer;
import build.jenesis.repository.demo.web.DemoRun;
import build.jenesis.repository.demo.web.StarterDemo;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryType;
import build.jenesis.repository.gate.store.ComplianceScreen;
import build.jenesis.repository.server.FormatDispatcher;
import build.jenesis.repository.server.RepositoryController;
import build.jenesis.repository.server.kernel.PublishTenant;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.SettingsEditor;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.RepositoryDocument;
import build.jenesis.repository.store.Requests;
import build.jenesis.repository.ui.SetupOffer;
import build.jenesis.repository.web.testkit.Web;
import io.micrometer.observation.ObservationRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The demo loaded into an empty tenant through the core's own contributor, over a filesystem store with the
 * deployment's gate armed and the repository's real edge, and nothing reaching the network: the upstream fetcher
 * refuses every call, which is a deployment that cannot reach the public registries.
 *
 * <p>What it holds the demo to: every repository its plan names is created with its description and routing; the
 * first-party packages are published through the edge a client's upload takes and served afterwards; the library the
 * deny list names is held for review, which only happens because the deny list was put in force before it was
 * published; the settings it switches on are in force and recorded as the operator who confirmed the demo; the reads
 * through the proxies that could not reach a registry are reported as steps, never thrown, and leave the advisory feed
 * off, while reads that were answered switch it on and ask for the scan; and once the tenant holds a repository
 * neither the offer nor a second run is made.
 */
class DemoRunTest {

    private static final String TENANT = "default";
    private static final String OPERATOR = "github/ada";

    private static final String GREETING = "/maven/org/jenesis/demo/greeting/1.0.0/greeting-1.0.0.jar";
    private static final String HELD = "/maven/org/jenesis/demo/legacy-crypto/0.9.0/legacy-crypto-0.9.0.jar";

    /** A registry nobody can reach: every fetch fails as a refused connection does. */
    private static final ProxyFormat.Fetcher OFFLINE = new ProxyFormat.Fetcher() {
        @Override
        public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) throws IOException {
            throw new ConnectException("the demo's suite reaches no network: " + url);
        }

        @Override
        public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders)
                throws IOException {
            throw new ConnectException("the demo's suite reaches no network: " + url);
        }

        @Override
        public Optional<ProxyFormat.Head> head(URI url, Map<String, String> requestHeaders) throws IOException {
            throw new ConnectException("the demo's suite reaches no network: " + url);
        }
    };

    @TempDir
    Path root;

    private ComplianceScreen.Binding binding;
    private ArtifactStore store;
    private Repositories repositories;
    private SettingsEditor editor;
    private Web.Recording audit;
    private RepositoryController edge;

    @BeforeEach
    void wire() throws IOException {
        // The deployment's gate, read from its live configuration on every publish, as the gate's wiring arms it: a
        // deny list switched on during the run screens the publishes after it.
        binding = ComplianceScreen.binding()
                .gate(() -> repositories.live().publishGate(PublishTenant.current())).open();
        store = binding.bind(Web.store(root));
        repositories = Web.repositories(store);
        audit = Web.audit();
        editor = Web.editor(repositories, audit);
        List<RepositoryFormat> formats = new ArrayList<>();
        formats.addAll(RepositoryType.installed("maven").orElseThrow().formats());
        formats.addAll(RepositoryType.installed("npm").orElseThrow().formats());
        edge = new RepositoryController(Web.routing(store, repositories),
                new FormatDispatcher(formats, Map.of(), OFFLINE), List.of(), OFFLINE);
    }

    @AfterEach
    void unbind() {
        binding.close();
    }

    private DemoRun run(DemoContributor... contributors) {
        return new DemoRun(store, editor, audit, ObservationRegistry.NOOP, () -> List.of(contributors),
                DemoRun.Edge.over(() -> edge));
    }

    @Test
    void an_empty_tenant_is_offered_the_demo_with_its_warning_and_its_phrase() throws IOException {
        Optional<SetupOffer.Offer> offer = new DemoOffer(run(new StarterDemo()))
                .offer(new SetupOffer.Viewer(Optional.of(TENANT)));

        assertThat(offer).as("a tenant holding no repository is offered the demo").isPresent();
        assertThat(offer.get().warning()).contains("switches features on").contains("reaches public registries")
                .contains("known vulnerabilities");
        assertThat(offer.get().action().flatMap(SetupOffer.Action::confirmation))
                .map(SetupOffer.Confirmation::phrase).contains("I want to trial jenesis");
        assertThat(String.join("\n", offer.get().consequences()))
                .as("every repository, setting, registry and vulnerable artifact is named before anything is done")
                .contains("demo-maven (hosted maven)", "demo-npm (hosted npm)",
                        "demo-maven-proxy (a maven proxy of https://repo1.maven.org/maven2/)",
                        "demo-npm-proxy (a npm proxy of https://registry.npmjs.org/)")
                .contains("Deny list:", "Deny list action:", "OSV feed:")
                .contains("log4j-core-2.14.1.jar", "lodash-4.17.11.tgz");
    }

    @Test
    void no_demo_is_offered_while_no_tenant_is_selected() throws IOException {
        assertThat(new DemoOffer(run(new StarterDemo())).offer(new SetupOffer.Viewer(Optional.empty())))
                .as("the demo fills a tenant, so without one there is nothing to offer").isEmpty();
    }

    @Test
    void the_demo_creates_publishes_holds_and_switches_on_and_reports_the_registries_it_could_not_reach()
            throws IOException {
        DemoRun run = run(new StarterDemo());

        DemoRun.State state = run.runNow(TENANT, OPERATOR).orElseThrow();

        assertThat(state.running()).as("the run is done, not left running").isFalse();
        assertThat(state.stopped()).isFalse();
        assertThat(state.actor()).isEqualTo(OPERATOR);
        for (String name : List.of("demo-maven", "demo-npm", "demo-maven-proxy", "demo-npm-proxy")) {
            Optional<RepositoryDocument> document = RepositoryDocument.read(repository(name));
            assertThat(document).as("repository %s is created", name).isPresent();
            assertThat(document.get().description()).as("with a description").startsWith("Demo: ");
        }
        assertThat(editor.config(Setting.Scope.REPOSITORY, TENANT, "demo-maven-proxy").apply("routing"))
                .as("a proxy fetches from its registry").isEqualTo("fallback https://repo1.maven.org/maven2/");
        assertThat(editor.config(Setting.Scope.REPOSITORY, TENANT, "demo-maven").apply("routing"))
                .as("a hosted repository takes uploads").isNullOrEmpty();

        assertThat(state.count(DemoRun.Kind.PUBLISH, Demo.Outcome.DONE))
                .as("two releases of a library, POM and jar each, and an npm package, in %s", state.steps())
                .isEqualTo(5);
        assertThat(edge.fetch(TENANT, "demo-maven", GREETING)).as("a published library is served").isEqualTo(200);
        assertThat(edge.fetch(TENANT, "demo-npm", "/jenesis-demo-greeting"))
                .as("and so is the published npm package's document").isEqualTo(200);

        assertThat(state.count(DemoRun.Kind.PUBLISH, Demo.Outcome.HELD))
                .as("the library the deny list names is held, POM and jar").isEqualTo(2);
        assertThat(edge.fetch(TENANT, "demo-maven", HELD)).as("a held library is not served").isEqualTo(404);
        assertThat(repository("demo-maven").readVersioned(Publication.quarantineKey(HELD)))
                .as("it waits in the quarantine queue").isPresent();

        assertThat(editor.effective(null, "deny-list", "")).contains("org.jenesis.demo:legacy-crypto");
        assertThat(editor.effective(null, "deny-list-action", "")).isEqualTo("QUARANTINE");
        assertThat(audit.rows()).as("each setting the demo changed is recorded as the operator who confirmed it")
                .filteredOn(row -> row.action().equals(AuditActions.SETTING_SET))
                .extracting(Web.Recorded::actor).isNotEmpty().containsOnly(OPERATOR);

        assertThat(state.count(DemoRun.Kind.FETCH, Demo.Outcome.FAILED))
                .as("every read through a proxy is reported as not made, none thrown").isEqualTo(
                        RepositoryType.installed("maven").orElseThrow().formats().stream()
                                .mapToLong(format -> format.demoArtifacts().size()).sum()
                        + RepositoryType.installed("npm").orElseThrow().formats().stream()
                                .mapToLong(format -> format.demoArtifacts().size()).sum());
        assertThat(state.nothingFetched()).as("so the page can say the registries were not reached").isTrue();
        assertThat(editor.effective(null, "osv", "")).as("the fail-closed feed is left off where nothing answered")
                .isEmpty();
        assertThat(state.steps()).anySatisfy(step -> {
            assertThat(step.kind()).isEqualTo(DemoRun.Kind.SKIP);
            assertThat(step.what()).contains("OSV feed");
        });
        assertThat(Requests.pending(store, "scan")).isEmpty();
        assertThat(state.requested("scan")).isFalse();
        assertThat(Requests.pending(store, Requests.WALK)).as("a walk over what was published is asked for offline too")
                .isPresent();
        assertThat(state.requested(Requests.WALK)).isTrue();
    }

    @Test
    void reads_the_registries_answered_switch_the_advisory_feed_on_and_ask_for_the_scan() throws IOException {
        DemoRun.Edge real = DemoRun.Edge.over(() -> edge);
        List<String> read = new ArrayList<>();
        DemoRun.Edge answering = new DemoRun.Edge() {
            @Override
            public int publish(String tenant, String repository, String path, InputStream body) throws IOException {
                return real.publish(tenant, repository, path, body);
            }

            @Override
            public int fetch(String tenant, String repository, String path) {
                read.add(repository + path);
                return 200;
            }
        };
        DemoRun run = new DemoRun(store, editor, audit, ObservationRegistry.NOOP, () -> List.of(new StarterDemo()),
                answering);

        DemoRun.State state = run.runNow(TENANT, OPERATOR).orElseThrow();

        assertThat(read).as("the known-vulnerable versions are read through the proxy of their registry")
                .contains("demo-maven-proxy/maven/org/apache/logging/log4j/log4j-core/2.14.1/log4j-core-2.14.1.jar",
                        "demo-npm-proxy/lodash/-/lodash-4.17.11.tgz");
        assertThat(state.nothingFetched()).isFalse();
        assertThat(editor.effective(null, "osv", "")).as("the advisory feed is switched on").isEqualTo("true");
        assertThat(Requests.pending(store, "scan")).as("the scan is asked for, a minute on").isPresent()
                .get().satisfies(request -> assertThat(request.notBefore()).isAfter(Instant.now()));
        assertThat(state.requested("scan")).isTrue();
    }

    @Test
    void a_read_the_gate_held_for_review_is_reported_as_held_rather_than_as_not_fetched() throws IOException {
        DemoRun.Edge real = DemoRun.Edge.over(() -> edge);
        DemoRun.Edge holding = new DemoRun.Edge() {
            @Override
            public int publish(String tenant, String repository, String path, InputStream body) throws IOException {
                return real.publish(tenant, repository, path, body);
            }

            @Override
            public int fetch(String tenant, String repository, String path) throws IOException {
                // What the pull-through leaves when the gate holds what it fetched: the copy under review, and a 404.
                Publication publication = new Publication(repository(repository));
                publication.link("/quarantine" + path, publication.storeBlob(new ByteArrayInputStream(new byte[]{1})));
                return 404;
            }
        };
        DemoRun run = new DemoRun(store, editor, audit, ObservationRegistry.NOOP, () -> List.of(new StarterDemo()),
                holding);

        DemoRun.State state = run.runNow(TENANT, OPERATOR).orElseThrow();

        assertThat(state.steps()).filteredOn(step -> step.kind() == DemoRun.Kind.FETCH).isNotEmpty()
                .allSatisfy(step -> {
                    assertThat(step.outcome()).isEqualTo(Demo.Outcome.HELD);
                    assertThat(step.detail()).startsWith("Held for review");
                });
    }

    @Test
    void once_the_tenant_holds_a_repository_neither_the_offer_nor_a_second_run_is_made() throws IOException {
        DemoRun run = run(new StarterDemo());
        run.runNow(TENANT, OPERATOR);

        assertThat(new DemoOffer(run).offer(new SetupOffer.Viewer(Optional.of(TENANT)))).isEmpty();
        assertThat(run.start(TENANT, OPERATOR)).hasValueSatisfying(reason -> assertThat(reason)
                .contains("A repository exists here already"));
        assertThat(run.runNow(TENANT, OPERATOR)).isEmpty();
    }

    @Test
    void a_contributor_switches_only_what_its_plan_named_and_one_that_fails_does_not_stop_the_run()
            throws IOException {
        DemoContributor undeclared = new DemoContributor() {
            @Override
            public int order() {
                return 20;
            }

            @Override
            public Plan plan() {
                return new Plan(List.of(new Repository("extra", "maven", "Demo: another module's", Optional.empty())),
                        List.of(), new LinkedHashMap<>(), List.of(), List.of());
            }

            @Override
            public void load(Demo demo) throws IOException {
                demo.settings(Map.of("proxy-enabled", "false"));
                throw new IllegalStateException("a contributor that fails part way");
            }
        };
        DemoContributor after = new DemoContributor() {
            @Override
            public int order() {
                return 30;
            }

            @Override
            public Plan plan() {
                return new Plan(List.of(new Repository("later", "npm", "Demo: loaded after a failure", Optional.empty())),
                        List.of("a thing of its own module's"), new LinkedHashMap<>(), List.of(), List.of());
            }

            @Override
            public void load(Demo demo) throws IOException {
                demo.skipped("Nothing", "this contributor only proves it was reached");
                demo.made("Make a thing as " + demo.actor(), Demo.Outcome.DONE, "made through its own module");
            }
        };

        DemoRun.State state = run(undeclared, after).runNow(TENANT, OPERATOR).orElseThrow();

        assertThat(editor.effective(null, "proxy-enabled", "true"))
                .as("a setting the warning did not name is not switched").isEqualTo("true");
        assertThat(state.steps()).anySatisfy(step -> {
            assertThat(step.kind()).isEqualTo(DemoRun.Kind.SETTING);
            assertThat(step.outcome()).isEqualTo(Demo.Outcome.FAILED);
            assertThat(step.detail()).contains("did not name proxy-enabled");
        });
        assertThat(state.steps()).as("the failure is recorded against the contributor").anySatisfy(step -> {
            assertThat(step.kind()).isEqualTo(DemoRun.Kind.LOAD);
            assertThat(step.detail()).contains("a contributor that fails part way");
        });
        assertThat(state.steps()).as("and the next contributor still loads").anySatisfy(step ->
                assertThat(step.outcome()).isEqualTo(Demo.Outcome.SKIPPED));
        assertThat(state.steps()).as("what it made through its own module is recorded as it said, as the operator")
                .contains(new DemoRun.Step(DemoRun.Kind.MADE, "Make a thing as " + OPERATOR, Demo.Outcome.DONE,
                        "made through its own module"));
        assertThat(state.running()).isFalse();
    }

    private ArtifactStore repository(String name) {
        return store.scope(TENANT).scope(name);
    }
}
