package build.jenesis.repository.walk.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.walk.WalkPass;
import build.jenesis.repository.walk.WalkSegment;
import build.jenesis.repository.walk.store.StoreArtifactWalk;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The corrupt-pass self-heal: pass state is durable in the walked store and nowhere else, so a manifest or segment
 * object rewritten to junk (a torn write, a truncated object, an operator's fat finger) must never be fatal. A
 * manifest that parses to {@code null} still occupies its compare-and-set slot, so the next pass CAS-replaces it -
 * and because the corrupt generation is unknowable, the fresh generation is re-based on the wall clock (a jump, not a
 * {@code +1}) so a stale segment object left by an earlier pass can never masquerade as current. A corrupt segment
 * object parses to {@code null} and is treated exactly as an unclaimed one. Either way the re-walk still visits every
 * key exactly once.
 */
class StoreWalkSelfHealTest {

    @TempDir
    Path root;

    private final MutableClock clock = new MutableClock();

    private ArtifactStore store(String name) {
        Path scoped = root.resolve(name);
        return ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? scoped.toString() : null);
    }

    private StoreArtifactWalk walk() {
        return new StoreArtifactWalk(2, 4, Duration.ofMinutes(10), clock);
    }

    private static List<String> seed(ArtifactStore store) throws IOException {
        List<String> keys = new ArrayList<>();
        for (char letter = 'a'; letter <= 'h'; letter++) {
            String key = "publish/" + letter + "/artifact";
            keys.add(key);
            store.writeVersioned(key, key.getBytes(StandardCharsets.UTF_8), null);
        }
        return keys.stream().sorted().toList();
    }

    /** Overwrite an existing pass object with bytes that carry no {@code generation} property, so both
     *  {@code parseManifest} and {@code parseSegment} fail to parse and return {@code null}. */
    private static void corrupt(ArtifactStore store, String key) throws IOException {
        Object token = store.readVersioned(key).orElseThrow().token();
        assertThat(store.writeVersioned(key, "\0 not a valid jenesis pass object \0"
                .getBytes(StandardCharsets.UTF_8), token))
                .as("the corruption overwrote the object in place").isTrue();
    }

    @Test
    void a_corrupt_manifest_reheals_at_a_fresh_clock_based_generation_and_still_visits_every_key() throws IOException {
        ArtifactStore store = store("corrupt-manifest");
        List<String> keys = seed(store);

        WalkPass first = walk().walk(store, "test", List.of("publish"), key -> {
        });
        assertThat(first.generation()).isEqualTo(1);
        assertThat(first.complete()).isTrue();

        // Corrupt the manifest and one segment object with junk bytes.
        corrupt(store, "walks/test/manifest");
        corrupt(store, "walks/test/segments/000");

        // parseManifest returns null for the corrupt object, so the observability reads report no pass at all.
        assertThat(walk().pass(store, "test")).as("a corrupt manifest parses to no readable pass").isEmpty();
        assertThat(walk().segments(store, "test")).as("and so exposes no segments").isEmpty();

        List<String> visited = new ArrayList<>();
        WalkPass rehealed = walk().walk(store, "test", List.of("publish"), visited::add);

        assertThat(rehealed.complete()).isTrue();
        assertThat(rehealed.generation())
                .as("a corrupt manifest re-bases the generation on the wall clock - a jump, never a +1")
                .isEqualTo(clock.instant().toEpochMilli())
                .isGreaterThan(1);
        assertThat(visited).as("the re-walk still visits every key exactly once")
                .containsExactlyElementsOf(keys);
    }

    @Test
    void a_corrupt_segment_under_a_live_manifest_is_treated_as_unclaimed_without_re_basing() throws IOException {
        ArtifactStore store = store("corrupt-segment");
        List<String> keys = seed(store);

        // A crash mid-pass leaves a live (incomplete) manifest at generation 1 with segment 000 CLAIMED.
        List<String> before = new ArrayList<>();
        assertThatThrownBy(() -> walk().walk(store, "test", List.of("publish"), key -> {
            before.add(key);
            throw new IOException("crash on the first key");
        })).hasMessageContaining("crash on the first key");
        assertThat(before).hasSize(1);

        corrupt(store, "walks/test/segments/000");

        // The manifest is still readable, but the corrupt segment parses to null and reads back as PENDING - the
        // parseSegment null-on-corruption branch, claimable exactly as an object that never recorded a claim.
        assertThat(walk().pass(store, "test")).hasValueSatisfying(pass -> {
            assertThat(pass.generation()).isEqualTo(1);
            assertThat(pass.complete()).isFalse();
        });
        assertThat(walk().segments(store, "test").get(0).state())
                .as("a corrupt segment is treated as pending").isEqualTo(WalkSegment.State.PENDING);

        clock.advance(Duration.ofMinutes(11)); // let any live claim lapse
        List<String> after = new ArrayList<>();
        WalkPass resumed = walk().walk(store, "test", List.of("publish"), after::add);

        assertThat(resumed.complete()).isTrue();
        assertThat(resumed.generation())
                .as("an intact manifest is not re-based: the pass stays generation 1").isEqualTo(1);
        assertThat(after).doesNotHaveDuplicates();
        Set<String> union = new HashSet<>(before);
        union.addAll(after);
        assertThat(union).as("no key is missed across the corruption and resume")
                .containsExactlyInAnyOrderElementsOf(keys);
    }
}
