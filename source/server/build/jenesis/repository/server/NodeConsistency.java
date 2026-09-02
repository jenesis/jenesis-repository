package build.jenesis.repository.server;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import tools.jackson.databind.json.JsonMapper;

/**
 * The multi-node consistency engine: it publishes this node's {@link NodeFingerprint} to the shared
 * {@link ArtifactStore} and reads every node's fingerprint back to build a {@link ConsistencyReport}. Fingerprints live
 * under the fixed, internal key prefix {@code consistency/nodes/<id>} - the same store every node already shares - so a
 * node joins the comparison simply by publishing, and the check needs no registry, no coordinator and no cross-node RPC.
 *
 * <p>The read is <strong>cheap by construction</strong>: {@link #report} lists exactly the {@code consistency/nodes/}
 * prefix (one directory of node ids, never the millions-entry artifact namespace) and reads one small object per node -
 * a cost bounded by the node count, not the store size. It never walks {@code blobs/} or any tenant space, so a
 * consistency check never turns into a full scan. Each node owns its own key, so a publish is an uncontended
 * compare-and-set on that one object; a node reads with {@link ArtifactStore#readVersioned} and writes with
 * {@link ArtifactStore#writeVersioned} on its own key's token, so a stale-token retry only ever races the same node.
 */
public final class NodeConsistency {

    /** The internal key prefix every node publishes its fingerprint under - a fixed, hidden operational space beside
     *  the store's other operational keys ({@code quota/}, {@code walks/}), never an artifact space. */
    public static final String PREFIX = "consistency/nodes/";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ArtifactStore store;
    private final ConsistencyReport.Settings settings;

    public NodeConsistency(ArtifactStore store, ConsistencyReport.Settings settings) {
        this.store = Objects.requireNonNull(store, "store");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public ConsistencyReport.Settings settings() {
        return settings;
    }

    /** The consistency-check tuning read from the {@code jenreg.consistency.*} settings, falling back to
     *  {@link ConsistencyReport.Settings#defaults()} for any key an operator leaves unset - the one place the dial
     *  names are resolved, shared by the publisher and the advisor so both run under the same window. */
    public static ConsistencyReport.Settings settingsFrom(UnaryOperator<String> config) {
        ConsistencyReport.Settings defaults = ConsistencyReport.Settings.defaults();
        return new ConsistencyReport.Settings(
                millis(config, "jenreg.consistency.staleness-window", defaults.stalenessWindowMillis()),
                millis(config, "jenreg.consistency.sweep-interval", defaults.sweepIntervalMillis()),
                (int) millis(config, "jenreg.consistency.sweep-intervals", defaults.sweepIntervals()),
                millis(config, "jenreg.consistency.dead-after", defaults.deadAfterMillis()),
                millis(config, "jenreg.consistency.forget-after", defaults.forgetAfterMillis()));
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

    /** Publish (or update) this node's fingerprint to the shared store under its own key, a compare-and-set on that
     *  one object so a concurrent re-publish by the same node never loses. Returns {@code true} when it landed. */
    public boolean publish(NodeFingerprint fingerprint) throws IOException {
        String key = PREFIX + fingerprint.nodeId();
        Optional<ArtifactStore.Versioned> current = store.readVersioned(key);
        Object token = current.map(ArtifactStore.Versioned::token).orElse(null);
        byte[] body = JSON.writeValueAsBytes(toMap(fingerprint));
        boolean written = store.writeVersioned(key, body, token);
        if (fingerprint.heartbeatMillis() - lastReapMillis >= REAP_INTERVAL_MILLIS) {
            lastReapMillis = fingerprint.heartbeatMillis();
            reap(fingerprint.heartbeatMillis());
        }
        return written;
    }

    /** The most fingerprints a report reads and a reap examines: a fleet is tens of nodes, and a listing past this
     *  is the accumulation the reap exists to prevent - the report says it was cut short rather than reading on. */
    public static final int MAX_NODES = 1000;

    /** How often a publishing node reaps the fingerprints of nodes silent past the forget-after window. */
    private static final long REAP_INTERVAL_MILLIS = Duration.ofHours(1).toMillis();

    private volatile long lastReapMillis;

    /**
     * Delete the fingerprint of every node silent for longer than {@link ConsistencyReport.Settings#forgetAfterMillis}
     * - a host that left the fleet, a pod that was rescheduled under a new name - so the listing a report reads holds
     * the nodes that exist and not every node that ever did. Run by a publishing node on its heartbeat, never by a
     * read; answers how many fingerprints were forgotten.
     */
    public int reap(long now) {
        int forgotten = 0;
        for (String id : ids()) {
            try {
                Optional<NodeFingerprint> fingerprint = store.readVersioned(PREFIX + id)
                        .map(versioned -> fromBytes(id, versioned.content()));
                if (fingerprint.isPresent()
                        && fingerprint.get().heartbeatAgeMillis(now) > settings.forgetAfterMillis()) {
                    store.delete(PREFIX + id);
                    forgotten++;
                }
            } catch (IOException | RuntimeException skip) {
            }
        }
        return forgotten;
    }

    /** The first {@link #MAX_NODES} node ids under the prefix - one bounded page, never the whole listing. */
    private List<String> ids() {
        List<String> ids = new ArrayList<>();
        store.page(PREFIX.substring(0, PREFIX.length() - 1), "", MAX_NODES, ids::add);
        return ids;
    }

    /** Read every published fingerprint and classify the fleet as of {@code now}. Lists only the node prefix and reads
     *  one small object per node - never a scan of the artifact namespace. A node whose object is missing or unparseable
     *  is skipped (it simply does not take part) rather than failing the whole read. */
    public ConsistencyReport report(long now) {
        List<NodeFingerprint> fingerprints = new ArrayList<>();
        List<String> ids = ids();
        for (String id : ids) {
            try {
                store.readVersioned(PREFIX + id)
                        .map(versioned -> fromBytes(id, versioned.content()))
                        .ifPresent(fingerprints::add);
            } catch (IOException | RuntimeException skip) {
                // A single unreadable/garbled node object must not fail the whole check - it just does not participate.
            }
        }
        return ConsistencyReport.analyze(fingerprints, now, settings).truncated(ids.size() >= MAX_NODES);
    }

    private static NodeFingerprint fromBytes(String id, byte[] content) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = JSON.readValue(content, Map.class);
            return fromMap(id, map);
        } catch (RuntimeException garbled) {
            return null;
        }
    }

