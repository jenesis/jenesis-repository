package build.jenesis.repository.walk;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ServableNames;

import module java.base;

/**
 * The screened enumeration: one call that <em>lists and screens</em> a container's child names, so a serving surface
 * receives only the names a {@code GET} would serve and never holds the raw ones at all. It is the enumeration face of
 * the {@link ServableNames servable-name seam} - every per-name verdict is delegated straight back to that seam, this
 * type decides nothing - driven by the shared bounded primitive {@link BoundedChildren}, so a screened listing is also
 * a bounded, resumable one without a second page loop existing anywhere.
 *
 * <p><strong>Why the screen has to live inside the enumeration.</strong> Every format used to hand-write the same two
 * steps: list the container, then filter each name through a withhold probe. The steps are separable, so they get
 * separated - by a refactor, by a new surface copied from an older one, by a format author who only knows the first
 * step - and a listing that forgot the second one publishes the <em>existence</em> of a quarantined or retracted
 * artifact, which is the disclosure the hold was meant to prevent. Here the two are one call and cannot be pulled
 * apart: the store paging is private, the caller never sees an unscreened name, and there is no constructor, no
 * policy and no override that yields an unscreened enumeration. Forgetting the screen is not a mistake this API can
 * express.
 *
 * <p><strong>Choosing a face, not a predicate.</strong> A caller says which seam face an enumerated name is judged by;
 * it can never supply the judgement itself:
 * <ul>
 *   <li>{@link #paths} - children of a {@code publish/} prefix, judged as served request paths
 *       ({@link ServableNames#disclosable}). The request path is <em>derived</em> from the scanned prefix, so the
 *       {@code "publish" + path} convention is applied in one place rather than re-spelled per surface.</li>
 *   <li>{@link #versionFolders} - children of a {@code publish/} coordinate prefix, judged as whole version folders
 *       ({@link ServableNames#disclosableVersionFolder}): a generated version index discloses a version, not a
 *       leaf.</li>
 *   <li>{@link #keys(ServableNames, ServableNames.Policy)} - children of a {@code blobs}-namespace pointer prefix,
 *       judged as pointer keys
 *       ({@link ServableNames#disclosableKey}); {@link #keys(ServableNames, ServableNames.Policy, UnaryOperator)}
 *       covers the common layout where the enumerated container (a version index) is <em>not</em> the parent of the
 *       key that carries the content (the artifact pointer), which is exactly how npm, NuGet, Cargo, Composer,
 *       RubyGems and CocoaPods are laid out.</li>
 * </ul>
 *
 * <p><strong>Containers are not leaves.</strong> A name a caller's {@link Containers} probe classifies as a container
 * (a directory in a browse tree, a sub-listing in a raw index) is forwarded unscreened, because it is a listing and
 * its own leaves carry the screen; the sink is told which it got. With no probe configured every name is a leaf and
 * every name is screened. The reserved {@linkplain ServableNames#QUARANTINE review subtree} is suppressed whenever the
 * scanned container is the {@linkplain ServableNames#PUBLISHED served pointer root}, so the one place it is stored but
 * never served is honoured by every surface that enumerates that root.
 *
 * <p><strong>Two caps, one outcome vocabulary.</strong> The scan cap (how many stored names one call may examine, from
 * the {@link BoundedChildren} bounds) bounds the work; the {@linkplain #take take} cap (how many disclosable names one
 * call may deliver) bounds the answer. Either one ends the call {@link Traversal.Outcome#TRUNCATED} with a
 * continuation cursor - never a short list that looks complete - and the bounds with no safe continuation (step
 * budget, hostile segment) still raise {@link TraversalException} exactly as {@link BoundedChildren} defines. The take
 * cap is only spent when a <em>further</em> disclosable name has been proven to exist, so a container whose
 * disclosable names exactly fill the page still answers exhausted.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> An immutable configuration (seam, face, policy, bounds, caps), safe to share, cache and
 *       drive concurrently; one {@link #scan} call owns all its mutable state and calls the caller's {@link Disclosed}
 *       sink and {@link Containers} probe only on the calling thread.</li>
 *   <li><b>Idempotency / replay.</b> A pure read that commits nothing: re-running a scan, or resuming from an older
 *       cursor, is always safe. A sink with side effects must be idempotent per name, since a crash before the cursor
 *       is committed replays the last page.</li>
 *   <li><b>Absence sentinel.</b> An absent or empty container is not an error - {@link Traversal.Outcome#EXHAUSTED}
 *       with zero delivered, and {@link #any} is {@code false}. {@code null} is never returned; a {@code null} or empty
 *       cursor starts at the beginning.</li>
 *   <li><b>Selection failure.</b> The face is chosen by construction, so an unscreened enumeration is unrepresentable
 *       rather than a silent fallback. A {@link #paths}/{@link #versionFolders} scan aimed outside the
 *       {@linkplain ServableNames#PUBLISHED served pointer root} - where a derived request path would be a fiction -
 *       fails immediately with {@link IllegalArgumentException}, as does a non-positive cap; a malformed prefix or a
 *       cursor that is not an immediate child of it fails exactly as {@link BoundedChildren} defines. None of these
 *       degrades to an empty page.</li>
 *   <li><b>Streaming.</b> Disclosable names stream to the sink one at a time; only one {@link BoundedChildren#page()}
 *       -wide page of names is buffered, and no artifact blob is ever opened - the screen reads pointers and existence
 *       markers only.</li>
 *   <li><b>Tenant scoping.</b> The scan is confined to the children of the scanned prefix of the tenant-scoped store
 *       it is handed, and every name is screened as a traversal-free segment before it is composed into a key or a
 *       request path, so a backend returning {@code ..} cannot walk an enumeration out of its scope. The seam is
 *       handed the same store, so the disclosure decision is scoped to the same tenant as the listing.</li>
 *   <li><b>Error visibility.</b> A screen never fails open. A hostile name whose probe throws a
 *       {@link RuntimeException} is contained inside the seam and judged undisclosable, so it is skipped rather than
 *       served; a checked {@link IOException} - a store outage, an interceptor failing closed, a sink failure -
 *       propagates and fails the whole enumeration, because a listing that silently lost names to an error is exactly
 *       the plausible-but-incomplete answer the bounds model forbids. Nothing is swallowed.</li>
 *   <li><b>Read purity.</b> Store reads only; no write, no external fetch, no cursor persistence of its own.</li>
 *   <li><b>Staleness.</b> A live read, not a snapshot: a hold that lands mid-scan is honoured for the names not yet
 *       examined, which is the safe direction. Names written during the call are seen only if they sort after the
 *       current page cursor.</li>
 *   <li><b>Ordering / concurrency.</b> Names arrive in the store's lexicographic child order, deterministically, and
 *       one call never parallelises itself; the screen is applied in that same order, so two callers of the same
 *       container in the same state see the same names in the same order.</li>
 *   <li><b>Bounded work / cancellation.</b> The scan cap, the take cap, the step budget and the page width bound every
 *       call. The visible outcome at a bound is {@link Traversal.Outcome#TRUNCATED} plus a cursor (scan and take caps)
 *       or a {@link TraversalException} naming the bound (steps, hostile segment). A caller cancels by throwing from
 *       {@link Disclosed#accept}.</li>
 *   <li><b>Durability / delivery.</b> Nothing is committed here; a caller that persists the continuation cursor
 *       commits it through the store after the page's effects, so a crash replays a page rather than skipping one.</li>
 * </ol>
 */
