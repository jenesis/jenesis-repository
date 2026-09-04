package build.jenesis.repository.server;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.QuotaArtifactStore;
import build.jenesis.repository.store.Tenants;
import build.jenesis.repository.store.TenantsProvider;

/**
 * Publishes this node's {@link NodeFingerprint} to the shared store on a heartbeat. A stable node id is
 * derived once - the {@code jenreg.consistency.node-id} setting if given, else the hostname, else a generated
 * per-process id - and held as <em>instance</em> state on this bean (never a mutable static), so a fleet of in-process
 * nodes in a test each carry their own identity. A daemon scheduler re-publishes every heartbeat interval, so a node's
 * liveness (and its current config and tenant generation, cursor position and sampled counters) stays fresh for the
 * fleet to compare against; the write is a single compare-and-set on this node's own key, so it never contends with
 * another node.
 *
 * <p>The heartbeat runs on this bean's own daemon scheduler, which it starts and closes: it is one of the two
 * periodic drivers core/AGENTS.md names as keeping a private timer rather than riding the composition root's scheduler,
 * because it owns its own lifecycle as a free-core bean.
 *
 * <p>The fingerprint is cheap to build: the config generation is a hash over the must-match settings <em>and the
 * tenant set</em>, read through the {@link Tenants} view on every heartbeat (a directory read, never a scan) so a node
 * that missed a config or tenant change diverges rather than reporting the generation it booted with; the counters
 * come from the store's own in-memory meter where present. Publishing is <strong>opt-in</strong> per deployment via
 * {@code jenreg.consistency.enabled} - a single-node deployment writes nothing into an otherwise-clean store - and
 * best-effort: a write refused by a read-only deployment, or a transient store error, is logged at debug and retried
 * on the next heartbeat rather than failing the node. The derived-index cursor is not maintained here (there is no
 * background sweep in the core), so it is published as zero with the heartbeat as its advance time - honest for a
 * single hosted node; a distribution that runs a real index sweep publishes its live cursor and freeze time.
 */
