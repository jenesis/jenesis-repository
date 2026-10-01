package build.jenesis.repository.cache.server;

import module java.base;
import build.jenesis.repository.cache.storage.CacheStorage;
import build.jenesis.repository.cache.storage.Names;
import build.jenesis.repository.cache.storage.ProjectPolicy;
import build.jenesis.repository.server.spi.AccessDenial;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.spi.KeyUsageTracker;
import build.jenesis.repository.walk.Traversal;
import build.jenesis.repository.store.Durations;
import build.jenesis.repository.store.StoreCache;
import io.micrometer.core.instrument.Counter;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * The build-cache logic, independent of any HTTP framework, driven by {@link CacheController} and tested directly. It
 * is multi-tenant: the storage root holds a folder per tenant, each a space of projects configured by their project
 * settings ({@link ProjectPolicy}). A request carries its project in a header and its key as
 * {@code jenk_<tenant>.<secret>}, so a key is checked only against its own tenant's space. Authorisation is the shared
 * {@link Authorization}, reading the credential's grants on each request (a revoked grant takes effect at once) against
 * {@code cache:read}/{@code cache:write}; an anonymous authorization or a trial bootstrap key lets every request
 * through. Project policies are held in an LRU cache and re-read past the policy window. A write triggers the project's
 * size-cap eviction; the periodic reaper re-applies the size cap, the ttl and the global free-space target across every
 * tenant and project, so an idle over-cap project converges too.
 */
public class Cache {

    private static final System.Logger LOGGER = System.getLogger(Cache.class.getName());

    /** How many of the coldest entries one free-space reclaim pass selects before re-scanning: the bound on its heap
     *  footprint, so a low-disk node reclaims in batches rather than sorting the whole store. */
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

    /** A project's policy as last read, and when this node read it ({@link #policyInterval}). */
    record Project(String name, long size, boolean lru, Instant verified) {
    }

    /** Where a project's policy comes from: its effective settings - the project's own over its tenant's over the
     *  deployment's ({@code StoredSettings.projectChain}) - read when this node has not read them within the policy
     *  window. {@link #NONE} is what a cache built without a store answers. */
    @FunctionalInterface
    public interface Policies {

        /** Nothing configured for any project: no cap, least recently used first, kept for ever. */
        Policies NONE = (_, _) -> _ -> null;

        /** The effective settings of {@code project} of {@code tenant}, {@code null} for an unset key. */
        UnaryOperator<String> of(String tenant, String project) throws IOException;
    }

    /** What this node remembers of an entry's recency: the stamp it last wrote or read, or the store it made itself,
     *  which leaves no stamp, so a renewal knows whether there is one to retire. */
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
    // Single-flight guard for the off-request free-space reclaim, so a burst of writers under low disk spawns one
    // sweep.
    private final AtomicBoolean reclaiming = new AtomicBoolean();
    // A counter per (tenant, project, outcome), resolved once rather than looked up on every request.
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final MeterRegistry registry;
    private final Duration reaper;
    /** How many entries' recency this node remembers; past it a hot entry's stamp is re-read once. */
    private static final int TOUCH_MEMORY = 200_000;
    /** The shipped {@code jenrepo.cache.touch-interval}; {@link #touchInterval(Duration)} is what binds it. */
    static final Duration DEFAULT_TOUCH_INTERVAL = Duration.ofHours(6);
    private volatile Duration touchInterval;
    private volatile Map<String, Known> touched;
    private volatile Duration policyInterval;
    private volatile Policies policies = Policies.NONE;
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

    /** How long a recency stamp stands before a hit renews it ({@code jenrepo.cache.touch-interval}); {@code null} or
     *  zero stamps every hit. Within the window a hit costs the store nothing for recency: this node remembers per
     *  entry the stamp it last wrote or read, or the store it made, and asks only for an entry it does not remember,
     *  stamping it only past the window. A renewal writes the new stamp and retires the known one, so an entry keeps
     *  one. A stamp is therefore up to a window older than the last use, the margin the ttl and the least-recently-used
     *  sweep accept so a warm build of thousands of hits writes nothing. The stamp is an object beside the entry on
     *  every backend, never a modification time, which object stores do not move on a read. */
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

    /** How long a project's policy - the size cap and sweep order its settings set - is trusted before a request reads
     *  it again; {@code null} or zero reads it every request. Reading it costs the settings documents of the project,
     *  its tenant and the deployment, otherwise the only store calls a hit pays. The window is
     *  {@code jenrepo.cache.ttl}, as for the credential the request was authorised against, so another node's edit
     *  shows within it. */
    public Cache policyInterval(Duration interval) {
        policyInterval = interval == null || interval.isZero() || interval.isNegative() ? null : interval;
        return this;
    }

