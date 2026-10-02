package build.jenesis.repository.demo.web;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.definitions.RoutingSettingsContributor;
import build.jenesis.repository.demo.Demo;
import build.jenesis.repository.demo.DemoContributor;
import build.jenesis.repository.format.RepositoryType;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.server.RepositoryController;
import build.jenesis.repository.server.kernel.PublishTenant;
import build.jenesis.repository.server.kernel.SettingsEditor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Lease;
import build.jenesis.repository.store.Requests;
import build.jenesis.repository.ui.store.RepositoryLifecycle;
import build.jenesis.repository.ui.store.SettingsAdmin;
import io.micrometer.observation.ObservationRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Loads the demo into one tenant, off the request path, and keeps what it did where the screen reads it back.
 *
 * <p>A run starts only while the tenant holds no repository, and holds a {@link Lease} in the tenant's
 * {@code .system/locks} space for its length, so two operators - on one node or two - start one run. It creates every
 * repository the contributors' plans name, through the one creation every surface makes, then runs each contributor's
 * load in order through a {@link Demo} that makes each operation the way a client or an operator makes it: a setting
 * through the settings editor, audited as the operator who confirmed the demo; a publish and a read through the
 * repository's own edge ({@link Edge}); a pass asked for as a standing request. Each operation records its outcome as
 * a step, and the run's document - one small JSON object at {@code .system/demo/run} in the tenant's space, rewritten
 * after every step - is what the screen polls.
 *
 * <p>The run owns its thread: a virtual thread started for it, which ends with the run. A node that dies part way
 * leaves the document saying it is running while nothing holds the lease, which the screen reports as stopped.
 */
public final class DemoRun {

    /** What the operator types to confirm the demo. */
    public static final String PHRASE = "I want to trial jenesis";

    private static final Logger LOGGER = LoggerFactory.getLogger(DemoRun.class);

    /** How long a run is believed to be running: the ttl of the lease it holds. */
    static final Duration LEASE = Duration.ofHours(1);

    private static final String LOCK = "demo";

    /** The run's document, in the tenant's own product space. */
    static final String DOCUMENT = Scopes.space("demo") + "/run";

    /** How many of a tenant's top-level names are looked at to decide whether it holds a repository: its own product
     *  spaces sort among them, and there are a handful of those. */
    private static final int PROBE = 64;

    /** How many steps a run's document keeps; past it, the count of the steps left out. */
    static final int STEPS = 200;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ArtifactStore root;
    private final SettingsEditor editor;
    private final AuditTrail audit;
    private final ObservationRegistry observations;
    private final Supplier<List<DemoContributor>> contributors;
    private final Edge edge;

    /**
     * @param root         the deployment's store, whose tenant spaces hold the repositories.
     * @param editor       the settings editor every surface changes a setting through.
     * @param audit        where a repository created by the run is recorded.
     * @param observations what times the run's repository creations, as the console's own.
     * @param contributors the contributors, asked when a plan is wanted.
     * @param edge         the repository's ingress and serving edge, in process.
     */
    public DemoRun(ArtifactStore root, SettingsEditor editor, AuditTrail audit, ObservationRegistry observations,
                   Supplier<List<DemoContributor>> contributors, Edge edge) {
        this.root = root;
        this.editor = editor;
        this.audit = audit;
        this.observations = observations;
        this.contributors = contributors;
        this.edge = edge;
    }

    /**
     * The repository's edge as the demo publishes and reads through it: a {@code PUT} and a {@code GET} a client would
     * make, in process. {@link #NONE} answers {@code 503} to both, for a console with no repository in its process.
     */
    public interface Edge {

        /** No repository runs in this process. */
        Edge NONE = new Edge() {
            @Override
            public int publish(String tenant, String repository, String path, InputStream body) {
                return UNSERVED;
            }

            @Override
            public int fetch(String tenant, String repository, String path) {
                return UNSERVED;
            }
        };

        /** What {@link #NONE} answers. */
        int UNSERVED = 503;

        int publish(String tenant, String repository, String path, InputStream body) throws IOException;

        int fetch(String tenant, String repository, String path) throws IOException;

