package build.jenesis.repository.store.testkit;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.PublishInterceptor;

/**
 * How one publication hook registers with the shared {@link PublicationHookContract} - and, more importantly, the seam
 * that decides <em>which contract it is held to</em>.
 *
 * <p><b>The role is derived, never declared.</b> The whole publication surface is discovered through a single
 * {@code uses PublicationObserver} clause and {@link Publication} splits the discovered list by
 * {@code instanceof PublishInterceptor}: an interceptor's verdict legs <em>propagate</em> while a plain observer's
 * legs are <em>contained</em>. Two hooks with opposite failure semantics therefore arrive through one clause, and a
 * kit that let a fixture <em>say</em> which one it is would happily run a fail-closed screen through the contained
 * observer legs - proving the artifact still serves when the screen failed, which is precisely the fail-open reading
 * the sub-interface exists to prevent. So {@link Role#of} asks the instance the same question {@link Publication}
 * asks it, {@link #role()} is only a default over that answer, and
 * {@link PublicationHookContract#checks(PublicationHookFixture)} re-derives it and refuses a fixture whose declaration
 * disagrees with its instance. A provider cannot be tested under the wrong failure semantics because no one - not the
 * fixture, not the suite - gets to choose.
 *
 * <p>The three roles and their sub-fixtures:
 * <ul>
 *   <li>{@link Observer} - an after-commit {@link PublicationObserver}. Contained, and lossy across the
 *       mutation-to-callback crash window, so it declares its {@link Delivery} class and an <em>executable</em>
 *       repair leg.</li>
 *   <li>{@link Interceptor} - a {@link PublishInterceptor}. Pre-commit and fail-closed on the verdict legs, contained
 *       on the observer legs it inherits.</li>
 *   <li>{@link Release} - a pre-commit hold-release hook (downstream's {@code gate.HoldReleaseObserver} shape). It is
 *       <em>not</em> a {@code PublicationObserver} at all - despite the name - so it is reached through the
 *       {@link ReleaseHook} adapter rather than through {@link Publication}, and the kit's first assertion about it is
 *       that the role derivation keeps it off the contained observer path.</li>
 * </ul>
 *
 * <p>A fixture holds no state between checks: each check gets a fresh, empty store and calls {@link #create()} per
 * simulated process, so an in-memory accumulation dies with a crash exactly as it would in production.
 */
public interface PublicationHookFixture {

    /** A short, stable name for this hook - what a failing check is reported against. */
    String hook();

    /** The fully qualified implementation class this fixture covers, as the census parses it out of the owning
     *  module's {@code provides ... with ...} clause (or, for a {@link Role#PRE_COMMIT_RELEASE_HOOK}, out of the
     *  downstream {@code HoldReleaseObserver} clause the free graph cannot see). */
    String providerClass();

    /** A <em>fresh</em> instance, as a restarted process would build it. Reached through discovery wherever the
     *  deployment reaches it that way, so a fixture never tests an instance the product never resolves. */
    Object create();

    /**
     * The descriptor a kit publish at {@code path} should carry for this hook.
     *
     * <p>The default is coordinate-less, which is a real publish shape and the one several properties are about: a
     * checksum, a generated sidecar, an envelope path that carries no version. A hook keyed on the neutral
     * ecosystem/coordinate/version triple skips exactly that shape by design, so for those hooks every kit publish
     * used to be invisible - and their fixtures had to exclude whole properties with a reason saying the kit could
     * not reach them, which turns a check into a note.
     *
     * <p>A fixture whose hook is coordinate-keyed overrides this with
     * {@link PublicationHookContract#coordinated(String, String, String)}, deriving a coordinate from the path so
     * every publish the kit makes is one the hook can see. Overriding changes nothing else: the visibility the kit
     * declares carries the refinement only when the descriptor has one.
     */
    default ArtifactDescriptor describe(String path) {
        return PublicationHookContract.descriptor(path);
    }