    /** Where every project's policy is read from - see {@link Policies}. */
    public Cache policies(Policies policies) {
        this.policies = Objects.requireNonNull(policies, "policies");
        return this;
    }

    /** The clock the touch and policy windows are measured on; a test moves it. */
    public Cache clock(InstantSource clock) {
        this.clock = clock;
        return this;
    }

    /** Override the off-request key-usage tracker (a disabled one by default), so an allowed read stamps last-used. */
    public Cache usageTracker(KeyUsageTracker usageTracker) {
        this.usageTracker = usageTracker;
        return this;
    }

    /** Start the ttl and free-space reaper thread; a no-op when no interval is configured. */
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

    /** Authorise a request and resolve its entry in its key's tenant, counting a denial. */
    public Resolution resolve(String project, String key, String step, String inputs, boolean write) {
        return resolve(null, project, key, step, inputs, write);
    }

    /** Authorise a request addressed to {@code named}'s cache ({@code /build/<tenant>/...}) and resolve its entry. A
     *  key reaches its own tenant's cache and no other, the bootstrap key the default tenant's; a key naming another
     *  tenant or lacking the project's right is refused as the deployment's {@link AccessDenial} says, decided from the
     *  names alone so it is the same whether the project or tenant exists. {@code null} addresses the key's own. */
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
            return new Rejected(AccessDenial.configured().status());
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
                // An unauthenticated request's names are attacker-chosen, and a forged key's checksum is a public
                // CRC32, so only an authenticated outcome tags names, or anyone could mint unbounded meter
                // registrations.
                count("", Outcome.UNAUTHORIZED);
                return new Rejected(401);
            }
            if (decision != Authorization.Decision.ALLOWED) {
                // FORBIDDEN covers a provisioned credential without the grant, worth attributing, and a forged
                // well-formed key, whose names must not become tags.
                count(provisioned(tenant, key) ? metric : "", Outcome.FORBIDDEN);
                return new Rejected(AccessDenial.configured().status());
            }
            if (usageTracker.enabled()) {
                usageTracker.record(tenant, Authorization.hash(key), null);
            }
        }
        if (!Names.isHex(step) || !Names.isHex(inputs)) {
            count(metric, Outcome.INVALID);
            return new Rejected(400);
        }
        // The tenant's view is resolved only when authorized, so a forged key cannot grow the scope map.
        CacheStorage scoped = scope(tenant);
        return new Allowed(metric, scoped, project(metric, tenant, name), new CacheStorage.Entry(name, step, inputs));
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
            // Unknown to this node: ask the store once - its stamp, else the entry's own time - and remember the
            // answer.
            CacheStorage.Recency recency = allowed.storage().recency(allowed.entry()).orElse(null);
            known = recency == null ? null : new Known(recency.at(), recency.stamped());
            if (window != null && known != null && now.isBefore(known.at().plus(window))) {
                memory.put(remembered, known);
                return;
            }
        }
        // A renewal retires a stamp; an entry known by its own time has none.
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
        // The entry's own time is its recency until stamped, remembered so a hit within the window neither reads nor
        // writes a stamp.
        touched.put(remembered(allowed), new Known(clock.instant().truncatedTo(ChronoUnit.SECONDS), false));
        if (allowed.project().size() > 0) {
            scheduleEviction(allowed.metric(), allowed.storage(), allowed.project());
        }
    }

    public boolean tooLarge(long contentLength) {
        return contentLength > max;
    }

    /** Whether a store cannot be admitted now: true when the volume is below the free target. The reclaim runs
     *  off-request as a single-flight background sweep, as the size-cap eviction does, so no writer pays an
     *  enumerate-and-sort of the store, and a writer under real exhaustion gets a 507 rather than blocking. */
    public boolean cannotFit() {
        if (!low()) {
            return false;
        }
        triggerReclaim();
        return low();
    }

    /** Kick a single-flight background free-space reclaim; a caller while one runs is a no-op. */
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
                        Counter.builder("jenrepo.cache.requests")
                                .description("Cache requests by tenant, project and outcome")
                                .tag("tenant", tenantTag)
                                .tag("project", projectTag)
                                .tag("outcome", outcome.label())
                                .register(registry))
                .increment();
    }

    /** Whether the key is a provisioned credential of its tenant: the gate for trusting its names as meter tags. */
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

    /** The tenants this node caches for, drained through the storage's paged root listing; every caller needs all of
     *  them, and tenants are provisioned, not client-created. */
    private List<String> tenants() {
        List<String> result = new ArrayList<>();
        String cursor = null;
        while (true) {
            // The storage holds tenant folders only - the product's own spaces are under .system beside it - so every
            // name of a tenant's shape is a tenant.
            Traversal.Result page = storage.listDir("", cursor, CacheStorage.PAGE, name -> {
                if (Names.isTenant(name)) {
                    result.add(name);
                }
            });
            if (page.exhausted()) {
                return result;
            }
            cursor = page.cursor().orElseThrow();
        }
    }

    /** Drive one project's paged entry enumeration to exhaustion, one page in heap at a time. Deleting during the sweep
     *  is safe because a cursor is a key: everything deleted is behind it. */
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

    private Project project(String cacheKey, String tenant, String name) {
        Project cached = projects.get(cacheKey);
        Instant now = clock.instant();
        Duration window = policyInterval;
        if (cached != null && window != null && now.isBefore(cached.verified().plus(window))) {
            return cached;      // read within the window: the store is not asked
        }
        UnaryOperator<String> config = policy(tenant, name);
        Project loaded = new Project(name, size(config, cacheKey), ProjectPolicy.lru(config.apply(ProjectPolicy.LRU)),
                now);
        projects.put(cacheKey, loaded);
        return loaded;
    }

    /** A project's effective settings, or none when they cannot be read: the cache keeps serving uncapped rather than
     *  failing the build, and says so. */
    private UnaryOperator<String> policy(String tenant, String project) {
        try {
            return policies.of(tenant, project);
        } catch (IOException | RuntimeException unreadable) {
            LOGGER.log(System.Logger.Level.WARNING, "the settings of cache project " + tenant + "/" + project
                    + " could not be read; it is served without a size cap or expiry until they can", unreadable);
            return _ -> null;
        }
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

    /** Enforce one project's size cap in bounded passes: the total is counted by streaming, and each round retains only
     *  the {@link #RECLAIM_BATCH} entries it will delete, through the bounded selection the free-space reclaim uses,
     *  since a write-triggered sort of a project's whole entry set would let a client choose its size. */
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
        // Each pass streams entries a project at a time through a bounded selection of the RECLAIM_BATCH coldest,
        // deletes them until the free target is met, then re-scans: the peak is one batch whatever the store's size.
        while (low()) {
            Selection selection = new Selection(RECLAIM_BATCH, true);
            Map<CacheStorage.Stored, CacheStorage> owners = new IdentityHashMap<>();
            for (String tenant : tenants()) {
                CacheStorage scoped = scope(tenant);
                projects(scoped, project -> sweep(scoped, project, entry -> {
                    // Deleted through the storage that enumerated it: a Stored's token is opaque and scope-relative.
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

    /** The bounded selection every sweep drives: the {@code k} entries that sort first under the eviction order -
     *  coldest for least-recently-used, warmest for most-recently-used - through a heap of at most {@code k}. A
     *  {@link Consumer}, so it is handed straight to the paged enumeration and can span several. */
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
                    // A backend hiccup must not kill the reaper, which would silently stop ttl and free-space eviction.
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
                UnaryOperator<String> config = policy(tenant, name);
                Duration ttl = ttl(config, name);
                if (ttl != null) {
                    expire(store, name, ttl);
                }
                // The size cap is otherwise applied only on a write; re-applied here so an idle over-cap project - its
                // cap lowered, or the cache enabled over existing entries - converges on the reaper's clock.
                long limit = size(config, name);
                if (limit > 0) {
                    evict(store, new Project(name, limit, ProjectPolicy.lru(config.apply(ProjectPolicy.LRU)),
                            clock.instant()));
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

    private static Duration ttl(UnaryOperator<String> config, String project) {
        try {
            return ProjectPolicy.ttl(config.apply(ProjectPolicy.TTL));
        } catch (IllegalArgumentException malformed) {
            // A malformed ttl is logged and that project's expiry skipped rather than read as "no ttl"; the catalogue
            // refuses such a value on every write path, so this guards one written into the store by hand.
            LOGGER.log(System.Logger.Level.WARNING, malformed.getMessage() + " for cache project " + project
                    + "; stale entries are not expired until the value is fixed");
            return null;
        }
    }

    private static long size(UnaryOperator<String> config, String project) {
        try {
            return ProjectPolicy.size(config.apply(ProjectPolicy.SIZE));
        } catch (IllegalArgumentException malformed) {
            // A malformed cap is logged rather than silently read as unlimited; it still parses as no cap, so the cache
            // keeps serving.
            LOGGER.log(System.Logger.Level.WARNING, malformed.getMessage() + " for cache project " + project
                    + "; the size cap is disabled until the value is fixed");
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
