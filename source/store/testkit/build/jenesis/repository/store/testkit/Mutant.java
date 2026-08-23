package build.jenesis.repository.store.testkit;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.store.testkit.PublicationHookFixture.Delivery;
import build.jenesis.repository.store.testkit.PublicationHookFixture.Observer;
import build.jenesis.repository.store.testkit.PublicationHookFixture.ReleaseHook;
import build.jenesis.repository.store.testkit.PublicationHookFixture.Role;

/**
 * One deliberately broken substitution for the deployment object a {@link PublicationHookContract.Property} is about,
 * injected at the seams {@link PublicationHookFixture} hands the kit one from - the falsification half of the kit
 * (carrying the earlier mechanism to this second kit).
 *
 * <p><b>Why the kit needs one at all.</b> Every check in {@link PublicationHookContract} states what a hook must do;
 * nothing states that the check <em>could have said otherwise</em>. the earlier hold-release legs would have passed
 * against a hook that was a no-op from end to end - the release surface still released, the retry still converged,
 * the discard still promoted nothing - and that was caught by an agent who happened to give the kit's paths a
 * coordinate, not by the kit. This enum makes the leg part of the kit: each property names the mutation that must
 * break it, and the suite fails when a mutation does not.
 *
 * <p><b>A mutant is not a mock.</b> {@link Publication} is still the real commit choreography, the real chain, the
 * real crash windows and the real store; a mutant wraps the <em>hook the fixture registers</em> - or, for the four
 * surface mutants, the deployment surface the fixture drives it through - and removes exactly one behaviour.
 * Everything else stays the deployment's own, so a check that survives a mutant is not measuring the behaviour the
 * mutant removed. That is also what keeps the mutation cheap enough for every falsifiable property to carry one: a
 * mutant is a few lines here, never a second fixture.
 *
 * <p><b>Three roles, three decorators, on purpose.</b> The publication family's whole point is that an after-commit
 * observer, a pre-commit screen and a pre-commit release hook have <em>opposite</em> failure semantics, so a single
 * decorator would have to branch on the role in every leg and would be one edit away from running one role's
 * mutation against another's contract. {@link #decorate} therefore switches once, at the seam, and each decorator
 * only ever holds the legs its role actually has.
 *
 * <p>{@link #NONE} is the identity: the unmutated leg every fixture already runs.
 */
public enum Mutant {

    /** Nothing is removed - the deployment's own hook, which is what the contract's ordinary leg drives. */
    NONE("nothing"),

    /**
     * The hook's work, everywhere: an observer records nothing on any of its four legs, a screen answers the neutral
     * {@code ACCEPT} / {@code false} and records nothing when it is told the outcome, and a release hook returns from
     * {@code onReleased} and {@code onDiscarded} without touching the store. The identity legs - the class, the role,
     * the declared namespaces - are the real hook's.
     *
     * <p>This is the kit's general vacuity probe and the literal shape hit: a property whose check a hook that
     * does nothing at all still satisfies is a property proven over nothing. The properties that legitimately survive
     * it are a reviewed list on the census.
     */
    NO_WORK_AT_ALL("the hook's work, everywhere - every leg returns without recording anything"),

    /**
     * The role derivation: the hook also implements the sub-interface of a neighbouring role, so
     * {@link Role#of} - the same {@code instanceof} test {@link Publication} performs on its discovered list - places
     * it in a family with the opposite failure semantics. A release hook becomes a contained after-commit observer;
     * an observer becomes a fail-closed screen.
     *
     * <p>It is not declared for a screen, because there is no wrapper that removes a role from
     * {@code Interceptor.create()}: the fixture SPI's own return type is {@code PublishInterceptor}, so a screen
     * cannot be handed out as anything else. That is a property of the fixture SPI rather than an exemption of the
     * hook's, and it is what the mutation's predicate says.
     */
    A_MISDERIVED_ROLE("the role derivation - the hook also answers a neighbouring role's sub-interface"),

    /**
     * The freshness of an instance: {@code create()} answers <em>one</em> instance for the whole check rather than
     * one per simulated process, so an in-memory accumulation survives a restart the crash legs believe they caused.
     */
    A_SHARED_INSTANCE("the freshness of an instance - create() answers the same hook every time"),

    /** Namespace containment: every leg also writes one key outside every namespace the fixture declares. */
    A_KEY_OUTSIDE_THE_NAMESPACES("namespace containment - each leg also writes one key outside every declared space"),

