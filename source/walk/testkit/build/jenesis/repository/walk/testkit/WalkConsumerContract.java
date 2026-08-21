package build.jenesis.repository.walk.testkit;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import build.jenesis.repository.walk.RebuildPass;
import build.jenesis.repository.walk.Trees;
import build.jenesis.repository.walk.WalkConsumer;
import build.jenesis.repository.walk.WalkPass;
import build.jenesis.repository.walk.WalkSegment;
import build.jenesis.repository.walk.testkit.WalkConsumerFixture.Corpus;

/**
 * The executable {@link WalkConsumer} contract: one parameterized body of checks that every consumer runs through a
 * {@link WalkConsumerFixture}, so the two promises the SPI's javadoc has always made in prose - <em>idempotent</em>
 * delivery and <em>at-least-once</em> crash-resume - are asserted rather than trusted.
 *
 * <p><b>What the kit does.</b> It seeds a corpus that straddles several checkpoint strides, runs a pass, kills it at
 * a named point, restarts the consumer <em>as a fresh instance</em> (a crashed process keeps no memory), resumes, and
 * then asks the fixture whether its durable projection converged. Six crash points are injected rather than one,
 * because they are not the same failure: nothing delivered yet, mid-stride, a full stride delivered with the cursor
 * commit dying <em>before</em> it lands, the same with the commit landing but the caller never learning it did,
 * the terminal segment commit, and the pass-completion hook. Each one leaves the walk in a different durable state,
 * and a consumer can be correct at five of them and lose data at the sixth.
 *
 * <p><b>Convergence is the fixture's declaration, not the kit's guess</b> - see {@link WalkConsumerFixture#projection}.
 * Two correct consumers hold different bytes for the same converged view, so the kit compares a normalised projection
 * the consumer's own contract defines, never stored bytes.
 *
 * <p><b>The kit never claims a stronger delivery property than the consumer provides</b> (the plan's gate 5). A
 * fixture declares its {@link WalkConsumerFixture.Delivery} class, and the post-resume assertion follows from it: a
 * per-item or stride-durable consumer must already be converged, while a pass-snapshot consumer must be either
 * converged or <em>visibly degraded</em> - and may never be a partial projection presented as a whole one, which is
 * exactly what &sect;5 forbids. The crash is injected with {@link FaultInjectingStore}, the one shared fault fixture,
 * armed by the delivery count the consumer itself reports, so a crash point is defined by what the consumer had seen
 * rather than by counting the walk's internal store calls.
 *
 * <p>Assertion-library-free on purpose: a check throws {@link AssertionError} naming the consumer, the property and
 * the expectation, so this module stays {@code java.base} + the walk SPI + the store testkit and the downstream
 * distribution can require it for its own fixtures exactly as it already requires the store testkit. The JUnit driver
 * lives under {@code test/**} and turns each check into one dynamic test.
 *
 * <h2>Clauses this kit discharges (T-304)</h2>
 * restating the
 * clause numbers the {@link Property} javadocs already cite: 2 and 5
 * ({@code FULL_PASS_REBUILDS_THE_DECLARED_PROJECTION}, {@code SECOND_PASS_OVER_UNCHANGED_STATE_IS_A_NO_OP},
 * {@code CRASH_MID_STRIDE_CONVERGES}), 6 ({@code A_FAILED_PASS_IS_RESUMABLE_NEVER_SILENTLY_COMPLETE}), 10
 * ({@code PASS_HOOKS_BRACKET_EVERY_DELIVERY}) and 12 ({@code CRASH_BEFORE_THE_FIRST_DELIVERY_CONVERGES}).
 *
 * @jenesis.covers build.jenesis.repository.walk.WalkConsumer 2, 5, 6, 10, 12
 */
public final class WalkConsumerContract {

    /** How many strides the corpus spans, plus one artifact past the last one - so a stride boundary and a
     *  non-boundary segment end are both exercised, and the terminal commit is never also a stride commit. */
    private static final int STRIDES = 3;