        /**
         * The edge of the repository {@code repository} answers, resolved on every call and {@link #UNSERVED} while it
         * answers none: a publish with the tenant bound around it, as the request filter binds it for a publish over
         * the wire, so the gate screens it by that tenant's policy; a read as a client's {@code GET}.
         */
        static Edge over(Supplier<RepositoryController> repository) {
            return new Edge() {
                @Override
                public int publish(String tenant, String name, String path, InputStream body) throws IOException {
                    RepositoryController edge = repository.get();
                    if (edge == null) {
                        return UNSERVED;
                    }
                    try (PublishTenant.Scope _ = PublishTenant.open(tenant)) {
                        return edge.publish(tenant, name, path, body);
                    }
                }

                @Override
                public int fetch(String tenant, String name, String path) throws IOException {
                    RepositoryController edge = repository.get();
                    return edge == null ? UNSERVED : edge.fetch(tenant, name, path);
                }
            };
        }
    }

    /** What a step was: the kind of operation, which the screen sums by. */
    public enum Kind { REPOSITORY, SETTING, PUBLISH, FETCH, REQUEST, SKIP, LOAD }

    /** How a step ended. */
    public enum Outcome { DONE, HELD, REFUSED, FAILED, SKIPPED }

    /** One step of a run: what kind of operation, what it was, how it ended and what the deployment said. */
    public record Step(Kind kind, String what, Outcome outcome, String detail) {

        public Step {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(what, "what");
            Objects.requireNonNull(outcome, "outcome");
            detail = detail == null ? "" : detail;
        }
    }

    /**
     * A run as stored: whether it is still going, whether it stopped part way (its document says running and nothing
     * holds its lease), who confirmed it, when it started and finished, its steps, and how many steps past the
     * {@link #STEPS} kept were left out.
     */
    public record State(boolean running, boolean stopped, String actor, Instant started, Instant finished,
                        List<Step> steps, int omitted) {

        public State {
            steps = List.copyOf(steps);
        }

        /** How many steps of {@code kind} ended {@code outcome}. */
        public long count(Kind kind, Outcome outcome) {
            return steps.stream().filter(step -> step.kind() == kind && step.outcome() == outcome).count();
        }

        /** How many steps ended {@code outcome}, whatever their kind. */
        public long count(Outcome outcome) {
            return steps.stream().filter(step -> step.outcome() == outcome).count();
        }

        /** Whether the run asked a proxy for anything and every one of those reads came back without it - the
         *  registries were not reached from this server. */
        public boolean nothingFetched() {
            return steps.stream().anyMatch(step -> step.kind() == Kind.FETCH)
                    && count(Kind.FETCH, Outcome.DONE) == 0;
        }

        /** Whether the run asked for a background pass. */
        public boolean requested() {
            return count(Kind.REQUEST, Outcome.DONE) > 0;
        }
    }

    /** Whether a run was started, and why not when it was not. */
    public record Started(boolean started, String reason) {
    }

    /** Each contributor with its plan, in the order they load. */
    public record Planned(DemoContributor contributor, DemoContributor.Plan plan) {
    }

    /** The contributors' plans, in the order they load: by {@link DemoContributor#order()}, then by class name. */
    public List<Planned> plans() {
        return contributors.get().stream()
                .sorted(Comparator.comparingInt(DemoContributor::order)
                        .thenComparing(contributor -> contributor.getClass().getName()))
                .map(contributor -> new Planned(contributor, contributor.plan()))
                .toList();
    }

    /** Whether {@code tenant} holds a repository: one page of its top-level names, at most {@value #PROBE}. */
    public boolean holdsRepository(String tenant) {
        boolean[] held = {false};
        root.scope(Scopes.require("tenant", tenant)).page("", "", PROBE, name -> held[0] |= Scopes.valid(name));
        return held[0];
    }

