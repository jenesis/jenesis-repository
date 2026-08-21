package build.jenesis.repository.gc.test;

import build.jenesis.repository.gc.GarbageCollector;
import build.jenesis.repository.gc.GcPlan;
import build.jenesis.repository.gc.store.MarkSweepGarbageCollector;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Known;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.walk.store.StoreArtifactWalk;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * the collector must be tellable that a root set is <em>incomplete</em>, and must refuse to sweep when it is.
 *
 * <p>The hazard is asymmetric in a way no other bound in this product is. A missing pointer root does not degrade
 * the scan - it makes an entire namespace's serving pointers invisible, so every blob beneath it reads as
 * unreferenced and the confirming pass deletes bytes that are being served. There is no partial repair available:
 * blobs are content-addressed and flat, so the collector cannot infer which of them belong to an unenumerable root
 * and spare just those. The only correct behaviour is refusal, and the refusal has to sit <em>at the deletion</em>
 * rather than in every caller, which is what {@link GarbageCollector#collect} now guarantees.
 *
 * <p>The signature is the other half of the fix and is asserted reflectively rather than by a test that would not
 * compile: a plain {@code List<String>} of roots can only ever state "these are the roots", so as long as such an
 * overload exists a caller with incomplete knowledge has no way to say so and no reason to think about it. It is
 * gone, and this suite fails if it returns.
 */
class GcRootSetRefusalTest {

    @TempDir
    Path root;

    private final MutableClock clock = new MutableClock();

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    private MarkSweepGarbageCollector collector() {
        return new MarkSweepGarbageCollector(new StoreArtifactWalk(5, 4, Duration.ofMinutes(10), clock));
    }

    private static ByteArrayInputStream bytes(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private static final Known<List<String>> UNNAMEABLE = Known.uninstalled(
            "no installed format can place the ecosystem(s) [npm], so their serving pointers are invisible to the "
                    + "reference scan and their artifact bytes would be reclaimed as unreferenced");

    @Test
    void a_root_set_that_cannot_be_named_in_full_collects_nothing_and_says_why() throws IOException {
        ArtifactStore store = store();
        String orphan = store.writeBlob(bytes("nothing points at me"));

        // Two passes on a complete root set would condemn the orphan and then reclaim it. On an unnameable one the
        // collector never even marks: no condemnation is written, so a later pass on a repaired root set still owes
        // the orphan its full grace rather than deleting it on the strength of a refused pass.
        GcPlan first = collector().collect(store, UNNAMEABLE, clock.instant());
        GcPlan second = collector().collect(store, UNNAMEABLE, clock.instant());

        assertThat(first.complete()).isFalse();
        assertThat(first.condemned()).isZero();
        assertThat(first.collected()).isZero();
        assertThat(first.spared()).isZero();
        assertThat(second.collected()).isZero();
        assertThat(store.exists("blobs/" + orphan)).isTrue();
        assertThat(store.list("gc")).isEmpty(); // not a single bookkeeping object was written
        assertThat(first.refusal()).contains((Known.Unknown<List<String>>) UNNAMEABLE);
        assertThat(first.refusal().orElseThrow().cause()).isEqualTo(Known.Cause.UNINSTALLED);
        assertThat(first.refusal().orElseThrow().detail()).contains("[npm]");
    }

    @Test
    void the_dry_run_of_a_refusal_is_a_refusal_rather_than_an_empty_plan() throws IOException {
        ArtifactStore store = store();
        var _ = store.writeBlob(bytes("nothing points at me"));

        GcPlan plan = collector().plan(store, UNNAMEABLE, clock.instant());

        // An operator previewing a collection must see the reason, not a plan that reads like a converged store.
        assertThat(plan.complete()).isFalse();
        assertThat(plan.collected()).isZero();
        assertThat(plan.refusal()).isPresent();
        assertThat(store.list("gc")).isEmpty();
    }

    @Test
    void a_refusal_is_told_apart_from_a_pass_that_merely_could_not_finish() throws IOException {
        ArtifactStore store = store();
        Publication publication = new Publication(store);
        publication.link("/maven/g/a/1/a-1.jar", publication.storeBlob(bytes("live")));

        // Same complete()==false, entirely different meaning: this one heals itself on the next interval, the
        // refusal above needs an operator to install a module. Fusing the two into one boolean is the defect class
        // this seam exists to end, one level up.
        GcPlan firstEverPlan = collector().plan(store, Known.known(List.of("publish")), clock.instant());

        assertThat(firstEverPlan.complete()).isFalse();
        assertThat(firstEverPlan.refusal()).isEmpty();
    }

    @Test
    void an_answered_but_empty_root_set_is_a_caller_bug_and_fails_loudly() {
        ArtifactStore store = store();

        // "I asked, and there are no pointer roots" is a contradiction rather than a deployment state - publish
        // always exists - so it fails visibly instead of being absorbed into a refusal nobody goes looking for.
        assertThatThrownBy(() -> collector().collect(store, Known.absent(), clock.instant()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one pointer root");
        assertThatThrownBy(() -> collector().collect(store, Known.known(List.of()), clock.instant()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one pointer root");
    }

    @Test
    void a_refused_plan_cannot_be_built_claiming_completeness_or_carrying_work() {
        Known.Unknown<List<String>> reason = (Known.Unknown<List<String>>) UNNAMEABLE;

        // The Traversal.Result trick: the outcome and the evidence for it are one fact by constructor invariant, so
        // a refusal can never be reported as a converged store even by a future implementation's mistake.
        assertThatThrownBy(() -> new GcPlan(true, 0, 0, 0, List.of(), Optional.of(reason)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GcPlan(false, 0, 0, 7, List.of(), Optional.of(reason)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(GcPlan.refused(reason).isEmpty()).isTrue();
    }

    @Test
    void no_overload_of_the_deletion_seam_accepts_a_root_set_that_cannot_say_it_is_incomplete() {
        List<Method> deciding = Arrays.stream(GarbageCollector.class.getMethods())
                .filter(method -> method.getName().equals("plan") || method.getName().equals("collect"))
                .toList();

        assertThat(deciding).hasSize(2); // exactly one plan and one collect - no overload to fall back into
        for (Method method : deciding) {
            assertThat(method.getParameterTypes()[1])
                    .describedAs("%s must take its roots as a three-valued answer", method.getName())
                    .isEqualTo(Known.class);
            assertThat(method.getParameterTypes())
                    .describedAs("%s must not accept a bare list or flag anywhere", method.getName())
                    .doesNotContain(List.class, Collection.class, Set.class, boolean.class, Boolean.class);
        }
    }
}
