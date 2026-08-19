package build.jenesis.repository.store;

import module java.base;

/**
 * Decouples the artifact bytes from their publication, format-neutrally. Each uploaded blob is stored once,
 * content-addressed by its SHA-256 ({@code blobs/<hash>}), so identical bytes published under several paths dedupe to
 * one object and live independently of any path. A publication is a small pointer ({@code publish/<request-path> ->
 * <hash>}); several paths can point at the same blob (the two Java layouts, a deduped coordinate, a latest mirror),
 * which is how a republish is just a pointer update. This primitive knows nothing of any layout: a format decides what
 * to publish where, and cross-publishing one layout's view into another's is a concern of the format modules, not of
 * this storage primitive.
 *
 * <p>The upload choreography runs at an ingress <em>edge</em> (a deploy controller, a batch explode, an import walk),
 * not inside a format, and it runs through one operation: {@link #commit}. It stores the streamed body
 * content-addressed, runs the discovered {@link PublishInterceptor} chain <em>once</em>, gates the republish, hands the
 * accepted blob to the claiming format's {@link AcceptedLayout} - which writes its parse results and sidecars and
 * <em>declares</em> what makes the artifact visible - links that declared {@link Visibility} last, and only then fires
 * {@link #published} so the after-commit {@link PublicationObserver}s ride the accepted publish. Screening a body
 * inside a format (a second, format-embedded chain run over already-screened bytes) is not this model, and neither is
 * an edge re-assembling the sequence by hand: {@code commit} is the one hosted-publish choreography, and the ingress
 * census asserts every hosted route runs through it.
 *
 * <h2>Why this class is {@code final}, and what that costs</h2>
 * The {@code final} is deliberate and load-bearing, not a habit. This class exists to be the product's <em>one</em>
 * hosted-publish choreography - the plan's third design gate ("extend the existing choke point; never add a parallel
 * one") and &sect;2's single-edge rule are both statements about it - and an interface seam, or a subclass, is exactly
 * how a second commit sequence enters a codebase. An embedder that needs different behaviour injects a different
 * {@link PublishInterceptor} chain or a different {@link AcceptedLayout}; it does not get to reorder store, screen,
 * gate, lay out, link and notify. Those two constructor seams are the sanctioned variation, and they are enough for
 * every caller in either edition.
 *
 * <p>The cost is paid by the tests, and it is worth naming rather than leaving to be rediscovered. The publication-hook
 * contract kit ({@code build.jenesis.repository.store.testkit}) can substitute a hook but not this class, so twenty of
 * its forty-six clauses - the chain's ordering and short-circuiting, where a review pointer lands relative to
 * {@link PublishInterceptor#committed}, what escapes the containment, the three crash windows above - are claims about
 * this choreography that no substitution for a <em>provider</em> could ever falsify. The kit closes nineteen of them by
 * arranging the hooks it does control so the choreography produces the same observable a mutated {@code Publication}
 * would produce (its {@code ChoreographyMutant}), which proves the checks discriminate; it is a faithful simulation of
 * the defect rather than the defect itself, and the twentieth - a crash before the chain runs at all - is out of reach
 * even so. <b>So a change to the sequence below is not covered by a mutation of the product, and the checks that guard
 * it are only as good as the probes they read.</b> Edit the ordering here with that in mind.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> An instance is a stateless view over one scoped {@link ArtifactStore} and its two hook
 *       lists; concurrent calls on one instance are safe and expected (the server creates them freely per request).
 *       Concurrency between two publications of the <em>same</em> request path resolves to last-writer-wins at the
 *       pointer compare-and-set, which is the outcome the two writes would have had a moment apart.</li>
 *   <li><b>Idempotency / replay.</b> Every write on this path converges: the blob is content-addressed, so a replay of
 *       identical bytes dedupes to the same object; a sidecar re-derived from the same blob is rewritten identically;
 *       the pointer compare-and-set re-lands the same body. A byte-identical re-{@link #commit} therefore leaves the
 *       store byte-identical and <em>repairs</em> a first attempt that crashed mid-layout. The after-commit observers
 *       are notified again on a replay, so an observer must tolerate duplicate delivery of the same publish.</li>
 *   <li><b>Absence sentinel.</b> {@link #located}, {@link #blob} and the pointer probes answer {@link Optional#empty}
 *       for "nothing published there"; {@code null} is never returned. A refused republish is an exception
 *       ({@link RepublishConflict}), never a silent no-op.</li>
 *   <li><b>Streaming.</b> The {@code body} of a {@link #commit} and the stream a layout takes from
 *       {@link Acceptance#open} are never materialised: the body is hashed on write into the content-addressed store
 *       and read back as a fresh stream. Only bounded metadata is buffered - a
 *       whole-document {@link PublishInterceptor.Content#sibling(String)} read is capped at
 *       {@value PublishInterceptor.Content#LARGEST_SIBLING} bytes, a bounded
 *       {@link PublishInterceptor.Content#sibling(String, int)} read materialises no more than the caller's own limit,
 *       and a {@link Acceptance#sidecar(String, byte[])} body is a small derived document by definition.</li>
 *   <li><b>Tenant scoping.</b> The store handed to the constructor is already tenant-and-repository scoped; every key
 *       this primitive writes ({@code blobs/}, {@code publish/}, {@code gc/condemned/}) and every key it hands a
 *       layout is relative to that scope, so nothing here can read or write across tenants.</li>
 *   <li><b>Error visibility.</b> Everything up to and including the commit point propagates: a screen that cannot
 *       render a verdict, a refused republish, a failing sidecar, a pointer that loses its compare-and-set three
 *       times - each fails the publish loudly and leaves nothing servable. Only the after-commit observer
 *       notifications are contained: a throwing {@link PublicationObserver} is logged and the publish stands, because
 *       a lost notification may over-serve or over-count but can never hide a served artifact or a hold.</li>
 *   <li><b>Ordering / concurrency.</b> Within one {@code commit} the order is fixed and total: store, screen, gate the
 *       republish, lay out sidecars, link the declared visibility in declaration order, notify. Interceptors run
 *       sorted by {@link PublishInterceptor#order()} (ties keep discovery order) and the strongest disposition across
 *       the chain routes the publication; observers are notified in discovery order, sequentially, and no observer
 *       ordering is otherwise promised.</li>
 *   <li><b>Bounded work / cancellation.</b> A pointer compare-and-set retries at most three times before failing by
 *       name; the {@code /quarantine} alias scan is bounded by the review queue and short-circuits on the first live
 *       alias; both sibling reads are byte-capped. No step here loops on an attacker-shaped input, and no bound is
 *       reached silently - though the two sibling reads do not answer a bound the same way, because they are not
 *       asked the same question. The whole-document {@link PublishInterceptor.Content#sibling(String)} read
 *       <em>throws</em> past {@value PublishInterceptor.Content#LARGEST_SIBLING}: its caller wants the document
 *       entire and has no use for a prefix, so handing one back as if it were whole would be the silently-incomplete
 *       answer. The bounded {@link PublishInterceptor.Content#sibling(String, int)} read <em>reports</em>: it honours
 *       the caller's own limit exactly - never this class's ceiling - and flags {@code truncated} rather than
 *       failing, because its caller asked to be told about the overflow and has a defined answer for it. Neither is
 *       silent, and both bounds are the caller's to reason about, so an inspector reading a companion through this
 *       view gets the same outcome it would get on any other ingress leg offering the same two reads.</li>
 *   <li><b>Durability / delivery.</b> The durable source of truth is the store: {@code blobs/<hash>} for content and
 *       the declared serving pointer(s) for visibility. <b>The commit point is the declared visibility write.</b>
 *       Before the first declared step nothing serves; after the last, everything does. Three crash windows follow,
 *       and none is papered over:
 *       <ul>
 *         <li><b>Before the commit point</b> - a crash after the blob landed, or after some sidecars landed, leaves an
 *             unreferenced blob and inert sidecars. Nothing serves, nothing is observed, and a replay of the same
 *             bytes converges onto exactly the same state (clause 2). Unreferenced blobs are reclaimed by garbage
 *             collection.</li>
 *         <li><b>Between two declared visibility steps</b> - a multi-pointer layout is <em>not</em> atomic across its
 *             pointers. A crash there leaves the artifact servable under the pointers already linked and absent under
 *             the rest, with no observer notified; the caller is told the publish failed, and a replay completes it.
 *             A layout that cannot tolerate partial visibility declares one pointer, not several.</li>
 *         <li><b>Between the commit point and {@link #published}</b> - the artifact serves but the after-commit
 *             observers never saw it. This window is inherent to a callback fired after a durable mutation and is not
 *             closed by anything in this class.</li>
 *       </ul>
 *       The delivery class of {@link PublicationObserver#onPublished} is therefore <b>best-effort, repaired by the
 *       full walk</b> - not at-least-once. A derived surface that must be complete rebuilds from the durable store
 *       through the walk SPI's {@code WalkConsumer}, exactly as {@link PublicationObserver}'s two-route contract
 *       requires; the live event is the steady state, the walk is the crash and gap heal-all.</li>
 * </ol>
 */