    /** The tenant's run, as stored: one point read, and one more for its lease while it says it is running. */
    public Optional<State> state(String tenant) throws IOException {
        ArtifactStore space = root.scope(Scopes.require("tenant", tenant));
        Optional<ArtifactStore.Versioned> stored = space.readVersioned(DOCUMENT);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        JsonNode document = JSON.readTree(stored.get().content());
        boolean running = document.path("status").asString("").equals("RUNNING");
        boolean stopped = running && lease(space).holder(LOCK, Instant.now()).isEmpty();
        List<Step> steps = new ArrayList<>();
        for (JsonNode step : document.path("steps")) {
            try {
                steps.add(new Step(Kind.valueOf(step.path("kind").asString("")), step.path("what").asString(""),
                        Outcome.valueOf(step.path("outcome").asString("")), step.path("detail").asString("")));
            } catch (IllegalArgumentException unknown) {
                // A step a different build wrote: shown by what it says, its outcome unknown here.
                steps.add(new Step(Kind.LOAD, step.path("what").asString(""), Outcome.FAILED,
                        step.path("detail").asString("")));
            }
        }
        return Optional.of(new State(running && !stopped, stopped, document.path("actor").asString(""),
                instant(document.path("started").asString("")), instant(document.path("finished").asString("")),
                steps, document.path("omitted").asInt(0)));
    }

    /**
     * Start the demo for {@code tenant} on a thread of its own, confirmed by {@code actor}, and answer at once. Refused
     * while the tenant holds a repository or a run is under way.
     */
    public Started start(String tenant, String actor) throws IOException {
        Optional<Recorder> recorder = begin(tenant, actor);
        if (recorder.isEmpty()) {
            return refusal(tenant);
        }
        Thread.ofVirtual().name("demo-" + tenant).start(() -> finish(recorder.get()));
        return new Started(true, "");
    }

    /**
     * The demo run to its end on the calling thread, and what it did: the seam a test drives, and what
     * {@link #start}'s thread runs.
     */
    public Optional<State> runNow(String tenant, String actor) throws IOException {
        Optional<Recorder> recorder = begin(tenant, actor);
        if (recorder.isEmpty()) {
            return Optional.empty();
        }
        finish(recorder.get());
        return state(tenant);
    }

    private Started refusal(String tenant) {
        return new Started(false, holdsRepository(tenant)
                ? "The tenant holds a repository already; the demo fills only an empty one."
                : "A demo is loading into this tenant already.");
    }

    /** Take the run's lease and record it as running, unless the tenant holds a repository or another run holds the
     *  lease. */
    private Optional<Recorder> begin(String tenant, String actor) throws IOException {
        ArtifactStore space = root.scope(Scopes.require("tenant", tenant));
        if (holdsRepository(tenant)) {
            return Optional.empty();
        }
        Lease lease = lease(space);
        if (!lease.acquire(LOCK, Lease.processHolder(), Instant.now())) {
            return Optional.empty();
        }
        Recorder recorder = new Recorder(tenant, actor, space, lease, Instant.now());
        recorder.write();
        return Optional.of(recorder);
    }

    /** Every repository the plans name, then every contributor's load, and the run marked done whatever happened. */
    private void finish(Recorder recorder) {
        try {
            run(recorder);
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("The demo for tenant {} stopped", recorder.tenant, failure);
            recorder.addQuietly(new Step(Kind.LOAD, "Load the demo", Outcome.FAILED, String.valueOf(failure)));
        } finally {
            recorder.done();
        }
    }

    private void run(Recorder recorder) throws IOException {
        List<Planned> planned = plans();
        Bound bound = new Bound(recorder.tenant, recorder.actor);
        for (Planned each : planned) {
            for (DemoContributor.Repository repository : each.plan().repositories()) {
                recorder.add(create(bound.lifecycle, repository));
            }
        }
        for (Planned each : planned) {
            if (each.plan().empty()) {
                continue;
            }
            try {
                each.contributor().load(new Run(recorder, each.plan().settings().keySet()));
            } catch (IOException | RuntimeException failure) {
                LOGGER.warn("The demo contributor {} stopped", each.contributor().getClass().getName(), failure);
                recorder.add(new Step(Kind.LOAD, "Load " + each.contributor().getClass().getSimpleName(),
                        Outcome.FAILED, "It stopped part way: " + failure.getMessage()));
            }
        }
    }

