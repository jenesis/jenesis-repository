package build.jenesis.repository.store.testkit;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.store.PublishInterceptor.Disposition;

/**
 * One deliberately broken commit choreography, for the twenty {@link PublicationHookContract.Property} clauses that
 * are about {@link Publication} rather than about a hook (D-148).
 *
 * <h2>The problem this exists for</h2>
 * D-135's {@link Mutant} substitutes the <em>hook</em>, which is the right subject for the clauses a provider owns.
 * Twenty of the kit's forty-six clauses are not those: they are claims about the choreography the hooks plug into -
 * the chain's ordering, its short-circuiting, where a review pointer lands relative to {@code committed}, what escapes
 * the containment, three crash windows - asserted with kit-owned probe screens while the fixture's hook rides along as
 * a bystander. No substitution for a hook can falsify them, so they sat on a reviewed {@code UNFALSIFIABLE} list, and
 * the consequence was worth stating out loud: <b>the falsification leg proved things about implementations and said
 * nothing about the choreography they plug into</b> - which is where several of this plan's crash-window claims live.
 *
 * <h2>Why the subject is not substituted, and what is substituted instead</h2>
 * {@link Publication} is {@code final}, and that is deliberate rather than incidental: the class exists to be the
 * product's <em>one</em> hosted-publish choreography, which is a structural claim an interface seam would give away
 * (the plan's third design gate - "extend the existing choke point; never add a parallel one" - and &sect;2's
 * single-edge rule). A test seam that let a caller supply a different commit sequence is exactly the second pipeline
 * the class is there to prevent, so it is not worth buying falsifiability with it. That argument is recorded in
 * {@code Publication}'s own javadoc, where a reader who wonders why they cannot substitute it meets it.
 *
 * <p>So the substitution goes where the kit <em>can</em> reach: the hook list handed to
 * {@link PublicationHookContract#publication}, which every check builds its publication through. Each mutant here
 * arranges those hooks so that the choreography <b>produces exactly the observable a mutated {@code Publication} would
 * produce</b> - a chain that stops at the first {@code REJECT}, a {@code committed} that skips the neutral verdict, a
 * review pointer that is not there when {@code committed} fires, a failure that is swallowed where it must propagate.
 * The check then has to say otherwise.
 *
 * <h2>What that proves, and what it does not</h2>
 * It proves the check <em>discriminates on the observable the clause names</em>, and the check is driving the real
 * {@code Publication}, so a change to the product that produced that observable would turn it red. That is the
 * property a falsification leg is for.
 *
 * <p>It does <b>not</b> prove that {@code Publication} is the only thing that could produce the observable: the
 * mutant is a faithful simulation of the defect rather than the defect itself. A mutation of the product would be
 * strictly stronger, and reaching one would mean compiling or instrumenting the class - a mechanism this kit does not
 * have and that would be a heavier thing to own than the gap it closes. <b>That is the honest limit of this leg, and
 * it is written here rather than in a ticket, because the next reader meets the code.</b>
 *
 * <p>One clause resists even this. {@link PublicationHookContract.Property#THE_BLOB_TO_CHAIN_CRASH_WINDOW_LEAVES_ONLY_AN_UNREFERENCED_BLOB}
 * kills the publish <em>before the chain runs at all</em>, so nothing the kit hands {@code Publication} is ever
 * invoked and there is no observable to arrange. It stays on the census's reviewed list, alone.
 *
 * <p>{@link #NONE} is the identity: the product's own choreography, which every ordinary check drives.
 */
public enum ChoreographyMutant {

    /** Nothing is arranged - the product's own commit choreography, which the contract's ordinary leg drives. */
    NONE("nothing"),

    /**
     * The additivity of the chain: a screen already asked once for this artifact is not asked again, so the same
     * screen registered twice votes once. The "helpful de-duplication" a provider registry would perform, in the one
     * family that has no {@code name()} and therefore none of the shared packaging guards.
     */
    A_CHAIN_THAT_ASKS_A_REPEATED_SCREEN_ONCE("the chain's additivity - a screen already asked for this artifact is "
            + "not asked again, so one registered twice votes once"),

    /**
     * Clause 11's counter-intuitive half: the chain stops asking once a screen has answered {@code REJECT}. It is the
     * plausible reading of a screening chain, it is what a kit written from that reading would assert, and it would
     * silently stop a recording screen from ever seeing a rejected artifact.
     */
    A_CHAIN_THAT_STOPS_AT_THE_FIRST_REJECT("the not-short-circuited half of clause 11 - once a screen answers REJECT, "
            + "the screens behind it are no longer asked"),

