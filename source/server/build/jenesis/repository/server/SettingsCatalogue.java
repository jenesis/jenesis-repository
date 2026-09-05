package build.jenesis.repository.server;

import module java.base;

/**
 * The core's runtime-setting catalogue (#146): the product's built-in, runtime-tunable configuration dials,
 * declared once here so every key the code reads through the effective-config lookup ({@code config.apply("<key>")})
 * has a single, discoverable home. This is the core analogue of the downstream {@code SettingsContributor} SPI
 * (a heavier, {@code ServiceLoader}-discovered, per-module contributor catalogue): the free product has one built-in
 * catalogue and no plugin-contributed settings, so a single {@link #ALL} list is the whole surface.
 *
 * <p>This catalogue is the declaration: a config key the code reads is either a runtime setting declared here or a
 * deploy-time bootstrap key, and {@link #keys()} is how a caller asks which. A key added with no home is stranded -
 * unreachable without hand-editing a store object - so adding one here is part of adding the read.
 *
 * <p>Deploy-time / bootstrap keys are deliberately NOT here: the store backend and its credentials (the
 * {@code JENESIS_*} env), the fixed-tenant routing ({@code jenreg.tenant}), the auth and read-only
 * deployment flags, and the per-node consistency enable/identity ({@code jenreg.consistency.enabled} /
 * {@code jenreg.consistency.node-id}, which are per-instance and cannot be one fleet-shared store setting). Those are
 * bound at startup from the environment/file configuration, not runtime-editable dials, and are the test's bootstrap
 * allowlist. A declared dial nobody reads is a dead dial, not a stranded key, and is out of the guard's scope.
 */
public final class SettingsCatalogue {

    /** One declared runtime setting: its effective-config key and a one-line human description. */
    public record Setting(String key, String description) {
    }

    /** Every runtime-tunable setting the core reads through the effective-config lookup. */
    public static final List<Setting> ALL = List.of(
            // --- Multi-node consistency dials (/ #146): the heartbeat cadence and the divergence-detection
            //     windows NodeFingerprintPublisher and NodeConsistency read through the effective-config lookup. The
            //     enable toggle and the node id are per-node deploy-time bootstrap, not dials, so they are on the
            //     test's bootstrap allowlist, not here. ---
            new Setting("jenreg.consistency.heartbeat",
                    "Milliseconds between this node's fingerprint publishes (the consistency heartbeat interval)"),
            new Setting("jenreg.consistency.staleness-window",
                    "Milliseconds a node may lag and still count as benign lag rather than stuck-diverged"),
            new Setting("jenreg.consistency.sweep-interval",
                    "Milliseconds between consistency sweeps (also the heartbeat fallback when the heartbeat is unset)"),
            new Setting("jenreg.consistency.sweep-intervals",
                    "How many frozen sweep intervals before a live-but-frozen node is reported stuck-diverged"),
            new Setting("jenreg.consistency.dead-after",
                    "Milliseconds after a node's last heartbeat before it is treated as dead and no longer compared"),

            // --- Pull-through proxy negative cache (source/proxy). ---
            new Setting("proxy-miss-ttl",
                    "How long a proxy negative-cache (upstream miss) entry is honoured before a re-fetch is allowed"),

            // --- Credential usage tracking (source/usage). ---
            new Setting("track-key-usage",
                    "Whether the batching key-usage tracker records each credential's last use and running count"),

            // --- Import-edge screen, both halves (ImportEdgeController / ImportScreen). ---
            new Setting("block-private-import-hosts",
                    "Whether the import edge refuses a migration URL that is plaintext http, or whose host resolves "
                            + "to a private/loopback address - one dial covering both, since a migration is walked "
                            + "with the upstream credentials attached"),

            // --- Strictly-opt-in anonymous role (WANON.1). ---
            new Setting("anonymous-rights",
                    "The rights a keyless caller is granted under an enforcing deployment; blank (default) grants none"),

            // --- Garbage-collection dials (source/gc). ---
            new Setting("jenreg.gc.stride",
                    "Mark-sweep GC batch stride (number of objects scanned per pass)"),
            new Setting("jenreg.gc.grace",
                    "Grace period an unreferenced object survives before mark-sweep GC may reclaim it"),

            // --- Artifact-walk dials (source/walk). ---
            new Setting("jenreg.walk.checkpoint",
                    "Artifact-walk checkpoint interval (entries between resumable cursor writes)"),
            new Setting("jenreg.walk.segments",
                    "Artifact-walk parallel segment count"),
            new Setting("jenreg.walk.ttl",
                    "Seconds an artifact-walk cursor stays resumable before it expires"),
            new Setting("jenreg.clock.skew",
                    "An ISO-8601 offset added to this node's clock for every stamp it stores - unset in a deployment; "
                            + "a fleet test sets it on one node to prove the others tolerate a peer whose clock runs "
                            + "ahead"));

    private SettingsCatalogue() {
    }

    /** The declared setting keys, for a caller that only needs the key set (the union of every declared key). */
    public static Set<String> keys() {
        Set<String> keys = new TreeSet<>();
        for (Setting setting : ALL) {
            keys.add(setting.key());
        }
        return keys;
    }
}
