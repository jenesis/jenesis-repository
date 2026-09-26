package build.jenesis.repository.cache.server;

import module java.base;
import build.jenesis.repository.cache.storage.CacheStorage;
import build.jenesis.repository.cache.storage.Names;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.spi.KeyUsageTracker;
import build.jenesis.repository.walk.Traversal;
import build.jenesis.repository.store.Durations;
import build.jenesis.repository.store.StoreCache;
import io.micrometer.core.instrument.Counter;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * The build-cache logic, independent of any HTTP framework so it can be driven by {@link CacheController}
 * (Spring MVC) and tested directly. It is multi-tenant: the storage root holds one folder per tenant,
 * each a self-contained space of projects (configured by their own {@code cache.properties}). A request
 * carries its project in a header and its key as {@code jenk_<tenant>.<secret>}, so a key is bound to one
 * tenant and only ever checked against that tenant's space. Authorisation is delegated to a shared
 * {@link Authorization}: it reads the per-credential grants on each request (so a revoked grant takes
 * effect at once) against the {@code cache:read}/{@code cache:write} surface, and an anonymous
 * authorization (or a configured trial bootstrap key) lets every request through. Project configs are
 * held in a version-revalidated LRU cache. A write triggers immediate per-project size-cap eviction; the
 * periodic reaper additionally re-applies the size cap, the ttl and the global free-space target across
 * every tenant and project by re-scanning the store, so an over-cap project that is idle - its cap lowered
 * after the writes landed, or the cache enabled over a store that already held entries from before it
 * existed - still converges rather than waiting for a write that may never come.
 */
public class Cache {

    private static final System.Logger LOGGER = System.getLogger(Cache.class.getName());

    /** How many of the coldest entries a single free-space reclaim pass selects and drops before re-scanning - the
     *  bound on the reclaim's in-heap footprint, so a low-disk node reclaims in batches rather than sorting the whole
     *  store's entry set at once. */
    private static final int RECLAIM_BATCH = 1024;

    public enum Outcome {
        HIT, MISS, STORED, PRESENT, REJECTED, INVALID, UNAUTHORIZED, FORBIDDEN, TOUCHED, FULL;

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The result of authorising a request: {@link Allowed} to proceed, or {@link Rejected} with a status. */
    public sealed interface Resolution permits Allowed, Rejected {
    }

    public record Allowed(String metric, CacheStorage storage, Project project, CacheStorage.Entry entry) implements Resolution {
    }

    public record Rejected(int status) implements Resolution {
    }

    /** A project's read policy as last read from its {@code cache.properties}, and when this node last read or
     *  confirmed it ({@link #policyInterval}). */
    record Project(String name, long size, boolean lru, Object version, Instant verified) {
        Project verifiedAt(Instant at) {
            return new Project(name, size, lru, version, at);
        }
    }

    /** What this node remembers of an entry's recency: the instant of the stamp it last wrote or read, or of the
     *  store it made itself, which leaves no stamp - so a renewal knows whether there is a stamp to retire. */
    private record Known(Instant at, boolean stamped) {
    }

    private final CacheStorage storage;
    private final Authorization authorization;
    private KeyUsageTracker usageTracker;
    private final long max;
    private final long minFree;
    private final int minFreePercent;
    private final String defaultProject;
    private final boolean projectRequired;
    private final String bootstrapKey;
    private final String defaultTenant;
    private final Map<String, Project> projects;
    private final Map<String, CacheStorage> scopes = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> evicting = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> dirty = new ConcurrentHashMap<>();
    // Single-flight guard for the off-request free-space reclaim (cannotFit): at most one background sweep runs at a
    // time, so a burst of writers under low disk never spawns a reclaim per PUT.
    private final AtomicBoolean reclaiming = new AtomicBoolean();
    // A request counter is stable per (tenant, project, outcome) and one is incremented on every request, so each is
    // resolved once and reused rather than rebuilding the builder + tag set and re-doing the registry lookup per call.
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final MeterRegistry registry;
    private final Duration reaper;
    /** How many entries' recency this node remembers; past it a hot entry's stamp is merely re-read once. */
    private static final int TOUCH_MEMORY = 200_000;
    /** The shipped {@code jenreg.cache.touch-interval}; {@link #touchInterval(Duration)} is what binds it. */
    static final Duration DEFAULT_TOUCH_INTERVAL = Duration.ofHours(6);
    private volatile Duration touchInterval;
    private volatile Map<String, Known> touched;
    private volatile Duration policyInterval;
    private volatile InstantSource clock = InstantSource.system();
    private volatile boolean reaping;
    private Thread reaperThread;