    /** Create one repository through the one creation every surface makes, and say how it went. */
    private static Step create(RepositoryLifecycle lifecycle, DemoContributor.Repository repository) {
        String what = "Create " + repository.name() + " (" + (repository.hosted() ? "hosted " + repository.type()
                : repository.type() + " proxy") + ")";
        try {
            Map<String, String> values = repository.hosted() ? Map.of()
                    : Map.of(RoutingSettingsContributor.KEY, repository.routing());
            RepositoryType.Creation creation = lifecycle.create(repository.name(), repository.type(),
                    repository.description(), values, true);
            return switch (creation) {
                case CREATED -> new Step(Kind.REPOSITORY, what, Outcome.DONE, repository.description());
                case CONFLICT -> new Step(Kind.REPOSITORY, what, Outcome.FAILED,
                        "A repository of that name holds another type.");
                default -> new Step(Kind.REPOSITORY, what, Outcome.SKIPPED, "It exists already, and is left as it is.");
            };
        } catch (IOException | IllegalArgumentException refused) {
            return new Step(Kind.REPOSITORY, what, Outcome.FAILED, refused.getMessage());
        }
    }

    /** The console's repository services bound to the run's tenant and operator, since the run has no request whose
     *  session would name them. */
    private final class Bound {

        private final RepositoryLifecycle lifecycle;

        private Bound(String tenant, String actor) {
            SettingsAdmin settings = new SettingsAdmin(root, editor, List::of, audit, () -> tenant, () -> actor,
                    _ -> null);
            lifecycle = new RepositoryLifecycle(root, () -> tenant, observations, audit, () -> actor, settings);
        }
    }

    /** The {@link Demo} one contributor loads through: each operation made, and its outcome recorded. */
    private final class Run implements Demo {

        private final Recorder recorder;
        private final Set<String> declared;

        private Run(Recorder recorder, Set<String> declared) {
            this.recorder = recorder;
            this.declared = Set.copyOf(declared);
        }

        @Override
        public String tenant() {
            return recorder.tenant;
        }

        @Override
        public String setting(String key) {
            String value = editor.effective(null, key, "");
            return value == null ? "" : value;
        }

        @Override
        public boolean settings(Map<String, String> values) throws IOException {
            String what = "Switch on " + String.join(", ", values.keySet().stream()
                    .map(key -> Labels.of(key) + " (" + key + ")").toList());
            Set<String> undeclared = new TreeSet<>(values.keySet());
            undeclared.removeAll(declared);
            if (!undeclared.isEmpty()) {
                recorder.add(new Step(Kind.SETTING, what, Outcome.FAILED, "Not switched: the demo's warning did not "
                        + "name " + String.join(", ", undeclared) + "."));
                return false;
            }
            try {
                editor.deployment(values, new SettingsEditor.Actor(recorder.tenant, recorder.actor));
            } catch (IllegalArgumentException refused) {
                recorder.add(new Step(Kind.SETTING, what, Outcome.FAILED, "Not switched: " + refused.getMessage()));
                return false;
            }
            recorder.add(new Step(Kind.SETTING, what, Outcome.DONE, String.join("; ", values.entrySet().stream()
                    .map(entry -> entry.getKey() + " = " + entry.getValue()).toList())));
            return true;
        }

        @Override
        public int publish(String repository, String path, InputStream body) throws IOException {
            String what = "Publish " + path + " to " + repository;
            int status;
            try {
                status = edge.publish(recorder.tenant, repository, path, body);
            } catch (IOException | RuntimeException failed) {
                recorder.add(new Step(Kind.PUBLISH, what, Outcome.FAILED, "Not published: " + failed.getMessage()));
                return 500;
            }
            recorder.add(switch (status) {
                case 202 -> new Step(Kind.PUBLISH, what, Outcome.HELD, "Held for review: it waits in the "
                        + "repository's quarantine queue for a reviewer to release or refuse it.");
                case 422 -> new Step(Kind.PUBLISH, what, Outcome.REFUSED, "Refused by the compliance gate: nothing "
                        + "was laid out, and the refusal is listed with its findings.");
                case Edge.UNSERVED -> new Step(Kind.PUBLISH, what, Outcome.FAILED, "Not published: no repository "
                        + "runs in this console's process.");
                default -> status >= 200 && status < 300
                        ? new Step(Kind.PUBLISH, what, Outcome.DONE, "Published.")
                        : new Step(Kind.PUBLISH, what, Outcome.FAILED, "Not published: the repository answered "
                                + status + ".");
            });
            return status;
        }