    /**
     * One documented contract clause. The enum is the kit's vocabulary: a fixture excludes a property by name and
     * reason, and the census fails on a property no fixture anywhere exercises, so the list can never grow a clause
     * that is asserted nowhere.
     */
    public enum Property {
        /** A consumer switched on over a store that already holds artifacts rebuilds its whole declared projection
         *  from the walk alone, delivering each retained pointer exactly once and writing nowhere outside its own
         *  namespaces (&sect;5, {@code WalkConsumer} clauses 2 and 5). */
        FULL_PASS_REBUILDS_THE_DECLARED_PROJECTION,
        /** Both dialects a stored pointer body uses reach the consumer: the bare lower-case SHA-256 hex the free
         *  {@code publish/} and {@code blobs/} pointers carry, and the algorithm-qualified {@code sha256:<hex>} an OCI
         *  tag pointer carries. They name the same blob, so a corpus spelling its hashes either way converges to the
         *  one declared projection - a pass that recognised only one of them would hand a consumer over that root
         *  nothing at all and still report the pass complete (&sect;5). */
        BOTH_POINTER_DIALECTS_ARE_DELIVERED,
        /** {@code onPassStarted} precedes this worker's first delivery, {@code beforeCheckpoint} follows the
         *  deliveries it covers, and {@code onPassCompleted} closes the pass - once each (clause 10). */
        PASS_HOOKS_BRACKET_EVERY_DELIVERY,
        /** A second full pass over unchanged stored state leaves the projection and the consumer's key space exactly
         *  as the first left them - a converge pass, not a generator of state (clause 2). */
        SECOND_PASS_OVER_UNCHANGED_STATE_IS_A_NO_OP,
        /** The pass dies before a single artifact reaches the consumer: no cursor was committed, so the resume starts
         *  the range over and the consumer converges (clause 12). */
        CRASH_BEFORE_THE_FIRST_DELIVERY_CONVERGES,
        /** The pass dies inside a stride, past one committed cursor: the resume replays the uncommitted tail, and at
         *  most one stride of it - the at-least-once half of clause 2. */
        CRASH_MID_STRIDE_CONVERGES,
        /** A full stride was delivered and the cursor commit dies <em>before</em> landing: the previous cursor stands
         *  and the whole stride replays, so nothing a consumer wrote can be stranded ahead of the cursor. */
        CRASH_BETWEEN_A_DURABLE_WRITE_AND_ITS_CHECKPOINT_CONVERGES,
        /** The same stride's cursor <em>lands</em> and the caller never learns it did - the lost-ack window. The
         *  stride is never replayed, so only a consumer whose derived writes precede the cursor converges: this is the
         *  check that separates a safe buffering consumer from a lossy one. */
        CRASH_AFTER_THE_CHECKPOINT_LANDED_CONVERGES,
        /** Everything was delivered and the terminal segment commit dies: the segment is not done, the resume replays
         *  only the tail past the last stride, and a pass-snapshot consumer must not commit that fragment. */
        CRASH_AT_SEGMENT_COMPLETION_CONVERGES,
        /** The walk finished and {@code onPassCompleted} dies - the pass-snapshot commit window. The pass is durably
         *  complete, so the next run is a fresh generation and a full re-walk. */
        CRASH_AT_PASS_COMPLETION_CONVERGES,
        /** A failed pass is left resumable and visibly incomplete: {@code ArtifactWalk.pass} still reports it active
         *  and its segment carries the cursor it reached, so a stuck rebuild is legible instead of looking done
         *  (gate 4, clause 6). */
        A_FAILED_PASS_IS_RESUMABLE_NEVER_SILENTLY_COMPLETE
    }

    /**
     * Where a pass is killed. Each point is armed off the consumer's own delivery count, so it is defined by what the
     * consumer had already seen - not by counting the walk's internal store calls, which would silently move the crash
     * the day the implementation changed. Every check re-asserts, from the durable pass state, that the crash really
     * landed where the point says it did; a point that stopped biting would fail rather than pass vacuously.
     */
    public enum CrashPoint {

        /** Before the first {@code onRetained}: the first pointer's own metadata read fails. */
        BEFORE_THE_FIRST_DELIVERY(Property.CRASH_BEFORE_THE_FIRST_DELIVERY_CONVERGES),
        /** Two artifacts past a committed cursor, inside the next stride. */
        MID_STRIDE(Property.CRASH_MID_STRIDE_CONVERGES),
        /** A whole stride delivered; the cursor commit throws before it lands. */
        BETWEEN_A_DURABLE_WRITE_AND_ITS_CHECKPOINT(Property.CRASH_BETWEEN_A_DURABLE_WRITE_AND_ITS_CHECKPOINT_CONVERGES),
        /** A whole stride delivered; the cursor commit lands and then throws - the lost ack. */
        AFTER_THE_CHECKPOINT_LANDED(Property.CRASH_AFTER_THE_CHECKPOINT_LANDED_CONVERGES),
        /** Everything delivered; the terminal segment commit throws before it lands. */
        AT_SEGMENT_COMPLETION(Property.CRASH_AT_SEGMENT_COMPLETION_CONVERGES),
        /** The walk completed; the {@code onPassCompleted} hook dies before the consumer can commit. */
        AT_PASS_COMPLETION(Property.CRASH_AT_PASS_COMPLETION_CONVERGES);

        private final Property property;

        CrashPoint(Property property) {
            this.property = property;
        }

        /** The contract property this crash point proves. */
        public Property property() {
            return property;
        }
    }

    /** One named, independently runnable contract check. */
    public record Check(Property property, String name, Body body) {

        public Check {
            Objects.requireNonNull(property, "property");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(body, "body");
        }
    }

