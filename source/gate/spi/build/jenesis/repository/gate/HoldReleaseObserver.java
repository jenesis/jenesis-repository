package build.jenesis.repository.gate;

import module java.base;
import build.jenesis.repository.store.Providers;
import build.jenesis.repository.store.ArtifactStore;

/**
 * A hook run when a reviewer releases a quarantined path, discovered with {@link ServiceLoader} so the set of
 * side-effects a release triggers is declared in one place ({@code provides}) rather than hardcoded at every release
 * surface. Before this, both the free-repository review side ({@code GatedRepository.release}) and the console's review
 * side ({@code ComplianceReview.releaseQuarantined}) named {@link KevHold} and {@link LicenseHold} by hand and had to be
 * kept in lock-step; now each surface calls {@link #released} once and a new retroactive-hold kind joins by adding a
 * provider, with no edit to the release surfaces.
 *
 * <p>An observer promotes a retroactive hold record into an override marker so a subsequent enforcement sweep never
 * re-holds a release a human has cleared. It is a no-op for a path it never held (a plain publish-time gate hold leaves
 * no such record), so registering an observer for a hold kind a deployment does not use is harmless.
 *
 * <p><b>A provider is how a hold is explained and released, never what makes it a hold.</b> The hold itself is the
 * durable {@code holds/<kind>/} record an enforce sweep wrote, and {@link HoldRecords} - not this registry - is what
 * {@link #anyHolds} and {@link #heldByAnotherKind} answer from. Uninstalling a compliance module therefore leaves
 * everything that module was holding held; what it costs is the ability to promote that kind's override on release and
 * to name the hold with a live plug-in's mark, which is a rendering loss, not a validity one (contract clause 5).
 *
 * <p><b>Ordering: every hook runs BEFORE the mutation it observes, over the repository's scoped store.</b> Despite the
 * "observer" name these are <em>pre-commit</em> hooks, and the release surface depends on it: {@code HoldLifecycle}
 * calls {@link #released} first and only then links the release pointer and unpublishes the {@code /quarantine}
 * pointer, so a crash between them leaves the artifact held-and-overridden - a state a re-run converges from, and one
 * the kev-/license-/reachability-enforce sweeps will not re-hold, because the human's override is already durable.
 * Ordering them the other way round would let a release become visible with its override marker unwritten, which is
 * exactly the state the next enforce pass re-holds. {@link #discarded} mirrors it: the hooks run before the release
 * pointer is evicted, before a blobs-namespace version's served blobs are discarded and before the {@code /quarantine}
 * pointer clears, so the pointer stays as the operator's retry surface if a hook fails.
 *
 * <p>Both fan-outs propagate: there is no {@code try}/{@code catch} around a hook, so a throwing hook fails the release
 * or discard and nothing is mutated. A hook must therefore be idempotent on retry - the surviving pointer is what the
 * operator retries from, and the hooks that already ran will run again.
 *
 * <h2>Contract</h2>
 *
 * <p>What the code honours today, read out of {@code HoldLifecycle} rather than out of an intention - the prose above
 * asserted the <em>opposite</em> ordering until corrected it, and believing it would have justified migrating
 * this role onto a contained after-commit delivery. Nine of the clauses below are executable:
 * {@code store.testkit}'s {@code PublicationHookContract} drives them through five release fixtures in
 * {@code test/hooks}, and each such clause names the property that proves it.
 *
 * <ol>
 * <li><b>Role - a pre-commit, fail-closed mutation hook, despite the name.</b> A hold-release hook is neither a
 *     {@code PublicationObserver} nor a {@code PublishInterceptor}: it rides the gate's own
 *     {@code uses HoldReleaseObserver} clause, not the store's, so no registration accident can route it into the
 *     after-commit family whose failures are logged and swallowed. {@link #released} is called by
 *     {@code HoldLifecycle.release} <em>first</em> - before the guarded release link, before {@code HoldClears} lifts
 *     the content-addressed withhold marker, before the {@code /quarantine} pointer is unpublished, before the stored
 *     quarantine dispatch descriptor is consumed and before the release {@code EventSink} event. {@link #discarded} is
 *     called by {@code HoldLifecycle.discard} before the dispatch descriptor is dropped, before a retroactive hold's
 *     release pointer is unpublished, before a blobs-namespace version's served blobs are evicted and before the
 *     {@code /quarantine} pointer clears. One thing precedes it: the quarantine log rows are reaped immediately
 *     before the fan-out, so a discard hook must not assume its own {@code audit/quarantine} row still exists.
 *     Proven by {@code A_RELEASE_HOOK_IS_NOT_A_CONTAINED_PUBLICATION_OBSERVER} and
 *     {@code THE_ROLE_IS_DERIVED_FROM_THE_INSTANCE}, which derive the role from the instance a deployment actually
 *     resolves rather than from anything a fixture declares. <b>The role must never be moved onto a deferred or
 *     outbox-backed delivery</b> (&sect;3.3): containment would let a release become visible with its override
 *     marker unwritten, which is exactly the state the kev-/license-/reachability-enforce sweeps re-hold.</li>
 * <li><b>Thread-safety.</b> A hook is instantiated per fan-out and used by the one thread that drives that fan-out, so
 *     an implementation need not be thread-safe - but it must hold no mutable instance state, because it is never the
 *     same instance twice (clause 10). Concurrency between <em>fan-outs</em> is real and unserialised: two reviewers
 *     releasing two paths of one version, or a review release racing an enforce sweep, run hooks concurrently over the
 *     same {@code holds/}/{@code overrides/} keys. A hook that reads-then-writes such a key must use the store's
 *     compare-and-set with the bounded-retry idiom, as {@code KevHold}/{@code LicenseHold}/{@code ReachabilityHold}
 *     do; a plain read-modify-write silently loses a concurrent override.</li>
 * <li><b>Idempotency / replay.</b> Every method must converge when called again with the same arguments. This is not
 *     advisory: the fan-out has no checkpoint, so a failure at hook <i>k</i> leaves hooks <i>1..k-1</i>' effects
 *     durable and the operator's retry re-runs <em>all</em> of them, including those that already succeeded. An
 *     override promotion is therefore an upsert (a union into the existing marker), never an append or an increment,
 *     and a record deletion is a delete-if-present. A hook may also be re-run after its own effect completed and the
 *     surface then failed downstream. Proven by {@code HOOKS_THAT_RAN_BEFORE_THE_FAILURE_ARE_IDEMPOTENT_ON_RETRY},
 *     which drives release-fail-retry-retry and asserts the override is equal after the second and third runs and the
 *     per-version record stays gone.</li>
 * <li><b>Absence sentinel.</b> {@code null} is never a legal return. {@link #holds} answers {@code false} - never an
 *     exception, never {@code null} - for a path no installed format maps to a coordinate and for a coordinate this
 *     kind never held; {@link #kind()} is never {@code null} and never blank. {@link #onReleased} and
 *     {@link #onDiscarded} are <em>no-ops</em> for a path this hook's own kind never held, so registering a provider
 *     for a hold kind a deployment does not use is harmless, and neither invents a record or an override for such a
 *     path. Proven by {@code A_HOOK_IS_A_NO_OP_FOR_A_PATH_IT_NEVER_HELD}.</li>
 * <li><b>Selection failure (&sect;9).</b> The policy is {@code ALL} and there is nothing to select: every discovered
 *     hook runs, there is no selection key, and no arrangement of providers is a resolution error. Absence is the
 *     boundary case that matters, and it is <b>asymmetric</b>. Adding a provider is harmless (clause 4). Removing one
 *     used to be fail-open, and is not any more: <b>a hold survives its module's absence, because the durable
 *     {@code holds/<kind>/} record - not the provider list - is what "is this held" is answered from.</b>
 *     {@link #anyHolds} and {@link #heldByAnotherKind} read {@link HoldRecords} first and only then fan out over the
 *     discovered hooks, so they answer {@code true} for a kind whose module is no longer installed, exactly as they do
 *     for one that is. That is the union of the two sources and it is fail-closed in both directions: the store leg
 *     covers a kind whose provider is gone, and the provider leg still covers a hook whose {@link #holds} keys on
 *     something other than its own record. Both callers therefore keep holding - {@code ComplianceScreen} still treats
 *     the {@code /quarantine} pointer as sweep-owned, so an accepted re-publish cannot launder it, and
 *     {@code HoldClears} still leaves the content-addressed withhold marker standing. What provider absence <em>does</em>
 *     decide is only what can be explained and what can be released: a kind with a record and no provider is
 *     {@link HoldRecords#orphanedKinds orphaned}, shown as such in the review queue and reaped only by an operator's
 *     explicit release or discard ({@link HoldRecords#releaseOrphaned}), never by the absence itself. Proven by
 *     {@code an_uninstalled_kinds_hold_still_holds} in the gate suite, where the reachability module is genuinely off
 *     the module graph, and by {@code OrphanedHoldTest} in {@code test/hooks} over the real review surface.
 *
 *     <p><b>A second module can be absent, and it is a different absence: the FORMAT that maps a request path to a
 *     coordinate.</b> The record survives it too - it is keyed by the triple, not by the path - but the path-keyed
 *     {@link #heldByAnotherKind(ArtifactStore, String, String)} and {@link #anyHolds} cannot reach it without one, so
 *     for an unplaceable path both answer {@code false} from both legs. That {@code false} means "nothing could be
 *     asked", and <b>no consumer may spend it as "nothing holds this"</b>: it is judged at each consumer, per site,
 *     because what to do about it differs. {@code ComplianceScreen.sweepHeld} answers
 *     sweep-owned so an accepted re-publish cannot launder a hold; the KEV auto-release refuses to release at all and
 *     says why. A release site that already holds the coordinate - which every enforcement sweep does - must instead
 *     use {@link #heldByAnotherKind(ArtifactStore, String, String, String, Collection, String)}, whose authoritative
 *     leg is coordinate-keyed and needs no format at all.</li>
 * <li><b>Tenant scoping (&sect;6).</b> A hook carries no tenant and must never derive one. The handed
 *     {@link ArtifactStore} is already tenant-and-repository scoped by the review surface, and every key a hook reads
 *     or writes must be relative to it. A hook must not open a second store, and must not read a coordinate's state
 *     from anywhere but the store it was handed - a cross-tenant read here would let one tenant's release clear
 *     another tenant's hold.</li>
 * <li><b>Error visibility (&sect;9).</b> Fail-closed, in both directions. A checked {@link IOException} and any
 *     {@link RuntimeException} propagate out of {@link #released}/{@link #discarded} unchanged - there is no
 *     {@code try}/{@code catch} on either fan-out - so the release or discard fails and <em>nothing</em> is mutated.
 *     That is the required behaviour, not a leak: "I could not record the override" and "the artifact is released"
 *     are opposite answers, and leaving a hold standing is always the safe one. A hook must therefore never swallow
 *     its own store failure into a silent success. The read fan-outs are fail-closed too: {@link #anyHolds} and
 *     {@link #heldByAnotherKind} propagate, so a caller that cannot prove no hold covers a path does not clear it.
 *     Proven by {@code A_THROWING_HOOK_PROPAGATES_AND_LEAVES_THE_HOLD_SAFE} (a poisoned hook) and
 *     {@code A_STORE_FAULT_MID_FAN_OUT_LEAVES_THE_HOLD_SAFE} (a fault-injected backend), both of which re-read the
 *     hold from durable state rather than from what the surface remembered.</li>
 * <li><b>Read purity (&sect;10) and write scope.</b> {@link #holds} and {@link #kind()} are pure reads: store reads
 *     and layout {@code describe} only, no external I/O, no network, and no mutation - {@link #anyHolds} is called on
 *     the accepted-publish hot path by {@code ComplianceScreen} and inside {@code HoldClears}' marker-clear guard,
 *     where a write or a network call would be a second gate. {@link #onReleased}/{@link #onDiscarded} are the write
 *     legs, and each must write only inside the {@code StorageNamespace} prefixes its own module declares.
 *     Proven by {@code THE_HOOK_STAYS_INSIDE_ITS_DECLARED_NAMESPACES}, which walks the store after the fan-out and
 *     attributes every touched prefix.</li>
 * <li><b>Staleness.</b> There is no staleness surface and none is needed: every method re-reads durable store state
 *     on every call, no hook caches a view of {@code holds/} or {@code overrides/} between calls, and none may - the
 *     records are written by enforce sweeps this hook never observes. A hook that memoised a coordinate's hold state
 *     would answer {@link #holds} from a snapshot the sweep has already moved past.</li>
 * <li><b>Lifecycle / ownership.</b> Discovery is {@link #discovered()}, the one {@link ServiceLoader} call this
 *     interface makes, and nothing is cached - so a provider is still constructed afresh on each of
 *     {@link #anyHolds}, {@link #heldByAnotherKind}, {@link #released} and {@link #discarded}, and one release
 *     still drives several distinct instances. Each of those four also has an overload taking the hooks as an
 *     argument: that is the substitution seam, and it is what lets a suite drive the real choreography over hooks
 *     it constructed rather than hand-fanning a loop of its own beside a fan-out that would re-discover the
 *     genuine hook and run it twice. A provider must therefore have a cheap public no-argument constructor, own no threads, no clients
 *     and no connections, and keep no state across calls; nothing is ever closed. All state that outlives a call
 *     belongs in the scoped store.</li>
 * <li><b>Ordering / concurrency.</b> Fan-out order is {@link ServiceLoader} discovery order - module-path order -
 *     and is deliberately <em>not</em> sorted and not part of the contract. Hooks must be mutually independent: each
 *     owns its own {@code holds/<kind>/} and {@code overrides/<kind>/} keys, no hook may read another kind's records
 *     during the fan-out, and no hook may depend on running before or after another. What <em>is</em> ordered is the
 *     surface: every hook completes before the release becomes visible, so a fan-out that fails part-way never serves
 *     a half-released artifact. Proven by {@code THE_RELEASE_IS_VISIBLE_ONLY_AFTER_EVERY_HOOK_SUCCEEDED}.</li>
 * <li><b>Bounded work / cancellation (&sect;9).</b> Both fan-outs run synchronously on the reviewer's request thread,
 *     with no timeout, no interruption protocol and no cap on the number of hooks - the release surface holds no
 *     lease and offers no cancellation, so any time a hook spends blocked is time a reviewer's HTTP request spends
 *     blocked. Each hook must therefore bound its own work in-band and against the size of the review state rather
 *     than the repository: a per-coordinate record read, a per-version guard, or a scan of the queued-work space
 *     ({@code ForwardingDiscardObserver} does one outbox name lookup). A hook must not walk the artifact tree, fetch
 *     over the network, or read an artifact body - only the small markers and the path's format-neutral descriptor.
 *     Nothing enforces this bound.</li>
 * <li><b>Durability / delivery.</b> There is no delivery class here, because there is no delivery: this role is
 *     <em>stronger</em> than at-least-once. The mutation does not happen unless every hook succeeded, so a hook is
 *     never "lost" and no repair leg exists or is needed. The crash windows are exactly two, and both converge.
 *     (i) Between two hooks: the earlier hooks' effects are durable, the {@code /quarantine} pointer still stands, and
 *     the retry re-runs every hook - which is what clause 3 exists for. (ii) Between the last hook and the surface's
 *     own first mutation: the artifact is left held-and-overridden, which a re-run converges from and which no enforce
 *     sweep re-holds, because the human's override is already durable. The durable source of truth is the store: the
 *     {@code publish/quarantine<path>} review pointer is the operator's retry surface and the
 *     {@code overrides/<kind>/} marker is the human's decision. Both are re-read from the store rather than
 *     remembered, so a crash at any point is indistinguishable from a retry.</li>
 * </ol>
 */