    /**
     * This hook's role. The default is the derivation {@link Publication} itself performs, and a fixture should not
     * override it: {@link PublicationHookContract#checks(PublicationHookFixture)} re-derives the role from
     * {@link #create()} and fails when the two disagree, so an override can only ever be a caught lie.
     */
    default Role role() {
        return Role.of(create());
    }

    /** The store key prefixes this hook may write under. The kit walks the store after a drive and fails on any key
     *  created outside them, so "it stayed in its own namespace" is a statement about the store, not about intent. */
    List<String> namespaces();

    /** This hook's durable state, read back out of {@code store} and normalised into the comparable view its own
     *  contract calls its projection - dropping whatever may legitimately differ between two converged runs. Empty
     *  when the hook has recorded nothing. This is the fixture's declaration of convergence and the only thing the
     *  kit compares. */
    Map<String, String> projection(ArtifactStore store) throws IOException;

    /** Whether this hook records durable state for the artifact <em>the kit itself publishes</em> - a plain accepted
     *  publish of a generic path. Most hooks do, and for those the kit demands proof that the record landed inside
     *  the publication's own scope. Two kinds legitimately do not, and the difference between them does not matter
     *  here because the observable is the same: a screen that holds no state at all (its only say is a verdict
     *  computed from the request path), and a recorder whose preconditions a generic accepted publish does not meet
     *  (it writes only for its own ecosystem, or only under a quarantine disposition). Both are the case
     *  {@link #projection} already allows for - "Empty when the hook has recorded nothing".
     *
     *  <p>The kit needs the declaration because from the outside "recorded nothing" and "recorded against the store
     *  one scope up" are the same observation of the scoped store, and only the first is correct behaviour. Both
     *  answers are falsifiable, so neither buys a pass: {@code true} must prove a write inside the scope it was
     *  handed, and {@code false} must prove a write nowhere at all - so a hook that quietly starts recording fails
     *  here rather than sliding through. Answer {@code false} only from the hook's own declared preconditions, and
     *  say which ones at the override; never to quiet the check for a hook that was merely not driven far enough. */
    default boolean recordsWhatTheKitPublishes() {
        return true;
    }

    /** The contract properties this hook's shape genuinely does not have, each with a mandatory reason naming where
     *  the property is proven instead. Empty by default: an exclusion is a deliberate, reviewable statement. */
    default Map<PublicationHookContract.Property, String> unsupported() {
        return Map.of();
    }

    /**
     * The three commit roles a publication hook can occupy, and the derivation that assigns one.
     *
     * <p>{@link #of} is deliberately the same two-line test {@link Publication} performs on its discovered list. It is
     * a total function over any hook object, which is what lets the kit cover the downstream hold-release hooks too:
     * a hook that is not a {@link PublicationObserver} at all cannot be routed through the contained observer path by
     * any accident of registration, and the kit says so out loud rather than assuming it.
     */
    enum Role {

        /** A plain {@link PublicationObserver}: after-commit, contained, best-effort, repaired by the full walk. */
        AFTER_COMMIT_OBSERVER,

        /** A {@link PublishInterceptor}: pre-commit, fail-closed on {@code assess} / {@code withheld} /
         *  {@code committed}, contained on the observer legs it inherits. */
        PUBLISH_INTERCEPTOR,

        /** A pre-commit hold-release hook - downstream's {@code gate.HoldReleaseObserver}. Despite the name it is not
         *  a {@code PublicationObserver}, it propagates, and it must never be routed through the contained
         *  after-commit outbox. */
        PRE_COMMIT_RELEASE_HOOK;

        /** The role {@code hook} occupies, asked of the instance exactly as {@link Publication} asks it. Order
         *  matters: an interceptor <em>is</em> an observer, so the sub-interface is tested first. */
        public static Role of(Object hook) {
            Objects.requireNonNull(hook, "hook");
            if (hook instanceof PublishInterceptor) {
                return PUBLISH_INTERCEPTOR;
            }
            if (hook instanceof PublicationObserver) {
                return AFTER_COMMIT_OBSERVER;
            }
            return PRE_COMMIT_RELEASE_HOOK;
        }