    /** Clause 12: the chain is abandoned after its first screen, which is what a timeout or a budget cut-off would
     *  look like from the outside - the screens behind the slow one never run. */
    A_CHAIN_ABANDONED_AFTER_THE_FIRST_SCREEN("clause 12's completeness - the chain is abandoned after its first "
            + "screen, as a timeout or a budget cut-off would leave it"),

    /** Clause 11's mirror: {@code withheld} is <em>not</em> short-circuited, so every screen is asked on every serve
     *  even after one has already answered {@code true}. */
    A_WITHHELD_THAT_ASKS_EVERY_SCREEN("the short-circuit on the read side - every screen is asked even after one has "
            + "already withheld the path"),

    /** Clause 11's ordering: the chain runs in the order the embedder happened to hand it in rather than by
     *  {@code order()}. Expressed by making every screen answer the same order, so the sort becomes a no-op - which
     *  is what a {@code Publication} that stopped sorting would leave behind. */
    A_CHAIN_IN_THE_ORDER_IT_WAS_GIVEN("clause 11's ordering - every screen answers the same order(), so the sort is a "
            + "no-op and the chain runs in the order it was handed in"),

    /** Clause 13: {@code committed} is an acknowledgement of a verdict rather than a notification of an outcome, so
     *  the screens that voted the neutral answer are not told. */
    A_COMMITTED_THAT_SKIPS_THE_NEUTRAL_VERDICT("clause 13's fan-out - committed fires only where the outcome was not "
            + "the neutral one, so a screen that voted ACCEPT is never told"),

    /**
     * Clause 7's whole direction: every failure on every leg is swallowed and the neutral answer returned. It is one
     * mutant rather than four because it is one claim - a failure on a verdict leg propagates - and because the fact
     * only the code states, that the containment is of {@code Exception} and an {@link Error} therefore escapes, is
     * the same claim asked about a different throwable kind.
     */
    A_CONTAINMENT_THAT_SWALLOWS_EVERY_FAILURE("clause 7 entire - every failure on every leg is swallowed and the "
            + "neutral answer returned, an escaping Error included"),

    /**
     * The other half of clause 7, which the swallowing mutant cannot express: a contained failure that also abandons
     * the rest of the fan-out. The shape a containment written as one {@code try} around the whole loop takes, where
     * the first failing observer silently costs every observer behind it.
     */
    AN_OBSERVER_FAILURE_THAT_STOPS_THE_FAN_OUT("the containment's per-observer scope - a failure is still contained, "
            + "but the observers behind the failing one are no longer notified"),

    /** Clause 5: the {@code Content} view honours no bound of the caller's, so a bounded sibling read answers the
     *  whole companion and reports no overflow - a prefix presented as a document. */
    A_CONTENT_VIEW_THAT_IGNORES_THE_CALLERS_BOUND("clause 5's bounded read - the sibling view ignores the caller's "
            + "limit and reports no truncation"),

    /** Clause 13's store-then-gate ordering: a serving pointer exists before the chain has voted, so a screen is
     *  looking at an artifact that already serves and {@code committed}'s ACCEPT really is a visibility claim. */
    A_POINTER_LINKED_BEFORE_THE_CHAIN_VOTES("clause 13's store-then-gate ordering - a serving pointer is linked "
            + "before the chain votes, so an artifact serves while it is still being screened"),

    /** Clause 13's quarantine ordering: the review pointer is gone by the time {@code committed} fires, so a screen
     *  notified of a hold cannot read the pointer its own verdict created - and a crashed hold becomes a release. */
    A_REVIEW_POINTER_REMOVED_BEFORE_COMMITTED("clause 13's quarantine ordering - the review pointer is removed before "
            + "committed fires, so the hold a verdict created is not there when the verdict is announced"),

    /** The withhold-change feed's transition-only rule: the ON leg is raised twice per durable transition, which is
     *  what a feed that fired on a converge as well as on a transition would look like to a consumer counting them. */
    A_WITHHOLD_FEED_THAT_FIRES_TWICE("the withhold feed's transition-only rule - the ON leg is raised more than once "
            + "per durable transition");

    private final String removes;

    ChoreographyMutant(String removes) {
        this.removes = removes;
    }

    /** What this mutant takes away, for the failure message of a check that survived it. */
    public String removes() {
        return removes;
    }

    /**
     * {@code hooks}, arranged so the choreography they run under produces this mutant's defect. The answer preserves
     * every hook's role - a screen stays a {@link PublishInterceptor}, so {@code Publication}'s own {@code instanceof}
     * split is untouched - because a mutant that changed which list a hook lands in would fail a check for a reason
     * that has nothing to do with the clause.
     */
    List<PublicationObserver> arrange(List<? extends PublicationObserver> hooks) {
        if (this == NONE) {
            return List.copyOf(hooks);
        }
        Run run = new Run(this);
        List<PublicationObserver> arranged = new ArrayList<>();
        if (this == A_POINTER_LINKED_BEFORE_THE_CHAIN_VOTES) {
            arranged.add(new EagerLink());
        }
        for (PublicationObserver hook : hooks) {
            if (hook instanceof PublishInterceptor screen) {
                run.screens.add(screen);
                arranged.add(run.new Screen(screen));
            } else {
                arranged.add(run.new Observer(hook));
            }
        }
        return List.copyOf(arranged);
    }

