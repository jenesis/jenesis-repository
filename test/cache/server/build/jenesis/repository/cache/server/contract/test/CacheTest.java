package build.jenesis.repository.cache.server.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cache.server.Cache;
import build.jenesis.repository.cache.server.Cache.Allowed;
import build.jenesis.repository.cache.server.Cache.Outcome;
import build.jenesis.repository.cache.server.Cache.Rejected;
import build.jenesis.repository.cache.server.Cache.Resolution;
import build.jenesis.repository.cache.storage.CacheStorage;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.cache.storage.testkit.CacheStorages;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.spi.KeyUsageTracker;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.walk.Traversal;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the multi-tenant cache logic against a real filesystem store, driving {@link Cache} exactly as
 * {@link build.jenesis.repository.cache.server.CacheController} does (the {@code get}/{@code head}/{@code put} helpers
 * mirror its request flow and status mapping). The blobs are {@code <root>/<tenant>/<project>/...} and
 * the credentials live in the shared {@link Authorization} store under {@code
 * <root>/auth/<tenant>/<sha256(key)>/}; a key is {@code jenk_<tenant>.<secret><checksum>}. Covers: blob round-trip,
 * miss, read/write and wildcard grants, tenant and project isolation, the default-project fallback and
 * its required mode, the trial bootstrap key, traversal safety, grant revocation/narrowing,
 * size/ttl/free-space eviction, per-tenant metrics, HEAD.
 */
public class CacheTest {

    private static final String ACME_RW = Authorization.mint("acme");
    private static final String ACME_RO = Authorization.mint("acme");
    private static final String ACME_NONE = Authorization.mint("acme");
    private static final String ACME_OTHER = Authorization.mint("acme");
    private static final String ACME_SECRET = Authorization.mint("acme");
    private static final String ACME_W = Authorization.mint("acme");
    private static final String ACME_K = Authorization.mint("acme");
    private static final String GLOBEX_RW = Authorization.mint("globex");
    private static final String GLOBEX_SECRET = Authorization.mint("globex");
    private static final String GHOST_SECRET = Authorization.mint("ghost");

    /** How many times {@link #await} re-reads before giving up. The bound is reads attempted, never a reading of the
     *  wall clock: a loaded machine must make the wait longer, not weaker. The old five-second deadline expired
     *  <em>silently</em>, so a reclaim that had not run yet fell through into the assertion below it and reported
     *  "the entry still exists" - the reaper's verdict - for what was really a machine too busy to have reaped. */
    private static final int READS = 400;

    @TempDir
    private Path root;

    private Cache cache() {
        return cache(new SimpleMeterRegistry());
    }

    private Cache cache(MeterRegistry registry) {
        return new Cache(CacheStorages.filesystem(root), authorization(), 1L << 31, 256, null, 0, 0, "default", false, null, "default", registry);
    }

    private static double count(MeterRegistry registry, String outcome) {
        return registry.get("jenreg.cache.requests").tags("tenant", "acme", "project", "demo", "outcome", outcome).counter().count();
    }

    private Authorization authorization() {
        return Authorization.enforcing(ArtifactStoreProvider.resolve("filesystem", cfgKey -> "jenreg.filesystem.root".equals(cfgKey) ? root.toString() : null));
    }

    @Test
    public void stores_and_reads_back_an_opaque_blob() throws Exception {
        Cache cache = cache();
        credential("acme", ACME_RW, "*=cache:read,cache:write");
        assertThat(put(cache, "demo", ACME_RW, "aa", "bb", "payload".getBytes(UTF_8))).isEqualTo(201);
        assertThat(get(cache, "demo", ACME_RW, "aa", "bb")).isEqualTo(200);
        assertThat(read(cache, "demo", ACME_RW, "aa", "bb")).isEqualTo("payload");
        assertThat(cached().resolve("acme").resolve("demo").resolve("aa").resolve("bb")).isRegularFile();
    }

    @Test
    public void a_repeated_store_is_declined_as_already_present() throws Exception {
        Cache cache = cache();
        credential("acme", ACME_RW, "*=cache:read,cache:write");
        assertThat(put(cache, "demo", ACME_RW, "aa", "bb", new byte[]{1})).isEqualTo(201);
        assertThat(put(cache, "demo", ACME_RW, "aa", "bb", new byte[]{1})).isEqualTo(204);
    }

    @Test
    public void a_get_on_a_miss_is_404() throws Exception {
        Cache cache = cache();
        credential("acme", ACME_RW, "*=cache:read,cache:write");
        assertThat(get(cache, "demo", ACME_RW, "aa", "bb")).isEqualTo(404);
    }

    @Test
    public void a_read_only_credential_cannot_store() throws Exception {
        Cache cache = cache();
        credential("acme", ACME_RO, "demo=cache:read");
        assertThat(put(cache, "demo", ACME_RO, "aa", "bb", new byte[]{1})).isEqualTo(403);
    }

    @Test
    public void a_credential_without_a_grant_on_the_project_is_forbidden() throws Exception {
        Cache cache = cache();
        credential("acme", ACME_RW, "*=cache:read,cache:write");
        credential("acme", ACME_OTHER, "other=cache:read,cache:write");
        put(cache, "demo", ACME_RW, "aa", "bb", new byte[]{1});
        assertThat(get(cache, "demo", ACME_RW, "aa", "bb")).isEqualTo(200);
        assertThat(get(cache, "demo", ACME_OTHER, "aa", "bb")).isEqualTo(403);
    }

    @Test
    public void a_wildcard_grant_reaches_every_project() throws Exception {
        Cache cache = cache();
        credential("acme", ACME_RW, "*=cache:read,cache:write");
        assertThat(put(cache, "one", ACME_RW, "aa", "bb", new byte[]{1})).isEqualTo(201);
        assertThat(put(cache, "two", ACME_RW, "aa", "bb", new byte[]{1})).isEqualTo(201);
    }

    @Test
    public void tenants_are_isolated_even_for_the_same_project_and_path() throws Exception {
        Cache cache = cache();
        credential("acme", ACME_RW, "*=cache:read,cache:write");
        credential("globex", GLOBEX_RW, "*=cache:read,cache:write");
        put(cache, "demo", ACME_RW, "aa", "bb", "acme-only".getBytes(UTF_8));
        assertThat(get(cache, "demo", ACME_RW, "aa", "bb")).isEqualTo(200);
        assertThat(get(cache, "demo", GLOBEX_RW, "aa", "bb")).isEqualTo(404);
        assertThat(cached().resolve("acme").resolve("demo")).isDirectory();
        assertThat(cached().resolve("globex").resolve("demo")).doesNotExist();
    }

