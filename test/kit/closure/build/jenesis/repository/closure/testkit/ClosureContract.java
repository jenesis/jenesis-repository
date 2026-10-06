package build.jenesis.repository.closure.testkit;

import module java.base;
import build.jenesis.repository.closure.ClosureTask;
import build.jenesis.repository.closure.spi.ClosureSection;
import build.jenesis.repository.closure.spi.ClosureSource;
import build.jenesis.repository.closure.spi.ClosureWalk;
import build.jenesis.repository.compliance.testkit.NoEgressResolver;
import build.jenesis.repository.inventory.HeldSubjects;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.testkit.FaultInjectingStore;

/**
 * The executable {@link ClosureSource} contract: one body of checks every installed source runs through a
 * {@link ClosureFixture}, each asking the source as the closure pass does ({@link ClosureTask#ask}) over a fresh store
 * holding nothing but what the check arranges. A carried source is therefore held to what its document places, and a
 * resolving one to what it chooses, by the same checks.
 *
 * <p>A check bracketing the source with the {@link NoEgressResolver} record is only as good as the tripwire's
 * installation: the driving module provides a subclass and proves it is installed, or every
 * {@link Property#FETCHES_NOTHING} check is vacuously true.
 */
public final class ClosureContract {

    private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");

    /** One clause of the SPI's contract. A fixture excludes a property by name and reason, and the census fails on a
     *  property every fixture excludes. */
    public enum Property {
        /** A release carrying nothing the source reads is no answer from it, so the next source is asked (clause
         *  2). */
        NOTHING_TO_SAY_IS_EMPTY,
        /** What the release names and the walk holds is a component, at the version named, and a closure with
         *  nothing cut is resolved (clauses 2 and 3). */
        PLACES_WHAT_THE_WALK_HOLDS,
        /** What the walk does not hold is a cut, never dropped, and makes the closure partial (clauses 2 and 3). */
        WHAT_IS_NOT_HELD_IS_A_CUT,
        /** A copy the screen held at its fill is no holding: a cut, never a component. */
        A_COPY_HELD_AT_ITS_FILL_IS_A_CUT,
        /** A holding withheld for review is not served, so it is a cut, never a component. */
        A_WITHHELD_HOLDING_IS_A_CUT,
        /** A release naming more than {@link ClosureSource#MAX_COMPONENTS} held packages is cut short at the bound,
         *  and says so (clause 4). */
        STOPS_AT_THE_BOUND_AND_SAYS_SO,
        /** A store read that fails raises, rather than answering a closure the failure shortened or no answer at all
         *  (clause 5). */
        A_FAILED_READ_RAISES,
        /** Nothing is fetched, even for a package the walk does not hold (clause 3). */
        FETCHES_NOTHING
    }

    /** One named, independently runnable check. */
    public record Check(Property property, String name, Body body) {
    }

    /** The body of a {@link Check}, run against a fixture and a fresh, empty store. */
    @FunctionalInterface
    public interface Body {
        void run(ClosureFixture fixture, ArtifactStore store) throws Exception;
    }

    private ClosureContract() {
    }

    /** Every check, in declaration order - the contract. */
    public static List<Check> checks() {
        return List.of(
                new Check(Property.NOTHING_TO_SAY_IS_EMPTY,
                        "a release carrying nothing the source reads is no answer from it",
                        ClosureContract::nothingToSay),
                new Check(Property.PLACES_WHAT_THE_WALK_HOLDS,
                        "what the release names and the walk holds is a component of a resolved closure",
                        ClosureContract::placesWhatIsHeld),
                new Check(Property.WHAT_IS_NOT_HELD_IS_A_CUT,
                        "what the walk does not hold is a cut of a partial closure",
                        ClosureContract::notHeldIsCut),
                new Check(Property.A_COPY_HELD_AT_ITS_FILL_IS_A_CUT,
                        "a copy held at its fill is a cut, never a component",
                        ClosureContract::heldAtFillIsCut),
                new Check(Property.A_WITHHELD_HOLDING_IS_A_CUT,
                        "a withheld holding is a cut, never a component",
                        ClosureContract::withheldIsCut),
                new Check(Property.STOPS_AT_THE_BOUND_AND_SAYS_SO,
                        "a closure stops at its bound and says so",
                        ClosureContract::bounded),
                new Check(Property.A_FAILED_READ_RAISES,
                        "a failed store read raises rather than shortening the closure",
                        ClosureContract::failedReadRaises),
                new Check(Property.FETCHES_NOTHING,
                        "nothing is fetched for a package the walk does not hold",
                        ClosureContract::fetchesNothing));
    }