    /**
     * Upsert: the hook's derived write is re-delivered under a path one character off from the second delivery on, so
     * the surface grows a row per delivery instead of converging. Written from the <em>second</em> delivery, so the
     * first delivery's convergence - which every replay check uses as its preamble - is untouched and the divergence
     * lands exactly where the property is stated.
     */
    A_ROW_PER_DELIVERY("upsert - every delivery after the first also records a row of its own"),

    /**
     * Tenant scoping: the hook records through the deployment root rather than through the doubly-scoped store the
     * publication routed through (&sect;6). The row exists and is correct; it is one repository over.
     */
    A_ROOT_SCOPED_RECORD("tenant scoping - the hook records through the deployment root, not the scope it was handed"),

    /**
     * The seam's own precondition: the hook's withhold leg also runs its publish leg, so a held artifact is recorded
     * as a published one. A {@code QUARANTINE} really does fire the withhold-change feed, so this is the shape an
     * observer subscribed to both feeds reaches by treating them as one.
     */
    A_PUBLISH_ROW_FROM_THE_WITHHOLD_LEG("the publish-row precondition - the withhold leg also records a publish"),

    /**
     * The one genuinely per-implementation verdict obligation: the screen catches its own store failure and answers
     * the neutral {@code ACCEPT} / {@code false} - "I could not check" degraded into "nothing against it", which is
     * the fail-open reading the sub-interface exists to prevent.
     */
    A_BLIND_ACCEPT("the screen's refusal to guess - its own store failure is caught into a neutral answer"),

    /** The neutral answer: an un-arranged screen votes {@code QUARANTINE}, so nothing is served for want of a
     *  verdict rather than for want of a reason. */
    A_NON_NEUTRAL_DEFAULT("the neutral answer - an un-arranged screen votes QUARANTINE"),

    /**
     * The re-consultation clause 9 rests on: the screen answers {@code withheld} from the first answer it ever gave,
     * for the lifetime of the instance. A verdict that changes after the fact then never retracts anything the
     * running process has already served.
     */
    A_LATCHED_WITHHELD("the re-consultation - withheld answers from the first answer the instance ever gave"),

    /** Read purity: the screen writes one key while answering {@code withheld}, on a path a GET takes. */
    A_WRITING_WITHHELD("read purity - the screen writes one key while answering withheld"),

    /**
     * Per-call state in a field: the screen answers every later publish with the verdict it reached for the first
     * one, which is what a screen keeping the artifact under assessment in an instance field does under concurrency -
     * except deterministically, because a check that only fails when the interleaving cooperates is not a check.
     */
    A_VERDICT_REMEMBERED_IN_A_FIELD("thread safety - every publish gets the verdict the first one reached"),

    /**
     * The fixture's declaration of convergence, reduced to the empty view - a convergence the untouched store already
     * satisfies. This is the second seam, and it is the shape: a projection that reads as converged
     * <em>over an empty answer</em>, where the comparison the check makes is true of a surface nothing ever wrote to.
     */
    A_CONVERGENCE_THE_SEEDED_STORE_ALREADY_HAS("the declared convergence - it is now the empty view, which an "
            + "untouched surface already satisfies"),

    /** The fixture's declared repair sweep, reduced to a no-op: "there is a walk that heals this" back to a claim. */
    A_REPAIR_THAT_DOES_NOTHING("the executable repair - the declared sweep no longer does anything"),

    /** The drain's replayability: from the second drain on it delivers one note of its own, so draining twice leaves
     *  a surface one row larger than draining once. */
    A_DRAIN_THAT_IS_NOT_REPLAYABLE("the drain's replayability - a second drain leaves one more row than the first"),

    /** The release surface's fail-closed direction: a hook's failure is caught and the release reports success. */
    A_RELEASE_SURFACE_THAT_SWALLOWS("the release surface's propagation - a hook's failure is caught and contained"),

    /**
     * The release surface's <em>ordering</em>, which is the whole of the third role's contract: the visibility
     * mutation happens first and the hooks run behind it, so a hook that fails leaves an artifact that is already
     * released and holds nobody cleared. The failure still propagates, which is what makes this the weaker break -
     * only a check that reads durable state afterwards can tell it from a correct surface.
     */
    A_RELEASE_SURFACE_THAT_MUTATES_FIRST("the release surface's ordering - the visibility mutation happens before "
            + "the fan-out"),