        @Override
        public int fetch(String repository, String path) throws IOException {
            String what = "Fetch " + path + " through " + repository;
            int status;
            try {
                status = edge.fetch(recorder.tenant, repository, path);
            } catch (IOException | RuntimeException failed) {
                recorder.add(new Step(Kind.FETCH, what, Outcome.FAILED, "Not fetched: " + failed.getMessage()));
                return 500;
            }
            recorder.add(switch (status) {
                case 404 -> new Step(Kind.FETCH, what, Outcome.FAILED, "Not fetched: the registry did not answer "
                        + "with it from this server, or the gate withheld it; the proxy stays without it.");
                case Edge.UNSERVED -> new Step(Kind.FETCH, what, Outcome.FAILED, "Not fetched: no repository runs in "
                        + "this console's process.");
                default -> status >= 200 && status < 300
                        ? new Step(Kind.FETCH, what, Outcome.DONE, "Cached, recorded and screened, as a client's "
                                + "first read would have it.")
                        : new Step(Kind.FETCH, what, Outcome.FAILED, "Not fetched: the proxy answered " + status
                                + ".");
            });
            return status;
        }

        @Override
        public void request(String pass, String reason, Duration after) throws IOException {
            String what = "Ask for the " + pass + " pass";
            try {
                Requests.request(root, pass, reason, Instant.now().plus(after));
            } catch (IllegalArgumentException refused) {
                recorder.add(new Step(Kind.REQUEST, what, Outcome.FAILED, refused.getMessage()));
                return;
            }
            recorder.add(new Step(Kind.REQUEST, what, Outcome.DONE, "Asked for: " + reason
                    + ". It runs within a minute or two of now, on whichever node runs the background passes."));
        }

        @Override
        public void skipped(String what, String why) throws IOException {
            recorder.add(new Step(Kind.SKIP, what, Outcome.SKIPPED, why));
        }
    }

    /** One run's document, rewritten after every step. */
    private static final class Recorder {

        private final String tenant;
        private final String actor;
        private final ArtifactStore space;
        private final Lease lease;
        private final Instant started;
        private final List<Step> steps = new ArrayList<>();
        private int omitted;
        private Instant finished;

        private Recorder(String tenant, String actor, ArtifactStore space, Lease lease, Instant started) {
            this.tenant = tenant;
            this.actor = actor;
            this.space = space;
            this.lease = lease;
            this.started = started;
        }

        private void add(Step step) throws IOException {
            if (steps.size() < STEPS) {
                steps.add(step);
            } else {
                omitted++;
            }
            write();
        }

        private void addQuietly(Step step) {
            try {
                add(step);
            } catch (IOException | RuntimeException unwritten) {
                LOGGER.warn("The demo for tenant {} could not record its last step", tenant, unwritten);
            }
        }

        /** Mark the run done and free the lease, so the screen stops polling and a later empty tenant may run again. */
        private void done() {
            finished = Instant.now();
            try {
                write();
                lease.release(LOCK, Lease.processHolder(), Instant.now());
            } catch (IOException | RuntimeException unwritten) {
                LOGGER.warn("The demo for tenant {} could not record that it finished; it reads as stopped once its "
                        + "lease lapses", tenant, unwritten);
            }
        }

        private void write() throws IOException {
            ObjectNode document = JSON.createObjectNode();
            document.put("version", 1);
            document.put("status", finished == null ? "RUNNING" : "DONE");
            document.put("actor", actor);
            document.put("started", started.toString());
            if (finished != null) {
                document.put("finished", finished.toString());
            }
            document.put("omitted", omitted);
            ArrayNode list = document.putArray("steps");
            for (Step step : steps) {
                ObjectNode entry = list.addObject();
                entry.put("kind", step.kind().name());
                entry.put("what", step.what());
                entry.put("outcome", step.outcome().name());
                entry.put("detail", step.detail());
            }
            space.write(DOCUMENT, new ByteArrayInputStream(JSON.writeValueAsBytes(document)));
        }
    }

    private static Lease lease(ArtifactStore space) {
        return new Lease(space, LEASE);
    }

    private static Instant instant(String text) {
        if (text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException unreadable) {
            return null;
        }
    }
}