    @Test
    public void a_missing_project_header_falls_back_to_the_default_project() throws Exception {
        Cache cache = cache();
        credential("acme", ACME_RW, "*=cache:read,cache:write");
        assertThat(put(cache, null, ACME_RW, "aa", "01", new byte[]{1})).isEqualTo(201);
        assertThat(cached().resolve("acme").resolve("default").resolve("aa").resolve("01")).isRegularFile();
    }

    @Test
    public void a_missing_project_header_is_rejected_when_the_header_is_required() throws Exception {
        Cache strict = new Cache(CacheStorages.filesystem(root), authorization(), 1L << 31, 256, null, 0, 0, "default", true, null, "default", new SimpleMeterRegistry());
        credential("acme", ACME_RW, "*=cache:read,cache:write");
        assertThat(put(strict, null, ACME_RW, "aa", "01", new byte[]{1})).isEqualTo(400);
        assertThat(put(strict, "demo", ACME_RW, "aa", "01", new byte[]{1})).isEqualTo(201);
    }

    @Test
    public void the_trial_bootstrap_key_grants_the_default_tenant_and_coexists_with_credentials() throws Exception {
        Cache trial = new Cache(CacheStorages.filesystem(root), authorization(), 1L << 31, 256, null, 0, 0, "default", false, "trial-secret", "default", new SimpleMeterRegistry());
        credential("acme", ACME_RW, "*=cache:read,cache:write");
        assertThat(put(trial, "demo", "trial-secret", "aa", "bb", "t".getBytes(UTF_8))).isEqualTo(201);
        assertThat(read(trial, "demo", "trial-secret", "aa", "bb")).isEqualTo("t");
        assertThat(cached().resolve("default").resolve("demo")).isDirectory();
        assertThat(put(trial, "demo", ACME_RW, "cc", "dd", "a".getBytes(UTF_8))).isEqualTo(201);
        assertThat(cached().resolve("acme").resolve("demo")).isDirectory();
    }

    @Test
    public void the_bootstrap_key_cannot_reach_a_non_default_tenant() throws Exception {
        Cache trial = new Cache(CacheStorages.filesystem(root), authorization(), 1L << 31, 256, null, 0, 0, "default", false, "trial-secret", "default", new SimpleMeterRegistry());
        credential("acme", ACME_RW, "*=cache:read,cache:write");
        put(trial, "demo", ACME_RW, "aa", "bb", "acme-secret".getBytes(UTF_8));
        assertThat(get(trial, "demo", ACME_RW, "aa", "bb")).isEqualTo(200);
        assertThat(get(trial, "demo", "trial-secret", "aa", "bb")).isEqualTo(404);
    }

    @Test
    public void a_missing_key_is_unauthorized() {
        assertThat(get(cache(), "demo", null, "aa", "bb")).isEqualTo(401);
    }

    @Test
    public void a_key_without_a_tenant_prefix_is_unauthorized() {
        assertThat(get(cache(), "demo", "no-tenant-prefix", "aa", "bb")).isEqualTo(401);
    }

    @Test
    public void a_key_beginning_with_a_dot_has_an_empty_tenant_and_is_unauthorized() {
        assertThat(get(cache(), "demo", ".evil", "aa", "bb")).isEqualTo(401);
    }

    @Test
    public void a_key_whose_tenant_prefix_is_not_a_safe_name_is_unauthorized() {
        Cache cache = cache();
        assertThat(get(cache, "demo", "a/b.secret", "aa", "bb")).isEqualTo(401);
        assertThat(get(cache, "demo", "a b.secret", "aa", "bb")).isEqualTo(401);
    }

    @Test
    public void a_project_header_that_is_not_a_safe_name_is_rejected() throws Exception {
        Cache cache = cache();
        credential("acme", ACME_RW, "*=cache:read,cache:write");
        assertThat(put(cache, "..", ACME_RW, "aa", "bb", new byte[]{1})).isEqualTo(400);
        assertThat(put(cache, "a/b", ACME_RW, "aa", "bb", new byte[]{1})).isEqualTo(400);
    }

    @Test
    public void a_non_hex_entry_path_is_rejected() throws Exception {
        Cache cache = cache();
        credential("acme", ACME_RW, "*=cache:read,cache:write");
        assertThat(get(cache, "demo", ACME_RW, "zz", "bb")).isEqualTo(400);
        assertThat(get(cache, "demo", ACME_RW, "aa", "gg")).isEqualTo(400);
    }

    @Test
    public void an_unknown_tenant_is_forbidden() {
        assertThat(get(cache(), "demo", GHOST_SECRET, "aa", "bb")).isEqualTo(403);
    }

    @Test
    public void a_credential_is_bound_to_the_tenant_in_its_key() throws Exception {
        Cache cache = cache();
        credential("acme", ACME_SECRET, "*=cache:read,cache:write");
        assertThat(put(cache, "demo", ACME_SECRET, "aa", "bb", new byte[]{1})).isEqualTo(201);
        assertThat(get(cache, "demo", GLOBEX_SECRET, "aa", "bb")).isEqualTo(403);
    }

    @Test
    public void a_write_only_grant_can_store_but_not_read() throws Exception {
        Cache cache = cache();
        credential("acme", ACME_W, "demo=cache:write");
        assertThat(put(cache, "demo", ACME_W, "aa", "bb", new byte[]{1})).isEqualTo(201);
        assertThat(get(cache, "demo", ACME_W, "aa", "bb")).isEqualTo(403);
    }

    @Test
    public void revoking_a_credential_takes_effect_on_the_next_request() throws Exception {
        Cache cache = cache();
        credential("acme", ACME_RW, "*=cache:read,cache:write");
        assertThat(put(cache, "demo", ACME_RW, "aa", "bb", new byte[]{1})).isEqualTo(201);
        assertThat(get(cache, "demo", ACME_RW, "aa", "bb")).isEqualTo(200);
        authorization().revoke("acme", Authorization.hash(ACME_RW));
        assertThat(get(cache, "demo", ACME_RW, "aa", "bb")).isEqualTo(403);
    }

    @Test
    public void narrowing_a_grant_takes_effect_on_the_next_request() throws Exception {
        Cache cache = cache();
        credential("acme", ACME_K, "demo=cache:read,cache:write");
        assertThat(put(cache, "demo", ACME_K, "aa", "bb", new byte[]{1})).isEqualTo(201);
        credential("acme", ACME_K, "demo=cache:read");
        assertThat(put(cache, "demo", ACME_K, "aa", "cc", new byte[]{1})).isEqualTo(403);
        assertThat(get(cache, "demo", ACME_K, "aa", "bb")).isEqualTo(200);
    }