    /** The body of a {@link Check}, run against a fixture, the driver's walk, and a fresh, empty, fault-armable
     *  store. */
    @FunctionalInterface
    public interface Body {
        void run(WalkConsumerFixture fixture, WalkHarness harness, FaultInjectingStore store) throws Exception;
    }

    private WalkConsumerContract() {
    }

    /**
     * Every contract check, in declaration order, independent of any fixture. The list is the contract: a consumer
     * runs all of it or names - with a reason - the properties its shape does not have.
     */
    public static List<Check> checks() {
        List<Check> checks = new ArrayList<>();
        checks.add(new Check(Property.FULL_PASS_REBUILDS_THE_DECLARED_PROJECTION,
                "a consumer switched on late rebuilds its whole projection from the walk alone",
                WalkConsumerContract::fullPassRebuildsTheDeclaredProjection));
        checks.add(new Check(Property.BOTH_POINTER_DIALECTS_ARE_DELIVERED,
                "a sha256:-prefixed pointer body is delivered exactly as a bare-hex one is",
                WalkConsumerContract::bothPointerDialectsAreDelivered));
        checks.add(new Check(Property.PASS_HOOKS_BRACKET_EVERY_DELIVERY,
                "the pass hooks bracket every delivery and the flush hook precedes every cursor commit",
                WalkConsumerContract::passHooksBracketEveryDelivery));
        checks.add(new Check(Property.SECOND_PASS_OVER_UNCHANGED_STATE_IS_A_NO_OP,
                "a second full pass over unchanged state changes nothing durable",
                WalkConsumerContract::secondPassOverUnchangedStateIsANoOp));
        for (CrashPoint point : CrashPoint.values()) {
            checks.add(new Check(point.property(),
                    "a crash " + point.name().toLowerCase(Locale.ROOT).replace('_', ' ')
                            + " resumes and converges to the declared projection",
                    (fixture, harness, store) -> crashResumeConverges(fixture, harness, store, point)));
        }
        checks.add(new Check(Property.A_FAILED_PASS_IS_RESUMABLE_NEVER_SILENTLY_COMPLETE,
                "a failed pass stays active and resumable rather than reporting itself complete",
                WalkConsumerContract::aFailedPassIsResumableNeverSilentlyComplete));
        return List.copyOf(checks);
    }

    /**
     * The checks {@code fixture} runs: every check whose property the fixture does not exclude. Excluding a property
     * without a reason fails here rather than silently shrinking the suite.
     */
    public static List<Check> checks(WalkConsumerFixture fixture) {
        Objects.requireNonNull(fixture, "fixture");
        fixture.unsupported().forEach((property, reason) -> {
            Objects.requireNonNull(property, "unsupported property");
            if (reason == null || reason.isBlank()) {
                throw new AssertionError("The '" + fixture.consumer() + "' fixture excludes " + property
                        + " without a reason; an exclusion must say which part of the consumer's shape does not have "
                        + "the property, and where the property is proven instead.");
            }
        });
        return checks().stream().filter(check -> !fixture.unsupported().containsKey(check.property())).toList();
    }

    // --- the contract ------------------------------------------------------------------------------------------

    private static void fullPassRebuildsTheDeclaredProjection(WalkConsumerFixture fixture, WalkHarness harness,
                                                              FaultInjectingStore store) throws Exception {
        // The history happened long before the plugin existed: the corpus is seeded, and only then is the consumer
        // switched on. A pass is its whole world - there is no publication event to have caught anything earlier.
        Corpus corpus = fixture.seed(store, artifacts(harness));
        equal(fixture.projection(store), Map.of(), fixture,
                "before its first pass a consumer's projection is empty - otherwise the rebuild below would be "
                        + "proving nothing");
        List<String> before = keys(store);

        Instrumented worker = pass(fixture, harness, store, null, corpus);

        equal(worker.deliveries, corpus.deliveries(), fixture,
                "one pass delivers every retained pointer exactly once" + selfFeeding(fixture));
        equal(fixture.projection(store), corpus.converged(), fixture,
                "the walk alone rebuilt the consumer's whole declared projection");
        equal(fixture.degradation(store), Optional.empty(), fixture,
                "a pass that converged leaves no degradation behind");

        // ... and it stayed in its own key space. Judged by walking the store rather than by trusting the consumer:
        // a projection written one namespace over is invisible to the equality above but is another plugin's data.
        List<String> allowed = new ArrayList<>(fixture.namespaces());
        allowed.add("walks");                                    // the walk's own durable pass state, not the consumer's
        List<String> escaped = keys(store).stream()
                .filter(key -> !before.contains(key))
                .filter(key -> allowed.stream().noneMatch(space -> key.equals(space) || key.startsWith(space + "/")))
                .toList();
        if (!escaped.isEmpty()) {
            throw failure(fixture, "the pass wrote " + escaped.size() + " key(s) outside this consumer's declared "
                    + "namespaces " + fixture.namespaces() + ": " + escaped);
        }
    }

