package build.jenesis.repository.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link Lease}: one object per job, acquired by compare-and-set, refused while another holder's lease is live and
 * stolen once it has expired, renewed and released only by its holder, reaped once expired, and {@code guarded} so an
 * unconditional action runs only while the lease is provably still held. The class carried the maintenance passes and
 * the stored reports for months without a test of its own; this is it.
 */
class LeaseTest {

    private static final Instant T0 = Instant.parse("2026-09-05T08:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(5);

    @TempDir
    Path root;

    private ArtifactStore store;

    private Lease lease;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        lease = new Lease(store, "locks", TTL);
    }

    @Test
    void a_live_lease_refuses_another_holder_and_an_expired_one_is_stolen() throws IOException {
        assertThat(lease.acquire("sweep", "node-a", T0)).isTrue();
        assertThat(lease.acquire("sweep", "node-b", T0.plusSeconds(30))).as("live: refused, never stolen").isFalse();
        assertThat(lease.acquire("sweep", "node-a", T0.plusSeconds(30)))
                .as("a live lease is refused to everyone, its holder included - the holder renews, it does not re-acquire")
                .isFalse();
        assertThat(lease.renew("sweep", "node-a", T0.plusSeconds(30))).as("the holder's path is renew").isTrue();
        // The renewal at thirty seconds moved the expiry to thirty seconds past the ttl; the steal comes after that.
        Instant lapsed = T0.plusSeconds(30).plus(TTL).plusSeconds(1);
        assertThat(lease.acquire("sweep", "node-b", lapsed)).as("expired: taken over against its token").isTrue();
        assertThat(lease.renew("sweep", "node-a", lapsed.plusSeconds(1)))
                .as("the old holder has provably lost it").isFalse();
    }

    @Test
    void renew_and_release_belong_to_the_holder() throws IOException {
        assertThat(lease.acquire("sweep", "node-a", T0)).isTrue();
        assertThat(lease.renew("sweep", "node-b", T0.plusSeconds(10))).as("not the holder").isFalse();
        assertThat(lease.renew("sweep", "node-a", T0.plusSeconds(10))).isTrue();
        // renewed at T0+10s for the ttl: still live at T0+ttl+5s, which the original expiry would not have been
        assertThat(lease.acquire("sweep", "node-b", T0.plus(TTL).plusSeconds(5))).as("the renewal moved the expiry").isFalse();
        // A rival's release leaves the object untouched - the boolean says only whether the compare-and-set landed or
        // had nothing to do, so the effect is what is asserted: the lease is still node-a's afterwards.
        lease.release("sweep", "node-b", T0.plusSeconds(20));
        assertThat(lease.acquire("sweep", "node-b", T0.plusSeconds(20))).as("not the rival's to release").isFalse();
        assertThat(lease.release("sweep", "node-a", T0.plusSeconds(20))).isTrue();
        assertThat(lease.acquire("sweep", "node-b", T0.plusSeconds(21))).as("released: free at once").isTrue();
    }

    @Test
    void guarded_runs_the_action_only_while_the_lease_is_still_held() throws IOException {
        assertThat(lease.acquire("sweep", "node-a", T0)).isTrue();
        List<String> ran = new ArrayList<>();
        assertThat(lease.guarded("sweep", "node-a", T0.plusSeconds(1), () -> ran.add("held"))).isTrue();
        assertThat(lease.guarded("sweep", "node-b", T0.plusSeconds(1), () -> ran.add("not mine"))).isFalse();
        assertThat(lease.acquire("sweep", "node-b", T0.plus(TTL).plusSeconds(1))).isTrue();
        assertThat(lease.guarded("sweep", "node-a", T0.plus(TTL).plusSeconds(2), () -> ran.add("lost"))).isFalse();
        assertThat(ran).as("the fence let exactly the held case through").containsExactly("held");
    }

    @Test
    void renewing_or_releasing_a_lock_nobody_took_is_refused_and_harmless() {
        // The two calls a caller makes when it has lost track of whether it ever held the lock - after a restart,
        // or in a finally block that runs whether or not the acquire succeeded. Neither may invent a holder:
        // a renewal must say no rather than create a lease out of nothing, and a release must leave a free lock
        // free rather than throw at the caller who was only tidying up.
        assertThatCode(() -> {
            assertThat(lease.renew("sweep", "node-a", T0)).as("nothing to renew").isFalse();
            lease.release("sweep", "node-a", T0);
            assertThat(lease.acquire("sweep", "node-b", T0)).as("still free to acquire").isTrue();
        }).doesNotThrowAnyException();
    }

    @Test
    void a_release_after_the_lease_was_stolen_never_frees_the_thiefs_lock() throws IOException {
        // The stalled-holder shape, which is the one that actually happens: A takes the lock, runs long enough for
        // its lease to lapse, and B legitimately steals it. When A finally finishes and releases in its finally
        // block, it must not hand B's live lock to whoever asks next - a release clears a lease this holder still
        // owns, and A no longer owns one. Distinct from a rival releasing a lease that never lapsed, above.
        assertThat(lease.acquire("sweep", "node-a", T0)).isTrue();
        assertThat(lease.acquire("sweep", "node-b", T0.plus(TTL).plusSeconds(1))).as("expired: stolen").isTrue();

        lease.release("sweep", "node-a", T0.plus(TTL).plusSeconds(2));

        assertThat(lease.acquire("sweep", "node-c", T0.plus(TTL).plusSeconds(3)))
                .as("B's hold is intact - the stale release freed nothing").isFalse();
        assertThat(lease.renew("sweep", "node-b", T0.plus(TTL).plusSeconds(4)))
                .as("and B can still renew what it holds").isTrue();
    }

    @Test
    void reaping_removes_only_expired_leases() throws IOException {
        assertThat(lease.acquire("old", "node-a", T0)).isTrue();
        assertThat(lease.acquire("young", "node-a", T0.plus(TTL))).isTrue();
        lease.reapExpired(T0.plus(TTL).plusSeconds(1));
        assertThat(store.readVersioned("locks/old")).as("expired: reaped").isEmpty();
        assertThat(store.readVersioned("locks/young")).as("live: kept").isPresent();
    }
}