    /** The state one arranged chain shares. It is per {@link #arrange} call - which is per publication, since every
     *  check builds its publication through {@link PublicationHookContract#publication} - and keyed by artifact path
     *  wherever the claim is about one commit, so two commits on the same publication do not inherit each other's. */
    private final class Run {

        private final ChoreographyMutant mutant;
        private final List<PublishInterceptor> screens = new ArrayList<>();
        private final Set<String> rejected = ConcurrentHashMap.newKeySet();
        private final Set<String> assessedOnce = ConcurrentHashMap.newKeySet();
        private final Map<String, Set<PublishInterceptor>> asked = new ConcurrentHashMap<>();
        private final AtomicBoolean fanningOut = new AtomicBoolean();
        private final AtomicBoolean observerFailed = new AtomicBoolean();

        private Run(ChoreographyMutant mutant) {
            this.mutant = mutant;
        }

        /** Whether this screen's {@code assess} is delegated at all for {@code path}, or skipped as an abandoned,
         *  short-circuited or de-duplicated chain would skip it. */
        private boolean asks(PublishInterceptor screen, String path) {
            return switch (mutant) {
                case A_CHAIN_THAT_STOPS_AT_THE_FIRST_REJECT -> !rejected.contains(path);
                case A_CHAIN_ABANDONED_AFTER_THE_FIRST_SCREEN -> assessedOnce.add(path);
                case A_CHAIN_THAT_ASKS_A_REPEATED_SCREEN_ONCE ->
                        asked.computeIfAbsent(path, _ -> ConcurrentHashMap.newKeySet()).add(screen);
                default -> true;
            };
        }

        /** One hook's observer legs, shared by both decorators because a screen inherits exactly the same four. */
        private void observed(PublicationObserver delegate, Leg leg) throws IOException {
            if (mutant == AN_OBSERVER_FAILURE_THAT_STOPS_THE_FAN_OUT && observerFailed.get()) {
                return;                        // the fan-out was abandoned by the failure ahead of this one
            }
            try {
                leg.run(delegate);
            } catch (Throwable failure) {
                if (mutant == AN_OBSERVER_FAILURE_THAT_STOPS_THE_FAN_OUT) {
                    observerFailed.set(true);
                }
                if (mutant == A_CONTAINMENT_THAT_SWALLOWS_EVERY_FAILURE
                        || mutant == AN_OBSERVER_FAILURE_THAT_STOPS_THE_FAN_OUT) {
                    return;                    // contained here, which is what Publication would otherwise decide
                }
                throw sneak(failure);
            }
        }

        /** A screen under the arranged choreography. */
        private final class Screen implements PublishInterceptor {

            private final PublishInterceptor delegate;

            private Screen(PublishInterceptor delegate) {
                this.delegate = delegate;
            }

            @Override
            public int order() {
                return mutant == A_CHAIN_IN_THE_ORDER_IT_WAS_GIVEN ? 0 : delegate.order();
            }

            @Override
            public Disposition assess(ArtifactDescriptor artifact, Content content) throws IOException {
                if (!asks(delegate, artifact.path())) {
                    return Disposition.ACCEPT;
                }
                Content view = mutant == A_CONTENT_VIEW_THAT_IGNORES_THE_CALLERS_BOUND ? unbounded(content) : content;
                Disposition verdict;
                try {
                    verdict = delegate.assess(artifact, view);
                } catch (Throwable failure) {
                    if (mutant == A_CONTAINMENT_THAT_SWALLOWS_EVERY_FAILURE) {
                        return Disposition.ACCEPT;
                    }
                    throw sneak(failure);
                }
                if (verdict == Disposition.REJECT) {
                    rejected.add(artifact.path());
                }
                return verdict;
            }

            @Override
            public boolean withheld(String path, ArtifactStore store) throws IOException {
                if (mutant == A_WITHHELD_THAT_ASKS_EVERY_SCREEN && fanningOut.compareAndSet(false, true)) {
                    try {
                        for (PublishInterceptor other : screens) {
                            if (other != delegate) {
                                other.withheld(path, store);
                            }
                        }
                    } finally {
                        fanningOut.set(false);
                    }
                }
                try {
                    return delegate.withheld(path, store);
                } catch (Throwable failure) {
                    if (mutant == A_CONTAINMENT_THAT_SWALLOWS_EVERY_FAILURE) {
                        return false;
                    }
                    throw sneak(failure);
                }
            }