    private static void bothPointerDialectsAreDelivered(WalkConsumerFixture fixture, WalkHarness harness,
                                                        FaultInjectingStore store) throws Exception {
        Corpus corpus = fixture.seed(store, artifacts(harness));

        // Every second seeded pointer is re-spelled in place as sha256:<hex>, the dialect an OCI tag pointer carries.
        // Nothing about the corpus changed - a qualified digest reference names the blob its bare hex names - so the
        // same deliveries and the same projection have to come back. Every SECOND one rather than all, because the
        // point is that the two dialects coexist inside one enumeration, not that one replaced the other.
        int requalified = requalify(fixture, store);
        isTrue(requalified > 0, fixture,
                "the corpus carries no bare-hex pointer to re-spell, so this check would prove nothing about the "
                        + "dialect it is named for");
        isTrue(requalified < corpus.deliveries(), fixture,
                "at least one pointer stays bare hex, so the pass is asked to read both dialects in one enumeration "
                        + "(re-spelled " + requalified + " of " + corpus.deliveries() + ")");

        Instrumented worker = pass(fixture, harness, store, null, corpus);

        equal(worker.deliveries, corpus.deliveries(), fixture,
                "every retained pointer is delivered whichever dialect its body spells the hash in (" + requalified
                        + " of " + corpus.deliveries() + " re-spelled as sha256:<hex>). A short count here means the "
                        + "pass reads a pointer body as bare hex only and silently drops every qualified one - so a "
                        + "consumer over a root whose pointers all carry that dialect is handed nothing at all and "
                        + "then reports itself converged, the silently-incomplete view \u00a75 forbids.");
        equal(fixture.projection(store), corpus.converged(), fixture,
                "and a qualified body resolves to the same blob the bare one does, so the projection is unchanged");
        equal(fixture.degradation(store), Optional.empty(), fixture,
                "a pass that converged leaves no degradation behind");
    }

    /**
     * Re-spell every second bare-hex pointer body under the fixture's roots as {@code sha256:<hex>} and answer how many
     * were re-spelled. A versioned rewrite against the token just read, so the kit changes a pointer exactly as a
     * writer of that pointer would; a leaf that is not a pointer at all (a sidecar row, a marker) is left alone,
     * because the corpus's non-pointer leaves are what make the delivery count meaningful in the first place.
     */
    private static int requalify(WalkConsumerFixture fixture, ArtifactStore store) throws IOException {
        List<String> keys = new ArrayList<>();
        for (String root : fixture.pointerRoots()) {
            Trees.descend(store, root, keys::add);
        }
        keys.sort(Comparator.naturalOrder());
        int requalified = 0;
        boolean turn = false;
        for (String key : keys) {
            Optional<ArtifactStore.Versioned> pointer = store.readVersioned(key);
            if (pointer.isEmpty()) {
                continue;
            }
            String body = new String(pointer.get().content(), StandardCharsets.UTF_8).trim();
            if (!bareHash(body)) {
                continue;
            }
            turn = !turn;
            if (!turn) {
                continue;
            }
            if (!store.writeVersioned(key, ("sha256:" + body).getBytes(StandardCharsets.UTF_8),
                    pointer.get().token())) {
                throw failure(fixture, "re-spelling the pointer at " + key + " lost its compare-and-set, so the "
                        + "corpus this check needs was never established");
            }
            requalified++;
        }
        return requalified;
    }

    /** Whether a pointer body is the bare lower-case SHA-256 hex - the dialect this check re-spells away from. */
    private static boolean bareHash(String body) {
        if (body.length() != 64) {
            return false;
        }
        for (int index = 0; index < body.length(); index++) {
            char character = body.charAt(index);
            if ((character < '0' || character > '9') && (character < 'a' || character > 'f')) {
                return false;
            }
        }
        return true;
    }