public final class ScreenedNames {

    /** Which seam face judges an enumerated name. There is deliberately no {@code NONE}. */
    private enum Face {
        /** A served request path under {@code publish/} - {@link ServableNames#disclosable}. */
        PATH,
        /** A whole version folder under {@code publish/} - {@link ServableNames#disclosableVersionFolder}. */
        VERSION_FOLDER,
        /** A {@code blobs}-namespace pointer key - {@link ServableNames#disclosableKey}. */
        KEY
    }

    /** Whether a scanned child is a container rather than a servable leaf; containers forward unscreened because
     *  their own leaves carry the screen. The argument is the child's full <em>store key</em> (the scanned prefix
     *  joined with the name), so a probe is a plain store question and needs no prefix bookkeeping of its own. */
    @FunctionalInterface
    public interface Containers {

        /** @param childKey the scanned prefix joined with the child name */
        boolean test(String childKey) throws IOException;
    }

    /** One disclosable name. A throw abandons the enumeration. */
    @FunctionalInterface
    public interface Disclosed {

        /**
         * Called once per disclosable child name, in the store's lexicographic child order.
         *
         * @param name      the child name (not a key - compose it onto the prefix, or onto the surface's own path)
         * @param container whether {@link Containers} classified it a container, and it therefore forwarded
         *                  unscreened; {@code false} means the seam judged this very name disclosable
         */
        void accept(String name, boolean container) throws IOException;
    }