    @Test
    public void evicts_least_recently_modified_until_under_size() throws Exception {
        Cache cache = cache();
        Path project = project("acme", "sized", "size=2500\n");
        credential("acme", ACME_RW, "*=cache:read,cache:write");
        put(cache, "sized", ACME_RW, "aa", "01", new byte[1000]);
        put(cache, "sized", ACME_RW, "aa", "02", new byte[1000]);
        Files.setLastModifiedTime(project.resolve("aa").resolve("01"), FileTime.from(Instant.now().minusSeconds(300)));
        Files.setLastModifiedTime(project.resolve("aa").resolve("02"), FileTime.from(Instant.now().minusSeconds(60)));
        put(cache, "sized", ACME_RW, "aa", "03", new byte[1000]);
        await("the least-recently-modified entry aa/01 to be evicted",
                () -> !Files.exists(project.resolve("aa").resolve("01")));
        assertThat(project.resolve("aa").resolve("01")).doesNotExist();
        assertThat(project.resolve("aa").resolve("02")).isRegularFile();
        assertThat(project.resolve("aa").resolve("03")).isRegularFile();
    }

    @Test
    public void a_read_refreshes_recency_so_the_read_entry_survives_eviction() throws Exception {
        Cache cache = cache();
        Path project = project("acme", "sized", "size=2500\n");
        credential("acme", ACME_RW, "*=cache:read,cache:write");
        put(cache, "sized", ACME_RW, "aa", "01", new byte[1000]);
        put(cache, "sized", ACME_RW, "aa", "02", new byte[1000]);
        // The read entry's stamp is older than the touch window, so the read renews it; inside the window a read
        // leaves the stamp alone by design, and the entry would be the older of the two. The read comes from another
        // node: the one that stored the entry remembers the store and would trust it for the window.
        Files.setLastModifiedTime(project.resolve("aa").resolve("01"), FileTime.from(Instant.now().minus(Duration.ofHours(7))));
        Files.setLastModifiedTime(project.resolve("aa").resolve("02"), FileTime.from(Instant.now().minusSeconds(60)));
        assertThat(get(cache(), "sized", ACME_RW, "aa", "01")).isEqualTo(200);
        put(cache, "sized", ACME_RW, "aa", "03", new byte[1000]);
        await("aa/02, the entry no read refreshed, to be evicted",
                () -> !Files.exists(project.resolve("aa").resolve("02")));
        assertThat(project.resolve("aa").resolve("02")).doesNotExist();
        assertThat(project.resolve("aa").resolve("01")).isRegularFile();
        assertThat(project.resolve("aa").resolve("03")).isRegularFile();
    }

    @Test
    public void reaper_evicts_entries_idle_longer_than_ttl() throws Exception {
        Path project = project("acme", "reaped", "ttl=PT10S\n");
        credential("acme", ACME_RW, "*=cache:read,cache:write");
        Cache reaped = new Cache(CacheStorages.filesystem(root), authorization(), 1L << 31, 256, Duration.ofMillis(100), 0, 0, "default", false, null, "default", new SimpleMeterRegistry());
        reaped.start();
        try {
            put(reaped, "reaped", ACME_RW, "aa", "01", new byte[]{1});
            put(reaped, "reaped", ACME_RW, "aa", "02", new byte[]{1});
            Files.setLastModifiedTime(project.resolve("aa").resolve("01"), FileTime.from(Instant.now().minusSeconds(300)));
            await("the reaper to evict the entry idle longer than the ttl",
                    () -> !Files.exists(project.resolve("aa").resolve("01")));
            assertThat(project.resolve("aa").resolve("01")).doesNotExist();
            assertThat(project.resolve("aa").resolve("02")).isRegularFile();
        } finally {
            reaped.stop();
        }
    }

    @Test
    public void reaper_re_applies_a_lowered_size_cap_to_an_idle_project() throws Exception {
        // The cap was generous when the entries were written, so no write evicted them; then it was lowered - the
        // same shape as enabling the cache over a store that already held entries from before it existed. With no
        // further write to re-trigger size eviction, only the periodic reaper can bring the idle project back under
        // cap: the self-healing bar - convergence over pre-existing/idle data, on the reaper's own clock.
        Path project = project("acme", "sized", "size=100000\n");
        credential("acme", ACME_RW, "*=cache:read,cache:write");
        Cache reaped = new Cache(CacheStorages.filesystem(root), authorization(), 1L << 31, 256, Duration.ofMillis(100), 0, 0, "default", false, null, "default", new SimpleMeterRegistry());
        reaped.start();
        try {
            put(reaped, "sized", ACME_RW, "aa", "01", new byte[1000]);
            put(reaped, "sized", ACME_RW, "aa", "02", new byte[1000]);
            put(reaped, "sized", ACME_RW, "aa", "03", new byte[1000]);
            Files.setLastModifiedTime(project.resolve("aa").resolve("01"), FileTime.from(Instant.now().minusSeconds(300)));
            Files.setLastModifiedTime(project.resolve("aa").resolve("02"), FileTime.from(Instant.now().minusSeconds(200)));
            Files.setLastModifiedTime(project.resolve("aa").resolve("03"), FileTime.from(Instant.now().minusSeconds(100)));
            assertThat(project.resolve("aa").resolve("01")).as("all three fit under the generous cap").isRegularFile();
            Files.writeString(project.resolve("cache.properties"), "size=2500\n");     // lowered, with no further write
            await("the reaper to trim the idle project back under its lowered cap",
                    () -> !Files.exists(project.resolve("aa").resolve("01")));
            assertThat(project.resolve("aa").resolve("01")).as("the reaper trims the idle over-cap project").doesNotExist();
            assertThat(project.resolve("aa").resolve("02")).isRegularFile();
            assertThat(project.resolve("aa").resolve("03")).isRegularFile();
        } finally {
            reaped.stop();
        }
    }

    @Test
    public void reaper_reclaims_globally_oldest_first_across_tenants_when_free_space_is_low() throws Exception {
        Path one = Files.createDirectories(cached().resolve("acme").resolve("one").resolve("aa"));
        Path two = Files.createDirectories(cached().resolve("globex").resolve("two").resolve("bb"));
        Path[] entries = {
                Files.writeString(one.resolve("01"), "x"),
                Files.writeString(one.resolve("02"), "x"),
                Files.writeString(two.resolve("03"), "x"),
                Files.writeString(two.resolve("04"), "x"),
                Files.writeString(one.resolve("05"), "x")};
        for (int index = 0; index < entries.length; index++) {
            Files.setLastModifiedTime(entries[index], FileTime.from(Instant.now().minusSeconds(500L - index * 60L)));
        }
        Cache free = new Cache(CacheStorages.filesystem(root), authorization(), 1L << 31, 256, Duration.ofMillis(100), 600, 0, "default", false, null, "default", new SimpleMeterRegistry()) {
            @Override
            protected long usableSpace() {
                return 1000 - 200L * hexEntries(cached());
            }

            @Override
            protected long totalSpace() {
                return 1000;
            }
        };
        free.start();
        try {
            await("the reaper to reclaim the three globally oldest entries",
                    () -> !Files.exists(entries[2]));
            assertThat(entries[0]).doesNotExist();
            assertThat(entries[1]).doesNotExist();
            assertThat(entries[2]).doesNotExist();
            assertThat(entries[3]).isRegularFile();
            assertThat(entries[4]).isRegularFile();
        } finally {
            free.stop();
        }
    }