    private static void passHooksBracketEveryDelivery(WalkConsumerFixture fixture, WalkHarness harness,
                                                      FaultInjectingStore store) throws Exception {
        Corpus corpus = fixture.seed(store, artifacts(harness));

        Instrumented worker = pass(fixture, harness, store, null, corpus);

        List<String> events = worker.events;
        isTrue(events.size() > 2, fixture, "the recorded hook sequence is not vacuous (was " + events + ")");
        equal(events.getFirst(), "started", fixture,
                "onPassStarted fires before this worker's first delivery - the moment a snapshot rebuilder resets");
        equal(events.getLast(), "completed", fixture,
                "onPassCompleted closes the pass, after the last delivery");
        equal(events.stream().filter("started"::equals).count(), 1L, fixture, "onPassStarted fires once per worker");
        equal(events.stream().filter("completed"::equals).count(), 1L, fixture,
                "onPassCompleted fires once per worker");
        equal(events.stream().filter("retained"::equals).count(), (long) corpus.deliveries(), fixture,
                "every retained pointer arrives between the two hooks");

        // The flush hook is the consumer's half of the commit protocol: it must fire, and it must fire AFTER the
        // deliveries it covers. Were it forwarded before them - or not at all - a consumer that batches its derived
        // writes would be resumed past items still sitting in its buffer, which is the whole reason the walk's
        // KeyVisitor.beforeCheckpoint is carried through to consumers.
        isTrue(events.contains("checkpoint"), fixture,
                "beforeCheckpoint reaches the consumer: without it a batching consumer has no moment at which its "
                        + "derived write is ordered before the cursor that would skip it");
        equal(events.get(events.indexOf("checkpoint") - 1), "retained", fixture,
                "a checkpoint follows the deliveries it covers");
        isTrue(events.indexOf("checkpoint") > events.indexOf("started"), fixture,
                "no flush is asked for before the worker has been told the pass started");
        int strides = corpus.deliveries() / harness.checkpoint();
        isTrue(events.stream().filter("checkpoint"::equals).count() >= strides, fixture,
                "a flush per committed stride at least (" + strides + " expected over " + corpus.deliveries()
                        + " deliveries with a stride of " + harness.checkpoint() + ")");
    }

    private static void secondPassOverUnchangedStateIsANoOp(WalkConsumerFixture fixture, WalkHarness harness,
                                                            FaultInjectingStore store) throws Exception {
        Corpus corpus = fixture.seed(store, artifacts(harness));

        Instrumented first = pass(fixture, harness, store, null, corpus);
        Map<String, String> after = fixture.projection(store);
        List<String> owned = owned(fixture, store);
        equal(after, corpus.converged(), fixture, "the first pass converged, or the no-op below means nothing");

        // A fresh process, a fresh generation, the same store: nothing changed out there, so nothing may change in
        // here. A consumer that appends per pass, stamps a growing history, or re-keys its rows would diverge - and
        // would grow without bound over a store that never changes.
        Instrumented second = pass(fixture, harness, store, null, corpus);

        equal(second.deliveries, corpus.deliveries(), fixture, "the second pass really enumerated the corpus again");
        isTrue(second.generation > first.generation, fixture,
                "a second pass is a new generation (" + first.generation + " -> " + second.generation + "), not a "
                        + "rejoin of the finished one");
        equal(fixture.projection(store), after, fixture,
                "a second pass over unchanged stored state leaves the declared projection exactly as it was");
        equal(owned(fixture, store), owned, fixture,
                "and leaves the consumer's key space exactly as it was - no per-pass residue accumulating under it");
    }

    private static void crashResumeConverges(WalkConsumerFixture fixture, WalkHarness harness,
                                             FaultInjectingStore store, CrashPoint point) throws Exception {
        Corpus corpus = fixture.seed(store, artifacts(harness));

        // 1. The process that dies. Its consumer instance dies with it - every later run builds a fresh one, so an
        //    in-memory accumulation is lost exactly as it would be under a real kill.
        Instrumented crashed = pass(fixture, harness, store, point, corpus);
        notNull(crashed.failure, fixture, point + ": the injected fault must fail the pass. It did not, so this "
                + "check no longer kills anything and everything below it is vacuous.");
        store.heal();
        landed(fixture, harness, store, point, corpus, crashed);
        long crashedGeneration = harness.walk().pass(store, RebuildPass.CONSUMER).orElseThrow().generation();

        // 2. The crashed worker stays dead long enough for its claim to expire; another process resumes the pass.
        harness.expireClaims();
        Instrumented resumed = pass(fixture, harness, store, null, corpus);
        isTrue(resumed.complete, fixture, point + ": the resumed pass completes");
        if (point != CrashPoint.AT_PASS_COMPLETION) {
            equal(resumed.generation, crashedGeneration, fixture,
                    point + ": a resume joins the crashed pass rather than restarting a new one");
            long replayed = crashed.deliveries + resumed.deliveries - corpus.deliveries();
            isTrue(replayed >= 0, fixture,
                    point + ": no retained pointer may be skipped across the crash (delivered "
                            + (crashed.deliveries + resumed.deliveries) + " of " + corpus.deliveries() + ")");
            isTrue(replayed <= harness.checkpoint(), fixture,
                    point + ": at-least-once means at most the uncommitted stride tail is replayed, never the whole "
                            + "pass (replayed " + replayed + ", stride " + harness.checkpoint() + ")");
        }

        // 3. Converged? Exactly as strongly as this consumer's delivery class allows, and no more.
        Map<String, String> projection = fixture.projection(store);
        Optional<String> degradation = fixture.degradation(store);
        if (fixture.delivery().convergesAcrossACrash()) {
            equal(projection, corpus.converged(), fixture, point + ": a " + fixture.delivery()
                    + " consumer's derived writes are ordered before the cursor that would skip them, so the resume "
                    + "leaves it converged. A projection missing entries here means items were covered by a "
                    + "committed cursor while their derived writes were still in the consumer's hands.");
            equal(degradation, Optional.empty(), fixture,
                    point + ": a consumer that converged reports no degradation");
        } else if (!projection.equals(corpus.converged())) {
            // The honest weaker claim: a pass-snapshot consumer is NOT converged by a resume, because it accumulates
            // in memory and the resume replays only the tail. What it must never do is quietly publish that fragment.
            isTrue(projection.isEmpty(), fixture, point + ": a " + fixture.delivery() + " consumer did not converge "
                    + "(which is legal), but it published a PARTIAL projection " + projection.keySet() + " where the "
                    + "converged one has " + corpus.converged().size() + " entries. A rebuild that replaces a whole "
                    + "view with the fragment a resumed pass happened to deliver is the silently-incomplete view "
                    + "\u00a75 forbids: refuse the commit and say so instead.");
            isTrue(degradation.isPresent(), fixture, point + ": a " + fixture.delivery() + " consumer that could not "
                    + "converge must SAY so durably - degrade-and-say-so is recorded per consumer, never silent.");
        }

        // 4. ... and every class self-heals: one more full pass, and the projection is the converged one.
        Instrumented healed = pass(fixture, harness, store, null, corpus);
        equal(healed.deliveries, corpus.deliveries(), fixture, point + ": the healing pass re-enumerates the corpus");
        equal(fixture.projection(store), corpus.converged(), fixture,
                point + ": a full pass after the crash converges the projection - a consumer that cannot be healed by "
                        + "re-running the walk has no self-heal route at all (\u00a75)");
        equal(fixture.degradation(store), Optional.empty(), fixture,
                point + ": the converged pass clears the degradation it may have recorded");
    }