public final class NodeFingerprintPublisher implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(NodeFingerprintPublisher.class);

    /** The settings that must be byte-for-byte identical on every node, so a differing value on any is a real split:
     *  the store backend, the routed and the default tenant and repository, the operator tenant, the authorization
     *  mode and the read-only flag. Two editions used to fold two different subsets of this list, so the two peers of
     *  one mechanism disagreed about what a split is; a setting unset on a deployment folds as blank on every node,
     *  which is why the union costs nothing. */
    static final List<String> MUST_MATCH = List.of("jenreg.store", "jenreg.tenant", "jenreg.repository",
            "jenreg.operator-tenant", "jenreg.default-tenant", "jenreg.default-repository", "jenreg.auth",
            "jenreg.read-only");

    private final NodeConsistency consistency;
    private final ArtifactStore store;
    private final Tenants tenants;
    private final boolean enabled;
    private final String nodeId;
    private final Map<String, String> mustMatch;
    private final long heartbeatMillis;
    private final ScheduledExecutorService scheduler;
    /** The last tenant set read successfully, so a transient listing failure folds the set last seen rather than an
     *  empty one that would report a false split. */
    private volatile List<String> lastTenants;

    /** Over a store: the tenant view is resolved through the same {@code TenantsProvider} seam the rest of the core
     *  uses - the single configured tenant with no tenants module installed, the store-backed scopes with one - and the
     *  quota counter is read from the store's meter when it carries one. */
    public NodeFingerprintPublisher(NodeConsistency consistency, ArtifactStore store, UnaryOperator<String> config) {
        this(consistency, store, TenantsProvider.resolve(store, config, configuredTenant(config)), config);
    }

    /** Over an explicit tenant view and no store meter - the seam a test drives two in-process nodes through. */
    public NodeFingerprintPublisher(NodeConsistency consistency, Tenants tenants, UnaryOperator<String> config) {
        this(consistency, null, tenants, config);
    }

    private NodeFingerprintPublisher(NodeConsistency consistency, ArtifactStore store, Tenants tenants,
                                     UnaryOperator<String> config) {
        this.consistency = Objects.requireNonNull(consistency, "consistency");
        this.store = store;
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        // Opt-in per deployment, like the other operational writers (demo seeding, batch ingestion): a single-node
        // deployment publishes nothing, so it never writes an operational key into an otherwise-clean store layout; a
        // multi-node deployment sets jenreg.consistency.enabled=true so its nodes publish and can be compared.
        this.enabled = "true".equalsIgnoreCase(String.valueOf(config.apply("jenreg.consistency.enabled")));
        this.nodeId = resolveNodeId(config);
        this.mustMatch = mustMatch(config);
        this.lastTenants = List.of(configuredTenant(config));
        this.heartbeatMillis = Math.max(1000L, millis(config, "jenreg.consistency.heartbeat",
                consistency.settings().sweepIntervalMillis()));
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "jenesis-consistency-" + nodeId);
            thread.setDaemon(true);
            return thread;
        });
    }

    /** This node's stable id, so a caller (the admin API) can name the node it is running on. */
    public String nodeId() {
        return nodeId;
    }

    /** Whether this node publishes its fingerprint - opt-in via {@code jenreg.consistency.enabled}. */
    public boolean enabled() {
        return enabled;
    }

    /** Publish once immediately, then on every heartbeat - Spring's {@code initMethod}. A disabled deployment does
     *  nothing, so it never writes into an otherwise-clean store; the read surfaces still work and report no node. */
    public void start() {
        if (!enabled) {
            return;
        }
        publishQuietly();
        scheduler.scheduleAtFixedRate(this::publishQuietly, heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS);
    }

    /** Build and publish this node's current fingerprint; never throws (best-effort heartbeat). */
    public void publishQuietly() {
        try {
            consistency.publish(fingerprint());
        } catch (IOException | RuntimeException best) {
            // A read-only deployment refuses the write, or the store hiccuped - retry on the next heartbeat rather than
            // fail the node. Consistency detects and reports; it never blocks the node it runs on.
            LOGGER.debug("consistency fingerprint publish skipped for node {}: {}", nodeId, best.toString());
        }
    }

    /** This node's current fingerprint: the config and tenant generation folded now, the counters read cheaply now. */
    NodeFingerprint fingerprint() {
        long now = System.currentTimeMillis();
        List<String> tenantSet = tenantSet();
        long generation = NodeFingerprint.configGeneration(mustMatch, tenantSet);
        return new NodeFingerprint(nodeId, now, now, 0L, "", generation, 0L, quotaUsed(), tenantSet.size(), Map.of());
    }

    /** The current tenant set - a cheap directory read - or the last one read when the directory cannot be listed,
     *  so the fold stays stable across a transient failure rather than reporting a split that is not there. */
    private List<String> tenantSet() {
        try {
            List<String> listed = tenants.list();
            lastTenants = listed;
            return listed;
        } catch (IOException | RuntimeException unreadable) {
            LOGGER.debug("consistency tenant-set read kept the last set seen: {}", unreadable.toString());
            return lastTenants;
        }
    }

    /** The bytes counted against the quota where the store meters them, else zero - a counter already in memory, never
     *  a scan. */
    private long quotaUsed() {
        try {
            return store instanceof QuotaArtifactStore quota ? quota.used() : 0L;
        } catch (IOException unreadable) {
            return 0L;
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    private static Map<String, String> mustMatch(UnaryOperator<String> config) {
        Map<String, String> settings = new TreeMap<>();
        for (String key : MUST_MATCH) {
            String value = config.apply(key);
            settings.put(key, value == null ? "" : value);
        }
        return settings;
    }

    private static String configuredTenant(UnaryOperator<String> config) {
        String tenant = config.apply("jenreg.tenant");
        return tenant == null || tenant.isBlank() ? "default" : tenant;
    }

    /** A stable node id: the explicit setting, else the hostname, else a generated per-process id (with a warning that
     *  a stable id is preferable so a restart does not leave an orphan fingerprint object behind). */
    private static String resolveNodeId(UnaryOperator<String> config) {
        String configured = config.apply("jenreg.consistency.node-id");
        if (configured != null && !configured.isBlank()) {
            return sanitize(configured.trim());
        }
        try {
            String host = InetAddress.getLocalHost().getHostName();
            if (host != null && !host.isBlank()) {
                return sanitize(host.trim());
            }
        } catch (UnknownHostException noHost) {
            // fall through to a generated id
        }
        String generated = "node-" + Long.toHexString(UUID.randomUUID().getMostSignificantBits() & 0xffffffffL);
        LOGGER.warn("SECURITY/OPS: jenreg.consistency.node-id is unset and the hostname is unavailable, so this node "
                + "uses a generated per-process id ({}). Set a stable jenreg.consistency.node-id so a restart re-uses "
                + "the same identity instead of leaving an orphan fingerprint behind.", generated);
        return generated;
    }

    /** Reduce an id to a traversal-free key segment, so it is safe as the node's fingerprint key. */
    private static String sanitize(String id) {
        String cleaned = id.replaceAll("[^A-Za-z0-9_.-]", "-");
        return cleaned.isBlank() || cleaned.equals(".") || cleaned.equals("..") ? "node" : cleaned;
    }

    private static long millis(UnaryOperator<String> config, String key, long fallback) {
        String value = config.apply(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException unparseable) {
            return fallback;
        }
    }
}
