package build.jenesis.repository.cli;

import module java.base;
import module java.net.http;
import module tools.jackson.databind;

/**
 * A thin client of a repository's HTTP API, holding the base URL and the key sent on each request as the {@code
 * Jenesis-Repository-Key} header. It drives the same surface the console and the API expose, so the CLI is equal to
 * them, and it is split the way that surface is: one family per subject - {@link #contents()}, {@link #review()},
 * {@link #risk()}, {@link #provenance()}, {@link #lifecycle()}, {@link #buildCache()}, {@link #access()},
 * {@link #operations()} and {@link #settings()} - each a class of its own, while what the client asks of any
 * deployment before choosing a family, its capabilities and its licence state, stays here. JSON is read and written
 * with Jackson; the reader ignores unknown fields, so a server that adds one does not break an older client.
 */
public final class RepositoryClient extends ClientCalls {

    private final ContentsClient contents;
    private final ReviewClient review;
    private final RiskClient risk;
    private final ProvenanceClient provenance;
    private final LifecycleClient lifecycle;
    private final BuildCacheClient buildCache;
    private final AccessClient access;
    private final OperationsClient operations;
    private final SettingsClient settings;

    /** A client addressing the repositories of the tenant {@code key} belongs to. */
    public RepositoryClient(URI base, String key, HttpClient client) {
        this(base, key, null, client);
    }

    /**
     * A client addressing the repositories of {@code tenant} - for a deployment whose URLs name a tenant other than
     * the key's, such as a single-tenant one serving its configured tenant to an operator key minted elsewhere.
     * {@code null} is the key's tenant.
     */
    public RepositoryClient(URI base, String key, String tenant, HttpClient client) {
        super(base, key, tenant, client);
        this.contents = new ContentsClient(this);
        this.review = new ReviewClient(this);
        this.risk = new RiskClient(this);
        this.provenance = new ProvenanceClient(this);
        this.lifecycle = new LifecycleClient(this);
        this.buildCache = new BuildCacheClient(this);
        this.access = new AccessClient(this);
        this.operations = new OperationsClient(this);
        this.settings = new SettingsClient(this);
    }

    /**
     * The tenant a bare repository name addresses: the one this client was given, else the one its key belongs to - a
     * key reads {@code jenk_<tenant>.<secret>} - else the tenant a deployment configuring none serves.
     */
    @Override
    public String tenant() {
        return super.tenant();
    }

    /** The repository's contents. */
    public ContentsClient contents() {
        return contents;
    }

    /** The queues that wait for a person's decision. */
    public ReviewClient review() {
        return review;
    }

    /** What the feeds, the ledgers and the policies say about what a repository holds. */
    public RiskClient risk() {
        return risk;
    }

    /** Where what a repository holds came from and what depends on it. */
    public ProvenanceClient provenance() {
        return provenance;
    }

    /** How long a repository keeps what it holds and where it sends it. */
    public LifecycleClient lifecycle() {
        return lifecycle;
    }

    /** The build cache's projects, and what the builds that use it report. */
    public BuildCacheClient buildCache() {
        return buildCache;
    }

    /** Who may do what. */
    public AccessClient access() {
        return access;
    }

    /** What the deployment is doing. */
    public OperationsClient operations() {
        return operations;
    }

    /** How the deployment is configured. */
    public SettingsClient settings() {
        return settings;
    }

    /** What the server's deployment carries - installed formats, import sources, report columns and feature
     *  flags - or {@code null} when an older server does not answer the endpoint. */
    public Capabilities capabilities() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/capabilities", null, null);
        if (response.statusCode() == 404) {
            return null;
        }
        require(response, 200, "read capabilities");
        return JSON.readValue(response.body(), Capabilities.class);
    }

    /**
     * The deployment's licence state, read from the {@code Jenesis-License} header any {@code /api/**} answer
     * carries - empty when the deployment sends none.
     *
     * <p>Empty is the ordinary case and never a warning: a deployment that sends no such header simply
     * does not send it, and inventing "unlicensed" out of an absence would be wrong in exactly the direction that
     * annoys people. This is the whole of the CLI's licence knowledge; there is no second endpoint to ask.
     */
    public Optional<String> licenseState() throws IOException, InterruptedException {
        return send("GET", "/api/capabilities", null, null).headers().firstValue("Jenesis-License");
    }

    /**
     * The server has no route for the call: the module that would serve it is not part of this deployment.
     *
     * <p>Told apart from every other {@code 404} by the server itself - a node answers a request no route matched with
     * {@code Jenesis-Installed: false} - because the same status for "you asked for something absent" and "this
     * deployment does not offer that at all" leaves a caller retrying what can never work. The dispatcher turns it
     * into its own exit code.
     */
    public static final class NotInstalled extends IOException {

        private static final long serialVersionUID = 1L;

        NotInstalled(String action) {
            super("The server has no route to " + action + ".");
        }
    }

    /** The served {@code /api/capabilities} document. The optional modules' feature flags sit at the <b>top level</b>
     *  ({@code scan}, {@code provenance}, {@code audit}, {@code dependents}, {@code search}, {@code walk},
     *  {@code gc}), because each is contributed by the module that owns it rather than lifted into a fixed view by
     *  the server; {@link Features} carries only the postures the server itself resolves. A flag a deployment does
     *  not carry is simply absent and reads as {@code false} - the SPI's no-op-by-absence contract. */
    public record Capabilities(int version, List<Format> formats, List<ImportSource> importSources,
                               List<RiskClient.Signal> signals, List<Module> modules, Features features,
                               boolean scan, boolean provenance, boolean audit, boolean dependents,
                               boolean search, boolean walk, boolean gc, List<CacheProtocol> cacheProtocols) {
    }

    /** A build tool the deployment's cache serves, and the endpoint its client is pointed at - {@code <tenant>} and
     *  {@code <project>} left for the reader to fill in. Contributed by the cache, so absent where it is not served. */
    public record CacheProtocol(String name, String endpoint) {
    }

    public record Format(String name, String ecosystem) {
    }

    public record ImportSource(String name, String label, boolean requiresFormat) {
    }

    /** One discovered module's state (an entry of the module list): its JPMS module name, whether it is {@code installed} on
     *  this deployment's module path, the key of its enablement gate ({@code null} for an always-on module), whether
     *  that gate resolves to {@code enabled}, and whether toggling it applies {@code live} or only on the next
     *  restart. A module named only by a leftover stored settings document reports {@code installed == false}. */
    public record Module(String module, boolean installed, String enableKey, boolean enabled, boolean live) {
    }

    /** The deployment postures the server resolves from its own beans. The module-contributed flags are not here -
     *  they are top-level entries of {@link Capabilities}. */
    public record Features(boolean advisories, boolean advisoriesEnabled, boolean staging, boolean retention,
                           boolean provenanceEnabled, boolean upstream, boolean tokenExchange, boolean rateLimit) {
    }
}