    private static void aFailedPassIsResumableNeverSilentlyComplete(WalkConsumerFixture fixture, WalkHarness harness,
                                                                    FaultInjectingStore store) throws Exception {
        Corpus corpus = fixture.seed(store, artifacts(harness));

        Instrumented crashed = pass(fixture, harness, store, CrashPoint.MID_STRIDE, corpus);
        notNull(crashed.failure, fixture, "the injected fault failed the pass");
        store.heal();

        WalkPass pass = harness.walk().pass(store, RebuildPass.CONSUMER).orElseThrow(() -> failure(fixture,
                "a pass that ran and failed still has durable state to read - an observability surface cannot report "
                        + "'stuck' about a pass it cannot see"));
        isTrue(!pass.complete(), fixture,
                "a pass whose worker died is ACTIVE, never COMPLETE. Reporting it complete would tell every "
                        + "walk-riding surface the rebuild is done while a range of the store was never enumerated - "
                        + "a plausible but incomplete answer (gate 4).");
        equal(pass.done(), 0, fixture, "the segment the dead worker held is not counted as done");

        WalkSegment segment = segment(fixture, harness, store);
        isTrue(segment.state() != WalkSegment.State.DONE, fixture, "the dead worker's segment is not done");
        notNull(segment.cursor(), fixture,
                "the segment carries the cursor the dead worker reached, so the resume continues from it rather than "
                        + "restarting the range");
        notNull(segment.holder(), fixture, "and names the holder whose lease has to expire before a takeover");
    }

    // --- running one pass --------------------------------------------------------------------------------------

    /** How many artifacts a check seeds: several checkpoint strides plus one, so a stride boundary and a
     *  non-boundary segment end are both exercised. */
    private static int artifacts(WalkHarness harness) {
        if (harness.checkpoint() < 2) {
            throw new AssertionError("the harness must configure a checkpoint stride of at least 2, or the crash "
                    + "points inside and at the end of a stride are the same point");
        }
        return STRIDES * harness.checkpoint() + 1;
    }

    /** Run one pass with a fresh consumer instance, optionally arming {@code point}; failures are captured rather
     *  than thrown, because a crash check asserts on the durable state a failed pass left behind. */
    private static Instrumented pass(WalkConsumerFixture fixture, WalkHarness harness, FaultInjectingStore store,
                                     CrashPoint point, Corpus corpus) {
        Instrumented worker = new Instrumented(fixture.create(), store, point, corpus.deliveries(),
                harness.checkpoint(), pointers(fixture));
        try {
            worker.observed(RebuildPass.run(harness.walk(), store, fixture.pointerRoots(), List.of(worker)));
        } catch (Throwable failure) {
            worker.failure = failure;
        }
        return worker;
    }

    /** The consumer under test, wrapped so the kit can see what it was handed and arm the crash off that count. The
     *  wrapper never changes what the delegate observes: it forwards first and arms afterwards. */
    private static final class Instrumented implements WalkConsumer {

        private final WalkConsumer delegate;
        private final FaultInjectingStore store;
        private final CrashPoint point;
        private final int corpus;
        private final int checkpoint;
        private final Predicate<String> pointers;
        private final List<String> events = new ArrayList<>();
        private int deliveries;
        private long generation = -1;
        private boolean complete;
        private Throwable failure;