public final class Publication {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(Publication.class);

    /** The one discovered publication hook class, loaded once at class load like {@code MavenFormat.MODULE_VIEWS}:
     *  every {@link PublicationObserver} on the module path, the interceptors among them included - a
     *  {@link PublishInterceptor} IS a {@code PublicationObserver}, so a single {@code uses PublicationObserver}
     *  clause discovers both. Empty in the core (no provider on the module path). */
    private static final List<PublicationObserver> OBSERVERS = ServiceLoader.load(PublicationObserver.class)
            .stream().map(ServiceLoader.Provider::get).toList();

    /** The verdict-bearing subset, split from the one discovered list by {@code instanceof PublishInterceptor}: the
     *  observers that also screen. So {@link #screen} drives exactly the interceptors while {@link #published} and
     *  {@link #unpublish} still notify every discovered observer - the interceptors ride the after-commit call too
     *  (their {@link PublishInterceptor#onPublished} defaults to a no-op, so this never double-counts a screen). */
    private static final List<PublishInterceptor> DISCOVERED = OBSERVERS.stream()
            .filter(observer -> observer instanceof PublishInterceptor)
            .map(observer -> (PublishInterceptor) observer)
            .toList();

    /** The reserved review-subtree request-path root ({@code /quarantine}) - the pointer face of the hold convention
     *  whose enumeration face is {@link ServableNames#QUARANTINE}. A hold writer links a review pointer at
     *  {@code /quarantine/<servedPath>}; this is that {@code /quarantine} prefix as a request path. */
    private static final String QUARANTINE_PATH = "/quarantine";

    /** Whether a request path is a {@code /quarantine} review pointer - the pointer face of the withhold-change feed -
     *  matched on the exact {@code /}-subtree boundary the enumeration seam ({@link ServableNames#reviewSubtree}) uses:
     *  the path is a hold pointer iff it equals {@value #QUARANTINE_PATH} or lies under {@value #QUARANTINE_PATH}{@code /}.
     *  A bare {@code startsWith("/quarantine")} would misclassify a sibling like {@code /quarantined/foo} or
     *  {@code /quarantine-cache/x} as a hold and fire a spurious withhold-feed signal with a mangled served path (the
     *  substring below would eat the leading slash and one more char), so the two seams would disagree on the boundary. */
    private static boolean isQuarantinePath(String requestPath) {
        return requestPath != null
                && (requestPath.equals(QUARANTINE_PATH) || requestPath.startsWith(QUARANTINE_PATH + "/"));
    }

    private final ArtifactStore store;
    private final List<PublishInterceptor> interceptors;
    private final List<PublicationObserver> observers;

    public Publication(ArtifactStore store) {
        this(store, DISCOVERED, OBSERVERS);
    }

    /** A publication whose upload post-processing runs an explicit screen list rather than the
     *  {@code ServiceLoader}-discovered one - the seam an embedder uses to inject screens that are not on the module
     *  path. Either way the chain runs sorted by {@link PublishInterceptor#order()}, ties keeping their given order. */
    public Publication(ArtifactStore store, List<PublishInterceptor> interceptors) {
        this(store, interceptors, OBSERVERS);
    }

    /** The fully explicit seam: screens and after-commit observers both injected rather than discovered. */
    public Publication(ArtifactStore store, List<PublishInterceptor> interceptors, List<PublicationObserver> observers) {
        this.store = store;
        this.interceptors = interceptors.stream().sorted(Comparator.comparingInt(PublishInterceptor::order)).toList();
        this.observers = observers;
    }

    /** The blob key ({@code blobs/<hash>}) a path resolves to when it is published and the blob is present - what a
     *  streaming {@code GET} sets its {@code Content-Length} from (through {@link ArtifactStore#size}) and then copies
     *  to the response (through {@link ArtifactStore#read}), instead of buffering the blob to learn its length. Empty
     *  when nothing is published there, the blob is gone, a screen {@link PublishInterceptor#withheld withholds} the
     *  path, or a {@link Withheld withheld/&lt;hash&gt;} marker retracts the bytes the path names - the quarantine read
     *  side, so a verdict that changes after the fact retracts a linked artifact from every serving surface without
     *  touching its pointer, and a content-addressed hold retracts it under every alias it is served by rather than
     *  only the ones the hold writer enumerated. */
    public Optional<String> located(String requestPath) throws IOException {
        // Delegate the servable-vs-not discrimination to the one enumeration seam so serve and enumeration can never
        // disagree (located empty iff state != SERVABLE); the seam composes this same publication's interceptor chain
        // and the withheld/<hash> marker convention. This is a behaviour-preserving refactor of the former inline
        // "chain withheld -> pointer resolve -> blobs/<hash> exists" (with the one gain the seam brings: a hostile,
        // unresolvable request path now fails closed to empty rather than throwing an InvalidPathException out of a
        // serve), so a linked, present, non-withheld path still resolves to blobs/<hash> exactly as before.
        if (new ServableNames(store, this).state(requestPath) != ServableNames.State.SERVABLE) {
            return Optional.empty();
        }
        return blob(requestPath).map(hash -> "blobs/" + hash);
    }

