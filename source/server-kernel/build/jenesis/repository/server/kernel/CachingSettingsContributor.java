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
                new Setting(StoreCache.TTL_SETTING, "Operations", "Store cache ttl",
                        "How long a node serves a credential, a settings document, a ceiling or a tenant list it has "
                                + "already read before asking the store again. On the node that made a write the cache "
                                + "is exact regardless; across nodes this is the bound on how stale another node's "
                                + "write may show. 0 switches caching off and every read is the store's; clear a "
                                + "node's caches at once with POST /api/admin/caches/clear, which clears that node. "
                                + "Credentials are the exception and no longer need this turned down: they carry a "
                                + "deployment epoch that every grant and revocation bumps, so a revoked key stops "
                                + "everywhere within seconds whatever this says - see the auth cache ttl.",
                        Setting.Kind.DURATION, StoreCache.DEFAULT_TTL_TEXT, false),
                new Setting(MissMemory.TTL_SETTING, "Operations", "Miss memory ttl",
                        "How long a node remembers that a coordinate it looked for was not there, and answers the "
                                + "same probe from memory instead of reading the store again - a build tool asking "
                                + "for a version range, a missing snapshot or an optional classifier asks the same "
                                + "question of the same repositories many times in a row. Only an absence is "
                                + "remembered, never a hit; a publish, release or delete on the node forgets the key "
                                + "at once, so the node that wrote serves what it wrote; another node serves a fresh "
                                + "publish once its entry expires, which is what this bounds. Node-local, held to a "
                                + "hundred thousand keys, dropped with the caches by POST /api/admin/caches/clear. 0 "
                                + "switches it off and every probe is the store's.",
                        Setting.Kind.DURATION, MissMemory.DEFAULT_TTL_TEXT, false),
                new Setting(DocumentMemory.TTL_SETTING, "Operations", "Document memory ttl",
                        "How long a node serves a listing it has already read - a packument, a Simple page, a "
                                + "maven-metadata.xml, a Packages file, a tag list - from memory before reading the "
                                + "store again, so a burst of builds starting at once costs the store one read per "
                                + "document rather than one per build. Only listings up to a megabyte are kept, "
                                + "never an artifact's bytes and never a pointer, since a hold must land on every "
                                + "node at once; a write on the node forgets the document, and another node serves "
                                + "a fresh publish in its listing once its copy expires, which is what this bounds. "
                                + "On several nodes that bound is what an operator is agreeing to: for up to this "
                                + "long a node can serve a listing without a version a peer has already accepted, "
                                + "so a client that publishes through one node and reads through another may not "
                                + "see its own release yet. It is a stale read and never a lost write - the base of "
                                + "a listing's compare-and-set is read past this memory for exactly that reason. "
                                + "Node-local, held to sixty-four megabytes, dropped with the caches by POST "
                                + "/api/admin/caches/clear. 0 switches it off.",
                        Setting.Kind.DURATION, DocumentMemory.DEFAULT_TTL_TEXT, false),
                new Setting(Authorization.CACHE_TTL_SETTING, "Operations", "Credential cache ttl",
                        "How long a node serves a credential's documents before asking the store again. Longer than "
                                + "the store cache ttl on purpose: an authorization happens on every request, and "
                                + "the auth epoch - one small document every credential mutation bumps, re-read every "
                                + "few seconds - is what bounds how long a revocation takes to reach another node, "
                                + "so this bounds only how often a busy node re-reads a credential it already has. "
                                + "Applies on restart.",
                        Setting.Kind.DURATION, Authorization.DEFAULT_CACHE_TTL_TEXT, false),
                new Setting(StoredCounter.FLUSH_SETTING, "Maintenance", "Counter flush cadence",
                        "How long a node holds the quota and folder-size deltas its publishes produce before folding "
                                + "them into one compare-and-set per counter; the node itself counts them at once. "
                                + "0 writes every delta as it happens, one compare-and-set per publish per counter.",
                        Setting.Kind.DURATION, StoredCounter.DEFAULT_FLUSH_TEXT, false));
    }
}