        /** Whether this role's own decision-bearing legs propagate rather than being contained. Both pre-commit roles
         *  do; the after-commit observer is the one contained class. */
        public boolean failsClosed() {
            return this != AFTER_COMMIT_OBSERVER;
        }
    }

    /**
     * The after-commit observer legs a hook <em>actually overrides</em>, derived by reflection rather than declared -
     * the same "ask the class, do not ask the fixture" rule as {@link Role#of}. It matters for an interceptor: its
     * inherited {@link PublishInterceptor#onPublished} defaults to a no-op, so a screen observes an accepted publish
     * only if it explicitly overrides it, and only then does the contained-leg contract have anything to bind to.
     */
    enum ObserverLeg {

        ON_PUBLISHED("onPublished", ArtifactDescriptor.class, ArtifactStore.class),
        ON_DELETED("onDeleted", ArtifactDescriptor.class, ArtifactStore.class),
        ON_WITHHELD("onWithheld", ArtifactDescriptor.class, ArtifactStore.class),
        ON_WITHHOLD_CLEARED("onWithholdCleared", ArtifactDescriptor.class, ArtifactStore.class);

        private final String method;
        private final Class<?>[] parameters;

        ObserverLeg(String method, Class<?>... parameters) {
            this.method = method;
            this.parameters = parameters;
        }

        /** The legs {@code hook} overrides: those whose implementation is declared neither on
         *  {@link PublicationObserver} nor on {@link PublishInterceptor}, which is what "the provider wrote this leg"
         *  means. A hook that is not a {@code PublicationObserver} overrides none. */
        public static Set<ObserverLeg> overriddenBy(Object hook) {
            Objects.requireNonNull(hook, "hook");
            if (!(hook instanceof PublicationObserver)) {
                return Set.of();
            }
            Set<ObserverLeg> overridden = EnumSet.noneOf(ObserverLeg.class);
            for (ObserverLeg leg : values()) {
                try {
                    Class<?> declaring = hook.getClass().getMethod(leg.method, leg.parameters).getDeclaringClass();
                    if (declaring != PublicationObserver.class && declaring != PublishInterceptor.class) {
                        overridden.add(leg);
                    }
                } catch (NoSuchMethodException impossible) {
                    throw new AssertionError("a PublicationObserver without " + leg.method, impossible);
                }
            }
            return Collections.unmodifiableSet(overridden);
        }
    }

    /**
     * The delivery classes an <em>after-commit</em> hook may declare, and the reason the kit asks for one at all: the
     * callback fires after a durable mutation, so what a crash in that window costs is a property of the hook, not of
     * {@link Publication}.
     *
     * <p><b>A leg that performs its effect inline has no constant here, and that is not an oversight.</b>
     * {@link PublicationObserver}'s clause 8 rule is that an effect handed straight to anything outside the scoped
     * store - a remote target, another repository in this same process - is neither re-derivable by a walk nor
     * durably enqueued, so it is at-most-once and no class describes it. A fixture for a hook that keeps such a leg
     * declares the class its <em>other</em> legs ride and excludes, with a reason naming the leg, whatever the inline
     * one cannot honour; that exclusion is then the record that the hook is wider than its declared class.
     *
     * <p>The third constant exists so that claiming it is a <em>named refusal</em> rather than an absent idea:
     * {@link PublicationHookContract} rejects a fixture that declares it, naming, because writing an outbox
     * <em>inside</em> an after-commit callback buys durable-after-enqueue and not at-least-once observation - the
     * commit-to-callback window is still lossy, and only a pre-commit intent/state machine proven at every crash point
     * could change that.
     */
    enum Delivery {

        /** The callback performs its derived write inline. A lost call leaves the derived surface stale - it may
         *  over-serve or over-count, never hide a served artifact or a hold - and the full-walk / sweep repair leg is
         *  what brings it back. */
        BEST_EFFORT_REPAIRED,