    /** The fingerprint as a JSON-serialisable ordered map. */
    static Map<String, Object> toMap(NodeFingerprint fingerprint) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("nodeId", fingerprint.nodeId());
        map.put("heartbeatMillis", fingerprint.heartbeatMillis());
        map.put("cursorAdvancedMillis", fingerprint.cursorAdvancedMillis());
        map.put("indexCursor", fingerprint.indexCursor());
        map.put("snapshotVersion", fingerprint.snapshotVersion());
        map.put("configGeneration", fingerprint.configGeneration());
        map.put("inventoryTotal", fingerprint.inventoryTotal());
        map.put("quotaUsed", fingerprint.quotaUsed());
        map.put("pointers", new TreeMap<>(fingerprint.pointers()));
        return map;
    }

    /** Rebuild a fingerprint from its stored map; the stored {@code nodeId} is trusted only as a fallback - the key's
     *  {@code id} is authoritative, so a mislabelled object cannot masquerade as another node. */
    @SuppressWarnings("unchecked")
    static NodeFingerprint fromMap(String id, Map<String, Object> map) {
        Map<String, String> pointers = new LinkedHashMap<>();
        Object raw = map.get("pointers");
        if (raw instanceof Map<?, ?> pointerMap) {
            pointerMap.forEach((key, value) -> pointers.put(String.valueOf(key), String.valueOf(value)));
        }
        return new NodeFingerprint(id, asLong(map.get("heartbeatMillis")), asLong(map.get("cursorAdvancedMillis")),
                asLong(map.get("indexCursor")), asString(map.get("snapshotVersion")),
                asLong(map.get("configGeneration")), asLong(map.get("inventoryTotal")), asLong(map.get("quotaUsed")),
                asLong(map.get("tenantCount")), pointers);
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