    private final ServableNames names;
    private final Face face;
    private final ServableNames.Policy policy;
    private final UnaryOperator<String> identity;
    private final Containers containers;
    private final BoundedChildren bounds;
    private final int take;

    private ScreenedNames(ServableNames names, Face face, ServableNames.Policy policy,
                          UnaryOperator<String> identity, Containers containers, BoundedChildren bounds, int take) {
        this.names = names;
        this.face = face;
        this.policy = policy;
        this.identity = identity;
        this.containers = containers;
        this.bounds = bounds;
        this.take = take;
    }

    private static ScreenedNames of(ServableNames names, Face face, ServableNames.Policy policy,
                                    UnaryOperator<String> identity) {
        return new ScreenedNames(Objects.requireNonNull(names, "names"), face, policy, identity,
                _ -> false, BoundedChildren.bounded(), Integer.MAX_VALUE);
    }

    /** Children of a {@code publish/} prefix, judged as the served request paths they resolve to under {@code policy}
     *  - the browse tree, a raw directory listing, any surface that lists what a {@code GET} would serve. The request
     *  path is derived from the scanned prefix ({@code publish/maven/g/a} + {@code 1.0.jar} is
     *  {@code /maven/g/a/1.0.jar}), so a scan aimed outside {@link ServableNames#PUBLISHED} is refused rather than
     *  screening a fictional path. */
    public static ScreenedNames paths(ServableNames names, ServableNames.Policy policy) {
        return of(names, Face.PATH, Objects.requireNonNull(policy, "policy"), null);
    }

    /** Children of a {@code publish/} coordinate prefix, judged as whole version folders - the generated version index
     *  (maven-metadata and its peers), where the unit of disclosure is the version, not one leaf inside it, and where
     *  no blob may be stated (a fake-hash or no-blob version must keep listing). */
    public static ScreenedNames versionFolders(ServableNames names) {
        return of(names, Face.VERSION_FOLDER, null, null);
    }

    /** Children of a {@code blobs}-namespace pointer prefix, judged as the pointer keys they are - the layout where
     *  the enumerated container already holds the pointers (a PyPI file set, a Go {@code @v} directory, an OCI tag
     *  space). */
    public static ScreenedNames keys(ServableNames names, ServableNames.Policy policy) {
        return of(names, Face.KEY, Objects.requireNonNull(policy, "policy"), null);
    }