    public Cache(CacheStorage storage, Authorization authorization, long max, int capacity, Duration reaper,
                 long minFree, int minFreePercent, String defaultProject, boolean projectRequired,
                 String bootstrapKey, String defaultTenant, MeterRegistry registry) {
        this.storage = storage;
        this.authorization = authorization;
        this.registry = registry;
        this.usageTracker = KeyUsageTracker.NONE;
        this.max = max;
        this.reaper = reaper;
        this.minFree = minFree;
        this.minFreePercent = minFreePercent;
        this.defaultProject = requireName(defaultProject, "default project");
        this.projectRequired = projectRequired;
        this.bootstrapKey = bootstrapKey == null || bootstrapKey.isBlank() ? null : bootstrapKey;
        this.defaultTenant = requireName(defaultTenant, "default tenant");
        this.projects = Caffeine.newBuilder().maximumSize(capacity).<String, Project>build().asMap();
        touchInterval(DEFAULT_TOUCH_INTERVAL);
        policyInterval(StoreCache.DEFAULT_TTL);
    }

    /**
     * How long a recency stamp stands before a hit renews it ({@code jenreg.cache.touch-interval}); {@code null} or
     * zero stamps every hit. Within the window a hit costs the store nothing for recency: this node remembers, per
     * entry, the stamp it last wrote or read - or the store it made itself, whose recency is the entry's own time
     * until a stamp exists - and only an entry it does not remember is asked for its recency - its stamps, then the
     * entry's own time when it has none - and stamped only if that is past the window. A renewal writes the new stamp and
     * retires the one it knows of, so an entry keeps one. The stamp is therefore up to one window older than the
     * entry's last use, which is the error margin the ttl and the least-recently-used sweep accept in exchange for
     * a warm build of thousands of hits writing nothing - the first warm build after a cold store included, since
     * the node that stored an entry remembers having done so. The stamp is an object beside the entry on every
     * backend alike, never a modification time, so a store copied from one backend to another keeps it.
     */
    public Cache touchInterval(Duration interval) {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            // Every hit stamps; the memory still holds the previous stamp so a renewal can retire it.
            touchInterval = null;
            touched = Caffeine.newBuilder().maximumSize(TOUCH_MEMORY).<String, Known>build().asMap();
        } else {
            touchInterval = interval;
            touched = Caffeine.newBuilder().maximumSize(TOUCH_MEMORY).expireAfterWrite(interval)
                    .<String, Known>build().asMap();
        }
        return this;
    }

    /**
     * How long a project's read policy - the size cap and sweep order its {@code cache.properties} sets - is trusted
     * before a request asks the store whether it changed; {@code null} or zero asks on every request. The check is
     * one version probe of the policy document, a round trip per hit on an object store and the last store call a
     * hit paid once recency moved into the touch window; so the node remembers when it last read or confirmed a
     * project's policy and asks again only past the window. The window is the deployment's {@code jenreg.cache.ttl},
     * as it is for the credential the same request was authorised against: another node's edit of a project's cap
     * shows here within it, and an operator who wants every request to see an edit at once sets it to zero.
     */
    public Cache policyInterval(Duration interval) {
        policyInterval = interval == null || interval.isZero() || interval.isNegative() ? null : interval;
        return this;
    }

    /** The clock the touch and policy windows are measured on; a test moves it. */
    public Cache clock(InstantSource clock) {
        this.clock = clock;
        return this;
    }

    /** Override the off-request key-usage tracker (defaults to a disabled one), so an allowed read stamps last-used. */
    public Cache usageTracker(KeyUsageTracker usageTracker) {
        this.usageTracker = usageTracker;
        return this;
    }

    /** Start the ttl/free-space reaper thread (a no-op when no interval is configured). */
    public void start() {
        if (reaper != null && !reaping) {
            reaping = true;
            reaperThread = Thread.ofVirtual().name("jenesis-cache-reaper").start(this::reap);
        }
    }