    /** The discard/release asymmetry: the discard leg runs the release leg, so a thrown-away version is recorded as
     *  one a human cleared. */
    A_DISCARD_THAT_PROMOTES("the discard/release asymmetry - a discard promotes an override"),

    /** Harmlessness: the hook raises for a path its own hold kind never held, so registering a hold kind a deployment
     *  does not use starts failing every release. */
    A_HOOK_THAT_RAISES_FOR_AN_UNHELD_PATH("harmlessness - the hook raises for a path its own kind never held");

    /** The key a {@link #A_KEY_OUTSIDE_THE_NAMESPACES} escape lands on - outside every namespace a fixture declares
     *  and outside every key {@link Publication} itself owns, so the escape walk has to see it or see nothing. */
    private static final String ESCAPED = "contract-mutant/escaped";

    private final String removes;

    Mutant(String removes) {
        this.removes = removes;
    }

    /** What this mutant takes away, in the words a failure message uses. */
    public String removes() {
        return removes;
    }

    /**
     * {@code fixture} with exactly this mutant's behaviour removed, or {@code fixture} itself for {@link #NONE}. The
     * decorator keeps the fixture's own identity, role, namespaces and projection - what it substitutes is the hook,
     * and for the surface mutants the one surface method the property is about - so a mutated check still runs
     * against the fixture's real declarations.
     *
     * <p>{@code root} is the check's own deployment-root store, which {@link #A_ROOT_SCOPED_RECORD} records through:
     * a scope violation is only observable against the store one scope up, and the check is the only thing that knows
     * which store that is.
     */
    public static PublicationHookFixture decorate(PublicationHookFixture fixture, Mutant mutant,
                                                  FaultInjectingStore root) {
        Objects.requireNonNull(fixture, "fixture");
        Objects.requireNonNull(mutant, "mutant");
        if (mutant == NONE) {
            return fixture;
        }
        return switch (fixture.role()) {
            case AFTER_COMMIT_OBSERVER -> new MutatedObserver((Observer) fixture, mutant, root);
            case PUBLISH_INTERCEPTOR ->
                    new MutatedInterceptor((PublicationHookFixture.Interceptor) fixture, mutant, root);
            case PRE_COMMIT_RELEASE_HOOK -> new MutatedRelease((PublicationHookFixture.Release) fixture, mutant);
        };
    }