    /** Children of a {@code blobs}-namespace container, judged as the pointer key each name's content lives at when
     *  that key is <em>not</em> under the enumerated container - the npm {@code versions} index naming a
     *  {@code tarballs} pointer, the NuGet version folder naming its {@code .nupkg}, and the Cargo / Composer /
     *  RubyGems / CocoaPods index-beside-content layouts. The mapper turns one enumerated name into the key that
     *  carries its bytes; it cannot turn the screen off. */
    public static ScreenedNames keys(ServableNames names, ServableNames.Policy policy, UnaryOperator<String> key) {
        return of(names, Face.KEY, Objects.requireNonNull(policy, "policy"), Objects.requireNonNull(key, "key"));
    }

    /** The same screen over different scan bounds - how many names one call may examine, how many page round-trips it
     *  may spend, how wide a page is. Narrow these for a request-scoped surface; the defaults are
     *  {@link BoundedChildren#bounded()}. */
    public ScreenedNames scanning(BoundedChildren bounds) {
        return new ScreenedNames(names, face, policy, identity, containers,
                Objects.requireNonNull(bounds, "bounds"), take);
    }

    /** The same screen delivering at most {@code take} disclosable names per call - the page size of a listing whose
     *  window is counted in <em>servable</em> entries (an OCI {@code n}, a search page), which keeps its page full by
     *  scanning past screened-out names within the scan bounds. */
    public ScreenedNames take(int take) {
        if (take <= 0) {
            throw new IllegalArgumentException("The take bound must be positive: " + take);
        }
        return new ScreenedNames(names, face, policy, identity, containers, bounds, take);
    }

    /** The same screen with a container probe: a child whose full store key the probe accepts is a listing, not a
     *  servable leaf, so it forwards unscreened and the sink is told so. */
    public ScreenedNames containers(Containers containers) {
        return new ScreenedNames(names, face, policy, identity,
                Objects.requireNonNull(containers, "containers"), bounds, take);
    }

    /** Screen {@code prefix}'s children from the beginning - {@link #scan(ArtifactStore, String, String, Disclosed)}
     *  with no cursor. */
    public Traversal.Result scan(ArtifactStore store, String prefix, Disclosed disclosed) throws IOException {
        return scan(store, prefix, null, disclosed);
    }

    /**
     * Deliver the disclosable immediate child names of {@code prefix} to {@code disclosed}, in the store's child
     * order, starting strictly after {@code cursor} ({@code null} or empty starts at the beginning), until the
     * container is drained or a cap is reached. Screened-out names are never delivered and never counted as delivered;
     * they still cost the scan, which is why the scan bound - not the take cap - is what bounds a container of held
     * names.
     *
     * <p>The result is {@link Traversal.Result#exhausted} only when the container was provably drained; a cap answers
     * {@link Traversal.Result#truncated} with a cursor to pass back. {@code delivered} counts the disclosable names
     * this call handed over and {@code steps} the stored names it examined to find them - the work a screened
     * enumeration actually does, since each examined name costs a seam probe.
     */
    public Traversal.Result scan(ArtifactStore store, String prefix, String cursor, Disclosed disclosed)
            throws IOException {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(disclosed, "disclosed");
        String scope = Objects.requireNonNull(prefix, "prefix");
        if (identity == null && face != Face.KEY) {
            served(scope); // fail now, not once per name, if a derived request path would be a fiction
        }
        boolean root = ServableNames.PUBLISHED.equals(scope);
        long[] examined = {0};
        long[] delivered = {0};
        String[] last = {null};
        try {
            Traversal.Result scanned = bounds.scan(store, scope, cursor, name -> {
                examined[0]++;
                if (root && ServableNames.reviewSubtree(name)) {
                    return; // the review subtree is stored but never served, at the one root it can appear under
                }
                boolean container = containers.test(Traversal.key(scope, name));
                if (!container && !disclosable(scope, name)) {
                    return; // the seam judged it undisclosable: it is not delivered, and its name never leaves here
                }
                if (delivered[0] == take) {
                    // A further disclosable name proves the container is not drained - so the take cap is spent here,
                    // WITHOUT delivering it, and the cursor resumes at the last name this call did deliver.
                    throw new Taken();
                }
                disclosed.accept(name, container);
                delivered[0]++;
                last[0] = Traversal.key(scope, name);
            });
            return scanned.exhausted()
                    ? Traversal.Result.exhausted(delivered[0], examined[0])
                    : Traversal.Result.truncated(scanned.cursor().orElseThrow(), delivered[0], examined[0]);
        } catch (Taken _) {
            return Traversal.Result.truncated(last[0], delivered[0], examined[0]);
        }
    }