    /** Whether any interceptor in this publication's chain withholds the request path from serving - the chain probe
     *  {@link #located} runs, factored out so {@link ServableNames} composes the caller's interceptor list rather than
     *  discovering a second one. A verdict-bearing {@code withheld} that fails closed by throwing propagates exactly as
     *  it does through {@link #located}. */
    boolean withheld(String requestPath) throws IOException {
        for (PublishInterceptor interceptor : interceptors) {
            if (interceptor.withheld(requestPath, store)) {
                return true;
            }
        }
        return false;
    }

    /** Stream content once, content-addressed while it is read, and return its hash - so a large artifact goes from the
     *  network to storage without being buffered whole in memory. The primitive a staging deploy or a cross-publish
     *  uses to hold bytes before any view points at them. */
    public String storeBlob(InputStream content) throws IOException {
        return store.writeBlob(content);
    }

    /** Point a request path at an already-stored blob - the primitive promotion and cross-publishing use to publish a
     *  blob under another view without re-uploading it. The pointer is the product's most load-bearing small object,
     *  so a compare-and-set conflict re-reads the token and retries (the bounded idiom every other load-bearing
     *  pointer write uses) rather than silently dropping the losing write: a concurrent republish of the same path
     *  resolves to last-writer-wins - the same outcome the two writes would have had a moment apart - and a caller
     *  whose link cannot land is told so instead of believing it published. Once the pointer lands, any garbage
     *  collector's {@code gc/condemned/<hash>} marker on the blob is cleared - identical content dedupes to one
     *  blob, so a "new" publish may link a blob a collector already judged unreferenced, and clearing the marker on
     *  the write path (every link site: publish, quarantine, promotion, cross-publish) un-condemns it before the
     *  collecting sweep's final marker re-read. One existence probe per link, a no-op wherever collection never
     *  condemned the blob; the marker key is the store-layout convention the {@code gc} SPI documents. */
    public void link(String requestPath, String hash) throws IOException {
        // The one cheap check the publish hot path pays: a non-quarantine link is exactly the write below and nothing
        // more. A /quarantine<path> link is the pointer face of the withhold-change feed - a hold writer (the gate's
        // QUARANTINE branch, a retroactive KEV/license/reachability sweep) links a review pointer here - so a FRESH one
        // (prior read absent, not an overwrite) fires onWithheld after the write. Transition-only: the sweeps guard on
        // presence before re-linking, so their idempotent converge passes overwrite rather than freshly link and raise
        // no event.
        boolean quarantine = isQuarantinePath(requestPath);
        for (int attempt = 0; attempt < 3; attempt++) {
            Optional<ArtifactStore.Versioned> prior = store.readVersioned("publish" + requestPath);
            Object token = prior.map(ArtifactStore.Versioned::token).orElse(null);
            if (store.writeVersioned("publish" + requestPath, hash.getBytes(StandardCharsets.UTF_8), token)) {
                String condemned = "gc/condemned/" + hash;
                if (store.exists(condemned)) {
                    store.delete(condemned);
                }
                if (quarantine && prior.isEmpty()) {
                    notifyWithheld(ArtifactDescriptor.at(null, requestPath.substring(QUARANTINE_PATH.length()))
                            .withBlob(hash, -1L));
                }
                return;
            }
        }
        throw new IOException("could not link publish" + requestPath + " after repeated version conflicts");
    }

    /** The content hash a path currently points at, or empty if nothing is published there. */
    public Optional<String> blob(String requestPath) throws IOException {
        return pointer("publish" + requestPath);
    }

    /** The hash body of an arbitrary pointer object, or empty when nothing is stored at the key - the keyed face of
     *  {@link #blob}, so the republish policy can probe a format's own serving-pointer namespace (an {@code npm/},
     *  {@code nuget/}, {@code pypi/} key) rather than only the {@code publish/} one this primitive owns. */
    private Optional<String> pointer(String key) throws IOException {
        return store.readVersioned(key)
                .map(versioned -> new String(versioned.content(), StandardCharsets.UTF_8).trim());
    }

    /**
     * Which live {@code /quarantine} review pointer OUTSIDE {@code excludedPaths} currently holds {@code hash} - the
     * cross-alias proof an automated content-addressed marker clear must run before lifting the marker. The withhold
     * marker is content-addressed (one {@code withheld/<hash>} marker withholds the bytes wherever served) and the
     * blobs-namespace serve gate keys withheld on the MARKER, not the per-path {@code /quarantine} pointer - so clearing
     * {@code hash} while a byte-identical sibling coordinate is still held would un-withhold that sibling. This is the
     * free-store owner of the {@code publish/quarantine} pointer convention (the {@link #isQuarantinePath} face)
     * answering the same question the downstream release paths' cross-alias guard asks before a release-time clear, so a
     * free-only clear (the OCI manifest ACCEPT-clear) can prove no-other-alias without reaching into downstream gate code.
     *
     * <p><strong>Three answers, because the scan has three outcomes.</strong> {@link Known.Present} carries the served
     * path whose review pointer still holds the hash - a live holder, named, so a refusal to clear can say which
     * coordinate kept it. {@link Known.Absent} means the review subtree was enumerated <em>whole</em> and no other
     * alias holds it, which is the only answer that entitles a caller to lift the marker; it is the exact
     * {@link Known.Determined} {@link Withheld#clear} demands. {@link Known.Unknown} means at least one node of the
     * subtree could not be read - an encoding-hostile pointer key a backend cannot resolve, an unreadable container -
     * so the scan saw a prefix of the queue and "no other alias" is precisely the claim it cannot make. That state
     * used to be a {@code false}, indistinguishable from a clean negative, and a {@code false} here lifts a hold.
     *
     * <p>The scan is a bounded depth-first walk of the {@code publish/quarantine} pointer subtree only - the review
     * queue, bounded by the number of currently held paths, never the whole repository - with an early exit on the first
     * live alias found. {@code excludedPaths} is in served-path form (the {@code /quarantine} prefix stripped), the
     * caller's own served path(s), so a pointer that maps back to the caller's own coordinate does not keep the marker
     * on its own account. A genuine store {@link IOException} propagates, so the caller does NOT clear - fail-closed,
     * since leaving a marker is always safe and clearing wrongly is the disclosure.
     */
    public Known<String> quarantineAlias(String hash, Set<String> excludedPaths) throws IOException {
        String root = "publish" + QUARANTINE_PATH;
        List<String> unreadable = new ArrayList<>();
        String holder = aliasHeld(root, root, hash, excludedPaths, unreadable);
        if (holder != null) {
            return Known.known(holder);   // a live holder, found: determinate whatever else the scan could not read
        }
        return unreadable.isEmpty()
                ? Known.absent()
                : Known.unknown(Known.Cause.FAILED, "the " + root + " review queue did not enumerate whole ("
                        + unreadable.size() + " unreadable node(s), first " + unreadable.getFirst() + "); refusing to "
                        + "answer, because 'no other alias holds these bytes' is what lifts a content-addressed hold");
    }