    @Test
    public void a_low_disk_put_does_not_enumerate_the_whole_store_on_the_request_thread() throws Exception {
        // A sustained low-disk state must not make every writer pay an O(total entries) enumerate-and-sort of the whole
        // store on the request thread. Seed entries a reclaim would enumerate, force the low-disk state, then assert
        // cannotFit() never runs that enumeration on the calling thread - it reclaims off-request (and the free target
        // is still unmet, so the PUT is still refused).
        Path project = Files.createDirectories(cached().resolve("acme").resolve("proj").resolve("aa"));
        Files.writeString(project.resolve("01"), "x");
        Files.writeString(project.resolve("02"), "x");
        Set<Thread> enumeratingThreads = ConcurrentHashMap.newKeySet();
        RecordingStorage spy = new RecordingStorage(CacheStorages.filesystem(root), enumeratingThreads);
        Cache cache = new Cache(spy, authorization(), 1L << 31, 256, null, 500, 0, "default", false, null, "default",
                new SimpleMeterRegistry()) {
            @Override
            protected long usableSpace() {
                return 0;
            }

            @Override
            protected long totalSpace() {
                return 1000;
            }
        };
        Thread caller = Thread.currentThread();

        boolean cannotFit = cache.cannotFit();

        assertThat(cannotFit).as("the volume is still below the free target, so the store is refused").isTrue();
        assertThat(enumeratingThreads)
                .as("the whole-store enumerate+sort never ran synchronously on the request thread")
                .doesNotContain(caller);
        // And it did run - just off-request, on the background reclaim thread - so space still frees on its own clock.
        await("the free-space reclaim to enumerate off the request thread",
                () -> enumeratingThreads.stream().anyMatch(thread -> thread != caller));
        assertThat(enumeratingThreads).anyMatch(thread -> thread != caller);
    }

    @Test
    public void a_store_is_refused_when_space_cannot_be_reclaimed() throws Exception {
        project("acme", "full", "");
        credential("acme", ACME_RW, "*=cache:read,cache:write");
        Cache full = new Cache(CacheStorages.filesystem(root), authorization(), 1L << 31, 256, null, 500, 0, "default", false, null, "default", new SimpleMeterRegistry()) {
            @Override
            protected long usableSpace() {
                return 0;
            }

            @Override
            protected long totalSpace() {
                return 1000;
            }
        };
        assertThat(put(full, "full", ACME_RW, "aa", "01", new byte[]{1})).isEqualTo(507);
        assertThat(cached().resolve("acme").resolve("full").resolve("aa").resolve("01")).doesNotExist();
    }

    @Test
    public void metrics_record_outcomes_per_tenant_and_project() throws Exception {
        MeterRegistry registry = new SimpleMeterRegistry();
        Cache cache = cache(registry);
        credential("acme", ACME_RW, "*=cache:read,cache:write");
        credential("acme", ACME_NONE, "other=cache:read");
        put(cache, "demo", ACME_RW, "aa", "01", new byte[]{1});
        assertThat(get(cache, "demo", ACME_RW, "aa", "01")).isEqualTo(200);
        assertThat(get(cache, "demo", ACME_RW, "aa", "02")).isEqualTo(404);
        put(cache, "demo", ACME_RW, "aa", "01", new byte[]{1});
        assertThat(get(cache, "demo", ACME_NONE, "aa", "01")).isEqualTo(403);
        assertThat(count(registry, "stored")).isEqualTo(1.0);
        assertThat(count(registry, "hit")).isEqualTo(1.0);
        assertThat(count(registry, "miss")).isEqualTo(1.0);
        assertThat(count(registry, "present")).isEqualTo(1.0);
        assertThat(count(registry, "forbidden")).isEqualTo(1.0);
    }

    @Test
    public void an_upload_larger_than_the_cap_is_rejected_as_413_and_stores_nothing() throws Exception {
        Cache small = new Cache(CacheStorages.filesystem(root), authorization(), 8, 256, null, 0, 0,
                "default", false, null, "default", new SimpleMeterRegistry());
        credential("acme", ACME_RW, "*=cache:read,cache:write");
        assertThat(put(small, "demo", ACME_RW, "aa", "01", "well past the eight-byte cap".getBytes(UTF_8)))
                .isEqualTo(413);
        assertThat(get(small, "demo", ACME_RW, "aa", "01")).as("nothing was stored").isEqualTo(404);
    }