    private static void plant(ArtifactStore store) throws IOException {
        store.write(ESCAPED, new ByteArrayInputStream("contract mutant".getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * The same artifact one delivery over, which is how a blind-append surface grows a row it should have upserted
     * onto the one already there.
     *
     * <p>It moves <b>both</b> halves of the identity, and that is the whole point. Moving only the path made this
     * mutant invisible to every fixture in the population, from two directions at once: a surface keyed on the
     * served pointer tree skipped the variant because a path nobody published is not in that tree, and a surface
     * keyed on the neutral ecosystem/coordinate/version triple upserted it away because the triple had not moved.
     * The mutant landed in the gap between the two families rather than in either of them, and four fixtures
     * carried exclusions saying they could not see it.
     *
     * <p>The version is what moves, not the coordinate: a coordinate-keyed surface must see a distinct row, and a
     * new <em>version</em> of a known coordinate is the shape those surfaces are built to record. Inventing a
     * coordinate would test whether they record an unrelated artifact, which is a different question.
     */
    private static ArtifactDescriptor variant(ArtifactDescriptor artifact, int delivery) {
        String suffix = "-mutant-" + delivery;
        return new ArtifactDescriptor(artifact.ecosystem(), artifact.coordinate(),
                artifact.version() == null ? null : artifact.version() + suffix,
                artifact.path() + suffix, artifact.contentType(), artifact.prerelease(),
                artifact.hash(), artifact.size());
    }

    /**
     * Link the variant so it is genuinely served, before the append that records it.
     *
     * <p>A hook whose projection inverts the served pointer tree - and several do - reads only what is published.
     * Handing it a descriptor for a path with no pointer is handing it something it is right to skip, so the
     * mutation changed nothing and the check passed against the very defect it names. The variant reuses the real
     * artifact's blob, so this adds a pointer and no bytes: the smallest thing that makes an appended row visible
     * to a surface that reads the tree rather than the notification.
     */
    private static void serve(ArtifactDescriptor variant, ArtifactStore store) throws IOException {
        if (variant.path() == null || variant.hash() == null) {
            return;
        }
        store.writeVersioned("publish" + variant.path(),
                variant.hash().getBytes(StandardCharsets.UTF_8), null);
    }

    // --- the after-commit observer ----------------------------------------------------------------------------------

    /** The fixture's observer with one behaviour removed. Everything the mutant does not name is forwarded, so the
     *  scheduler of this role - {@link Publication}'s commit protocol - still makes every decision it makes. */
    private static final class MutatedObserver implements Observer {

        private final Observer delegate;
        private final Mutant mutant;
        private final FaultInjectingStore root;
        private final AtomicInteger deliveries = new AtomicInteger();
        private final AtomicInteger drains = new AtomicInteger();
        private PublicationObserver shared;

        private MutatedObserver(Observer delegate, Mutant mutant, FaultInjectingStore root) {
            this.delegate = delegate;
            this.mutant = mutant;
            this.root = root;
        }

        @Override
        public String hook() {
            return delegate.hook();
        }

        @Override
        public String providerClass() {
            return delegate.providerClass();
        }

        @Override
        public Role role() {
            return delegate.role();                 // the fixture's declaration is unchanged; only the instance moved
        }

        @Override
        public Map<PublicationHookContract.Property, String> unsupported() {
            return delegate.unsupported();
        }

        @Override
        public List<String> namespaces() {
            return delegate.namespaces();
        }

        @Override
        public Delivery delivery() {
            return delegate.delivery();
        }

        @Override
        public Map<String, String> projection(ArtifactStore store) throws IOException {
            return delegate.projection(store);
        }

        @Override
        public Map<String, String> converged(List<ArtifactDescriptor> published) {
            return mutant == A_CONVERGENCE_THE_SEEDED_STORE_ALREADY_HAS ? Map.of() : delegate.converged(published);
        }

        @Override
        public void repair(ArtifactStore store) throws IOException {
            if (mutant != A_REPAIR_THAT_DOES_NOTHING) {
                delegate.repair(store);
            }
        }

        @Override
        public Map<String, String> enqueued(ArtifactStore store) throws IOException {
            return delegate.enqueued(store);
        }

        @Override
        public void drain(ArtifactStore store) throws IOException {
            delegate.drain(store);
            if (mutant == A_DRAIN_THAT_IS_NOT_REPLAYABLE && drains.incrementAndGet() > 1) {
                // With a blob, and a blob that is really in the store. A bare at(ecosystem, path) carries a null
                // hash, and a hook is entitled to drop one - forwarding's observer returns on hash() == null before
                // it enqueues anything - so a hash-less redelivery made this mutation inert against exactly the
                // fixtures whose surface it exists to grow.
                String path = "/kit/contract-mutant-drain-" + drains.get();
                byte[] body = path.getBytes(StandardCharsets.UTF_8);
                Publication publication = new Publication(store, List.of(), List.of());
                String hash = publication.storeBlob(new ByteArrayInputStream(body));
                publication.link(path, hash);
                delegate.create().onPublished(
                        ArtifactDescriptor.at("kit", path).withBlob(hash, body.length), store);
                delegate.drain(store);
            }
        }

        @Override
        public PublicationObserver create() {
            if (mutant != A_SHARED_INSTANCE) {
                return broken(delegate.create());
            }
            if (shared == null) {
                shared = broken(delegate.create());
            }
            return shared;
        }

        private PublicationObserver broken(PublicationObserver hook) {
            return mutant == A_MISDERIVED_ROLE ? new MiswiredObserver(hook) : new BrokenObserver(hook);
        }

        /** An observer that also answers {@code PublishInterceptor}, so the derivation puts it in the fail-closed
         *  family. It votes the neutral ACCEPT and answers "serves", so nothing but the derivation changes. */
        private static final class MiswiredObserver implements PublishInterceptor {

            private final PublicationObserver hook;

            private MiswiredObserver(PublicationObserver hook) {
                this.hook = hook;
            }

            @Override
            public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
                hook.onPublished(artifact, store);
            }
        }

        /** The fixture's observer with exactly one of its four legs' behaviours removed. */
        private final class BrokenObserver implements PublicationObserver {

            private final PublicationObserver hook;

            private BrokenObserver(PublicationObserver hook) {
                this.hook = hook;
            }

            @Override
            public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
                switch (mutant) {
                    case NO_WORK_AT_ALL -> {
                        return;
                    }
                    case A_ROOT_SCOPED_RECORD -> hook.onPublished(artifact, root);
                    case A_ROW_PER_DELIVERY -> {
                        hook.onPublished(artifact, store);
                        int delivery = deliveries.incrementAndGet();
                        if (delivery > 1) {
                            ArtifactDescriptor appended = variant(artifact, delivery);
                            serve(appended, store);
                            hook.onPublished(appended, store);
                        }
                    }
                    case A_KEY_OUTSIDE_THE_NAMESPACES -> {
                        hook.onPublished(artifact, store);
                        plant(store);
                    }
                    default -> hook.onPublished(artifact, store);
                }
            }

            @Override
            public void onDeleted(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
                if (mutant == NO_WORK_AT_ALL) {
                    return;
                }
                hook.onDeleted(artifact, store);
                if (mutant == A_KEY_OUTSIDE_THE_NAMESPACES) {
                    plant(store);
                }
            }

            @Override
            public void onWithheld(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
                if (mutant == NO_WORK_AT_ALL) {
                    return;
                }
                hook.onWithheld(subject, store);
                if (mutant == A_PUBLISH_ROW_FROM_THE_WITHHOLD_LEG) {
                    hook.onPublished(subject, store);
                }
                if (mutant == A_KEY_OUTSIDE_THE_NAMESPACES) {
                    plant(store);
                }
            }

            @Override
            public void onWithholdCleared(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
                if (mutant == NO_WORK_AT_ALL) {
                    return;
                }
                hook.onWithholdCleared(subject, store);
                if (mutant == A_KEY_OUTSIDE_THE_NAMESPACES) {
                    plant(store);
                }
            }
        }
    }

    // --- the publish interceptor ------------------------------------------------------------------------------------

    /** The fixture's screen with one behaviour removed. The chain, the ordering, the short-circuiting and the crash
     *  windows stay {@link Publication}'s; only this screen's own answers change. */
    private static final class MutatedInterceptor implements PublicationHookFixture.Interceptor {

        private final PublicationHookFixture.Interceptor delegate;
        private final Mutant mutant;
        private final FaultInjectingStore root;
        private final AtomicInteger deliveries = new AtomicInteger();
        private final AtomicInteger reads = new AtomicInteger();
        private PublishInterceptor shared;

        private MutatedInterceptor(PublicationHookFixture.Interceptor delegate, Mutant mutant,
                                   FaultInjectingStore root) {
            this.delegate = delegate;
            this.mutant = mutant;
            this.root = root;
        }

        @Override
        public String hook() {
            return delegate.hook();
        }

        @Override
        public String providerClass() {
            return delegate.providerClass();
        }

        @Override
        public Role role() {
            return delegate.role();
        }

        @Override
        public Map<PublicationHookContract.Property, String> unsupported() {
            return delegate.unsupported();
        }

        @Override
        public List<String> namespaces() {
            return delegate.namespaces();
        }

        @Override
        public Map<String, String> projection(ArtifactStore store) throws IOException {
            return delegate.projection(store);
        }

        @Override
        public Set<PublishInterceptor.Disposition> verdicts() {
            return delegate.verdicts();
        }

        @Override
        public void arrange(ArtifactStore store, ArtifactDescriptor artifact,
                            PublishInterceptor.Disposition verdict) throws IOException {
            delegate.arrange(store, artifact, verdict);
        }

        @Override
        public boolean arrangeWithhold(ArtifactStore store, String path) throws IOException {
            return delegate.arrangeWithhold(store, path);
        }

        @Override
        public List<String> reads() {
            return delegate.reads();
        }

        @Override
        public PublishInterceptor create() {
            if (mutant != A_SHARED_INSTANCE) {
                return new BrokenScreen(delegate.create());
            }
            if (shared == null) {
                shared = new BrokenScreen(delegate.create());
            }
            return shared;
        }

        /** The fixture's screen with exactly one behaviour removed. {@code order()} is forwarded, so the chain still
         *  sorts this screen where the deployment would. */
        private final class BrokenScreen implements PublishInterceptor {

            private final PublishInterceptor screen;
            private final AtomicReference<Disposition> remembered = new AtomicReference<>();
            private final AtomicReference<Boolean> latched = new AtomicReference<>();

            private BrokenScreen(PublishInterceptor screen) {
                this.screen = screen;
            }

            @Override
            public int order() {
                return screen.order();
            }

            @Override
            public Disposition assess(ArtifactDescriptor artifact, Content content) throws IOException {
                if (mutant == NO_WORK_AT_ALL) {
                    return Disposition.ACCEPT;
                }
                if (mutant == A_NON_NEUTRAL_DEFAULT) {
                    return Disposition.QUARANTINE;
                }
                if (mutant == A_VERDICT_REMEMBERED_IN_A_FIELD) {
                    Disposition first = remembered.get();
                    if (first != null) {
                        return first;
                    }
                    Disposition reached = screen.assess(artifact, content);
                    remembered.compareAndSet(null, reached);
                    return reached;
                }
                if (mutant == A_BLIND_ACCEPT) {
                    try {
                        return screen.assess(artifact, content);
                    } catch (Exception _) {
                        return Disposition.ACCEPT;        // "I could not check" answered as "nothing against it"
                    }
                }
                Disposition reached = screen.assess(artifact, content);
                if (mutant == A_KEY_OUTSIDE_THE_NAMESPACES) {
                    plant(content.store());
                }
                return reached;
            }

            @Override
            public boolean withheld(String path, ArtifactStore store) throws IOException {
                if (mutant == NO_WORK_AT_ALL) {
                    return false;
                }
                if (mutant == A_LATCHED_WITHHELD) {
                    Boolean first = latched.get();
                    if (first != null) {
                        return first;
                    }
                    boolean answered = screen.withheld(path, store);
                    latched.compareAndSet(null, answered);
                    return answered;
                }
                if (mutant == A_BLIND_ACCEPT) {
                    try {
                        return screen.withheld(path, store);
                    } catch (Exception _) {
                        return false;                     // an unanswerable hold probe answered as "serves"
                    }
                }
                if (mutant == A_WRITING_WITHHELD) {
                    store.write(namespaces().getFirst() + "/contract-mutant-read-" + reads.incrementAndGet(),
                            new ByteArrayInputStream("read".getBytes(StandardCharsets.UTF_8)));
                }
                return screen.withheld(path, store);
            }

            @Override
            public void committed(ArtifactDescriptor artifact, Disposition disposition, ArtifactStore store)
                    throws IOException {
                switch (mutant) {
                    case NO_WORK_AT_ALL -> {
                        return;
                    }
                    case A_ROOT_SCOPED_RECORD -> screen.committed(artifact, disposition, root);
                    case A_ROW_PER_DELIVERY -> {
                        screen.committed(artifact, disposition, store);
                        int delivery = deliveries.incrementAndGet();
                        if (delivery > 1) {
                            screen.committed(variant(artifact, delivery), disposition, store);
                        }
                    }
                    case A_KEY_OUTSIDE_THE_NAMESPACES -> {
                        screen.committed(artifact, disposition, store);
                        plant(store);
                    }
                    default -> screen.committed(artifact, disposition, store);
                }
            }

            @Override
            public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
                if (mutant == NO_WORK_AT_ALL) {
                    return;
                }
                screen.onPublished(artifact, mutant == A_ROOT_SCOPED_RECORD ? root : store);
            }

            @Override
            public void onDeleted(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
                if (mutant == NO_WORK_AT_ALL) {
                    return;
                }
                screen.onDeleted(artifact, store);
            }

            @Override
            public void onWithheld(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
                if (mutant == NO_WORK_AT_ALL) {
                    return;
                }
                screen.onWithheld(subject, store);
            }

            @Override
            public void onWithholdCleared(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
                if (mutant == NO_WORK_AT_ALL) {
                    return;
                }
                screen.onWithholdCleared(subject, store);
            }
        }
    }