    /** The checks {@code fixture} runs: every one but those it names as unsupported. */
    public static List<Check> checks(ClosureFixture fixture) {
        return checks().stream().filter(check -> !fixture.unsupported().containsKey(check.property())).toList();
    }

    /** The installed source {@code fixture} drives. */
    public static ClosureSource source(ClosureFixture fixture) {
        return ClosureSource.installed().stream().filter(source -> source.name().equals(fixture.source()))
                .findFirst().orElseThrow(() -> new AssertionError("no installed closure source is named '"
                        + fixture.source() + "'; installed: "
                        + ClosureSource.installed().stream().map(ClosureSource::name).toList()));
    }

    private static void nothingToSay(ClosureFixture fixture, ArtifactStore store) throws IOException {
        fixture.bare(store, NOW);
        Optional<ClosureSection.Closure> answer = ask(fixture, store);
        expect(fixture, answer.isEmpty(), "a release carrying nothing it reads is no answer, so the next source is "
                + "asked; it answered " + answer.orElse(null));
    }

    private static void placesWhatIsHeld(ClosureFixture fixture, ArtifactStore store) throws IOException {
        List<ClosureFixture.Named> named = List.of(fixture.dependency(0), fixture.dependency(1));
        for (ClosureFixture.Named dependency : named) {
            fixture.hold(store, dependency, NOW);
        }
        fixture.publish(store, named, NOW);
        ClosureSection.Closure closure = answered(fixture, store);
        for (ClosureFixture.Named dependency : named) {
            expect(fixture, component(closure, dependency), dependency + " is held, so it is a component: " + closure);
        }
        expect(fixture, closure.cuts().isEmpty() && closure.status() == ClosureSection.Status.RESOLVED,
                "nothing is cut, so the closure is resolved: " + closure);
    }

    private static void notHeldIsCut(ClosureFixture fixture, ArtifactStore store) throws IOException {
        ClosureFixture.Named held = fixture.dependency(0);
        ClosureFixture.Named missing = fixture.dependency(1);
        fixture.hold(store, held, NOW);
        fixture.publish(store, List.of(held, missing), NOW);
        ClosureSection.Closure closure = answered(fixture, store);
        expect(fixture, component(closure, held), held + " is held, so it is a component: " + closure);
        expect(fixture, !component(closure, missing) && cut(closure, missing), missing + " is not held, so it is a "
                + "cut: " + closure);
        expect(fixture, closure.status() == ClosureSection.Status.PARTIAL, "a closure with a cut is partial: "
                + closure);
    }

    private static void heldAtFillIsCut(ClosureFixture fixture, ArtifactStore store) throws IOException {
        ClosureFixture.Named held = fixture.dependency(0);
        ClosureFixture.Named review = fixture.dependency(1);
        fixture.hold(store, held, NOW);
        holdForReview(fixture, store, review);
        fixture.publish(store, List.of(held, review), NOW);
        ClosureSection.Closure closure = answered(fixture, store);
        expect(fixture, component(closure, held), held + " is held, so it is a component: " + closure);
        expect(fixture, !component(closure, review) && cut(closure, review), review + " is a copy held at its fill, "
                + "no holding, so it is a cut: " + closure);
    }

    private static void withheldIsCut(ClosureFixture fixture, ArtifactStore store) throws IOException {
        ClosureFixture.Named held = fixture.dependency(0);
        ClosureFixture.Named withheld = fixture.dependency(1);
        fixture.hold(store, held, NOW);
        fixture.hold(store, withheld, NOW);
        holdForReview(fixture, store, withheld);
        fixture.publish(store, List.of(held, withheld), NOW);
        ClosureSection.Closure closure = answered(fixture, store);
        expect(fixture, component(closure, held), held + " is held, so it is a component: " + closure);
        expect(fixture, !component(closure, withheld) && cut(closure, withheld), withheld + " is withheld for "
                + "review, so it is a cut: " + closure);
    }