    public void stop() {
        reaping = false;
        if (reaperThread != null) {
            reaperThread.interrupt();
            try {
                reaperThread.join(Duration.ofSeconds(5));
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            reaperThread = null;
        }
    }

    public long max() {
        return max;
    }

    /** Authorise a request and resolve its entry in the tenant its key belongs to, recording the rejection metric for
     *  a denial. */
    public Resolution resolve(String project, String key, String step, String inputs, boolean write) {
        return resolve(null, project, key, step, inputs, write);
    }

    /**
     * Authorise a request addressed to {@code named}'s cache - {@code /build/<tenant>/...} - and resolve its entry.
     * The URL names the tenant and the key decides whether it may be addressed: a key reaches its own tenant's cache
     * and no other, and the bootstrap key the default tenant's. A key naming another tenant is a {@code 403}, never
     * a crossing; {@code null} addresses the key's own.
     */
    public Resolution resolve(String named, String project, String key, String step, String inputs, boolean write) {
        String name = project;
        if (name == null || name.isBlank()) {
            if (projectRequired) {
                count("", Outcome.INVALID);
                return new Rejected(400);
            }
            name = defaultProject;
        }
        if (!Names.isProject(name)) {
            count("", Outcome.INVALID);
            return new Rejected(400);
        }
        if (key == null || key.isBlank()) {
            count("", Outcome.UNAUTHORIZED);
            return new Rejected(401);
        }
        boolean bootstrap = bootstrapKey != null && MessageDigest.isEqual(
                key.getBytes(StandardCharsets.UTF_8), bootstrapKey.getBytes(StandardCharsets.UTF_8));
        String tenant;
        if (bootstrap) {
            tenant = defaultTenant;
        } else {
            tenant = Authorization.tenantOf(key);
            if (tenant == null || !Names.isTenant(tenant)) {
                count("", Outcome.UNAUTHORIZED);
                return new Rejected(401);
            }
        }
        if (named != null && !named.equals(tenant)) {
            count("", Outcome.FORBIDDEN);
            return new Rejected(403);
        }
        String metric = tenant + "/" + name;
        if (!bootstrap) {
            Authorization.Decision decision;
            try {
                decision = authorization.authorize(key, name, write ? Authorization.CACHE_WRITE : Authorization.CACHE_READ);
            } catch (IOException _) {
                decision = Authorization.Decision.FORBIDDEN;
            }
            if (decision == Authorization.Decision.UNAUTHORIZED) {
                // An unauthenticated request's tenant/project are attacker-chosen strings: tagging metrics with
                // them would let anyone mint unbounded meter registrations (a forged key's checksum is a public
                // CRC32). Only an authenticated outcome tags real names.
                count("", Outcome.UNAUTHORIZED);
                return new Rejected(401);
            }
            if (decision != Authorization.Decision.ALLOWED) {
                // FORBIDDEN covers both a provisioned credential lacking the grant (a real tenant, worth
                // attributing) and a forged-but-well-formed key (attacker-chosen names that must not become
                // meter tags) - only a provisioned credential's denial is tagged with its names.
                count(provisioned(tenant, key) ? metric : "", Outcome.FORBIDDEN);
                return new Rejected(403);
            }
            if (usageTracker.enabled()) {
                usageTracker.record(tenant, Authorization.hash(key), null);
            }
        }
        if (!Names.isHex(step) || !Names.isHex(inputs)) {
            count(metric, Outcome.INVALID);
            return new Rejected(400);
        }
        // The tenant's storage view is resolved only for an authorized request, so a forged key cannot grow the
        // scope map with made-up tenant names.
        CacheStorage scoped = scope(tenant);
        return new Allowed(metric, scoped, project(scoped, metric, name), new CacheStorage.Entry(name, step, inputs));
    }

    public boolean exists(Allowed allowed) {
        return allowed.storage().exists(allowed.entry());
    }

    public void touch(Allowed allowed) {
        Duration window = touchInterval;
        Map<String, Known> memory = touched;
        String remembered = remembered(allowed);
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Known known = memory.get(remembered);
        if (window != null && known != null && now.isBefore(known.at().plus(window))) {
            return;     // stamped, read as stamped or stored within the window: the store hears nothing
        }
        if (known == null) {
            // Unknown to this node: ask the storage once - its stamp, or the entry's own time when it has none, as
            // another node's store or a restart leaves it - and remember the answer as if this node had made it.
            CacheStorage.Recency recency = allowed.storage().recency(allowed.entry()).orElse(null);
            known = recency == null ? null : new Known(recency.at(), recency.stamped());
            if (window != null && known != null && now.isBefore(known.at().plus(window))) {
                memory.put(remembered, known);
                return;
            }
        }
        // A stamp is retired by the renewal; an entry known by its own time - stored here or elsewhere - has none.
        Instant previous = known != null && known.stamped() ? known.at() : null;
        allowed.storage().stamp(allowed.entry(), now, previous);
        memory.put(remembered, new Known(now, true));
    }

    private static String remembered(Allowed allowed) {
        return allowed.metric() + '/' + allowed.entry().step() + '/' + allowed.entry().inputs();
    }

    public void read(Allowed allowed, OutputStream out) throws IOException {
        allowed.storage().read(allowed.entry(), out);
    }

    /** Store the entry and schedule size-cap eviction when the project caps its size. */
    public void store(Allowed allowed, InputStream in) throws IOException {
        allowed.storage().store(allowed.entry(), in);
        // The entry's own time is its recency until a stamp exists: remembered, so a hit within the window of the
        // store - the first warm build after a cold one - neither asks for a stamp nor writes one.
        touched.put(remembered(allowed), new Known(clock.instant().truncatedTo(ChronoUnit.SECONDS), false));
        if (allowed.project().size() > 0) {
            scheduleEviction(allowed.metric(), allowed.storage(), allowed.project());
        }
    }

    public boolean tooLarge(long contentLength) {
        return contentLength > max;
    }

    /** Whether a store cannot be admitted now: true when the volume is below the free target. A sustained low-disk
     *  state must not make every writer pay an O(total entries) enumerate-and-sort of the whole store on the request
     *  thread, so the reclaim runs off-request as a single-flight background sweep (the same off-request shape the
     *  size-cap eviction drain uses) rather than synchronously here; the periodic reaper reclaims on its own clock too.
     *  Space therefore frees without any PUT scanning the store, and a writer under genuine exhaustion still gets a
     *  507 rather than blocking on a full sweep. */
    public boolean cannotFit() {
        if (!low()) {
            return false;
        }
        triggerReclaim();
        return low();
    }

    /** Kick a single-flight background free-space reclaim: at most one runs at a time (a second caller while one is in
     *  flight is a no-op), so a burst of writers under low disk schedules one sweep, not one per PUT. */
    private void triggerReclaim() {
        if (reclaiming.compareAndSet(false, true)) {
            Thread.ofVirtual().name("jenesis-cache-reclaim").start(() -> {
                try {
                    reclaim();
                } finally {
                    reclaiming.set(false);
                }
            });
        }
    }

    public void count(String metric, Outcome outcome) {
        int slash = metric.indexOf('/');
        String tenant = slash < 0 ? "none" : metric.substring(0, slash);
        String project = slash < 0 ? "none" : metric.substring(slash + 1);
        if (tenant.isEmpty()) {
            tenant = "none";
        }
        if (project.isEmpty()) {
            project = "none";
        }
        String tenantTag = tenant;
        String projectTag = project;
        counters.computeIfAbsent(tenant + '\0' + project + '\0' + outcome.label(), _ ->
                        Counter.builder("jenreg.cache.requests")
                                .description("Cache requests by tenant, project and outcome")
                                .tag("tenant", tenantTag)
                                .tag("project", projectTag)
                                .tag("outcome", outcome.label())
                                .register(registry))
                .increment();
    }

    /** Whether the key is a provisioned credential of its tenant - the gate for trusting its names as meter
     *  tags: an unknown credential's tenant/project are unauthenticated input. */
    private boolean provisioned(String tenant, String key) {
        try {
            return authorization.credential(tenant, Authorization.hash(key)).isPresent();
        } catch (IOException _) {
            return false;
        }
    }

    private CacheStorage scope(String tenant) {
        return scopes.computeIfAbsent(tenant, storage::scope);
    }

    /** The tenants this node caches for, drained through the storage's paged root listing. The remainder is followed
     *  rather than reported: a tenant is provisioned, not client-created, and every caller here (the reaper, the
     *  free-space reclaim) needs all of them - what pages beneath this is the per-project entry set, which is what a
     *  build inflates. */
    private List<String> tenants() {
        List<String> result = new ArrayList<>();
        String cursor = null;
        while (true) {
            Traversal.Result page = storage.listDir("", cursor, CacheStorage.PAGE, name -> {
                // "auth" is the credential tree the single-node deployment roots beside the tenant folders; it matches
                // the tenant name shape, so exclude it by name or a free-space reclaim would enumerate (and, should
                // any of its files ever look like entries, evict) the credential store.
                if (Names.isTenant(name) && !name.equals("auth")) {
                    result.add(name);
                }
            });
            if (page.exhausted()) {
                return result;
            }
            cursor = page.cursor().orElseThrow();
        }
    }

    /** Drive one project's paged entry enumeration to exhaustion, handing every entry to {@code action}. A sweep's
     *  answer is a total or a deletion set, so the remainder is always followed; what the bound buys is that only one
     *  page of entries is ever in heap, never the project. Deleting from inside the sweep is safe because a cursor is
     *  a key, not an index: everything deleted is already behind it. */
    private static void sweep(CacheStorage store, String project, Consumer<CacheStorage.Stored> action) {
        String cursor = null;
        while (true) {
            Traversal.Result page = store.entries(project, cursor, CacheStorage.PAGE, action);
            if (page.exhausted()) {
                return;
            }
            cursor = page.cursor().orElseThrow();
        }
    }

    /** The same, for one scope's project names. */
    private static void projects(CacheStorage store, Consumer<String> action) {
        String cursor = null;
        while (true) {
            Traversal.Result page = store.projects(cursor, CacheStorage.PAGE, action);
            if (page.exhausted()) {
                return;
            }
            cursor = page.cursor().orElseThrow();
        }
    }

    private Project project(CacheStorage store, String cacheKey, String name) {
        Project cached = projects.get(cacheKey);
        Instant now = clock.instant();
        Duration window = policyInterval;
        if (cached != null && window != null && now.isBefore(cached.verified().plus(window))) {
            return cached;      // read or confirmed within the window: the store is not asked
        }
        Object version = store.configVersion(name);
        if (cached != null && Objects.equals(cached.version(), version)) {
            Project confirmed = cached.verifiedAt(now);
            projects.put(cacheKey, confirmed);
            return confirmed;
        }
        Properties cache = store.readConfig(name, "cache.properties");
        boolean lru = !"false".equalsIgnoreCase(cache.getProperty("lru", "true").trim());
        Project loaded = new Project(name, parseSize(cache.getProperty("size"), cacheKey), lru, version, now);
        projects.put(cacheKey, loaded);
        return loaded;
    }

    private void scheduleEviction(String key, CacheStorage store, Project project) {
        dirty.computeIfAbsent(key, _ -> new AtomicBoolean()).set(true);
        AtomicBoolean running = evicting.computeIfAbsent(key, _ -> new AtomicBoolean());
        if (running.compareAndSet(false, true)) {
            Thread.ofVirtual().name("jenesis-cache-eviction").start(() -> drain(key, store, project, running));
        }
    }

    private void drain(String key, CacheStorage store, Project project, AtomicBoolean running) {
        AtomicBoolean flag = dirty.get(key);
        try {
            while (flag.getAndSet(false)) {
                evict(store, project);
            }
        } finally {
            running.set(false);
        }
        if (flag.get() && running.compareAndSet(false, true)) {
            Thread.ofVirtual().name("jenesis-cache-eviction").start(() -> drain(key, store, project, running));
        }
    }

    /**
     * Enforce one project's size cap, in bounded passes rather than one enumerate-and-sort.
     *
     * <p>This used to hold the project's whole entry set in a list and sort it, which is an allocation proportional to
     * whatever a build had cached - on a write-triggered path, so a client controlled both when it ran and how large
     * it was. The total is now counted by streaming, and each round retains only the {@link #RECLAIM_BATCH} entries it
     * is about to delete, selected through the same bounded heap the free-space reclaim uses.
     */
    private void evict(CacheStorage store, Project project) {
        long limit = project.size();
        if (limit <= 0) {
            return;
        }
        long[] total = new long[1];
        sweep(store, project.name(), entry -> total[0] += entry.size());
        if (total[0] <= limit) {
            return;
        }
        while (total[0] > limit) {
            Selection selection = new Selection(RECLAIM_BATCH, project.lru());
            sweep(store, project.name(), selection);
            List<CacheStorage.Stored> batch = selection.selected();
            if (batch.isEmpty()) {
                return;
            }
            boolean progressed = false;
            for (CacheStorage.Stored entry : batch) {
                if (total[0] <= limit) {
                    break;
                }
                store.delete(entry);
                total[0] -= entry.size();
                progressed = true;
            }
            if (!progressed || batch.size() < RECLAIM_BATCH) {
                return;                             // the project is exhausted (or the batch cannot reach the limit)
            }
        }
    }

    private boolean low() {
        if (minFree <= 0 && minFreePercent <= 0) {
            return false;
        }
        long usable = usableSpace();
        if (minFree > 0 && usable < minFree) {
            return true;
        }
        if (minFreePercent > 0) {
            long total = totalSpace();
            return total > 0 && usable * 100L < total * minFreePercent;
        }
        return false;
    }

    private void reclaim() {
        // A sustained low-disk state must never make the reclaim build an in-heap list of the WHOLE store's entries and
        // sort it - an O(total entries) enumerate-and-sort run exactly when the node is already disk-degraded, the OOM
        // this bounds. Each pass streams the entries a project at a time (a lazily-flattened view, never a whole-store
        // snapshot) through a bounded max-heap that retains only the RECLAIM_BATCH coldest, deletes them until the free
        // target is met, then re-scans for the next batch - so the peak footprint is one batch, whatever the store's
        // size. The single-flight guard around this keeps at most one such sweep running.
        while (low()) {
            Selection selection = new Selection(RECLAIM_BATCH, true);
            Map<CacheStorage.Stored, CacheStorage> owners = new IdentityHashMap<>();
            for (String tenant : tenants()) {
                CacheStorage scoped = scope(tenant);
                projects(scoped, project -> sweep(scoped, project, entry -> {
                    // An entry is deleted through the storage that ENUMERATED it, never through the root one. A
                    // Stored's token is opaque and scope-relative: handing a tenant-scoped entry to the root storage
                    // only ever worked because one backend's token happened to be an absolute path, and it silently
                    // deleted nothing on any backend whose token was not.
                    owners.put(entry, scoped);
                    selection.accept(entry);
                }));
            }
            List<CacheStorage.Stored> batch = selection.selected();
            if (batch.isEmpty()) {
                return;
            }
            boolean progressed = false;
            for (CacheStorage.Stored entry : batch) {
                if (!low()) {
                    return;
                }
                owners.getOrDefault(entry, storage).delete(entry);
                progressed = true;
            }
            if (!progressed || batch.size() < RECLAIM_BATCH) {
                return;                             // the store is exhausted (or the coldest cannot free the target)
            }
        }
    }

    /**
     * The bounded selection every sweep here drives: the {@code k} entries that sort first under the eviction order -
     * coldest for a least-recently-used policy, warmest for the most-recently-used one - accumulated in one streaming
     * pass through a heap that never holds more than {@code k}, so a size cap or a free-space reclaim picks what to
     * drop without ever materialising or sorting the whole entry set in heap.
     *
     * <p>It is a {@link Consumer} rather than a function over a collection precisely so it can be handed straight to
     * the storage's paged enumeration: the entries arrive one at a time, page by page, and are never anywhere else at
     * once. Selecting across tenants and projects is then just driving one selection through several enumerations.
     */
    private static final class Selection implements Consumer<CacheStorage.Stored> {

        /** The eviction order: the entries that sort FIRST are the ones to delete. */
        private final Comparator<CacheStorage.Stored> order;
        /** The retained candidates, worst-first, so the one to drop when a better arrives is always the head. */
        private final PriorityQueue<CacheStorage.Stored> retained;
        private final int k;

        private Selection(int k, boolean coldest) {
            this.k = k;
            this.order = coldest
                    ? Comparator.comparing(CacheStorage.Stored::recency)
                    : Comparator.comparing(CacheStorage.Stored::recency).reversed();
            this.retained = new PriorityQueue<>(this.order.reversed());
        }

        @Override
        public void accept(CacheStorage.Stored entry) {
            if (retained.size() < k) {
                retained.add(entry);
            } else if (order.compare(entry, retained.peek()) < 0) {
                retained.poll();
                retained.add(entry);
            }
        }

        /** What was selected, in deletion order. */
        private List<CacheStorage.Stored> selected() {
            List<CacheStorage.Stored> selected = new ArrayList<>(retained);
            selected.sort(order);
            return selected;
        }
    }

    protected long usableSpace() {
        return storage.usableSpace();
    }

    protected long totalSpace() {
        return storage.totalSpace();
    }

    private void reap() {
        while (reaping) {
            try {
                Thread.sleep(reaper);
            } catch (InterruptedException _) {
                return;
            }
            if (reaping) {
                try {
                    reapAll();
                    reclaim();
                } catch (RuntimeException e) {
                    // One backend hiccup (an S3 throttle, an Azure blip during a listing) must not kill the reaper
                    // for the life of the process - a dead reaper means TTL and free-space eviction silently stop.
                    LOGGER.log(System.Logger.Level.WARNING, "cache reaper sweep failed; retrying next interval", e);
                }
            }
        }
    }

    private void reapAll() {
        for (String tenant : tenants()) {
            CacheStorage store = scope(tenant);
            List<String> names = new ArrayList<>();
            projects(store, names::add);
            for (String name : names) {
                if (!Names.isProject(name)) {
                    continue;
                }
                Properties config = store.readConfig(name, "cache.properties");
                Duration ttl = ttl(config, name);
                if (ttl != null) {
                    expire(store, name, ttl);
                }
                // Size-cap eviction is otherwise applied only on a write (scheduleEviction). Re-apply it here so a
                // project that is over cap yet idle converges on the reaper's own clock rather than waiting for a
                // write that may never come - the cap was lowered after the writes landed, or the cache was enabled
                // over a store that already held entries from before it existed. Like the ttl and free-space sweeps,
                // it reconstructs the policy over pre-existing data by re-scanning the store, not from live writes.
                long limit = parseSize(config.getProperty("size"), name);
                if (limit > 0) {
                    boolean lru = !"false".equalsIgnoreCase(config.getProperty("lru", "true").trim());
                    evict(store, new Project(name, limit, lru, store.configVersion(name), clock.instant()));
                }
            }
        }
    }

    private void expire(CacheStorage store, String project, Duration ttl) {
        Instant threshold = Instant.now().minus(ttl);
        sweep(store, project, entry -> {
            if (entry.recency().isBefore(threshold)) {
                store.delete(entry);
            }
        });
    }

    private static Duration ttl(Properties cache, String project) {
        String value = cache.getProperty("ttl");
        if (value == null || value.isBlank()) {
            return null;
        }
        Duration duration;
        try {
            duration = Durations.parse(value);
        } catch (RuntimeException _) {
            duration = null;
        }
        if (duration == null || duration.isZero() || duration.isNegative()) {
            // As a malformed size: the reaper sweeps every project in one pass, so one project's typo is said out
            // loud and that project's expiry is skipped, rather than read as "no ttl" in silence.
            LOGGER.log(System.Logger.Level.WARNING, "unparseable ttl '" + value + "' for cache project " + project
                    + "; stale entries are not expired until the value is fixed");
            return null;
        }
        return duration;
    }

    private static long parseSize(String value, String project) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException _) {
            // A typo ("size=2gb") must not read as "unlimited" in silence: the misconfigured project would just
            // grow unbounded. It still parses as no-cap (fail-open keeps the cache serving), but says so.
            LOGGER.log(System.Logger.Level.WARNING, "unparseable size '" + value + "' for cache project "
                    + project + "; the size cap is disabled until the value is fixed");
            return 0;
        }
    }

    private static String requireName(String value, String what) {
        if (value == null || !Names.isProject(value.trim())) {
            throw new IllegalArgumentException(
                    "Invalid " + what + " '" + value + "': use letters, digits and underscores only.");
        }
        return value.trim();
    }
}