    // --- the pre-commit release hook --------------------------------------------------------------------------------

    /** The fixture's release hook with one behaviour removed - and, for the two surface mutants, the fixture's own
     *  release surface with its ordering or its propagation removed. Both are deployment objects: downstream owns the
     *  hook and the surface alike, and the third role's contract is as much about the surface's ordering as about the
     *  hook's idempotency. */
    private static final class MutatedRelease implements PublicationHookFixture.Release {

        private final PublicationHookFixture.Release delegate;
        private final Mutant mutant;
        private ReleaseHook shared;

        private MutatedRelease(PublicationHookFixture.Release delegate, Mutant mutant) {
            this.delegate = delegate;
            this.mutant = mutant;
        }

        @Override
        public String hook() {
            return delegate.hook();
        }

        @Override
        public String providerClass() {
            return delegate.providerClass();
        }

        @Override
        public Role role() {
            return delegate.role();
        }

        @Override
        public Map<PublicationHookContract.Property, String> unsupported() {
            return delegate.unsupported();
        }

        @Override
        public List<String> namespaces() {
            return delegate.namespaces();
        }

        @Override
        public Map<String, String> projection(ArtifactStore store) throws IOException {
            return delegate.projection(store);
        }

        @Override
        public void hold(ArtifactStore store, String path, byte[] body) throws IOException {
            delegate.hold(store, path, body);
        }