        private Instrumented(WalkConsumer delegate, FaultInjectingStore store, CrashPoint point, int corpus,
                             int checkpoint, Predicate<String> pointers) {
            this.delegate = delegate;
            this.store = store;
            this.point = point;
            this.corpus = corpus;
            this.checkpoint = checkpoint;
            this.pointers = pointers;
            if (point == CrashPoint.BEFORE_THE_FIRST_DELIVERY) {
                // The first pointer's own metadata read fails, before the pass has told the consumer anything at all.
                store.failNextOn(FaultInjectingStore.Op.SIZE, pointers);
            }
        }

        private void observed(Optional<WalkPass> pass) {
            pass.ifPresent(value -> {
                generation = value.generation();
                complete = value.complete();
            });
        }

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public void onRetained(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
            events.add("retained");
            delegate.onRetained(artifact, store);
            deliveries++;
            arm();
        }

        @Override
        public void beforeCheckpoint(String cursor) throws IOException {
            events.add("checkpoint");
            delegate.beforeCheckpoint(cursor);
        }

        @Override
        public void onPassStarted(WalkPass pass) {
            events.add("started");
            generation = pass.generation();
            delegate.onPassStarted(pass);
        }

        @Override
        public void onPassCompleted(WalkPass pass) {
            events.add("completed");
            if (point == CrashPoint.AT_PASS_COMPLETION) {
                // The process dies in the pass-completion window - before the consumer commits, which for a snapshot
                // rebuilder is the only moment it ever writes.
                throw new UncheckedIOException(new IOException("injected crash at pass completion"));
            }
            delegate.onPassCompleted(pass);
        }

        /** Arm the fault for the crash point once the consumer has seen the deliveries that define it. The walk's
         *  own pass-state writes are the target for the commit-window points, so the consumer's derived writes are
         *  never the thing that fails - the crash is the node dying, not the consumer misbehaving. */
        private void arm() {
            if (point == null) {
                return;
            }
            Predicate<String> cursorCommit = FaultInjectingStore.keyPrefix("walks/");
            switch (point) {
                // The store goes away mid-enumeration, two artifacts past a cursor that did land.
                case MID_STRIDE -> {
                    if (deliveries == checkpoint + 2) {
                        store.failNextOn(FaultInjectingStore.Op.SIZE, pointers);
                    }
                }
                // A whole stride is delivered; the cursor commit that would cover it never lands.
                case BETWEEN_A_DURABLE_WRITE_AND_ITS_CHECKPOINT -> {
                    if (deliveries == checkpoint) {
                        store.failNextOn(FaultInjectingStore.Op.WRITE_VERSIONED, cursorCommit);
                    }
                }
                // The same commit lands and the worker dies before it learns so - the lost ack. The stride is now
                // covered and will never be replayed, whatever the consumer still had in hand.
                case AFTER_THE_CHECKPOINT_LANDED -> {
                    if (deliveries == checkpoint) {
                        store.crashAfterWrite(FaultInjectingStore.Op.WRITE_VERSIONED, cursorCommit);
                    }
                }
                // Everything is delivered and the terminal segment commit dies.
                case AT_SEGMENT_COMPLETION -> {
                    if (deliveries == corpus) {
                        store.failNextOn(FaultInjectingStore.Op.WRITE_VERSIONED, cursorCommit);
                    }
                }
                default -> {
                    // BEFORE_THE_FIRST_DELIVERY is armed at construction; AT_PASS_COMPLETION fires from the hook.
                }
            }
        }
    }

    // --- the crash really landed where the point says ------------------------------------------------------------

    /**
     * Re-derive, from the durable pass state a crash left behind, that the crash landed where {@link CrashPoint} says.
     * Every crash point is armed off a delivery count, so a change to the walk's commit sequence would otherwise move
     * a crash somewhere else and leave this suite green while testing something entirely different.
     */
    private static void landed(WalkConsumerFixture fixture, WalkHarness harness, FaultInjectingStore store,
                               CrashPoint point, Corpus corpus, Instrumented crashed) throws IOException {
        int expected = switch (point) {
            case BEFORE_THE_FIRST_DELIVERY -> 0;
            case MID_STRIDE -> harness.checkpoint() + 2;
            case BETWEEN_A_DURABLE_WRITE_AND_ITS_CHECKPOINT, AFTER_THE_CHECKPOINT_LANDED -> harness.checkpoint();
            case AT_SEGMENT_COMPLETION, AT_PASS_COMPLETION -> corpus.deliveries();
        };
        equal(crashed.deliveries, expected, fixture,
                point + ": the crash must land after exactly " + expected + " deliveries, or it is not the window "
                        + "this check names");

        if (point == CrashPoint.AT_PASS_COMPLETION) {
            isTrue(harness.walk().pass(store, RebuildPass.CONSUMER).orElseThrow().complete(), fixture,
                    point + ": the walk completed durably before the hook died - that is what makes the next run a "
                            + "fresh generation rather than a resume");
            return;
        }
        isTrue(!harness.walk().pass(store, RebuildPass.CONSUMER).orElseThrow().complete(), fixture,
                point + ": a crashed pass is left active");
        WalkSegment segment = segment(fixture, harness, store);
        boolean committed = switch (point) {
            case BEFORE_THE_FIRST_DELIVERY, BETWEEN_A_DURABLE_WRITE_AND_ITS_CHECKPOINT -> false;
            default -> true;
        };
        equal(segment.cursor() != null, committed, fixture, point + ": the cursor "
                + (committed ? "landed before the crash" : "never landed, so the previous one still stands")
                + " - it is " + (segment.cursor() == null ? "absent" : "present") + ", which is the opposite of what "
                + "this crash point exists to create");
        isTrue(segment.state() != WalkSegment.State.DONE, fixture,
                point + ": the segment the crashed worker held is not done");
    }