    /** Depth-first search of the {@code publish/quarantine} pointer subtree for a live review pointer, outside
     *  {@code excludedPaths}, whose body is {@code hash} - the served path of the first one found, or {@code null}.
     *  {@code prefix} is the current key, its immediate children are
     *  enumerated with {@link ArtifactStore#list}, and a leaf is a key with no children. Each non-root node is probed as
     *  a pointer (a directory node reads empty and is skipped), so a pointer that also has descendants is not missed, and
     *  the walk short-circuits on the first alias. A body is compared through {@link ServableNames#hash(byte[])}, so a
     *  pointer linked in the qualified {@code sha256:<hex>} dialect still counts as the alias it is rather than
     *  silently clearing a live hold. A node that cannot be read is recorded in {@code unreadable} rather than skipped:
     *  it is contained (one bad entry never throws out of the guard) but never forgotten, because a skipped node is
     *  exactly where the alias that should have kept the marker would have been. Mirror of the downstream
     *  {@code HoldLifecycle.aliasHeld}. */
    private String aliasHeld(String root, String prefix, String hash, Set<String> excludedPaths,
                             List<String> unreadable) throws IOException {
        if (!prefix.equals(root)) {
            String servedPath = prefix.substring(root.length());   // the /quarantine prefix stripped == the served path
            if (!excludedPaths.contains(servedPath)) {
                try {
                    // The body is read through the one seam that owns a stored pointer's dialect rather than compared
                    // raw: a review pointer's body is the bare content hash every hold writer links today, but the
                    // comparison target is the bare hash the withheld/<hash> marker is keyed by, so a body ever linked
                    // in the algorithm-qualified sha256:<hex> dialect would compare unequal, the scan would report NO
                    // other alias and the clear would lift a marker a sibling coordinate still holds - a fail-OPEN
                    // disclosure, the exact class ServableNames.hash was introduced for. Normalising can only ever
                    // find MORE aliases, so it only ever narrows the clear, which is this guard's declared direction.
                    Optional<String> pointer = store.readVersioned(prefix)
                            .map(versioned -> ServableNames.hash(versioned.content()));
                    if (pointer.isPresent() && pointer.get().equals(hash)) {
                        return servedPath;   // a byte-identical sibling coordinate still holds the hash
                    }
                } catch (RuntimeException hostile) {
                    // A garbled / encoding-hostile pointer key (an InvalidPathException out of resolve), or a store
                    // that could not be read. Contained - one bad entry never throws out of the guard - but recorded,
                    // because this entry may be the very alias that should have kept the marker, and the answer must
                    // therefore be "unknown" rather than a negative the caller would clear on.
                    unreadable.add(prefix);
                }
            }
        }
        List<String> children;
        try {
            children = store.list(prefix);
        } catch (RuntimeException unreadableContainer) {
            unreadable.add(prefix);   // the subtree below here was not enumerated; the scan is a prefix, not the whole
            return null;
        }
        for (String child : children) {
            String holder = aliasHeld(root, prefix + "/" + child, hash, excludedPaths, unreadable);
            if (holder != null) {
                return holder;
            }
        }
        return null;
    }

    /** Remove a single published pointer; the blob it referenced is left for a later garbage collection, since
     *  another pointer may still reference it. Every discovered {@link PublicationObserver} is notified of the
     *  removal ({@code onDeleted}, once per removed pointer) with what this site knows - the request path and the
     *  blob hash the pointer named, read before the delete; no coordinate, since this primitive knows no layouts -
     *  and a failing observer is logged and contained exactly as on a publish, never blocking the removal. */
    public void unpublish(String requestPath) throws IOException {
        Optional<ArtifactStore.Versioned> pointer = store.readVersioned("publish" + requestPath);
        if (pointer.isEmpty()) {
            return;
        }
        store.delete("publish" + requestPath);
        String named = new String(pointer.get().content(), StandardCharsets.UTF_8).trim();
        ArtifactDescriptor removed = ArtifactDescriptor.at(null, requestPath);
        notifyDeleted(hash(named) ? removed.withBlob(named, -1L) : removed);
        // The pointer face of the withhold-change feed's transition-OFF leg: removing a /quarantine<servedPath> review
        // pointer clears that hold, so fire onWithholdCleared with the served path (the /quarantine prefix stripped) and
        // the pointer's hash - IN ADDITION TO the onDeleted above, which for a quarantine path carries no coordinate the
        // coordinate-keyed observers act on. A non-quarantine unpublish pays only one startsWith.
        if (isQuarantinePath(requestPath)) {
            ArtifactDescriptor cleared = ArtifactDescriptor.at(null, requestPath.substring(QUARANTINE_PATH.length()));
            notifyWithholdCleared(hash(named) ? cleared.withBlob(named, -1L) : cleared);
        }
    }

    /** Remove the pointer at {@code described.path()} exactly like {@link #unpublish(String)}, but notify the
     *  observers with the caller's layout-enriched descriptor - ecosystem, coordinate and version filled in where
     *  this neutral primitive cannot - completing its blob identity from the pointer when the caller left it
     *  unset. The seam a layout-aware eviction uses so a removal event carries what the eviction already knows. */
    public void unpublish(ArtifactDescriptor described) throws IOException {
        Optional<ArtifactStore.Versioned> pointer = store.readVersioned("publish" + described.path());
        if (pointer.isEmpty()) {
            return;
        }
        store.delete("publish" + described.path());
        String named = new String(pointer.get().content(), StandardCharsets.UTF_8).trim();
        notifyDeleted(described.hash() == null && hash(named) ? described.withBlob(named, described.size()) : described);
        // The withhold-change feed's transition-OFF pointer leg, exactly as the string variant: a removed
        // /quarantine<servedPath> pointer fires onWithholdCleared with the stripped served path and the pointer's hash.
        if (isQuarantinePath(described.path())) {
            ArtifactDescriptor cleared =
                    ArtifactDescriptor.at(null, described.path().substring(QUARANTINE_PATH.length()));
            notifyWithholdCleared(hash(named) ? cleared.withBlob(named, -1L) : cleared);
        }
    }

    /** Notify every observer of a serving-pointer removal this primitive did not perform - the seam a layout-aware
     *  eviction calls once per pointer it deletes in a format's <em>own</em> namespace (a blobs-namespace key
     *  outside {@code publish/}), so those removals are observed exactly like an {@link #unpublish}. Failures are
     *  logged and contained like every observer notification; nothing is read or deleted here - the caller already
     *  removed the pointer and describes it. */
    public void deleted(ArtifactDescriptor removed) {
        notifyDeleted(removed);
    }

    /** Notify every observer of an accepted artifact this primitive did not lay out - the seam an ingress edge calls
     *  once per artifact it has screened to {@code ACCEPT} and laid out into a format's namespace (through
     *  {@link #screen} then the format's own {@link #storeBlob}/{@link #link} or {@code Blobs} writes), so an
     *  edge-screened publish is observed exactly once the edge has linked the accepted artifact. The mirror of
     *  {@link #deleted}: the caller already stored and linked the artifact and describes it, so nothing is read or
     *  written here. Failures are logged and contained like every observer notification, never failing the caller's
     *  already-completed publish. This is the sole seam that carries {@link PublicationObserver#onPublished}: with the
     *  screen+layout choreography living at the ingress edges, a blobs-namespace deploy fires its observer through here. */
    public void published(ArtifactDescriptor published) {
        notifyPublished(published);
    }

