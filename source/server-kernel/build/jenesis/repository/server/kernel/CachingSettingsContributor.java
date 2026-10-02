package build.jenesis.repository.server.kernel;

import module java.base;

import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;
import build.jenesis.repository.store.DocumentMemory;
import build.jenesis.repository.store.MissMemory;
import build.jenesis.repository.store.StoreCache;
import build.jenesis.repository.store.StoredCounter;

/**
 * Describes the two dials behind what a request costs the store, surfaced here because this is where the settings
 * catalogue is assembled: how long a node may serve a small document it has already read, and how long it may
 * hold a counter's delta before folding it into one compare-and-set. Both render their key and default straight off
 * the constants the mechanisms read, so the catalogue and the code cannot drift.
 */
public final class CachingSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting(StoreCache.TTL_SETTING, "Caches", "Store cache ttl",
                        "How long a node serves a credential, a settings document, a ceiling or a tenant list it has "
                                + "already read before asking the store again. A write is exact on the node that made "
                                + "it; across nodes this bounds how stale another node's write may show. Credentials "
                                + "are the exception: a revoked key stops everywhere within seconds whatever this "
                                + "says. POST /api/admin/caches/clear clears a node's caches at once. Zero switches "
                                + "caching off.",
                        Setting.Kind.DURATION, StoreCache.DEFAULT_TTL_TEXT, false).advanced(),
                new Setting(MissMemory.TTL_SETTING, "Caches", "Miss memory ttl",
                        "How long a node remembers that a coordinate it looked for was not there, and answers the same "
                                + "probe from memory - a build tool asks for a missing snapshot or an optional "
                                + "classifier many times in a row. Only an absence is remembered, and a write on the "
                                + "node forgets the key at once, so this bounds how long another node may still answer "
                                + "not found for a fresh publish. POST /api/admin/caches/clear drops it. Zero switches "
                                + "it off.",
                        Setting.Kind.DURATION, MissMemory.DEFAULT_TTL_TEXT, false).advanced(),
                new Setting(DocumentMemory.TTL_SETTING, "Caches", "Document memory ttl",
                        "How long a node serves a listing it has already read - a packument, a Simple page, a Packages "
                                + "file, a tag list - from memory, so a burst of builds costs the store one read per "
                                + "document rather than one per build. A write on the node forgets the document; "
                                + "across nodes, this is how long a client that publishes through one node may not yet "
                                + "see its release listed by another. A stale read, never a lost write. Zero switches "
                                + "it off.",
                        Setting.Kind.DURATION, DocumentMemory.DEFAULT_TTL_TEXT, false).advanced(),
                new Setting(Authorization.CACHE_TTL_SETTING, "Caches", "Credential cache ttl",
                        "How long a node serves a credential's documents before asking the store again. Longer than "
                                + "the store cache ttl on purpose: a revocation reaches every node within seconds "
                                + "through the auth epoch whatever this says, so this bounds only how often a busy "
                                + "node re-reads a credential it already holds. Applies on restart.",
                        Setting.Kind.DURATION, Authorization.DEFAULT_CACHE_TTL_TEXT, false).advanced(),
                new Setting(StoredCounter.FLUSH_SETTING, "Maintenance", "Counter flush cadence",
                        "How long a node holds the quota and folder-size deltas its publishes produce before folding "
                                + "them into one compare-and-set per counter; the node itself counts them at once. "
                                + "Zero writes every delta as it happens, one compare-and-set per publish per counter.",
                        Setting.Kind.DURATION, StoredCounter.DEFAULT_FLUSH_TEXT, false).advanced());
    }
}