    /**
     * Whether {@code prefix} holds at least one disclosable child - the membership question a catalog asks before it
     * lists a name ("does this image still carry a servable tag?"), answered by the same screened scan, short-circuited
     * at the first surviving name.
     *
     * <p>A boolean has no continuation, so a scan bound reached without an answer is <em>not</em> reported as
     * {@code false}: it raises {@link TraversalException} rather than letting a pathological container of held names
     * look like an empty one. {@code false} therefore always means "provably none".
     */
    public boolean any(ArtifactStore store, String prefix) throws IOException {
        try {
            // The sink stops the scan on the FIRST disclosable name: a membership question is answered, not paged, so
            // an image whose first tag survives costs one probe rather than a walk of its whole tag space.
            Traversal.Result result = scan(store, prefix, (_, _) -> {
                throw new Found();
            });
            if (result.truncated()) {
                throw new TraversalException(TraversalException.Reason.STEPS, prefix,
                        "the screened scan examined " + result.steps() + " names without proving whether any is "
                                + "disclosable, and a membership answer has no continuation to hand back");
            }
            return false;
        } catch (Found _) {
            return true;
        }
    }

    /** The seam decides; this only picks the face the caller declared. */
    private boolean disclosable(String scope, String name) throws IOException {
        String identified = identity != null ? identity.apply(name) : derived(scope, name);
        return switch (face) {
            case PATH -> names.disclosable(identified, policy);
            case VERSION_FOLDER -> names.disclosableVersionFolder(identified);
            case KEY -> names.disclosableKey(identified, policy);
        };
    }

    /** The identity a child name carries when the caller supplied no mapper: the request path it serves at for the
     *  {@code publish/} faces, the pointer key it lives at for the {@code blobs}-namespace face. */
    private String derived(String scope, String name) {
        return face == Face.KEY
                ? (scope.isEmpty() ? name : scope + "/" + name)
                : served(scope) + "/" + name;
    }

    /** The request-path parent a {@code publish/} scan prefix corresponds to ({@code publish} is the root, whose
     *  children serve at {@code /<name>}). A prefix outside that namespace has no request path, and screening a
     *  fabricated one would judge the wrong artifact, so it is refused. */
    private static String served(String prefix) {
        if (!prefix.equals(ServableNames.PUBLISHED) && !prefix.startsWith(ServableNames.PUBLISHED + "/")) {
            throw new IllegalArgumentException("A served-path screen must scan the '" + ServableNames.PUBLISHED
                    + "' pointer namespace, but the scan prefix was '" + prefix + "'; use keys(...) for a "
                    + "blobs-namespace container, or supply the identity explicitly");
        }
        return prefix.substring(ServableNames.PUBLISHED.length());
    }

    /** The take cap's internal stop signal - never observable by a caller, and never confusable with a store failure
     *  because it is private and can only be thrown from the sink above. */
    private static final class Taken extends Stop {

        private static final long serialVersionUID = 1L;
    }

    /** {@link #any}'s stop signal, deliberately a DIFFERENT type from {@link Taken}: {@link #scan} catches only the
     *  take cap's, so this one rides straight through the scan to the membership question that threw it. */
    private static final class Found extends Stop {

        private static final long serialVersionUID = 1L;
    }

    /** A control signal, not a diagnosis: capturing a stack at every page boundary would cost more than the page. */
    private abstract static sealed class Stop extends IOException permits Taken, Found {

        private static final long serialVersionUID = 1L;

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }
}