    /** Whether a pointer's content is the lower-case SHA-256 hex a {@link #link} writes - the only shape carried
     *  into a removal descriptor's blob identity, so a corrupt pointer never masquerades as a hash. */
    private static boolean hash(String value) {
        if (value.length() != 64) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if ((character < '0' || character > '9') && (character < 'a' || character > 'f')) {
                return false;
            }
        }
        return true;
    }

    /** One notification, so the six after-commit faces reach their observers through one containment rather than six
     *  copies of it. Declares {@code Exception} because the SPI's methods declare {@code IOException} and containing
     *  it is the point. */
    @FunctionalInterface
    private interface Notification {
        void to(PublicationObserver observer) throws Exception;
    }

    /**
     * <b>The one containment behind every after-commit observer notify (D-198).</b> Six faces - published, deleted,
     * and the two withhold transitions in their instance and static forms - used to carry six copies of this loop,
     * and a copy is a place where one of them quietly stops matching the others; this is the same
     * one-choke-point move {@code EventSink.emit} makes for its own fan-out.
     *
     * <p>Three properties, and the middle one is what D-195's census found missing.
     * <ol>
     *   <li><b>An ordinary failure is contained and named, and the next observer still runs.</b> These fire after the
     *       publish has committed, so a notification that could not be filed must never retract an artifact that is
     *       already linked and serving - but it must not vanish either, so the observer's class and the subject are
     *       logged at {@code WARNING} (PRINCIPLES &sect;9: a fail-soft still emits a diagnostic).</li>
     *   <li><b>An {@link Error} is attributed and then propagates.</b> It is the runtime or the module graph giving
     *       way rather than a notification failing to file, and filing it as one observer's contained failure would
     *       leave a deployment serving artifacts on a broken runtime with a WARNING to show for it. It used to
     *       propagate with <em>no</em> line at all, which is D-094's ruling half-applied: an operator learned that
     *       the publish 500ed and nothing about which of N installed observers had given way. The propagation
     *       direction is deliberately unchanged - it is arguable, because the publish HAS committed and the client
     *       is told it failed, and that argument is a separate decision from this diagnosis.</li>
     *   <li><b>The identity is the observer's class, read before the call.</b> This SPI carries no {@code name()},
     *       so there is nothing to re-enter - but reading it up front is what keeps it that way, and it is the same
     *       rule D-094 and D-162 landed one host each.</li>
     * </ol>
     */
    private static void notify(List<PublicationObserver> observers, String subject, Notification notification) {
        for (PublicationObserver observer : observers) {
            String identity = observer.getClass().getName();
            try {
                notification.to(observer);
            } catch (Error broken) {
                try {
                    LOGGER.error("publication observer " + identity + " raised an Error for " + subject
                            + " - not contained: an Error is the runtime or the module graph giving way, not a "
                            + "notification failing to file", broken);
                } catch (Throwable diagnostic) {
                    // Rendering the diagnostic can itself fail on the runtime that just gave way. The attribution is
                    // worth having but never worth REPLACING the Error it attributes.
                    broken.addSuppressed(diagnostic);
                }
                throw broken;
            } catch (Exception exception) {
                LOGGER.warn("publication observer " + identity + " failed for " + subject, exception);
            }
        }
    }

    private void notifyDeleted(ArtifactDescriptor removed) {
        notify(observers, "removal of " + removed.path(), observer -> observer.onDeleted(removed, store));
    }

    private void notifyPublished(ArtifactDescriptor published) {
        notify(observers, published.path(), observer -> observer.onPublished(published, store));
    }

    /** The withhold-change feed's transition-ON notify - the pointer face {@link #link} fires over this publication's
     *  observer list, contained exactly like {@link #notifyDeleted}. */
    private void notifyWithheld(ArtifactDescriptor subject) {
        notify(observers, "withhold of " + subject.path(), observer -> observer.onWithheld(subject, store));
    }

    /** The transition-OFF mirror {@link #unpublish} fires when a {@code /quarantine} review pointer is removed. */
    private void notifyWithholdCleared(ArtifactDescriptor subject) {
        notify(observers, "withhold-clear of " + subject.path(),
                observer -> observer.onWithholdCleared(subject, store));
    }

    /** The withhold-change feed's transition-ON notify over the ServiceLoader-discovered {@link #OBSERVERS} - the
     *  package-private static seam the same-package {@link Withheld#mark} (a static primitive with no {@code Publication}
     *  instance) fires the marker face through, reusing the one discovered observer list rather than a second discovery.
     *  Failures are logged and contained exactly as on the instance notify paths, so a hold's marker write never fails
     *  open because a downstream consumer is down. */
    static void notifyWithheld(ArtifactDescriptor subject, ArtifactStore store) {
        notify(OBSERVERS, "withhold of hash " + subject.hash(), observer -> observer.onWithheld(subject, store));
    }

    /** The transition-OFF mirror the same-package {@link Withheld#clear} fires through - the marker-cleared face. */
    static void notifyWithholdCleared(ArtifactDescriptor subject, ArtifactStore store) {
        notify(OBSERVERS, "withhold-clear of hash " + subject.hash(),
                observer -> observer.onWithholdCleared(subject, store));
    }

    /** The outcome of a screened upload: the disposition the interceptor chain reached and the SHA-256 the blob was
     *  stored under - present whatever the disposition, since the blob is written content-addressed before the gate. */
    public record Published(PublishInterceptor.Disposition disposition, String hash) {
    }

    /**
     * Store an upload content-addressed and run the {@link PublishInterceptor} chain over its neutral
     * {@link ArtifactDescriptor} <em>without linking any serving pointer of its own</em>: an accepted upload links no
     * pointer - the caller owns the accepted write, laying the content out in its own layout from the returned hash -
     * while a quarantined one is still diverted to the {@code /quarantine} view for review and a rejected one leaves
     * only the unreferenced blob for garbage collection. The blob is inert until a pointer references it, so the chain
     * gates before any link - nothing is buffered and there is no published-then-retracted window. With the default
     * empty chain this is exactly a {@link #storeBlob} that always {@code ACCEPT}s.
     *
     * <p>This is the single sanctioned screen seam, and {@link #commit} is its only caller: an ingress edge does not
     * screen by hand, it commits, and the operation screens once on its behalf, gates the republish, drives the
     * accepted layout and fires {@link #published} after the declared visibility has landed. A hosted route that
     * called this directly would own the ordering that {@code commit} exists to own, so the core's ingress census
     * asserts there is no such caller. The interceptors' {@link PublishInterceptor#committed} notifications fire here,
     * before any layout; the after-commit observers do not - they ride the {@link #published} seam
     * {@code commit} fires once the accepted artifact is visible.
     */
    public Published screen(ArtifactDescriptor artifact, InputStream content) throws IOException {
        return route(artifact, content);
    }

    private Published route(ArtifactDescriptor artifact, InputStream content) throws IOException {
        String hash = storeBlob(content);
        ArtifactDescriptor stored = artifact.withBlob(hash, store.size("blobs/" + hash));
        PublishInterceptor.Content access = contentOf(hash);
        PublishInterceptor.Disposition disposition = PublishInterceptor.Disposition.ACCEPT;
        for (PublishInterceptor interceptor : interceptors) {
            PublishInterceptor.Disposition verdict = interceptor.assess(stored, access);
            if (verdict.compareTo(disposition) > 0) {
                disposition = verdict;
            }
        }
        switch (disposition) {
            // ACCEPT links no pointer of its own: the screening edge owns the accepted write and lays the stored blob
            // out in its own format namespace from the returned hash, then fires published() for the observers.
            case ACCEPT -> {
            }
            // QUARANTINE still diverts to the quarantine view (stored but not served) for review.
            case QUARANTINE -> link("/quarantine" + artifact.path(), hash);
            // REJECT links nothing; the orphaned blob is left for garbage collection.
            case REJECT -> {
            }
        }
        for (PublishInterceptor interceptor : interceptors) {
            interceptor.committed(stored, disposition, store);
        }
        return new Published(disposition, hash);
    }

    // --- the pointer-last accepted-layout commit --------------------------------------------------------------------

    /**
     * The republish conflict / idempotency policy an ingress edge hands {@link #commit} as <em>data</em>, so a format
     * never re-implements "is this coordinate already taken" beside the layout it actually owns. The policy is
     * evaluated once, <em>before</em> the accepted layout writes anything, so a refused republish fails loudly with
     * nothing half-written (&sect;9).
     *
     * <p>{@code pointer} is the store key whose body is the currently published content hash - a format's own serving
     * pointer, since the coordinate a republish collides on is the format's, not this primitive's. A {@code null}
     * pointer means the publication's own {@code publish/<request-path>} object, derived from the descriptor.
     *
     * @param mode    what a collision means
     * @param pointer the probed serving-pointer key, or {@code null} for {@code publish/<descriptor path>}
     */
    public record Republish(Mode mode, String pointer) {

        /** What an already-published coordinate means for the incoming upload. */
        public enum Mode {
            /** Last-writer-wins: no probe at all, the pointer simply moves. The free formats' behaviour today. */
            OVERWRITE,
            /** A re-publish of <em>identical</em> bytes converges (the layout re-runs and lands the same state, so a
             *  half-written first attempt is repaired); different bytes at a taken coordinate raise
             *  {@link RepublishConflict}. The retry-safe policy for a registry that owns immutable versions. */
            IDEMPOTENT,
            /** Any already-published coordinate raises {@link RepublishConflict}, identical bytes included - the
             *  strict "cannot publish over a previously published version" registries advertise. */
            REFUSED
        }

        public Republish {
            Objects.requireNonNull(mode, "republish mode");
            if (mode == Mode.OVERWRITE && pointer != null) {
                throw new IllegalArgumentException("OVERWRITE probes no pointer, so naming " + pointer
                        + " would suggest a check that never runs");
            }
        }

        /** Last-writer-wins, the policy every free format publishes under today: no probe, no extra read. */
        public static Republish overwrite() {
            return new Republish(Mode.OVERWRITE, null);
        }

        /** {@link Mode#IDEMPOTENT} against the publication's own {@code publish/<request-path>} pointer. */
        public static Republish idempotent() {
            return new Republish(Mode.IDEMPOTENT, null);
        }

        /** {@link Mode#IDEMPOTENT} against a format's own serving-pointer key. */
        public static Republish idempotent(String pointer) {
            return new Republish(Mode.IDEMPOTENT, Objects.requireNonNull(pointer, "pointer"));
        }

        /** {@link Mode#REFUSED} against the publication's own {@code publish/<request-path>} pointer. */
        public static Republish refused() {
            return new Republish(Mode.REFUSED, null);
        }

        /** {@link Mode#REFUSED} against a format's own serving-pointer key. */
        public static Republish refused(String pointer) {
            return new Republish(Mode.REFUSED, Objects.requireNonNull(pointer, "pointer"));
        }

        /** The key this policy probes for {@code artifact} - the explicit one, or the publication's own pointer. */
        String key(ArtifactDescriptor artifact) {
            return pointer != null ? pointer : "publish" + artifact.path();
        }
    }

    /**
     * Raised by {@link #commit} when the {@link Republish} policy refuses an upload whose coordinate is already
     * published. It is thrown <em>before</em> the accepted layout runs, so nothing was laid out and no serving pointer
     * moved; the stored blob is the usual unreferenced content-addressed object a garbage collection reclaims. An
     * ingress edge maps it to its format's documented status (a {@code 409}, a {@code 403}, a {@code 400}); it carries
     * the probed key and both hashes so the response can say which coordinate collided with what.
     */
    public static final class RepublishConflict extends IOException {

        private final String pointer;
        private final String published;
        private final String offered;

        RepublishConflict(String pointer, String published, String offered) {
            super(pointer + " is already published as " + published
                    + (published.equals(offered) ? " and this policy refuses a re-publish of identical bytes"
                            : " and cannot be re-published as " + offered));
            this.pointer = pointer;
            this.published = published;
            this.offered = offered;
        }

        /** The serving-pointer key that was already taken. */
        public String pointer() {
            return pointer;
        }

        /** The content hash currently published at {@link #pointer()}. */
        public String published() {
            return published;
        }

        /** The content hash the refused upload was stored under. */
        public String offered() {
            return offered;
        }
    }

    /**
     * The accepted upload handed to an {@link AcceptedLayout}: the blob is already in the content-addressed store and
     * has already passed the one screen, and nothing serves it yet. A layout reads the bytes back through
     * {@link #open} (a restream, never a buffer) and writes its parse results through {@link #sidecar}; the serving
     * pointer is <em>not</em> its to write - it declares it in the returned {@link Visibility}, and {@link #commit}
     * links it once the layout has returned. That split is what makes "sidecars before the pointer" the only order a
     * declaring layout can express.
     */
    public interface Acceptance {

        /** The accepted artifact with its content-addressed identity (hash and stored size) stamped on. */
        ArtifactDescriptor artifact();

        /** The SHA-256 the accepted body was stored under ({@code blobs/<hash>}). */
        default String hash() {
            return artifact().hash();
        }

        /** The accepted body's stored byte length. */
        default long size() {
            return artifact().size();
        }

        /** The scoped store the publication runs against, for the reads and format-native writes a layout needs. */
        ArtifactStore store();

        /** Reopen the accepted blob. A fresh stream each call, so a layout that parses and then re-reads never holds
         *  the artifact in memory (&sect;1); the caller closes it. */
        InputStream open() throws IOException;

        /** Write one parse result / derived document beside the accepted artifact, <em>before</em> any serving pointer
         *  exists. A sidecar is not a serving surface, so the key may not live in the {@code publish/} pointer
         *  namespace - a would-be pointer written here would defeat the ordering this operation exists to guarantee,
         *  and is refused rather than silently accepted. */
        void sidecar(String key, byte[] body) throws IOException;

        /** Streaming {@link #sidecar(String, byte[])} for a derived document large enough to be worth not buffering. */
        void sidecar(String key, InputStream body) throws IOException;
    }

    /** One serving-visibility write {@link #commit} performs after the accepted layout returned - the "pointer" half of
     *  pointer-last. It receives the accepted content hash and the scoped store, so a format links its own native
     *  pointer (an OCI tag object, an ecosystem's version file) without this primitive knowing that layout. */
    @FunctionalInterface
    public interface Serving {
        void link(String hash, ArtifactStore store) throws IOException;
    }

    /** The format-specific half of a hosted publish: write the parse results and sidecars for an accepted blob, then
     *  <em>declare</em> what makes it visible. Never a second gate - the body reaching a layout has already passed the
     *  one screen {@link #commit} ran, and a layout that wants to refuse the write answers {@link Visibility#declined}
     *  rather than screening again. */
    @FunctionalInterface
    public interface AcceptedLayout {
        Visibility lay(Acceptance accepted) throws IOException;
    }

    /**
     * What an {@link AcceptedLayout} declares makes its accepted artifact visible - the last thing a hosted publish
     * writes, and the point after which {@link PublicationObserver#onPublished} may fire. Four shapes:
     * <ul>
     *   <li>{@link #at} - one or more {@code publish/<request-path>} pointers this primitive links from the accepted
     *       hash, in declaration order;</li>
     *   <li>{@link #through} - format-native pointer writes ({@code Serving} steps) run in declaration order, for a
     *       layout whose serving surface is not a {@code publish/} pointer (an OCI tag object, an ecosystem key
     *       namespace);</li>
     *   <li>{@link #laidOut} - the layout is an opaque format-SPI callback ({@code RepositoryFormat.handle},
     *       {@code RepositoryImporter.importArtifact}) that linked its own pointer inside the callback. The ordering
     *       cannot be enforced structurally for these, so the ingress census asserts it behaviourally instead;</li>
     *   <li>{@link #declined} - the layout wrote nothing servable (an edge refusal). No pointer, and no observer.</li>
     * </ul>
     * A layout may additionally {@linkplain #describing refine} the neutral descriptor the observers are notified with,
     * for the formats whose real coordinate is only known once the stored bytes have been parsed.
     */
    public static final class Visibility {

        /** One declared visibility write: exactly one of the two components is set. */
        private record Step(String requestPath, Serving serving) {
        }

        private static final Visibility DECLINED = new Visibility(false, List.of(), null);
        private static final Visibility LAID_OUT = new Visibility(true, List.of(), null);

        private final boolean commits;
        private final List<Step> steps;
        private final ArtifactDescriptor described;

        private Visibility(boolean commits, List<Step> steps, ArtifactDescriptor described) {
            this.commits = commits;
            this.steps = steps;
            this.described = described;
        }

        /** Nothing servable was written, so no pointer is linked and no observer is notified - the shape an edge
         *  refusal takes once the body has already been screened and stored. */
        public static Visibility declined() {
            return DECLINED;
        }

        /** The layout already linked its own serving pointer inside an opaque format-SPI callback. */
        public static Visibility laidOut() {
            return LAID_OUT;
        }

        /** Link {@code publish/<requestPath>} at the accepted hash once the layout has returned. */
        public static Visibility at(String requestPath) {
            return new Visibility(true, List.of(new Step(requireRequestPath(requestPath), null)), null);
        }

        /** Run the declared format-native pointer writes, in order, once the layout has returned. */
        public static Visibility through(Serving serving) {
            return new Visibility(true, List.of(new Step(null, Objects.requireNonNull(serving, "serving"))), null);
        }

        /** A further {@code publish/} pointer, linked after the steps already declared. */
        public Visibility andAt(String requestPath) {
            return new Visibility(true, appended(new Step(requireRequestPath(requestPath), null)), described);
        }

        /** A further format-native pointer write, run after the steps already declared. */
        public Visibility andThrough(Serving serving) {
            return new Visibility(true,
                    appended(new Step(null, Objects.requireNonNull(serving, "serving"))), described);
        }

        /**
         * Notify the after-commit observers with {@code refined} rather than the descriptor the ingress edge screened
         * against. The seam for a format whose real coordinate is only readable <em>after</em> the bytes are stored
         * and parsed (a package archive whose manifest carries the id and version, an envelope endpoint whose request
         * path carries no version at all), so an observer keyed on the neutral ecosystem/coordinate/version triple is
         * not handed a coordinate-less envelope path. The content-addressed identity is stamped on by {@link #commit},
         * so a refinement never has to carry the hash.
         */
        public Visibility describing(ArtifactDescriptor refined) {
            if (!commits) {
                throw new IllegalStateException("a declined visibility notifies no observer, so it describes nothing");
            }
            return new Visibility(true, steps, Objects.requireNonNull(refined, "refined"));
        }

        private List<Step> appended(Step step) {
            if (!commits) {
                throw new IllegalStateException("a declined visibility commits nothing, so it takes no further step");
            }
            List<Step> extended = new ArrayList<>(steps);
            extended.add(step);
            return List.copyOf(extended);
        }

        private static String requireRequestPath(String requestPath) {
            Objects.requireNonNull(requestPath, "requestPath");
            if (requestPath.isEmpty() || requestPath.charAt(0) != '/') {
                throw new IllegalArgumentException("a publish pointer is declared by request path, so it starts with "
                        + "'/': " + requestPath);
            }
            return requestPath;
        }
    }

    /** The outcome of a {@link #commit}: the one screen's disposition, the artifact with its content-addressed
     *  identity (and any {@linkplain Visibility#describing refinement} the layout applied), and whether visibility
     *  actually committed - false for a non-{@code ACCEPT} disposition and for an accepted body whose layout
     *  {@linkplain Visibility#declined declined}. The after-commit observers were notified exactly when
     *  {@code visible} is true. */
    public record Commit(PublishInterceptor.Disposition disposition, ArtifactDescriptor artifact, boolean visible) {

        /** The SHA-256 the body was stored under, present whatever the disposition. */
        public String hash() {
            return artifact.hash();
        }
    }

    /**
     * The one hosted-publish choreography: screen once, gate the republish, lay the accepted blob out sidecars-first,
     * link the serving pointer last, and only then notify the after-commit observers. Every free ingress edge - the
     * deploy edge, the import walk, the OCI manifest choke point - runs a hosted publish through here, so there is one
     * publish commit point in the product rather than one per format.
     *
     * <p>In order:
     * <ol>
     *   <li>{@link #screen} stores the body content-addressed as it is read (hash-on-write, never buffered) and runs
     *       the discovered {@link PublishInterceptor} chain <b>exactly once</b>. A {@code QUARANTINE} is diverted to
     *       the review view and a {@code REJECT} leaves an unreferenced blob; neither lays out and neither observes.</li>
     *   <li>The {@link Republish} policy is evaluated against the already-known content hash, before any layout write:
     *       a refusal raises {@link RepublishConflict} with nothing half-written.</li>
     *   <li>The {@link AcceptedLayout} writes its parse results and sidecars and <em>declares</em> its
     *       {@link Visibility}. Nothing it writes here is servable.</li>
     *   <li>The declared visibility is linked, in declaration order. <b>This is the commit point</b>: before the first
     *       step the publication serves nothing, after the last it serves fully.</li>
     *   <li>{@link #published} notifies the after-commit observers exactly once - strictly after visibility committed,
     *       never before, and never at all when the layout declined or the chain did not accept.</li>
     * </ol>
     *
     * <p>A failure at any step before the commit point propagates and leaves nothing servable; a failure inside a
     * declared visibility step propagates too, so a partly-linked multi-pointer layout is reported rather than
     * silently reported as published. See the {@code Contract} block's durability clause for the exact crash windows
     * this ordering leaves and the delivery class it supports.
     */
    public Commit commit(ArtifactDescriptor artifact, InputStream body, Republish republish, AcceptedLayout layout)
            throws IOException {
        Objects.requireNonNull(republish, "republish");
        Objects.requireNonNull(layout, "layout");
        Published screened = screen(artifact, body);
        String hash = screened.hash();
        ArtifactDescriptor stored = artifact.withBlob(hash, store.size("blobs/" + hash));
        if (screened.disposition() != PublishInterceptor.Disposition.ACCEPT) {
            return new Commit(screened.disposition(), stored, false);
        }
        admit(republish, artifact, hash);
        Visibility visibility = layout.lay(new Accepted(stored));
        if (!visibility.commits) {
            return new Commit(PublishInterceptor.Disposition.ACCEPT, stored, false);
        }
        // The commit point. Every declared step is a compare-and-set write of a small pointer object; the artifact is
        // servable from the first one that lands and completely visible once the last has.
        for (Visibility.Step step : visibility.steps) {
            if (step.requestPath() != null) {
                link(step.requestPath(), hash);
            } else {
                step.serving().link(hash, store);
            }
        }
        ArtifactDescriptor committed = visibility.described == null
                ? stored
                : visibility.described.withBlob(hash, stored.size());
        published(committed);
        return new Commit(PublishInterceptor.Disposition.ACCEPT, committed, true);
    }

    /** Evaluate the republish policy before the layout writes anything: {@code OVERWRITE} does not even read, so the
     *  hot path pays nothing for a policy no free format uses; the probing modes read the named pointer once and raise
     *  {@link RepublishConflict} rather than letting a layout discover the collision mid-write. */
    private void admit(Republish republish, ArtifactDescriptor artifact, String hash) throws IOException {
        if (republish.mode() == Republish.Mode.OVERWRITE) {
            return;
        }
        String key = republish.key(artifact);
        Optional<String> current = pointer(key);
        if (current.isEmpty() || (republish.mode() == Republish.Mode.IDEMPOTENT && current.get().equals(hash))) {
            return;
        }
        throw new RepublishConflict(key, current.get(), hash);
    }

    /** The {@link Acceptance} handed to an {@link AcceptedLayout}: a restream view over the accepted blob plus the
     *  sidecar writer, whose {@code publish/} refusal is the structural half of pointer-last. */
    private final class Accepted implements Acceptance {

        private final ArtifactDescriptor artifact;

        private Accepted(ArtifactDescriptor artifact) {
            this.artifact = artifact;
        }

        @Override
        public ArtifactDescriptor artifact() {
            return artifact;
        }

        @Override
        public ArtifactStore store() {
            return store;
        }

        @Override
        public InputStream open() throws IOException {
            return store.open("blobs/" + artifact.hash());
        }

        @Override
        public void sidecar(String key, byte[] body) throws IOException {
            sidecar(key, new ByteArrayInputStream(body));
        }

        @Override
        public void sidecar(String key, InputStream body) throws IOException {
            store.write(requireSidecarKey(key), body);
        }
    }

    /** A sidecar is a parse result beside the artifact, never the thing that makes it servable, so the
     *  {@code publish/} pointer namespace is refused here: writing a serving pointer through the sidecar seam would
     *  put it <em>before</em> the rest of the layout, which is exactly the ordering this operation removes. A layout
     *  that means to publish declares it in its {@link Visibility} instead. */
    private static String requireSidecarKey(String key) {
        Objects.requireNonNull(key, "key");
        if (key.isEmpty()) {
            throw new IllegalArgumentException("a sidecar needs a key");
        }
        if (key.equals("publish") || key.startsWith("publish/")) {
            throw new IllegalArgumentException("publish/ is the serving-pointer namespace, so " + key
                    + " is a pointer, not a sidecar - declare it in the returned Visibility so it is linked last");
        }
        return key;
    }

    /**
     * A read view over one stored blob and the paths already published beside it, so a gate reads an artifact back
     * from storage rather than the store holding it in memory to show it. This is what each interceptor is handed
     * during a publish, over the blob just stored.
     *
     * <p>It is public because a publish is not the only moment a gate has to assess stored bytes against their
     * published siblings. A held artifact whose sibling declaration arrives <em>later</em> - a Maven jar, screened
     * before the POM the client sends one request afterwards - is re-assessed from exactly this view, over the held
     * blob's hash, and the sibling reads then resolve the document that has since landed. Building a second view for
     * that would mean restating {@link PublishInterceptor.Content#sibling(String, int)}'s bounded-read contract, and
     * two statements of one bound are two chances to disagree about it.
     *
     * @param hash the content hash of the blob to read through {@link PublishInterceptor.Content#open()}
     */
    public PublishInterceptor.Content contentOf(String hash) {
        return new PublishInterceptor.Content() {
            @Override
            public ArtifactStore store() {
                return store;
            }

            @Override
            public InputStream open() throws IOException {
                return store.open("blobs/" + hash);
            }

            @Override
            public Optional<byte[]> sibling(String path) throws IOException {
                // The whole-document read, expressed over the bounded one at this seam's own ceiling: a sibling read
                // whole is small metadata, and an oversized one is an anomaly a gate must be told about, not silently
                // fed a prefix it would mistake for the document. The bounded read never buffers past the ceiling, so
                // the over-limit blob never lands whole in memory before we notice.
                Optional<PublishInterceptor.Content.Bounded> bounded =
                        sibling(path, PublishInterceptor.Content.LARGEST_SIBLING);
                if (bounded.isEmpty()) {
                    return Optional.empty();
                }
                if (bounded.get().truncated()) {
                    throw new IOException("sibling " + path + " exceeds the "
                            + PublishInterceptor.Content.LARGEST_SIBLING + "-byte cap for a whole-sibling metadata "
                            + "read; a caller that can work from a prefix reads it through sibling(path, limit), "
                            + "which honours its own bound and reports the overflow instead of failing on it");
                }
                return Optional.of(bounded.get().content());
            }

            @Override
            public Optional<PublishInterceptor.Content.Bounded> sibling(String path, int limit) throws IOException {
                if (limit <= 0) {
                    throw new IllegalArgumentException("a bounded sibling read needs a positive limit, not " + limit);
                }
                Optional<String> key = located(path);
                if (key.isEmpty()) {
                    return Optional.empty();
                }
                // Read one byte PAST the caller's limit and nothing more. That single extra byte is what separates
                // "the sibling is exactly limit bytes and you hold all of it" from "there is more behind the bound",
                // so a companion of exactly the requested size is reported whole rather than pessimistically flagged -
                // a digest computed over it really is the companion's digest. The bound honoured here is the caller's
                // own, never LARGEST_SIBLING: a caller that asked for more than the whole-document ceiling asked
                // because it can account for the bytes, and cutting it back to a ceiling it never named would hand it
                // a prefix under a bound it did not choose.
                try (InputStream in = store.open(key.get())) {
                    byte[] prefix = in.readNBytes(limit + 1);
                    boolean truncated = prefix.length > limit;
                    return Optional.of(new PublishInterceptor.Content.Bounded(
                            truncated ? Arrays.copyOf(prefix, limit) : prefix, truncated));
                }
            }
        };
    }
}