    @Test
    public void a_forged_key_registers_no_tenant_tagged_meter() {
        MeterRegistry registry = new SimpleMeterRegistry();
        Cache cache = cache(registry);
        // A well-formed key for a tenant that was never provisioned: the checksum is a public CRC32, so anyone
        // can mint one - its made-up tenant name must never become a meter tag or a storage-scope map entry.
        assertThat(cache.resolve("demo", Authorization.mint("forged_tenant"), "aa", "01", false))
                .isInstanceOf(Rejected.class);
        assertThat(registry.find("jenreg.cache.requests").tags("tenant", "forged_tenant").counter())
                .as("an unauthenticated request's tenant is not trusted into the meter registry").isNull();
        assertThat(registry.get("jenreg.cache.requests").tags("tenant", "none", "outcome", "forbidden")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    public void head_touches_a_present_entry_and_misses_or_forbids_otherwise() throws Exception {
        MeterRegistry registry = new SimpleMeterRegistry();
        Cache cache = cache(registry);
        credential("acme", ACME_RW, "*=cache:read,cache:write");
        credential("acme", ACME_NONE, "other=cache:read");
        put(cache, "demo", ACME_RW, "aa", "01", new byte[]{1});
        Path entry = cached().resolve("acme").resolve("demo").resolve("aa").resolve("01");
        // A never-stamped entry ages from its modification time; a HEAD from a node that did not store it stamps it
        // - as an object beside it, never by moving that time, so a copy of the store carries the stamp.
        FileTime stale = FileTime.from(Instant.now().minus(Duration.ofHours(7)));
        Files.setLastModifiedTime(entry, stale);
        assertThat(head(cache(registry), "demo", ACME_RW, "aa", "01")).isEqualTo(200);
        assertThat(Files.getLastModifiedTime(entry).toInstant()).isEqualTo(stale.toInstant());
        try (Stream<Path> stamps = Files.list(entry.resolveSibling("01.used"))) {
            assertThat(stamps.count()).as("one stamp beside the entry").isEqualTo(1L);
        }
        assertThat(head(cache, "demo", ACME_RW, "aa", "ff")).isEqualTo(404);
        assertThat(head(cache, "demo", ACME_NONE, "aa", "01")).isEqualTo(403);
        assertThat(count(registry, "touched")).isEqualTo(1.0);
    }

    @Test
    public void an_allowed_request_records_key_usage_when_tracking_is_enabled() throws Exception {
        RecordingUsageTracker tracker = new RecordingUsageTracker(true);
        Cache cache = cache().usageTracker(tracker);
        credential("acme", ACME_RW, "*=cache:read,cache:write");
        assertThat(put(cache, "demo", ACME_RW, "aa", "01", new byte[]{1})).isEqualTo(201);
        assertThat(tracker.uses)
                .as("an allowed request stamps the credential's use with its tenant, key hash and a null address")
                .containsExactly(new RecordingUsageTracker.Use("acme", Authorization.hash(ACME_RW), null));
    }

    @Test
    public void a_disabled_tracker_records_no_usage() throws Exception {
        RecordingUsageTracker tracker = new RecordingUsageTracker(false);
        Cache cache = cache().usageTracker(tracker);
        credential("acme", ACME_RW, "*=cache:read,cache:write");
        assertThat(put(cache, "demo", ACME_RW, "aa", "01", new byte[]{1})).isEqualTo(201);
        assertThat(tracker.uses).as("the enabled()==false leg skips record() entirely").isEmpty();
    }

    @Test
    public void an_unparseable_size_fails_open_to_no_cap_rather_than_throwing() throws Exception {
        // "size=2gb" is not a byte count (Long.parseLong throws): parseSize must fail open to 0 (cap disabled) and log
        // a warning, never propagate the NumberFormatException out of resolve(). A successful PUT of a body that would
        // otherwise exceed a small cap - and both entries surviving with no reaper - proves the cap was disabled.
        project("acme", "misconfigured", "size=2gb\n");
        credential("acme", ACME_RW, "*=cache:read,cache:write");
        Cache cache = cache();
        assertThat(put(cache, "misconfigured", ACME_RW, "aa", "01", new byte[2000])).isEqualTo(201);
        assertThat(put(cache, "misconfigured", ACME_RW, "aa", "02", new byte[2000])).isEqualTo(201);
        assertThat(cached().resolve("acme").resolve("misconfigured").resolve("aa").resolve("01"))
                .as("the size cap is disabled, so no write-triggered eviction reclaimed the older entry").isRegularFile();
        assertThat(cached().resolve("acme").resolve("misconfigured").resolve("aa").resolve("02")).isRegularFile();
    }

    @Test
    public void an_unparseable_ttl_fails_open_to_no_expiry_while_the_reaper_stays_alive() throws Exception {
        // "ttl=soon" is not a duration in the deployment's grammar: ttl() must fail open to null (no expiry), never
        // throw out of the reaper. An idle entry in the misconfigured project survives, while a sibling project with a
        // valid short ttl is still reaped - so the reaper is demonstrably alive and only the malformed dial fails open.
        Path bad = project("acme", "noexpiry", "ttl=soon\n");
        Path good = project("acme", "expires", "ttl=PT10S\n");
        credential("acme", ACME_RW, "*=cache:read,cache:write");
        Cache reaped = new Cache(CacheStorages.filesystem(root), authorization(), 1L << 31, 256, Duration.ofMillis(100),
                0, 0, "default", false, null, "default", new SimpleMeterRegistry());
        reaped.start();
        try {
            put(reaped, "noexpiry", ACME_RW, "aa", "01", new byte[]{1});
            put(reaped, "expires", ACME_RW, "aa", "01", new byte[]{1});
            Files.setLastModifiedTime(bad.resolve("aa").resolve("01"), FileTime.from(Instant.now().minusSeconds(3600)));
            Files.setLastModifiedTime(good.resolve("aa").resolve("01"), FileTime.from(Instant.now().minusSeconds(3600)));
            await("the reaper to expire the entry under the valid ttl",
                    () -> !Files.exists(good.resolve("aa").resolve("01")));
            assertThat(good.resolve("aa").resolve("01")).as("a valid ttl still expires: the reaper is alive").doesNotExist();
            assertThat(bad.resolve("aa").resolve("01"))
                    .as("the malformed ttl failed open to no expiry, so the idle entry survives").isRegularFile();
        } finally {
            reaped.stop();
        }
    }

    @Test
    public void the_union_across_projects_enumerates_exactly_the_hex_named_entries() throws Exception {
        // The free-space sweep's input, on the hermetic filesystem backend: every hex-named entry across every
        // project, and nothing else (a project's cache.properties config file is not an entry). The SPI offers no
        // whole-store sweep to ask for it - the union is composed here out of the two bounded enumerations, exactly
        // the way Eviction.reclaim composes it, so a backend that lost an entry to a page boundary shows up here.
        CacheStorage storage = CacheStorages.filesystem(root).scope("acme");
        storage.store(new CacheStorage.Entry("one", "aa", "01"), new ByteArrayInputStream(new byte[]{1}));
        storage.store(new CacheStorage.Entry("one", "bb", "02"), new ByteArrayInputStream(new byte[]{2, 2}));
        storage.store(new CacheStorage.Entry("two", "cc", "03"), new ByteArrayInputStream(new byte[]{3, 3, 3}));
        Properties config = new Properties();
        config.setProperty("size", "100");
        storage.writeConfig("one", "cache.properties", config);          // non-hex-named: never an entry

        List<CacheStorage.Stored> all = new ArrayList<>();
        for (String project : allProjects(storage)) {
            all.addAll(allEntriesOf(storage, project));
        }
        assertThat(all).extracting(stored -> {
                    String token = String.valueOf(stored.token());     // opaque: read its text, do not cast it
                    return token.substring(Math.max(token.lastIndexOf('/'), token.lastIndexOf('\\')) + 1);
                })
                .as("exactly the hex-named entries, across both projects, the config file excluded")
                .containsExactlyInAnyOrder("01", "02", "03");
        assertThat(all).extracting(CacheStorage.Stored::size).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    /** Every project name, drained one at a time through the paged enumeration - a page width of 1 so the drain
     *  itself is what is under test, not a single page that happened to fit. */
    private static List<String> allProjects(CacheStorage storage) {
        List<String> names = new ArrayList<>();
        String cursor = null;
        while (true) {
            Traversal.Result page = storage.projects(cursor, 1, names::add);
            if (page.exhausted()) {
                return names;
            }
            cursor = page.cursor().orElseThrow();
        }
    }

    /** Every entry of one project, drained the same way. */
    private static List<CacheStorage.Stored> allEntriesOf(CacheStorage storage, String project) {
        List<CacheStorage.Stored> entries = new ArrayList<>();
        String cursor = null;
        while (true) {
            Traversal.Result page = storage.entries(project, cursor, 1, entries::add);
            if (page.exhausted()) {
                return entries;
            }
            cursor = page.cursor().orElseThrow();
        }
    }

    // --- helpers that mirror CacheController's request flow + status mapping ---

    private static int get(Cache cache, String project, String key, String step, String inputs) {
        Resolution resolution = cache.resolve(project, key, step, inputs, false);
        if (resolution instanceof Rejected rejected) {
            return rejected.status();
        }
        Allowed allowed = (Allowed) resolution;
        if (!cache.exists(allowed)) {
            cache.count(allowed.metric(), Outcome.MISS);
            return 404;
        }
        cache.touch(allowed);
        cache.count(allowed.metric(), Outcome.HIT);
        return 200;
    }

    private static int head(Cache cache, String project, String key, String step, String inputs) {
        Resolution resolution = cache.resolve(project, key, step, inputs, false);
        if (resolution instanceof Rejected rejected) {
            return rejected.status();
        }
        Allowed allowed = (Allowed) resolution;
        if (!cache.exists(allowed)) {
            cache.count(allowed.metric(), Outcome.MISS);
            return 404;
        }
        cache.touch(allowed);
        cache.count(allowed.metric(), Outcome.TOUCHED);
        return 200;
    }

    private static int put(Cache cache, String project, String key, String step, String inputs, byte[] body) throws IOException {
        Resolution resolution = cache.resolve(project, key, step, inputs, true);
        if (resolution instanceof Rejected rejected) {
            return rejected.status();
        }
        Allowed allowed = (Allowed) resolution;
        if (cache.exists(allowed)) {
            cache.count(allowed.metric(), Outcome.PRESENT);
            return 204;
        }
        if (cache.tooLarge(body.length)) {
            cache.count(allowed.metric(), Outcome.REJECTED);
            return 413;
        }
        if (cache.cannotFit()) {
            cache.count(allowed.metric(), Outcome.FULL);
            return 507;
        }
        cache.store(allowed, new ByteArrayInputStream(body));
        cache.count(allowed.metric(), Outcome.STORED);
        return 201;
    }

    private static String read(Cache cache, String project, String key, String step, String inputs) throws IOException {
        Allowed allowed = (Allowed) cache.resolve(project, key, step, inputs, false);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        cache.read(allowed, out);
        return out.toString(UTF_8);
    }

    private void credential(String tenant, String key, String grants) throws IOException {
        int eq = grants.indexOf('=');
        authorization().setGrant(tenant, Authorization.hash(key), grants.substring(0, eq), grants.substring(eq + 1));
    }

    private Path project(String tenant, String name, String cacheProperties) throws IOException {
        Path project = Files.createDirectories(cached().resolve(tenant).resolve(name));
        Files.writeString(project.resolve("cache.properties"), cacheProperties);
        return project;
    }

    /** Re-read until {@code condition} holds, bounded by {@link #READS} attempts and loud when they run out, so an
     *  exhausted wait names the thing that never happened instead of letting the next assertion mis-report it. */
    private static void await(String describe, BooleanSupplier condition) {
        for (int read = 0; read < READS; read++) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while awaiting " + describe, interrupted);
            }
        }
        throw new AssertionError("Never observed " + describe + " across " + READS + " reads");
    }

    @Test
    public void a_hit_inside_the_touch_window_neither_reads_nor_stamps_again() throws Exception {
        Stamps stamps = new Stamps();
        Cache cache = new Cache(new StampingStorage(CacheStorages.filesystem(root), stamps), authorization(),
                1L << 31, 256, null, 0, 0, "default", false, null, "default", new SimpleMeterRegistry());
        Cache other = new Cache(new StampingStorage(CacheStorages.filesystem(root), stamps), authorization(),
                1L << 31, 256, null, 0, 0, "default", false, null, "default", new SimpleMeterRegistry());
        credential("acme", ACME_RW, "*=cache:read,cache:write");
        put(cache, "demo", ACME_RW, "aa", "01", new byte[]{1});
        stamps.recency = Instant.now().minus(Duration.ofMinutes(5));   // stamped five minutes ago, by anyone
        assertThat(head(other, "demo", ACME_RW, "aa", "01")).isEqualTo(200);
        assertThat(get(other, "demo", ACME_RW, "aa", "01")).isEqualTo(200);
        assertThat(head(other, "demo", ACME_RW, "aa", "01")).isEqualTo(200);
        assertThat(stamps.reads).as("another node asks for the age once and remembers it").isEqualTo(1);
        assertThat(stamps.touches).as("a stamp inside the window is not renewed").isZero();
    }

    @Test
    public void the_node_that_stored_an_entry_neither_asks_nor_stamps_within_the_window_of_the_store() throws Exception {
        Stamps stamps = new Stamps();
        AtomicReference<Instant> clock = new AtomicReference<>(Instant.now());
        Cache cache = new Cache(new StampingStorage(CacheStorages.filesystem(root), stamps), authorization(),
                1L << 31, 256, null, 0, 0, "default", false, null, "default", new SimpleMeterRegistry())
                .clock(clock::get);
        credential("acme", ACME_RW, "*=cache:read,cache:write");
        put(cache, "demo", ACME_RW, "aa", "01", new byte[]{1});
        assertThat(get(cache, "demo", ACME_RW, "aa", "01")).isEqualTo(200);
        assertThat(head(cache, "demo", ACME_RW, "aa", "01")).isEqualTo(200);
        assertThat(stamps.reads).as("the store is remembered: the first warm build asks nothing").isZero();
        assertThat(stamps.touches).as("the entry's own time is its recency until the window passes").isZero();
        clock.set(clock.get().plus(Duration.ofHours(6)).plusSeconds(1));
        assertThat(get(cache, "demo", ACME_RW, "aa", "01")).isEqualTo(200);
        assertThat(stamps.reads).as("a store of this node's own left no stamp to look for").isZero();
        assertThat(stamps.touches).as("stamped once the window of the store passed").isEqualTo(1);
        assertThat(stamps.retired).as("and there was no previous stamp to retire").isNull();
    }

    @Test
    public void another_node_trusts_an_unstamped_entrys_own_time_within_the_window_and_stamps_past_it() throws Exception {
        Stamps stamps = new Stamps();
        AtomicReference<Instant> clock = new AtomicReference<>(Instant.now());
        Cache cache = new Cache(new StampingStorage(CacheStorages.filesystem(root), stamps), authorization(),
                1L << 31, 256, null, 0, 0, "default", false, null, "default", new SimpleMeterRegistry());
        Cache other = new Cache(new StampingStorage(CacheStorages.filesystem(root), stamps), authorization(),
                1L << 31, 256, null, 0, 0, "default", false, null, "default", new SimpleMeterRegistry())
                .clock(clock::get);
        credential("acme", ACME_RW, "*=cache:read,cache:write");
        put(cache, "demo", ACME_RW, "aa", "01", new byte[]{1});
        // The entry's own time is the storing node's real clock at the put, which a loaded machine can place a second
        // or more after this node's clock was read. The window is therefore measured from after the put: advancing
        // from the earlier reading left the entry just under six hours old on a slow runner, correctly unstamped.
        Instant stored = Instant.now();
        assertThat(get(other, "demo", ACME_RW, "aa", "01")).isEqualTo(200);
        assertThat(head(other, "demo", ACME_RW, "aa", "01")).isEqualTo(200);
        assertThat(stamps.reads).as("a node that did not store the entry asks once").isEqualTo(1);
        assertThat(stamps.touches).as("and trusts the entry's own time within the window - no stamp for a young entry").isZero();
        clock.set(stored.plus(Duration.ofHours(6)).plusSeconds(1));
        assertThat(get(other, "demo", ACME_RW, "aa", "01")).isEqualTo(200);
        assertThat(stamps.touches).as("stamped once the window of the store passed").isEqualTo(1);
        assertThat(stamps.retired).as("with no stamp to retire, since the time was the entry's own").isNull();
        assertThat(stamps.reads).as("without asking again").isEqualTo(1);
    }

    @Test
    public void a_stamp_past_the_window_is_renewed_once_and_remembered_until_the_window_passes() throws Exception {
        Stamps stamps = new Stamps();
        AtomicReference<Instant> clock = new AtomicReference<>(Instant.now());
        Cache cache = new Cache(new StampingStorage(CacheStorages.filesystem(root), stamps), authorization(),
                1L << 31, 256, null, 0, 0, "default", false, null, "default", new SimpleMeterRegistry());
        Cache other = new Cache(new StampingStorage(CacheStorages.filesystem(root), stamps), authorization(),
                1L << 31, 256, null, 0, 0, "default", false, null, "default", new SimpleMeterRegistry())
                .clock(clock::get);
        credential("acme", ACME_RW, "*=cache:read,cache:write");
        put(cache, "demo", ACME_RW, "aa", "01", new byte[]{1});
        stamps.recency = clock.get().minus(Duration.ofHours(7));
        assertThat(head(other, "demo", ACME_RW, "aa", "01")).isEqualTo(200);
        assertThat(stamps.touches).as("a stamp past the window is renewed").isEqualTo(1);
        assertThat(stamps.retired).as("and the one found is retired").isEqualTo(clock.get().minus(Duration.ofHours(7)));
        assertThat(get(other, "demo", ACME_RW, "aa", "01")).isEqualTo(200);
        assertThat(stamps.touches).as("the renewal is remembered").isEqualTo(1);
        assertThat(stamps.reads).isEqualTo(1);
        clock.set(clock.get().plus(Duration.ofHours(6)).plusSeconds(1));
        assertThat(get(other, "demo", ACME_RW, "aa", "01")).isEqualTo(200);
        assertThat(stamps.reads).as("a stamp this node wrote is renewed without asking again").isEqualTo(1);
        assertThat(stamps.touches).as("renewed once the window passed").isEqualTo(2);
    }

    @Test
    public void no_touch_window_stamps_every_hit_without_asking() throws Exception {
        Stamps stamps = new Stamps();
        Cache cache = new Cache(new StampingStorage(CacheStorages.filesystem(root), stamps), authorization(),
                1L << 31, 256, null, 0, 0, "default", false, null, "default", new SimpleMeterRegistry())
                .touchInterval(null);
        Cache other = new Cache(new StampingStorage(CacheStorages.filesystem(root), stamps), authorization(),
                1L << 31, 256, null, 0, 0, "default", false, null, "default", new SimpleMeterRegistry())
                .touchInterval(null);
        credential("acme", ACME_RW, "*=cache:read,cache:write");
        put(cache, "demo", ACME_RW, "aa", "01", new byte[]{1});
        assertThat(head(cache, "demo", ACME_RW, "aa", "01")).isEqualTo(200);
        assertThat(get(cache, "demo", ACME_RW, "aa", "01")).isEqualTo(200);
        assertThat(stamps.touches).as("every hit stamps").isEqualTo(2);
        assertThat(stamps.reads).as("the node that stored the entry knows there was no stamp to retire").isZero();
        assertThat(head(other, "demo", ACME_RW, "aa", "01")).isEqualTo(200);
        assertThat(stamps.reads).as("another node asks once, to know which stamp it retires").isEqualTo(1);
        assertThat(stamps.touches).isEqualTo(3);
    }

    @Test
    public void a_request_inside_the_policy_window_does_not_ask_for_the_projects_policy_again() throws Exception {
        Stamps stamps = new Stamps();
        AtomicReference<Instant> clock = new AtomicReference<>(Instant.now());
        Cache cache = new Cache(new StampingStorage(CacheStorages.filesystem(root), stamps), authorization(),
                1L << 31, 256, null, 0, 0, "default", false, null, "default", new SimpleMeterRegistry())
                .clock(clock::get);
        credential("acme", ACME_RW, "*=cache:read,cache:write");
        put(cache, "demo", ACME_RW, "aa", "01", new byte[]{1});
        assertThat(stamps.policies).as("the first request reads the policy").isEqualTo(1);
        assertThat(get(cache, "demo", ACME_RW, "aa", "01")).isEqualTo(200);
        assertThat(head(cache, "demo", ACME_RW, "aa", "01")).isEqualTo(200);
        assertThat(stamps.policies).as("a request inside the window trusts the policy it read").isEqualTo(1);
        clock.set(clock.get().plus(Duration.ofMinutes(5)).plusSeconds(1));
        assertThat(get(cache, "demo", ACME_RW, "aa", "01")).isEqualTo(200);
        assertThat(stamps.policies).as("past the window the store is asked once more").isEqualTo(2);
        assertThat(get(cache, "demo", ACME_RW, "aa", "01")).isEqualTo(200);
        assertThat(stamps.policies).as("and the confirmation opens a new window").isEqualTo(2);
    }

    @Test
    public void a_miss_asks_the_storage_nothing_about_recency() throws Exception {
        Stamps stamps = new Stamps();
        Cache cache = new Cache(new StampingStorage(CacheStorages.filesystem(root), stamps), authorization(),
                1L << 31, 256, null, 0, 0, "default", false, null, "default", new SimpleMeterRegistry());
        credential("acme", ACME_RW, "*=cache:read,cache:write");
        assertThat(get(cache, "demo", ACME_RW, "aa", "01")).isEqualTo(404);
        assertThat(head(cache, "demo", ACME_RW, "aa", "01")).isEqualTo(404);
        assertThat(stamps.reads).as("an entry that is not there has no stamps worth asking for").isZero();
        assertThat(stamps.touches).as("and gets none").isZero();
    }

    @Test
    public void no_policy_window_asks_for_the_projects_policy_on_every_request() throws Exception {
        Stamps stamps = new Stamps();
        Cache cache = new Cache(new StampingStorage(CacheStorages.filesystem(root), stamps), authorization(),
                1L << 31, 256, null, 0, 0, "default", false, null, "default", new SimpleMeterRegistry())
                .policyInterval(Duration.ZERO);
        credential("acme", ACME_RW, "*=cache:read,cache:write");
        put(cache, "demo", ACME_RW, "aa", "01", new byte[]{1});
        assertThat(get(cache, "demo", ACME_RW, "aa", "01")).isEqualTo(200);
        assertThat(head(cache, "demo", ACME_RW, "aa", "01")).isEqualTo(200);
        assertThat(stamps.policies).as("every request asks whether the policy changed").isEqualTo(3);
    }

    /** What the cache asked the storage about recency and policy, shared across the tenant scopes it resolves. */
    private static final class Stamps {
        int touches;
        int reads;
        int policies;
        Instant recency;
        Instant retired;
    }

    /** A storage whose recency is whatever the test says and whose stamps are counted rather than written. */
    private static final class StampingStorage extends RecordingStorage {
        private final Stamps stamps;

        private StampingStorage(CacheStorage delegate, Stamps stamps) {
            super(delegate, ConcurrentHashMap.newKeySet());
            this.stamps = stamps;
        }

        @Override
        public CacheStorage scope(String tenant) {
            return new StampingStorage(delegate.scope(tenant), stamps);
        }

        @Override
        public void stamp(Entry entry, Instant at, Instant previous) {
            stamps.touches++;
            stamps.recency = at;
            stamps.retired = previous;
        }

        @Override
        public Optional<CacheStorage.Recency> recency(Entry entry) {
            stamps.reads++;
            // The test's stamp when it planted one; else the real answer - the entry's own time, or nothing.
            return Optional.ofNullable(stamps.recency).map(at -> new CacheStorage.Recency(at, true))
                    .or(() -> delegate.recency(entry));
        }

        @Override
        public Object configVersion(String project) {
            stamps.policies++;
            return delegate.configVersion(project);
        }
    }

    /** A {@link CacheStorage} decorator that records which thread runs an entry enumeration, so a test can assert the
     *  free-space reclaim runs off the request thread. Every call delegates unchanged; {@link #scope} propagates the
     *  same recorder. A test double, never a backend. */
    private static class RecordingStorage implements CacheStorage {

        final CacheStorage delegate;
        private final Set<Thread> enumeratingThreads;

        RecordingStorage(CacheStorage delegate, Set<Thread> enumeratingThreads) {
            this.delegate = delegate;
            this.enumeratingThreads = enumeratingThreads;
        }

        @Override
        public CacheStorage scope(String tenant) {
            return new RecordingStorage(delegate.scope(tenant), enumeratingThreads);
        }

        @Override
        public Traversal.Result entries(String project, String cursor, int limit, Consumer<Stored> entries) {
            enumeratingThreads.add(Thread.currentThread());
            return delegate.entries(project, cursor, limit, entries);
        }

        @Override
        public boolean projectExists(String project) {
            return delegate.projectExists(project);
        }

        @Override
        public Properties readConfig(String project, String file) {
            return delegate.readConfig(project, file);
        }

        @Override
        public Object configVersion(String project) {
            return delegate.configVersion(project);
        }

        @Override
        public boolean exists(Entry entry) {
            return delegate.exists(entry);
        }

        @Override
        public Optional<CacheStorage.Recency> recency(Entry entry) {
            return delegate.recency(entry);
        }

        @Override
        public void stamp(Entry entry, Instant at, Instant previous) {
            delegate.stamp(entry, at, previous);
        }

        @Override
        public void read(Entry entry, OutputStream out) throws IOException {
            delegate.read(entry, out);
        }

        @Override
        public void store(Entry entry, InputStream in) throws IOException {
            delegate.store(entry, in);
        }

        @Override
        public Traversal.Result projects(String cursor, int limit, Consumer<String> names) {
            return delegate.projects(cursor, limit, names);
        }

        @Override
        public void delete(Stored entry) {
            delegate.delete(entry);
        }

        @Override
        public long usableSpace() {
            return delegate.usableSpace();
        }

        @Override
        public long totalSpace() {
            return delegate.totalSpace();
        }

        @Override
        public void createProject(String project) throws IOException {
            delegate.createProject(project);
        }

        @Override
        public void writeConfig(String project, String file, Properties properties) throws IOException {
            delegate.writeConfig(project, file, properties);
        }

        @Override
        public Properties readFile(String path) {
            return delegate.readFile(path);
        }

        @Override
        public void writeFile(String path, Properties properties) throws IOException {
            delegate.writeFile(path, properties);
        }

        @Override
        public boolean writeFileVersioned(String path, Properties properties, Object expected) throws IOException {
            return delegate.writeFileVersioned(path, properties, expected);
        }

        @Override
        public Object fileVersion(String path) {
            return delegate.fileVersion(path);
        }

        @Override
        public Traversal.Result listDir(String prefix, String cursor, int limit, Consumer<String> names) {
            return delegate.listDir(prefix, cursor, limit, names);
        }

        @Override
        public void deleteDir(String path) throws IOException {
            delegate.deleteDir(path);
        }
    }

    /** A {@link KeyUsageTracker} that records each offered use rather than batching it off-thread, so a test can assert
     *  exactly which use the cache offered on an allowed request. A test double, never the production batching worker. */
    private static final class RecordingUsageTracker implements KeyUsageTracker {

        record Use(String tenant, String hash, String address) {
        }

        private final boolean enabled;
        final List<Use> uses = new ArrayList<>();

        private RecordingUsageTracker(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public boolean alive() {
            return enabled;
        }

        @Override
        public long dropped() {
            return 0L;
        }

        @Override
        public void record(String tenant, String hash, String address) {
            uses.add(new Use(tenant, hash, address));
        }

        @Override
        public void start() {
        }

        @Override
        public void close() {
        }
    }

    /**
     * The cache's own root inside the repository's store. The cache has no backend of its own - it delegates to the
     * store and takes one segment there - so the fixture configures {@link #root} as the STORE root and everything
     * the cache writes lands one level below it. An assertion about the on-disk layout has to say which of the two
     * it means, and it always means this one.
     */
    private Path cached() {
        return root.resolve(Scopes.SYSTEM).resolve(Scopes.CACHE);
    }

    private static int hexEntries(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return (int) walk.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().chars().allMatch(c -> Character.digit(c, 16) >= 0))
                    .count();
        } catch (IOException _) {
            return 0;
        }
    }
}