    private static void bounded(ClosureFixture fixture, ArtifactStore store) throws IOException {
        List<ClosureFixture.Named> named = new ArrayList<>();
        for (int i = 0; i <= ClosureSource.MAX_COMPONENTS; i++) {
            ClosureFixture.Named dependency = fixture.dependency(i);
            fixture.hold(store, dependency, NOW);
            named.add(dependency);
        }
        fixture.publish(store, named, NOW);
        ClosureSection.Closure closure = answered(fixture, store);
        int recorded = closure.components().size() + closure.foreign().size();
        expect(fixture, recorded <= ClosureSource.MAX_COMPONENTS, "at most " + ClosureSource.MAX_COMPONENTS
                + " components are recorded, " + recorded + " were");
        expect(fixture, closure.truncated() && closure.status() == ClosureSection.Status.PARTIAL,
                "a closure stopped at its bound says so: truncated " + closure.truncated() + ", "
                        + closure.status());
    }

    private static void failedReadRaises(ClosureFixture fixture, ArtifactStore store) throws IOException {
        ClosureFixture.Named held = fixture.dependency(0);
        fixture.hold(store, held, NOW);
        fixture.publish(store, List.of(held), NOW);
        FaultInjectingStore failing = FaultInjectingStore.wrap(store);
        for (FaultInjectingStore.Op op : List.of(FaultInjectingStore.Op.READ, FaultInjectingStore.Op.OPEN,
                FaultInjectingStore.Op.OPEN_FROM, FaultInjectingStore.Op.READ_VERSIONED)) {
            failing.failEveryOn(op, FaultInjectingStore.anyKey());
        }
        Optional<ClosureSection.Closure> answer;
        try {
            answer = ask(fixture, failing);
        } catch (IOException | UncheckedIOException raised) {
            return;
        }
        throw new AssertionError(fixture.source() + ": over a store whose every read fails, the source must raise; "
                + "it answered " + answer.orElse(null));
    }

    private static void fetchesNothing(ClosureFixture fixture, ArtifactStore store) throws IOException {
        fixture.publish(store, List.of(fixture.dependency(0)), NOW);
        List<String> before = NoEgressResolver.attempted();
        ask(fixture, store);
        List<String> reached = NoEgressResolver.since(before);
        expect(fixture, reached.isEmpty(), "nothing is fetched for a package the walk does not hold; it reached for "
                + reached);
    }

    /** {@code dependency} held for review: its hold's subject recorded and its review pointer linked, as the screen
     *  holds a proxied copy at its fill - over a holding, the same pointer withholds it. */
    private static void holdForReview(ClosureFixture fixture, ArtifactStore store, ClosureFixture.Named dependency)
            throws IOException {
        Publication publication = new Publication(store);
        HeldSubjects.hold(publication, store, fixture.path(dependency),
                publication.storeBlob(new ByteArrayInputStream(new byte[]{1})), fixture.ecosystem(),
                dependency.coordinate(), dependency.version());
    }

    private static Optional<ClosureSection.Closure> ask(ClosureFixture fixture, ArtifactStore store)
            throws IOException {
        ClosureFixture.Named release = fixture.release();
        return ClosureTask.ask(source(fixture), ClosureWalk.of(store), fixture.ecosystem(), release.coordinate(),
                release.version(), NOW);
    }

    private static ClosureSection.Closure answered(ClosureFixture fixture, ArtifactStore store) throws IOException {
        return ask(fixture, store).orElseThrow(() -> new AssertionError(fixture.source() + ": a release naming what "
                + "its source reads is answered, and this one was not"));
    }

    private static boolean component(ClosureSection.Closure closure, ClosureFixture.Named named) {
        return closure.components().stream().anyMatch(component -> component.coordinate().equals(named.coordinate())
                && component.version().equals(named.version()));
    }

    private static boolean cut(ClosureSection.Closure closure, ClosureFixture.Named named) {
        return closure.cuts().stream().anyMatch(cut -> cut.coordinate().equals(named.coordinate()));
    }

    private static void expect(ClosureFixture fixture, boolean holds, String expectation) {
        if (!holds) {
            throw new AssertionError(fixture.source() + ": " + expectation);
        }
    }
}