            @Override
            public void committed(ArtifactDescriptor artifact, Disposition disposition, ArtifactStore store)
                    throws IOException {
                if (mutant == A_COMMITTED_THAT_SKIPS_THE_NEUTRAL_VERDICT && disposition == Disposition.ACCEPT) {
                    return;
                }
                if (mutant == A_REVIEW_POINTER_REMOVED_BEFORE_COMMITTED) {
                    String pointer = "publish/quarantine" + artifact.path();
                    if (store.exists(pointer)) {
                        store.delete(pointer);
                    }
                }
                try {
                    delegate.committed(artifact, disposition, store);
                } catch (Throwable failure) {
                    if (mutant == A_CONTAINMENT_THAT_SWALLOWS_EVERY_FAILURE) {
                        return;
                    }
                    throw sneak(failure);
                }
            }

            @Override
            public void onPublished(ArtifactDescriptor published, ArtifactStore store) throws IOException {
                observed(delegate, hook -> hook.onPublished(published, store));
            }

            @Override
            public void onDeleted(ArtifactDescriptor removed, ArtifactStore store) throws IOException {
                observed(delegate, hook -> hook.onDeleted(removed, store));
            }

            @Override
            public void onWithheld(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
                observed(delegate, hook -> hook.onWithheld(subject, store));
                if (mutant == A_WITHHOLD_FEED_THAT_FIRES_TWICE) {
                    observed(delegate, hook -> hook.onWithheld(subject, store));
                }
            }

            @Override
            public void onWithholdCleared(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
                observed(delegate, hook -> hook.onWithholdCleared(subject, store));
            }
        }

        /** A plain after-commit observer under the arranged choreography - the same four legs, no verdict. */
        private final class Observer implements PublicationObserver {

            private final PublicationObserver delegate;

            private Observer(PublicationObserver delegate) {
                this.delegate = delegate;
            }

            @Override
            public void onPublished(ArtifactDescriptor published, ArtifactStore store) throws IOException {
                observed(delegate, hook -> hook.onPublished(published, store));
            }

            @Override
            public void onDeleted(ArtifactDescriptor removed, ArtifactStore store) throws IOException {
                observed(delegate, hook -> hook.onDeleted(removed, store));
            }

            @Override
            public void onWithheld(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
                observed(delegate, hook -> hook.onWithheld(subject, store));
                if (mutant == A_WITHHOLD_FEED_THAT_FIRES_TWICE) {
                    observed(delegate, hook -> hook.onWithheld(subject, store));
                }
            }

            @Override
            public void onWithholdCleared(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
                observed(delegate, hook -> hook.onWithholdCleared(subject, store));
            }
        }
    }

    /** One observer leg, so the containment is expressed once for all four rather than four times over. */
    @FunctionalInterface
    private interface Leg {
        void run(PublicationObserver hook) throws IOException;
    }

    /** The screen that links a serving pointer before anything has voted - sorted to the front of the chain, because
     *  the claim it breaks is about what is true when the FIRST screen looks. */
    private static final class EagerLink implements PublishInterceptor {

        @Override
        public int order() {
            return Integer.MIN_VALUE;
        }

        @Override
        public Disposition assess(ArtifactDescriptor artifact, Content content) throws IOException {
            String pointer = "publish" + artifact.path();
            if (!content.store().exists(pointer)) {
                content.store().writeVersioned(pointer, artifact.hash().getBytes(StandardCharsets.UTF_8), null);
            }
            return Disposition.ACCEPT;
        }
    }

    /** A content view whose bounded sibling read honours no bound at all and reports no overflow. The whole-document
     *  read is left alone: it is the same seam's other half and it is the contrast the clause is about. */
    private static PublishInterceptor.Content unbounded(PublishInterceptor.Content delegate) {
        return new PublishInterceptor.Content() {

            @Override
            public ArtifactStore store() {
                return delegate.store();
            }

            @Override
            public InputStream open() throws IOException {
                return delegate.open();
            }

            @Override
            public Optional<byte[]> sibling(String path) throws IOException {
                return delegate.sibling(path);
            }

            @Override
            public Optional<Bounded> sibling(String path, int limit) throws IOException {
                return delegate.sibling(path, Integer.MAX_VALUE - 8)
                        .map(bounded -> new Bounded(bounded.content(), false));
            }
        };
    }

    /** Rethrow whatever a delegate raised without wrapping it, so a check still sees the throwable its own probe
     *  produced. The legs are declared {@code throws IOException} and a checked exception of any other kind cannot
     *  reach here, because nothing on this seam declares one. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneak(Throwable failure) throws T {
        throw (T) failure;
    }
}
