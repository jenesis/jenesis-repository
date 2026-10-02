package build.jenesis.repository.demo;

import module java.base;

/**
 * Sample content for the demo the first-run guide offers while a tenant holds no repository: a few repositories, the
 * settings their screens need switched on, and what is published into them or pulled through them, so that someone
 * trying the product sees every screen populated. The console's demo module contributes the core's own content -
 * hosted and proxy repositories of the formats installed, first-party packages, one held for review, and versions with
 * known vulnerabilities - and another module adds its own beside it (build-cache projects, a licence policy) as a bean
 * of its console configuration.
 *
 * <p>A contributor speaks twice. {@link #plan()} says what it will do before anything is done, which the offer turns
 * into its warning and the operator confirms by typing a phrase; {@link #load} then does it, through the
 * {@link Demo} the run hands it. Every repository a plan names is created by the run before any contributor loads, so
 * a contributor may publish into another's.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> {@link #plan()} is called on request threads, concurrently; {@link #load} on the run's own
 *     thread, once per run, never concurrently with another contributor's. A contributor holds no per-run state.</li>
 * <li><b>Idempotency / replay.</b> A run starts only while the tenant holds no repository, so {@link #load} meets an
 *     empty tenant; it is not asked to converge over a previous run's content, and a run stopped part way is not
 *     resumed - the tenant then holds repositories and no new run starts.</li>
 * <li><b>Absence sentinel.</b> A contributor with nothing to add for the formats installed returns {@link Plan#NONE};
 *     {@code null} is never legal, from {@link #plan()} or inside a plan.</li>
 * <li><b>Selection failure.</b> An {@code ALL} seam: every contributing bean is asked, in {@link #order()} and then
 *     by class name. None being present leaves the demo with nothing to load, and the offer is not made.</li>
 * <li><b>Tenant scoping.</b> {@link Demo#tenant()} is the tenant the operator offered the demo to; every repository is
 *     created in it, every publish and fetch addresses it. Settings are the deployment's, which only a super-admin -
 *     who alone is offered the demo - may switch.</li>
 * <li><b>Error visibility.</b> Every operation through {@link Demo} records its own outcome, so a refused setting, a
 *     held or refused publish and an unreachable registry are each reported rather than thrown. An exception out of
 *     {@link #load} ends that contributor's load and is recorded against it; the run goes on with the next
 *     contributor, and is never left running.</li>
 * <li><b>Read purity.</b> {@link #plan()} performs no I/O: it is a constant function of the modules installed, asked
 *     on every render of the guide's first page. {@link #load} reaches the network only through
 *     {@link Demo#fetch}, and only to registries its plan names in {@link Plan#reaches()}.</li>
 * <li><b>Declared before done.</b> {@link Demo#settings} refuses a key the plan's {@link Plan#settings()} does not
 *     name, so the warning the operator confirmed lists every setting a run changes; the run creates only the
 *     repositories the plan names, and a load reaches only the registries it names.</li>
 * <li><b>Lifecycle / ownership.</b> A contributor is a Spring bean of its module's console configuration and owns what
 *     that configuration gives it; the run owns the {@link Demo} it hands over, which is valid only during
 *     {@link #load}.</li>
 * <li><b>Ordering / determinism.</b> Plans are listed, repositories created and loads run in {@link #order()}, then by
 *     class name; within a load, operations run in the order the contributor makes them, which is how it puts a deny
 *     list in force before the publish it should hold.</li>
 * <li><b>Bounded work / cancellation.</b> A demo is a handful of repositories and a few dozen small artifacts: a load
 *     publishes what it generates in memory - kilobytes, never a bundled payload - and fetches a fixed short list.
 *     A run holds a lease for an hour; a run still going past it is reported as stopped.</li>
 * </ol>
 */
public interface DemoContributor {

    /** Where this contributor's content loads among the others: lower first. The core's own content takes 10. */
    default int order() {
        return 100;
    }

    /** What {@link #load} will do, said before it is done. */
    Plan plan();

    /** Load the content {@link #plan()} described through {@code demo}. */
    void load(Demo demo) throws IOException;

    /**
     * What a contributor will do: the repositories it creates, each deployment setting it switches and the value it
     * brings, the public registries a load reaches, and the code with known vulnerabilities it loads - each line read
     * out to the operator before the demo is confirmed.
     *
     * @param repositories the repositories the run creates for this contributor before it loads.
     * @param settings     each deployment setting a load may switch, by key, with what it brings - the value it is set
     *                     to, or what a list setting gains.
     * @param reaches      the public registries and services a load reaches, as an operator recognises them.
     * @param vulnerable   the artifacts with known vulnerabilities a load brings in, one line each.
     */
    record Plan(List<Repository> repositories, SequencedMap<String, String> settings, List<String> reaches,
                List<String> vulnerable) {

        /** Nothing to add. */
        public static final Plan NONE = new Plan(List.of(), new LinkedHashMap<>(), List.of(), List.of());

        public Plan {
            repositories = List.copyOf(repositories);
            settings = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(settings));
            reaches = List.copyOf(reaches);
            vulnerable = List.copyOf(vulnerable);
        }

        /** Whether the plan does anything at all. */
        public boolean empty() {
            return repositories.isEmpty() && settings.isEmpty();
        }
    }

    /**
     * A repository the run creates: its name, the type it holds, the description it is shown with, and its routing -
     * empty for a hosted repository that accepts uploads, {@code fallback <url>} for a proxy of a registry.
     */
    record Repository(String name, String type, String description, String routing) {

        public Repository {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            description = description == null ? "" : description;
            routing = routing == null ? "" : routing;
        }

        /** Whether the repository accepts uploads rather than fetching from a registry. */
        public boolean hosted() {
            return routing.isBlank();
        }
    }
}