    /** The single segment the harness's plan cuts, so a crash check reads one unambiguous cursor. */
    private static WalkSegment segment(WalkConsumerFixture fixture, WalkHarness harness, FaultInjectingStore store)
            throws IOException {
        List<WalkSegment> segments = harness.walk().segments(store, RebuildPass.CONSUMER);
        if (segments.size() != 1) {
            throw failure(fixture, "the harness must cut the pass into exactly one segment so a crash point names one "
                    + "cursor; it cut " + segments.size() + ". Lower the walk's segment target, or seed one root.");
        }
        return segments.getFirst();
    }

    // --- helpers -----------------------------------------------------------------------------------------------

    /** Keys under any of this fixture's pointer roots - what {@code RebuildPass} reads to build a delivery, and so
     *  where a "the store went away mid-enumeration" fault belongs. */
    private static Predicate<String> pointers(WalkConsumerFixture fixture) {
        List<String> roots = List.copyOf(fixture.pointerRoots());
        return key -> key != null && roots.stream().anyMatch(root -> key.startsWith(root + "/"));
    }

    /** Every stored key, found through the shared descent primitive rather than a hand-rolled walk - so a consumer
     *  that planted a deep key cannot overflow the check meant to catch it. */
    /**
     * The hint a self-feeding consumer's author needs when the delivery count does not match (D-255).
     *
     * <p>A consumer whose namespaces overlap the roots the pass enumerates writes rows that the same pass then
     * enumerates, so its count is not one per seeded artifact. That is legitimate - a module view really is a
     * serving pointer - but it is invisible from the failure alone, and the first fixture in this position worked
     * it out by hand. Saying it here is what stops the next one repeating that.
     */
    private static String selfFeeding(WalkConsumerFixture fixture) {
        List<String> overlapping = fixture.namespaces().stream()
                .filter(namespace -> fixture.pointerRoots().stream()
                        .anyMatch(root -> namespace.startsWith(root) || root.startsWith(namespace)))
                .toList();
        return overlapping.isEmpty() ? "" : ". NOTE: this consumer writes into " + overlapping + ", which the pass "
                + "also enumerates, so its rows feed the walk that wrote them and the count is NOT one per seeded "
                + "artifact - declare what the pass really delivers, and say in the fixture why that number is "
                + "stable (see WalkConsumerFixture.Corpus)";
    }

    private static List<String> keys(ArtifactStore store) throws IOException {
        List<String> keys = new ArrayList<>();
        for (String top : store.list("")) {
            Trees.descend(store, top, keys::add);
        }
        keys.sort(Comparator.naturalOrder());
        return keys;
    }

    /** Every stored key inside this consumer's declared namespaces - its own key space, whose stability across a
     *  repeated pass is half of what "idempotent" means. */
    private static List<String> owned(WalkConsumerFixture fixture, ArtifactStore store) throws IOException {
        List<String> owned = new ArrayList<>();
        for (String space : fixture.namespaces()) {
            Trees.descend(store, space, owned::add);   // an absent namespace simply yields nothing
        }
        owned.sort(Comparator.naturalOrder());
        return owned;
    }

    private static void equal(Object actual, Object expected, WalkConsumerFixture fixture, String what) {
        if (!Objects.deepEquals(actual, expected)) {
            throw failure(fixture, what + " - expected " + expected + " but was " + actual);
        }
    }

    private static void isTrue(boolean actual, WalkConsumerFixture fixture, String what) {
        if (!actual) {
            throw failure(fixture, what);
        }
    }

    private static void notNull(Object actual, WalkConsumerFixture fixture, String what) {
        if (actual == null) {
            throw failure(fixture, what + " - expected a value but was null");
        }
    }

    private static AssertionError failure(WalkConsumerFixture fixture, String message) {
        return new AssertionError(fixture.consumer() + ": " + message);
    }
}
