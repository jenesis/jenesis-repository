package build.jenesis.repository.server;
import module java.base;
import module org.slf4j;

import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.spi.CapabilityContributor;
import build.jenesis.repository.server.spi.ImportEdgeProvider;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryType;
import build.jenesis.repository.importer.ImportSourceProvider;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.RepositoryDocument;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.store.QuotaExceededException;
import build.jenesis.repository.store.ReadOnlyException;
import tools.jackson.databind.json.JsonMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import build.jenesis.repository.failure.Failures;
import org.springframework.web.ErrorResponse;
import org.springframework.web.util.DisconnectedClientHelper;

/**
 * The HTTP surface of the repository, mirroring {@link RepositoryApplication}'s framework-neutral
 * dispatch but over Spring MVC. A catch-all resolves the request to its repository through {@link RepositoryRouting}
 * (fixed-tenant by default), reads what that repository holds ({@link HeldFormat}) - one format, or a combined type of
 * several - and offers the request to those formats alone over the repository's doubly-scoped store through the
 * shared {@link FormatDispatcher}, with the type's mount restored in front of the path (a single format's
 * {@link RepositoryFormat#mount mount}, none for a combined type, whose URLs keep each format's segment); a
 * repository that holds no format, or one this deployment does not install, does not answer, and a path its formats
 * do not claim is a {@code 404}. When an upstream is configured for the
 * matched format and the format is a {@link ProxyFormat}, a local miss is served through the {@link PullThroughCache}
 * from that upstream and cached, so a later read is a local hit. The single-tenant import edge
 * ({@code POST /api/repository/import} and {@code GET /api/repository/import/<id>}) is served by the separate
 * {@link ImportEdgeController} bean - a bean of its own so a richer distribution can OWN the import edge through the
 * {@link ImportEdgeProvider} SPI without a cross-layer mapping override. Authorization is not done here:
 * {@link RepositorySecurityAutoConfiguration} gates the wire through the {@link Authorization} credential model.
 */
@RestController
public class RepositoryController {

    private static final Logger LOGGER = LoggerFactory.getLogger(RepositoryController.class);