        /** The callback durably enqueues a note and a later drain performs the effect. Stronger than best-effort
         *  <em>after</em> the callback returned (the note survives the process), and exactly as lossy before it. */
        DURABLE_AFTER_ENQUEUE,

        /** Not available on this seam. Reserved so a fixture that claims it is refused by name. */
        COMMIT_COUPLED_AT_LEAST_ONCE;

        /** Whether {@code Publication}'s commit protocol supports this class today. */
        public boolean supported() {
            return this != COMMIT_COUPLED_AT_LEAST_ONCE;
        }
    }

    /**
     * The after-commit half of the family: a plain {@link PublicationObserver}, whose every leg is contained and whose
     * delivery is best-effort across the mutation-to-callback window.
     */
    interface Observer extends PublicationHookFixture {

        @Override
        PublicationObserver create();

        /** What durability this surface actually rides. The kit holds it to this class and to no stronger one. */
        Delivery delivery();

        /** The projection this surface must hold once every one of {@code published} has been observed - the
         *  fixture's declaration of convergence, since two correct observers hold entirely different bytes for the
         *  same converged view. */
        Map<String, String> converged(List<ArtifactDescriptor> published);

        /**
         * The projection this surface holds after {@code withheld} were <em>withheld</em> rather than published -
         * the fixture's declaration of what the withhold leg alone writes.
         *
         * <p><b>Why this is separate from {@link #converged}, and why a key comparison was not enough.</b> A
         * {@code QUARANTINE} legitimately fires the withhold-change feed, so an observer subscribed to it may hold a
         * row for a held artifact - and {@code index-retraction} and {@code search-publication} legitimately write
         * ONE key per path and upsert it from both legs. For those, "the surface holds the publish row's key" is
         * correct behaviour and the defect's signature at the same time, so no assertion over KEYS can tell them
         * apart. The values can: the correct hook writes what the withhold leg means, the defective one writes what
         * a publish would have meant.
         *
         * <p>Empty by default, which is the honest answer for the majority - a hook that records nothing at all for a
         * held artifact. Overriding it with the same map {@code converged} returns is a declaration that this hook
         * cannot tell the two legs apart, and the kit reads that as the property being inapplicable rather than
         * satisfied; say why at the override.
         */
        default Map<String, String> withheld(List<ArtifactDescriptor> withheld) {
            return Map.of();
        }

        /**
         * Rebuild this surface from durable store truth alone: the walk or sweep that heals a dropped best-effort
         * call. The kit <em>runs</em> it (with a fresh hook instance, over a store the observer never saw) and
         * asserts convergence, so "there is a repair leg" is an executed fact rather than a claim in a comment.
         */
        void repair(ArtifactStore store) throws IOException;

        /** For {@link Delivery#DURABLE_AFTER_ENQUEUE}: the notes durably enqueued but not yet delivered. The kit
         *  asserts this is non-empty the moment the callback returns - that is what the class means - and empty again
         *  once {@link #drain} has run. */
        default Map<String, String> enqueued(ArtifactStore store) throws IOException {
            return Map.of();
        }

        /** For {@link Delivery#DURABLE_AFTER_ENQUEUE}: deliver the enqueued notes. Idempotent: the kit drains twice
         *  and requires the same projection. */
        default void drain(ArtifactStore store) throws IOException {
        }
    }

    /**
     * The verdict-bearing half: a {@link PublishInterceptor}, pre-commit and fail-closed.
     *
     * <p>The kit supplies its own probe screens for the chain-level clauses (ordering, short-circuiting, strongest
     * disposition, containment), and drives them <em>with this fixture's screen in the same chain</em> so the
     * provider is really on the path. What only the fixture can supply is how to make its own screen reach a verdict,
     * because a real screen votes on state rather than on a constructor argument.
     */
    interface Interceptor extends PublicationHookFixture {

        @Override
        PublishInterceptor create();