public interface HoldReleaseObserver {

    /** React to a reviewer releasing {@code path}: a no-op unless this observer's hold kind held the path. */
    void onReleased(ArtifactStore store, String path) throws IOException;

    /** React to a reviewer discarding {@code path} without releasing it: drop this observer's hold record for the
     *  path, so a thrown-away version's {@code holds/} row does not dangle forever (a discarded version has no
     *  {@code published/} sidecar, so no eviction or reconcile sweep would ever reach it). No override is promoted -
     *  no human cleared the finding. A default no-op so an observer without a per-version record ignores it. */
    default void onDiscarded(ArtifactStore store, String path) throws IOException {
    }

    /** Whether this observer's hold kind currently holds a retroactive record for {@code path}'s coordinate version -
     *  the read the accepted-re-publish guard consults so a re-upload of a held version never clears a sweep-owned
     *  hold pointer of <em>any</em> kind (before this, only the KEV kind was consulted, and an accepted re-publish
     *  declaring clean metadata laundered a license-retro hold). Default {@code false} for an observer without a
     *  per-version record. */
    default boolean holds(ArtifactStore store, String path) throws IOException {
        return false;
    }

    /** The stable kind token of this observer's hold records - the {@code <kind>} segment of its
     *  {@code holds/<kind>/<eco>/<coord>/<ver>} keys ({@code "kev"}, {@code "license"}, {@code "reachability"}, ...).
     *  Lets an automated release of ONE kind ask whether any OTHER registered kind still holds a coordinate, without
     *  naming kinds at the release site. The default - {@code getClass().getName()} - is a value no real hold-key
     *  prefix ever equals, so an observer that overrides neither {@code kind()} nor {@link #holds} never matches an
     *  exclusion (and, holding nothing, never blocks a release). A provider with per-version records overrides it to
     *  its own {@code holds/<kind>/} prefix segment. */
    default String kind() {
        return getClass().getName();
    }