    /** The capability contributors: those discovered on the module path, once - this endpoint is read on every CLI
     *  {@code 404}, and which are installed cannot differ between two requests of one JVM - and those the deployment
     *  contributes as its own, which read its own beans. What each one <em>answers</em> is still resolved per request,
     *  through the caller's own effective-value chain. */
    private final List<CapabilityContributor> contributors;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A routable repository name, the traversal-free segment shape every routing validates, so a
     *  {@code repo=} query parameter can never escape its store scope (no {@code /}, {@code \} or {@code ..}). */
    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9_-]+");

    private static final int DEFAULT_PAGE = 500;
    private static final int MAX_PAGE = 1000;

    private final RepositoryRouting routing;
    private final FormatDispatcher dispatcher;
    private final ScreenedDispatch screened;
    private final List<ImportSourceProvider> importSources;
    private final ProxyFormat.Fetcher fetcher;
    private final BatchIngestion batch;
    private final RepositorySettings settings;
    private final ArtifactStore root;
    private final RoutedServing routed;
    private final EdgeHooks hooks;
    private final AuditTrail audit;
    private final Reads reads;

    /** The screened edge restricted to one repository type's formats, per type: a repository's request is offered to
     *  the formats it holds and to no other, so a path another format would claim is not served out of it. */
    private final Map<String, ScreenedDispatch> restricted = new ConcurrentHashMap<>();

    /** The capability-merge report last written to the log, so a collision is logged when it appears or changes rather
     *  than on every hit of a polled endpoint - which contributions collide is a property of the installed module set,
     *  so an unchanged deployment would otherwise repeat one line forever and bury it. The held value is an immutable
     *  list published through {@code volatile}, the same once-set-holder idiom the core's other lock-free
     *  memoized seams use; a benign race logs the same report twice and never loses one. */
    private volatile List<String> reportedCapabilityProblems = List.of();

    /**
     * The single-tenant entry point: routing, dispatch, import sources and a pull-through fetcher, with every
     * optional concern left off - no batch ingestion, no deployment settings, no un-scoped root, no routed serving
     * and no edition hooks. It is the shape a deployment that configures none of those gets.
     */
    public RepositoryController(RepositoryRouting routing,
                                FormatDispatcher dispatcher,
                                List<ImportSourceProvider> importSources,
                                ProxyFormat.Fetcher fetcher) {
        this(routing, dispatcher, importSources, fetcher, null, RepositorySettings.NONE, null, RoutedServing.NONE,
                EdgeHooks.NONE,
                AuditTrail.NONE, Reads.NONE);
    }

    /**
     * The effective value of a setting for the repository a request addresses - its own value where the key is a
     * repository setting and it set one, else its tenant's, else the deployment's - or the deployment's alone when
     * {@code tenant} and {@code repository} are {@code null}; {@code null} when nothing sets it. It is what a format
     * reads off {@link build.jenesis.repository.format.FormatExchange#setting(String)} and what the edge's own flags
     * read.
     */
    @FunctionalInterface
    public interface RepositorySettings {

        /** Nothing is set: every format and every flag on its shipped default. */
        RepositorySettings NONE = (_, _, _) -> null;

        String value(String tenant, String repository, String key);

        /** The deployment's value of {@code key}. */
        default String deployment(String key) {
            return value(null, null, key);
        }
    }

    /**
     * Whether the caller of a request may read another path - asked when a format reads there on the caller's behalf
     * ({@link build.jenesis.repository.format.FormatExchange#readable}), and decided as the deployment's
     * authorization decides a {@code GET} of that path.
     */
    @FunctionalInterface
    public interface Reads {

        /** Nothing is readable on a caller's behalf. */
        Reads NONE = (_, _) -> false;

        boolean permits(HttpServletRequest request, String path);
    }

    /**
     * Every concern a deployment can wire in, each with a documented "off" value. A deployment builds this full form
     * and a test builds the bare one; no intermediate constructor adds one argument at a time, and the account of
     * what each argument turns on is here.
     *
     * @param batch     explodes a {@code PUT}/{@code POST} carrying the explode header into per-entry publishes
     *                  through the same dispatcher when it claims the archive; {@code null} leaves the header an
     *                  inert plain upload.
     * @param settings  resolves each request's {@link build.jenesis.repository.format.FormatExchange#setting(String)}
     *                  - a bare setting key to its effective value for the repository the request addresses,
     *                  {@code null} when unset - so a format can read a toggle off the exchange;
     *                  {@link RepositorySettings#NONE} keeps every format on its shipped default.
     * @param root      the un-scoped store, so the {@code /api/assets} enumeration can scope to an explicitly named
     *                  {@code repo} within the request's tenant; {@code null} leaves it on the request's own routed
     *                  space.
     * @param routed    consulted on a read so a repository defined as a read-through proxy or a group view serves
     *                  across its backings; {@link RoutedServing#NONE} leaves every repository on its own store.
     * @param hooks     the ingress concerns - tenant binding, release-immutability, quarantine dispatch, deploy
     *                  observation - threaded into the one shared screening edge; {@link EdgeHooks#NONE} is the
     *                  no-op.
     * @param audit     where a change a format makes through its own protocol - a client's yank or deprecate - is
     *                  recorded, as the request's caller in the request's tenant; {@link AuditTrail#NONE} records
     *                  nothing.
     * @param reads     whether the caller may read another repository's path, for a format that reads there on its
     *                  behalf - a registry's cross-repository blob mount; {@link Reads#NONE} permits nothing, so such
     *                  a read always falls back.
     */
    public RepositoryController(RepositoryRouting routing,
                                FormatDispatcher dispatcher,
                                List<ImportSourceProvider> importSources,
                                ProxyFormat.Fetcher fetcher,
                                BatchIngestion batch,
                                RepositorySettings settings,
                                ArtifactStore root,
                                RoutedServing routed,
                                EdgeHooks hooks,
                                AuditTrail audit,
                                Reads reads) {
        this(routing, dispatcher, importSources, fetcher, batch, settings, root, routed, hooks, audit, reads, List.of());
    }

    /**
     * As the constructor above, with the capability contributions the deployment makes itself beside the ones the
     * module path provides - a contribution that reads the deployment's own beans, which a discovered contributor,
     * constructed with no deployment, cannot.
     *
     * @param contributed the deployment's own contributions, merged after the discovered ones
     */
    public RepositoryController(RepositoryRouting routing,
                                FormatDispatcher dispatcher,
                                List<ImportSourceProvider> importSources,
                                ProxyFormat.Fetcher fetcher,
                                BatchIngestion batch,
                                RepositorySettings settings,
                                ArtifactStore root,
                                RoutedServing routed,
                                EdgeHooks hooks,
                                AuditTrail audit,
                                Reads reads,
                                List<CapabilityContributor> contributed) {
        List<CapabilityContributor> all = new ArrayList<>(CapabilityContributor.installed());
        all.addAll(contributed);
        this.contributors = List.copyOf(all);
        this.routing = routing;
        this.dispatcher = dispatcher;
        this.screened = new ScreenedDispatch(dispatcher, hooks);
        this.importSources = importSources;
        this.fetcher = fetcher;
        this.batch = batch;
        this.settings = settings;
        this.root = root;
        this.routed = routed;
        this.hooks = hooks;
        this.audit = audit;
        this.reads = reads;
    }

    /**
     * The format catch-all: an artifact request under {@code /repository/**} (its prefix stripped by
     * {@link RepositoryRouting} before dispatch) or the OCI {@code /v2/**} registry the Docker protocol pins at the
     * host root, resolved to its artifact space and offered to the {@link RepositoryFormat} plugins over that store by
     * the {@link FormatDispatcher}. A repository's operations and staged uploads answer outside its URL space
     * ({@code /api/repository/...}, {@code /staging/...}), so this sees nothing but a format's own paths; an unclaimed
     * one is a {@code 404}. A format with a configured
     * upstream that is a {@link ProxyFormat} serves a local miss through the {@link PullThroughCache}. A write
     * carrying the batch explode header is walked entry by entry by {@link BatchIngestion} - each member screened at
     * the same {@link ScreenedDispatch} ingress edge a single deploy uses - when the feature is enabled; otherwise the
     * header is inert and the body is a plain single upload.
     */
    @RequestMapping(value = {"/repository/**", "/v2", "/v2/**"}, method = {RequestMethod.GET, RequestMethod.HEAD,
            RequestMethod.PUT, RequestMethod.POST, RequestMethod.PATCH, RequestMethod.DELETE})
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // The registry's own catalog names no tenant and no repository: it lists every image of the tenant the request
        // answers for, across that tenant's container-image repositories.
        if (root != null && isRead(request.getMethod()) && RegistryCatalog.addresses(request.getRequestURI())) {
            new RegistryCatalog(root, dispatcher).answer(routing.tenant(request), request, response);
            return;
        }
        RepositoryRouting.Route route = routing.route(request);
        boolean write = isWrite(request.getMethod());
        // A repository answers only for the one format it holds. One that holds none - never created, or created
        // before repositories held a format - does not answer at all: a write would otherwise lay out a format the
        // repository was never meant to hold, and a read through a pull-through upstream would fill it the same way.
        Optional<HeldFormat> held = route.repository().isEmpty()
                ? probe(request).map(registry -> new HeldFormat(registry, registry.formatPath(route.path())))
                : HeldFormat.of(routing, route, dispatcher.formats());
        if (held.isEmpty()) {
            response.setStatus(404);
            Optional<String> unserved = route.repository().isEmpty() ? Optional.empty() : unserved(route);
            if (unserved.isPresent() || write && !route.repository().isEmpty()) {
                response.setContentType("text/plain;charset=UTF-8");
                response.getWriter().write(unserved.orElseGet(() -> absent(route.repository())));
            }
            return;
        }
        RepositoryType type = held.get().type();
        String key = PresentedKey.fromAnyClient(request);
        request.setAttribute(FAILING_FORMAT, held.get());
        ServletFormatExchange exchange = new ServletFormatExchange(request, response, held.get().path(),
                setting -> settings.value(route.tenant(), route.repository(), setting),
                type.mount(), (action, target) -> audit.record(route.tenant(),
                        key == null ? "anonymous" : Authorization.hash(key), action, route.repository() + "/" + target),
                path -> readable(request, route, type, path));
        // A write (PUT/POST/PATCH/DELETE) to a route that is not a valid write target is a 405 before any layout - the
        // seam a routing uses to reject a write to a read-only repository - answered in the claiming format's own
        // error dialect.
        if (write && !route.writable()) {
            Optional<RepositoryFormat> claiming = held.get().claiming();
            if (claiming.isPresent()) {
                claiming.get().refuse(exchange, 405);
            } else {
                response.setStatus(405);
            }
            return;
        }
        // A write the repository's type does not take from the format that claims it - a Jenesis PUT into a java
        // repository, whose module view Maven publishes - is a 405 as well.
        if (write && held.get().claiming().filter(format -> !type.publishes(format)).isPresent()) {
            response.setStatus(405);
            return;
        }
        ScreenedDispatch screened = screened(type);
        if (batch != null && batch.claims(exchange)) {
            // Each exploded entry is screened at the same ingress edge a single deploy uses (the shared
            // ScreenedDispatch, carrying this controller's EdgeHooks), so a batch upload is screened exactly like a
            // series of individual deploys - one screening implementation, EdgeHooks and all.
            batch.explode(exchange, route.tenant(), route.store(), screened);
            return;
        }
        // A read of a routed repository (a proxy of an upstream, or a group view over members) is served across its
        // backings through the routing seam - a proxy pulls through its own upstream on a local miss, a group tries
        // its members in order - so every format gets per-repository routing behind this one controller. A plain
        // hosted repository (the common case, and the only case the core binds) declines the seam and
        // dispatches over its own store, keeping the deployment-wide format-level pull-through. Writes are never
        // routed here: a routed group deploy lands in its push-target member on the write path.
        if (isRead(request.getMethod()) && routed.routes(route.tenant(), route.repository())) {
            Optional<RepositoryFormat> claiming = held.get().claiming();
            if (claiming.isPresent()) {
                routed.serve(route.tenant(), route.repository(), claiming.get(), exchange);
            } else {
                response.setStatus(404);
            }
            return;
        }
        // A folder of a format whose paths are a folder tree answers a listing of the repository's own store, where the
        // repository turned listings on. A routed repository never reaches here, so a proxy or a group never lists
        // what it would then have to fetch.
        if (held.get().claiming().filter(RepositoryFormat::browsable).isPresent() && FolderListing.asked(exchange)) {
            FolderListing.answer(exchange, route.store());
            return;
        }
        // A claimed single-body write (PUT/POST/PATCH on a screened() format) is screened at this ingress edge before
        // the format lays it out: the body is stored and the discovered interceptor chain runs once, then an accepted
        // blob is restreamed into the format for pure layout (QUARANTINE -> 202, REJECT -> 422). An unscreened format
        // (OCI) and every read/delete dispatch through the normal loop untouched. With the core's empty chain
        // this is byte-for-byte a direct dispatch; it carries the full ComplianceScreen chain under fixed tenancy.
        if (!screened.dispatch(route.tenant(), exchange, route.store())) {
            response.setStatus(404);
        }
    }

    /**
     * The store of another repository of the request's tenant, for a format reading {@code path} there on the caller's
     * behalf: present only when the caller may read the path - decided first, and as a {@code GET} of it would be -
     * and it names a repository of this tenant that holds the same format. One empty answer for every refusal, so
     * the question discloses nothing.
     */
    private Optional<ArtifactStore> readable(HttpServletRequest request, RepositoryRouting.Route route,
                                             RepositoryType type, String path) {
        RepositoryRouting.Target named = RepositoryRouting.target(path);
        if (route.repository().isEmpty() || named.repository().isEmpty() || !named.tenant().equals(route.tenant())
                || !reads.permits(request, path)) {
            return Optional.empty();
        }
        try {
            Optional<RepositoryRouting.Route> other = routing.route(named.tenant(), named.repository(), named.path());
            if (other.isEmpty()) {
                return Optional.empty();
            }
            Optional<HeldFormat> held = HeldFormat.of(routing, other.get(), dispatcher.formats());
            return held.isPresent() && held.get().type().name().equals(type.name())
                    ? Optional.of(other.get().store()) : Optional.empty();
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /**
     * Publish one artifact in process, through this controller's own ingress edge.
     *
     * <p>It exists so that a surface with no request behind it - the admin console's deploy screen - publishes the
     * way a client does rather than around it. Everything a {@code PUT} to {@code /repository/**} passes through is
     * passed through here: the routing decides the store and whether the target accepts a write, the repository's
     * format is the only one offered the body, the discovered interceptor chain screens it exactly once, an accepted
     * blob is restreamed into the format for layout, and a held or refused body answers as it would on the wire. The
     * alternative - a screen that scopes a store and writes blobs - is a hole in the gate rather than a feature,
     * which is why there is no way to do that from here.
     *
     * <p>The routing is asked for a route it can resolve without a request, and <b>a routing that cannot say refuses
     * the publish</b>: the answer is a {@code 404} rather than a guess, because a guessed route is one whose
     * writability nobody checked.
     *
     * @param tenant     the tenant to publish into.
     * @param repository the repository within it.
     * @param path       the path within the repository the artifact lands at, as a client names it after
     *                   {@code /repository/<repository>}.
     * @param body       the artifact's bytes; streamed, never buffered, and not closed here.
     * @return the status the edge answered: {@code 2xx} laid out, {@code 202} held for review, {@code 422} refused
     *         by the gate, {@code 404} claimed by no format or no route, {@code 405} a target that takes no write.
     */
    public int publish(String tenant, String repository, String path, InputStream body) throws IOException {
        Optional<RepositoryRouting.Route> resolved = routing.route(tenant, repository, path);
        if (resolved.isEmpty()) {
            return 404;
        }
        RepositoryRouting.Route route = resolved.get();
        if (!route.writable()) {
            return 405;
        }
        Optional<HeldFormat> held = HeldFormat.of(routing, route, dispatcher.formats());
        if (held.isEmpty()) {
            return 404;
        }
        if (held.get().claiming().filter(format -> !held.get().type().publishes(format)).isPresent()) {
            return 405;
        }
        CapturingExchange exchange = new CapturingExchange(route.tenant(), route.repository(), held.get().path(),
                body);
        if (!screened(held.get().type()).dispatch(route.tenant(), exchange, route.store())) {
            return 404;
        }
        return exchange.status();
    }

    /**
     * Read one path in process, through the serving path a {@code GET} to {@code /repository/**} takes, and discard
     * the body.
     *
     * <p>It is {@link #publish}'s twin for a surface with no request behind it that needs what a read leaves behind
     * rather than its bytes - the console's demo pulling known versions through a proxy, so they are cached,
     * inventoried and screened as a client's pull would have them. The routing resolves the repository, a routed
     * repository - a proxy or a group - is served across its backings, so a proxy pulls from its upstream on a local
     * miss and screens what it fetched, and a hosted one dispatches over its own store; a withheld path stays a
     * {@code 404}. No request carries settings, headers or a caller here, so a format reads every repository setting
     * at its default and the read is made as nobody.
     *
     * @param tenant     the tenant to read from.
     * @param repository the repository within it.
     * @param path       the path within the repository, as a client names it after {@code /repository/<repository>}.
     * @return the status the read answered: {@code 2xx} served, {@code 404} absent, withheld or claimed by no format
     *         or no route, or whatever a proxy answers for an upstream that failed.
     */
    public int fetch(String tenant, String repository, String path) throws IOException {
        Optional<RepositoryRouting.Route> resolved = routing.route(tenant, repository, path);
        if (resolved.isEmpty()) {
            return 404;
        }
        RepositoryRouting.Route route = resolved.get();
        Optional<HeldFormat> held = HeldFormat.of(routing, route, dispatcher.formats());
        if (held.isEmpty()) {
            return 404;
        }
        CapturingExchange exchange = CapturingExchange.read(route.tenant(), route.repository(), held.get().path());
        if (routed.routes(route.tenant(), route.repository())) {
            Optional<RepositoryFormat> claiming = held.get().claiming();
            if (claiming.isEmpty()) {
                return 404;
            }
            routed.serve(route.tenant(), route.repository(), claiming.get(), exchange);
            return exchange.status();
        }
        if (!screened(held.get().type()).dispatch(route.tenant(), exchange, route.store())) {
            return 404;
        }
        return exchange.status();
    }

    /**
     * The OCI registry's version probe, which names no tenant and no repository: the registry answers it if one is
     * installed. A URL naming a tenant and no repository is not the probe, and answers nothing.
     */
    private Optional<RepositoryType> probe(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.equals("/v2") || uri.equals("/v2/")
                ? RepositoryType.of("oci", dispatcher.formats())
                : Optional.empty();
    }

    private ScreenedDispatch screened(RepositoryType type) {
        return restricted.computeIfAbsent(type.name(),
                _ -> new ScreenedDispatch(dispatcher.only(type.formats()), hooks));
    }

    /** The repositories this node has already said, in its log, cannot serve - each is named once, not per request. */
    private static final Set<String> UNSERVED = ConcurrentHashMap.newKeySet();

    /**
     * Why a repository that exists answers nothing, when it does: its type names a format no installed module serves,
     * or it holds content but no type at all - created before repositories held a format. Both answer {@code 404}, as a
     * repository that does not exist does, and both say what to do; nothing converts one, since the format it should
     * hold is the operator's to name. Empty for a repository that does not exist, which keeps saying nothing on a read.
     */
    private Optional<String> unserved(RepositoryRouting.Route route) throws IOException {
        Optional<RepositoryDocument> document = routing.document(route);
        String reason;
        if (document.isPresent()) {
            reason = "Repository '" + route.repository() + "' holds the format '" + document.get().format()
                    + "', which no module installed in this deployment serves. Install the module that serves it.";
        } else if (!route.store().isEmpty("")) {
            reason = "Repository '" + route.repository() + "' holds content but no format: it was made before "
                    + "repositories held one. Give it the format it holds - PUT /repository/" + route.tenant() + "/"
                    + route.repository() + " with the type, or create it in the console under Repositories - and its "
                    + "content is served as that format.";
        } else {
            return Optional.empty();
        }
        if (UNSERVED.add(route.tenant() + "/" + route.repository())) {
            LOGGER.warn(reason);
        }
        return Optional.of(reason);
    }

    /** What a publish into a repository that does not exist or holds no served format is told. */
    static String absent(String repository) {
        return "Repository '" + repository + "' does not exist or holds no format this deployment serves. Create it "
                + "in the console under Repositories, choosing the format it holds.";
    }

    private static boolean isRead(String method) {
        return "GET".equals(method) || "HEAD".equals(method);
    }

    /** A mutating verb - the write that a non-writable route refuses with a {@code 405}. */
    private static boolean isWrite(String method) {
        return "PUT".equals(method) || "POST".equals(method) || "PATCH".equals(method) || "DELETE".equals(method);
    }

    /**
     * The paged asset enumeration - the outbound mirror of the
     * import connectors, so a jenesis instance can be walked by another tool (or another jenesis) and getting your
     * data out is never an afterthought. {@code GET /api/assets?repo=<name>&cursor=<token>&limit=<n>} returns a
     * flat, stably-ordered slice of the repository's published assets: each entry's {@code path}, {@code size} and
     * {@code sha256} come straight from the {@link build.jenesis.repository.store.Publication publication pointer},
     * or for a format keeping its own key space from the pointer its layout serves the path from ({@link AssetCatalog})
     * (no blob is ever opened - read-first) and its {@code format}/{@code ecosystem}/{@code coordinate}/
     * {@code version} from the owning format's layout, and {@code served} is the URL path it is served at -
     * {@code /repository/<tenant>/<repository>} and the path within the repository. The opaque {@code cursor} in the
     * response fetches the next page and is {@code null} once the walk is exhausted. {@code repo} names a repository
     * of the tenant the request answers for ({@link RepositoryRouting#tenant}) and is validated as a traversal-free
     * segment before it scopes the store; the wire is key-auth'd like every
     * other read ({@code repository:read}) by {@link RepositorySecurityAutoConfiguration}, which authorizes the
     * <em>effective</em> {@code repo} the store is scoped to (not merely the routed name) so this enumeration cannot
     * read a repository the key is not scoped for.
     */
    @GetMapping("/api/assets")
    public void assets(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String tenant = routing.tenant(request);
        String repository = request.getParameter("repo");
        if (repository == null || !REPOSITORY.matcher(repository).matches() || !Scopes.valid(tenant)) {
            respond(response, 400, "repo must be a routable name matching " + REPOSITORY.pattern());
            return;
        }
        String after;
        String cursor = request.getParameter("cursor");
        if (cursor == null || cursor.isBlank()) {
            after = null;
        } else {
            try {
                after = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException _) {
                respond(response, 400, "malformed cursor");
                return;
            }
        }
        Optional<ArtifactStore> scoped = root == null
                ? routing.route(tenant, repository, "/").map(RepositoryRouting.Route::store)
                : Optional.of(root.scope(tenant).scope(repository));
        if (scoped.isEmpty()) {
            respond(response, 404, "no such repository");
            return;
        }
        ArtifactStore store = scoped.get();
        // Where each asset is served: the repository's URL and the path within it - the asset's path with the
        // repository type's mount taken off - so a client, another instance migrating out of this one say, fetches
        // it without knowing how this deployment addresses tenants or formats.
        String url = "/repository/" + tenant + "/" + repository;
        String mount = RepositoryDocument.read(store)
                .flatMap(document -> RepositoryType.of(document.format(), dispatcher.formats()))
                .map(RepositoryType::mount).orElse("");
        AssetCatalog.Page page;
        try {
            page = new AssetCatalog(store, dispatcher::owner, dispatcher.formats())
                    .page(after, pageSize(request.getParameter("limit")));
        } catch (IllegalArgumentException _) {
            respond(response, 400, "malformed cursor");
            return;
        }
        List<Map<String, Object>> assets = new ArrayList<>();
        for (AssetCatalog.Asset asset : page.assets()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", asset.path());
            entry.put("served", url + (asset.path().startsWith(mount)
                    ? asset.path().substring(mount.length()) : asset.path()));
            entry.put("size", asset.size());
            entry.put("sha256", asset.sha256());
            entry.put("format", asset.format());
            entry.put("ecosystem", asset.ecosystem());
            entry.put("coordinate", asset.coordinate());
            entry.put("version", asset.version());
            entry.put("prerelease", asset.prerelease());
            assets.add(entry);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("repository", repository);
        body.put("assets", assets);
        body.put("cursor", page.cursor() == null ? null
                : Base64.getUrlEncoder().withoutPadding().encodeToString(page.cursor().getBytes(StandardCharsets.UTF_8)));
        response.setHeader("Content-Type", "application/json");
        respond(response, 200, JSON.writeValueAsString(body));
    }

    private static int pageSize(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_PAGE;
        }
        try {
            return Math.max(1, Math.min(MAX_PAGE, Integer.parseInt(value.trim())));
        } catch (NumberFormatException _) {
            return DEFAULT_PAGE;
        }
    }

    /**
     * Advertises the deployment-wide capabilities a client or console reads to adapt its behaviour - the
     * read-only flag (so a console shows a banner and hides write affordances, and a mirror client knows writes are
     * refused) and whether the wire is credential-gated. Read like every other {@code /api} surface; a distribution
     * with more capabilities extends the map without a client change - through the {@link CapabilityContributor} SPI
     * (below), not a bean override.
     *
     * <p>The base map ({@code readOnly}, {@code auth}, {@code anonymousRights}) is built here, then every
     * {@code ServiceLoader}-discovered {@link CapabilityContributor} is {@linkplain CapabilityContributor#merge merged}
     * into it: a richer distribution contributes its formats / import-sources / module-flags
     * onto this one free-served endpoint by shipping a contributor module - the server already {@code uses} the SPI, so
     * no core change is needed. With no contributor installed (the product) the served map is exactly the base map,
     * byte-for-byte unchanged. On a key conflict the base key wins (see {@link CapabilityContributor}'s merge rule), so
     * a contributor can only extend the product's own flags, never shadow them.
     *
     * <p>What the rule <em>refuses</em> is served too. A contributed key the base already owns, or a contributor that
     * threw building its view, is named in the body under {@code capabilityConflicts} / {@code capabilityFailures} and
     * logged once here - never dropped in silence, which would leave an operator debugging a console that renders the
     * wrong thing with nothing anywhere to explain it. The endpoint still answers: a plugin's mistake costs that
     * plugin's entry, never the product's own capability advertisement. A healthy deployment reports
     * nothing, so neither key appears and the zero-contributor body is unchanged.
     */
    @GetMapping("/api/capabilities")
    public void capabilities(HttpServletResponse response) throws IOException {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("readOnly", readOnly());
        base.put("auth", Boolean.parseBoolean(settings.deployment("auth")));
        // Advertise the strictly-opt-in anonymous role so a console shows an explicit "Anonymous access"
        // banner and a client knows keyless reads are served. Empty (the default) means no anonymous access. Read off
        // the same jenrepo.* settings the other flags read, so no extra dependency is threaded in.
        base.put("anonymousRights", anonymousRights());
        // Merge each discovered contribution onto the base map, so a richer distribution extends the one free
        // /api/capabilities without a bean override or a WebMvcRegistrations mapping suppression. Base keys win a
        // conflict; with no contributor the body is the base map
        // unchanged. The discovery itself lives in the SPI home, not here, so this surface and every other consumer
        // of the same flags read one answer from one pipeline rather than each loading its own.
        CapabilityContributor.Merged merged = CapabilityContributor.merge(base, contributors, settings::deployment);
        report(merged);
        response.setHeader("Content-Type", "application/json");
        respond(response, 200, JSON.writeValueAsString(merged.capabilities()));
    }

    /** Log what the capability merge refused - a contributed key the base already owned, a contributor that threw -
     *  so the drop is visible to an operator reading logs as well as to one reading the body. Logged on <em>change</em>
     *  rather than per request: which contributions collide is a property of the installed module set, so it is the
     *  same report on every hit of a polled endpoint, and repeating it would bury it. A deployment that stops
     *  colliding is logged clear the same way. */
    private void report(CapabilityContributor.Merged merged) {
        List<String> lines = merged.report();
        if (!lines.equals(reportedCapabilityProblems)) {
            reportedCapabilityProblems = List.copyOf(lines);
            lines.forEach(LOGGER::warn);
            if (lines.isEmpty()) {
                LOGGER.info("Every /api/capabilities contribution is now served; the previous conflicts are resolved");
            }
        }
    }

    /** The deployment-wide read-only flag, read off the same {@code jenrepo.*} settings the formats read a
     *  toggle from, so no extra dependency is threaded in; unset means read-write. */
    private boolean readOnly() {
        return Boolean.parseBoolean(settings.deployment("read-only"));
    }

    /** The strictly-opt-in anonymous-role grant advertised on {@code /api/capabilities}, read off the same
     *  {@code jenrepo.*} settings; empty (the default) means no anonymous access. */
    private String anonymousRights() {
        String value = settings.deployment("anonymous-rights");
        return value == null ? "" : value.trim();
    }

    /** A write refused by the storage quota maps to {@code 507 Insufficient Storage} - the limit was hit before any
     *  bytes were stored, so this is a clean rejection the client can surface. */
    @ExceptionHandler(QuotaExceededException.class)
    public void quotaExceeded(QuotaExceededException exception, HttpServletResponse response) throws IOException {
        respond(response, 507, exception.getMessage());
    }

    /** A write refused because the coordinate is already published maps to {@code 409 Conflict} - the registry
     *  vocabulary every client already understands, and what npm, crates.io and NuGet all answer for a duplicate
     *  version. Without this the refusal would surface as an unhandled {@code IOException} and a client would be told
     *  the server had broken, when in fact it had held a released version immutable exactly as documented. */
    @ExceptionHandler(Publication.RepublishConflict.class)
    public void republishConflict(Publication.RepublishConflict exception, HttpServletResponse response)
            throws IOException {
        respond(response, 409, exception.getMessage());
    }

    /** A publish whose blob a collector removed while it was in flight is a transient failure, not a refusal: sent
     *  again, the bytes are stored again. {@code 503} with a {@code Retry-After} is how a client is told to do that. */
    @ExceptionHandler(Publication.BlobCollected.class)
    public void blobCollected(Publication.BlobCollected exception, HttpServletResponse response) throws IOException {
        LOGGER.debug("A publish raced a collection and is answered 503: {}", exception.getMessage());
        response.setHeader("Retry-After", "1");
        respond(response, 503, "The upload raced a cleanup of the same content; send it again.");
    }

    /** A write whose compare-and-set lost every try to peers on the same document is a transient refusal, not a
     *  server error: sent again a moment later it lands. {@code 503} with a {@code Retry-After} is how a client is
     *  told to do that. */
    @ExceptionHandler(Retries.Contended.class)
    public void contended(Retries.Contended exception, HttpServletResponse response) throws IOException {
        LOGGER.debug("A write lost every compare-and-set and is answered 503: {}", exception.getMessage());
        response.setHeader("Retry-After", "1");
        respond(response, 503, "The repository was busy with a concurrent change to the same item; send it again.");
    }

    /** A write refused because the deployment is read-only maps to {@code 403 Forbidden} - the store choke point
     *  rejected the mutation before any bytes were stored, whatever endpoint or internal path attempted it. */
    @ExceptionHandler(ReadOnlyException.class)
    public void readOnly(ReadOnlyException exception, HttpServletResponse response) throws IOException {
        respond(response, 403, exception.getMessage());
    }

    /**
     * A failure no handler above meant: logged once with a reference ({@link Failures}), and answered with the sentence
     * and the reference in the claiming format's own error dialect ({@link RepositoryFormat#failed}), so a client
     * prints something a person can quote and nothing of the failure's insides. Spring's own typed status exceptions
     * keep their answers, and a route no format claimed is answered by Spring's error page, which references it the
     * same way; a response already streaming can only be cut short, and the log line is all there is.
     */
    @ExceptionHandler(Exception.class)
    public void failed(Exception failure, HttpServletRequest request, HttpServletResponse response) throws Exception {
        Optional<RepositoryFormat> format = request.getAttribute(FAILING_FORMAT) instanceof HeldFormat held
                ? held.claiming() : Optional.empty();
        if (failure instanceof ErrorResponse || format.isEmpty()) {
            throw failure;          // a typed status, or no format to answer in: Spring's error page answers it
        }
        if (DisconnectedClientHelper.isClientDisconnectedException(failure)) {
            return;                 // the client hung up mid-response: nothing failed on this side
        }
        String reference = Failures.record(request.getMethod() + " " + request.getRequestURI(), failure);
        if (!response.isCommitted()) {
            response.resetBuffer();
            format.get().failed(new ServletFormatExchange(request, response, request.getRequestURI()),
                    Failures.sentence(reference));
        }
    }

    /** The request attribute the format a request was dispatched to is kept under, for {@link #failed}. */
    private static final String FAILING_FORMAT = RepositoryController.class.getName() + ".format";

    private static void respond(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = response.getOutputStream()) {
            out.write(bytes);
        }
    }
}