        @Override
        public boolean held(ArtifactStore store, String path) throws IOException {
            return delegate.held(store, path);
        }

        @Override
        public boolean visible(ArtifactStore store, String path) throws IOException {
            return delegate.visible(store, path);
        }

        @Override
        public boolean records(ArtifactStore store, String path) throws IOException {
            return delegate.records(store, path);
        }

        @Override
        public Optional<String> override(ArtifactStore store, String path) throws IOException {
            return delegate.override(store, path);
        }

        @Override
        public void release(ArtifactStore store, String path, List<ReleaseHook> hooks) throws IOException {
            switch (mutant) {
                case A_RELEASE_SURFACE_THAT_SWALLOWS -> {
                    try {
                        delegate.release(store, path, hooks);
                    } catch (IOException _) {
                        // exactly the containment this role must not have: the reviewer is told the release landed
                    }
                }
                case A_RELEASE_SURFACE_THAT_MUTATES_FIRST -> {
                    delegate.release(store, path, List.of());   // the visibility mutation, first
                    for (ReleaseHook hook : hooks) {            // ... and the fan-out behind it, still propagating
                        hook.onReleased(store, path);
                    }
                }
                default -> delegate.release(store, path, hooks);
            }
        }

        @Override
        public void discard(ArtifactStore store, String path, List<ReleaseHook> hooks) throws IOException {
            delegate.discard(store, path, hooks);
        }