    /** Whether ANY hold kind holds a retroactive record for {@code path} - the read the gate's accepted-publish leg
     *  uses to decide whether a pointer at the path is sweep-owned. The durable {@link HoldRecords} are asked FIRST and
     *  are authoritative: a kind whose module has been uninstalled still holds, because its record still stands and
     *  module absence must never release anything. The provider fan-out runs after, and only widens the answer
     *  - it is what still covers a hook whose {@link #holds} keys on state other than its own {@code holds/} record. */
    static boolean anyHolds(ArtifactStore store, String path) throws IOException {
        return anyHolds(store, path, discovered());
    }

    /** {@link #anyHolds} over an explicit set of hooks - the substitution seam. See {@link #discovered()}. */
    static boolean anyHolds(ArtifactStore store, String path, Iterable<HoldReleaseObserver> observers)
            throws IOException {
        if (!HoldRecords.heldKinds(store, path).isEmpty()) {
            return true;
        }
        for (HoldReleaseObserver observer : observers) {
            if (observer.holds(store, path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether any kind other than {@code releasingKind} still holds a retroactive record for {@code path} - the
     * kind-neutral guard every <em>automated</em> (non-human) release MUST consult before un-retracting any pointer or
     * marker, so no one kind's release strands another kind's hold. The {@link #anyHolds} counterpart that excludes
     * the releasing kind's own record, and answered from the same two sources in the same order: the durable
     * {@link HoldRecords} first - so an UNINSTALLED kind's hold still blocks an automated release, which is the whole
     * point of a kind-neutral guard - then the discovered providers, so a future hold kind joins by writing a record
     * or adding a provider, with no edit to any release site.
     *
     * <p><b>The durable leg no longer needs an installed format.</b> It used to: with the owning format's
     * module off the graph nothing could turn the request path into a coordinate, so this answered {@code false} for a
     * hold that was standing - the same dependence left behind. {@link HoldRecords#heldKinds(ArtifactStore,
     * String)} now falls back to the durable {@code subjects/} record the hold wrote when it was placed, so an
     * uninstalled format costs this guard nothing. The <em>provider</em> leg keeps the describe-dependence, because
     * every observer's {@link #holds} is path-keyed and resolves its own coordinate; it only ever widens the answer,
     * so what it loses is coverage of a hook keying on state other than its own record, never a record already found
     * above. A path nothing can place at all - no format, no recorded subject - still answers {@code false} from both
     * legs, and that answer is <em>"nothing could be asked"</em> which a consumer must never spend as <em>"no other
     * kind holds"</em>: judged at the consumer, per site, exactly as {@link #anyHolds}' is, where
     * {@code ComplianceScreen.sweepHeld} treats an unplaceable path as sweep-owned so an accepted re-publish cannot
     * launder a hold, and the KEV auto-release refuses to release at all rather than release unguarded. An automated
     * release site that HAS the coordinate - which every enforcement sweep does, since its records are
     * coordinate-keyed - should ask
     * {@link #heldByAnotherKind(ArtifactStore, String, String, String, Collection, String)} instead, whose durable leg
     * needs no format at all.
     */
    static boolean heldByAnotherKind(ArtifactStore store, String path, String releasingKind) throws IOException {
        return heldByAnotherKind(store, path, releasingKind, discovered());
    }

    /** {@link #heldByAnotherKind(ArtifactStore, String, String)} over an explicit set of hooks. */
    static boolean heldByAnotherKind(ArtifactStore store, String path, String releasingKind,
                                     Iterable<HoldReleaseObserver> observers) throws IOException {
        for (String kind : HoldRecords.heldKinds(store, path)) {
            if (!kind.equals(releasingKind)) {
                return true;
            }
        }
        for (HoldReleaseObserver observer : observers) {
            if (!observer.kind().equals(releasingKind) && observer.holds(store, path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The kind-neutral guard asked of the COORDINATE rather than of one request path - the form an automated release
     * site that already holds {@code (ecosystem, coordinate, version)} must use.
     *
     * <p>The durable leg is {@link HoldRecords#heldKinds(ArtifactStore, String, String, String)}, which is keyed by the
     * triple and so needs no installed format at all: a hold placed by an enforcement sweep is found whether or not the
     * module that placed it, or the module that lays the version out, is on the graph today. Only the provider fan-out
     * is path-keyed, because {@link #holds} is; it runs over {@code servedPaths} - the paths the caller has already
     * proved it could enumerate - and only widens the answer, exactly as in the path form. Pass the served paths of the
     * version; an empty list is legitimate for a version an installed format enumerates no path for, and the durable
     * leg still answers. What must NOT be passed is the empty list standing in for "I could not enumerate them": the
     * caller judges that first and keeps the hold.
     */
    static boolean heldByAnotherKind(ArtifactStore store, String ecosystem, String coordinate, String version,
                                     Collection<String> servedPaths, String releasingKind) throws IOException {
        return heldByAnotherKind(store, ecosystem, coordinate, version, servedPaths, releasingKind, discovered());
    }

    /** {@link #heldByAnotherKind(ArtifactStore, String, String, String, Collection, String)} over an explicit set
     *  of hooks. */
    static boolean heldByAnotherKind(ArtifactStore store, String ecosystem, String coordinate, String version,
                                     Collection<String> servedPaths, String releasingKind,
                                     Iterable<HoldReleaseObserver> observers) throws IOException {
        for (String kind : HoldRecords.heldKinds(store, ecosystem, coordinate, version)) {
            if (!kind.equals(releasingKind)) {
                return true;
            }
        }
        for (HoldReleaseObserver observer : observers) {
            if (observer.kind().equals(releasingKind)) {
                continue;
            }
            for (String path : servedPaths) {
                if (observer.holds(store, path)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Fan a release out to every discovered observer, in module-path order, <em>before</em> the release surface links
     *  the release pointer or clears the {@code /quarantine} pointer. The {@link ServiceLoader} call lives here, in the
     *  SPI home ({@code gate}) that {@code uses} the service, so a release surface reaches the observers through one
     *  static rather than naming each hold type. Nothing is caught: a throwing hook fails the release with the hold
     *  still standing. */
    static void released(ArtifactStore store, String path) throws IOException {
        released(store, path, discovered());
    }

    /** {@link #released} over an explicit set of hooks - the substitution seam. */
    static void released(ArtifactStore store, String path, Iterable<HoldReleaseObserver> observers)
            throws IOException {
        for (HoldReleaseObserver observer : observers) {
            observer.onReleased(store, path);
        }
    }

    /** Fan a discard out to every discovered observer, the {@link #released} counterpart for the review surface that
     *  throws a held artifact away - and, like it, <em>before</em> anything is destroyed: the release pointer is still
     *  live, the version's served blobs are still there and the {@code /quarantine} pointer still indexes the review
     *  queue when a hook runs. That is what lets a hook record a durable intent about the destruction (the forwarding
     *  module's discard tombstone is one), and it is why a throwing hook must leave the discard undone. */
    static void discarded(ArtifactStore store, String path) throws IOException {
        discarded(store, path, discovered());
    }

    /** {@link #discarded} over an explicit set of hooks - the substitution seam. */
    static void discarded(ArtifactStore store, String path, Iterable<HoldReleaseObserver> observers)
            throws IOException {
        for (HoldReleaseObserver observer : observers) {
            observer.onDiscarded(store, path);
        }
    }

    /**
     * The installed hooks - the <b>one</b> {@code ServiceLoader} call for this SPI, which every no-hook-argument
     * static above delegates to.
     *
     * <p>Each of those statics used to load for itself, which made the fan-out impossible to substitute: a test
     * could hand-fan its own hooks, or it could call the real choreography, but not both - calling the real one
     * re-discovered the genuine hook and ran it a second time. Four hold-release hooks were consequently held to a
     * contract that read a hook the test never constructed, which is why the two roles that <em>do</em> take their
     * collaborators as an argument are the two whose omission mutations all bite.
     */
    static Iterable<HoldReleaseObserver> discovered() {
        // Through the shared primitive, which is what makes a duplicate kind a packaging error rather than a silent
        // winner. A kind is not a label: it names the hook's own holds/<kind>/ and overrides/<kind>/ key space, so two
        // observers answering to one kind make ONE HOOK'S RELEASE CLEAR ANOTHER'S HOLD - the fail-open direction
        // D-095 closed from the removed-provider side and this reached from the duplicated-provider side (D-163b).
        //
        // Sorting by kind is a side effect and a welcome one. The ordering clause below says fan-out order is
        // discovery order and is deliberately NOT part of the contract, precisely because hooks must be mutually
        // independent - so nothing may rely on it, and making the unspecified deterministic across nodes costs
        // nothing and removes a difference between two deployments that only module-path order explains.
        return Providers.all("hold-release", ServiceLoader.load(HoldReleaseObserver.class),
                HoldReleaseObserver::kind, _ -> true, Optional::of);
    }
}