        /** The dispositions this screen can be arranged to reach. Must contain {@link PublishInterceptor.Disposition#ACCEPT}
         *  - the neutral answer every screen has - and names the others as the shape of this particular gate. */
        Set<PublishInterceptor.Disposition> verdicts();

        /** Seed the durable state that makes this screen vote {@code verdict} for {@code artifact}. Called only for a
         *  verdict in {@link #verdicts()}, and always before the body is published, so the screen decides on state it
         *  could really have been holding. */
        void arrange(ArtifactStore store, ArtifactDescriptor artifact, PublishInterceptor.Disposition verdict)
                throws IOException;

        /** Seed the durable state that makes {@link PublishInterceptor#withheld} answer {@code true} for {@code path},
         *  or answer {@code false} when this screen has no read side at all. */
        boolean arrangeWithhold(ArtifactStore store, String path) throws IOException;

        /**
         * The store key prefixes this screen <em>reads</em> to render a verdict. The kit faults exactly these and
         * asserts the screen throws rather than voting {@link PublishInterceptor.Disposition#ACCEPT}: clause 7's "a
         * screen must not catch its own store failures into a default ACCEPT" is the one verdict-leg obligation that
         * is genuinely per-implementation, and it is invisible to any chain-level check. Empty for a screen that
         * consults no state.
         */
        List<String> reads();
    }

    /**
     * The pre-commit hold-release role. Downstream owns both the SPI ({@code gate.HoldReleaseObserver}) and its
     * implementations, so the kit reaches a hook through this adapter rather than through a core type - which is
     * also the honest model, since a release hook is not discovered by {@link Publication} at all.
     */
    interface Release extends PublicationHookFixture {

        @Override
        ReleaseHook create();

        /** Put {@code path} under a review hold and record whatever this hook keys on: the safe pre-mutation state a
         *  failed release must be left in. */
        void hold(ArtifactStore store, String path, byte[] body) throws IOException;

        /** Whether {@code path} is still held, read from durable state - never from anything the release surface
         *  remembered. */
        boolean held(ArtifactStore store, String path) throws IOException;

        /** Whether the released artifact is visible (serving), read from durable state. The release must become
         *  visible only after <em>every</em> hook succeeded. */
        boolean visible(ArtifactStore store, String path) throws IOException;

        /** This hook's own per-version hold record for {@code path} - the {@code holds/<kind>/...} row a retroactive
         *  sweep writes - read from durable state. A discard drops it and a release promotes it. */
        boolean records(ArtifactStore store, String path) throws IOException;

        /** The override marker this hook promoted its record into on a completed release, or empty when it promoted
         *  none. Empty after a discard, because no human cleared the finding; present and <em>stable</em> after any
         *  number of retries, because a hook that already ran must upsert rather than promote twice. */
        Optional<String> override(ArtifactStore store, String path) throws IOException;

        /** Run the release surface over {@code hooks}, in order, propagating the first failure. The kit passes its
         *  own poison hook among the real ones, which is how "a throw leaves the hold safely retryable" is driven
         *  without asking a provider to sabotage itself. */
        void release(ArtifactStore store, String path, List<ReleaseHook> hooks) throws IOException;

        /** The discard mirror: the held artifact is thrown away rather than released, so a per-version record is
         *  dropped and no override is promoted. */
        void discard(ArtifactStore store, String path, List<ReleaseHook> hooks) throws IOException;
    }

    /** A hold-release hook reduced to what the kit drives - the downstream {@code HoldReleaseObserver} surface, minus
     *  the discovery statics the core has no view of. */
    interface ReleaseHook {

        /** React to a reviewer releasing {@code path}. Propagates: a hook that cannot record its override must not let
         *  the release become visible. */
        void onReleased(ArtifactStore store, String path) throws IOException;

        /** React to a reviewer discarding {@code path} without releasing it. Propagates for the same reason. */
        default void onDiscarded(ArtifactStore store, String path) throws IOException {
        }
    }
}