        @Override
        public ReleaseHook create() {
            if (mutant != A_SHARED_INSTANCE) {
                return broken(delegate.create());
            }
            if (shared == null) {
                shared = broken(delegate.create());
            }
            return shared;
        }

        private ReleaseHook broken(ReleaseHook hook) {
            return mutant == A_MISDERIVED_ROLE ? new ContainedHook(hook) : new BrokenHook(hook);
        }

        /** A release hook that is also a {@link PublicationObserver}, so one registration accident routes it through
         *  the contained after-commit path - the outcome the role's first check exists to make impossible. */
        private static final class ContainedHook implements ReleaseHook, PublicationObserver {

            private final ReleaseHook hook;

            private ContainedHook(ReleaseHook hook) {
                this.hook = hook;
            }

            @Override
            public void onReleased(ArtifactStore store, String path) throws IOException {
                hook.onReleased(store, path);
            }

            @Override
            public void onDiscarded(ArtifactStore store, String path) throws IOException {
                hook.onDiscarded(store, path);
            }

            @Override
            public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) {
            }
        }

        private final class BrokenHook implements ReleaseHook {

            private final ReleaseHook hook;

            private BrokenHook(ReleaseHook hook) {
                this.hook = hook;
            }

            @Override
            public void onReleased(ArtifactStore store, String path) throws IOException {
                switch (mutant) {
                    case NO_WORK_AT_ALL -> {
                        return;
                    }
                    case A_HOOK_THAT_RAISES_FOR_AN_UNHELD_PATH -> {
                        if (!delegate.records(store, path)) {
                            throw new IOException("this hold kind never held " + path);
                        }
                        hook.onReleased(store, path);
                    }
                    case A_KEY_OUTSIDE_THE_NAMESPACES -> {
                        hook.onReleased(store, path);
                        plant(store);
                    }
                    default -> hook.onReleased(store, path);
                }
            }

            @Override
            public void onDiscarded(ArtifactStore store, String path) throws IOException {
                switch (mutant) {
                    case NO_WORK_AT_ALL -> {
                        return;
                    }
                    case A_DISCARD_THAT_PROMOTES -> hook.onReleased(store, path);
                    case A_KEY_OUTSIDE_THE_NAMESPACES -> {
                        hook.onDiscarded(store, path);
                        plant(store);
                    }
                    default -> hook.onDiscarded(store, path);
                }
            }
        }
    }
}
