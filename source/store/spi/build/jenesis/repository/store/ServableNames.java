package build.jenesis.repository.store;

import build.jenesis.repository.store.Publication;
import module java.base;

/**
 * The one servable-name enumeration screen: every surface that materialises published NAMES (children, versions,
 * tags, coordinates, index stanzas) routes its disclosure decision through here, choosing a {@link Policy}. It
 * answers EXACTLY what the serve path answers - it composes {@link Publication}'s withheld chain and the
 * {@link Withheld withheld/&lt;hash&gt;} marker convention, never a stricter or looser private truth - so a listing
 * and a download can never disagree on what is held. {@link Publication#located} is itself a thin wrapper over
 * {@link #state}, so serve and enumeration share the one discrimination.
 *
 * <p><b>Both namespaces read both halves of the hold.</b> The two faces below differ only in how they compose the
 * pointer key - {@code publish<request-path>} for {@link #state}, the format's own key for {@link #keyState} - and
 * then run the identical probe order: chain, pointer, {@link Withheld withheld/&lt;hash&gt;} marker, blob stat. That
 * symmetry is the point rather than an implementation detail, because the two halves of a hold cover different
 * things: a {@code /quarantine<path>} pointer holds ONE alias, and the marker holds the BYTES wherever they are
 * served. A {@code publish/} face that read only the chain therefore let a content-addressed hold be escaped by any
 * alias the hold writer's path enumeration did not name - the Maven cross-publish's {@code /module/<name>/<name>.jar}
 * "latest" view being the driven case: it belongs to no single version, so neither {@code paths} overload of
 * the Maven layout that placed the jar reports it - both are version-addressed - and so no hold writer ever links a
 * review pointer at it, yet it points straight at the held blob. (The Jenesis layout does name that pointer, for every
 * version of a module <em>it</em> published first-hand; a jar cross-published from a Maven coordinate is recorded
 * under the Maven ecosystem and is never placed through it.) Reading the marker here retracts the view for exactly as
 * long as it names those bytes, and re-serves it the moment a republish re-aims it at an unheld version - which is
 * what "latest" means and what a path-keyed hold could not express.
 *
 * <p><b>Fail-closed by construction.</b> Every store probe this type makes is wrapped so that a name whose probe
 * throws a {@link RuntimeException} - a hostile / non-ASCII key a store backend cannot even
 * {@code resolve} ({@code FilesystemArtifactStore.resolve} does {@code root.resolve(key)} and throws
 * {@link java.nio.file.InvalidPathException} on an encoding-hostile name) - is treated as NOT disclosable and logged,
 * never rethrown. One hostile name in a page can therefore no longer 500 a whole listing, and it is never disclosed
 * either. Checked {@link IOException}s (an interceptor that fails closed on the publish path, a store I/O failure)
 * propagate exactly as they do through {@link Publication#located} today.
 *
 * <p>The {@link Policy} split is what keeps a membership surface (search, generated version indexes) from paying - or
 * being broken by - a blob stat: {@link Policy#HIDE_WITHHELD} runs the withhold reads and stats no blob, so a
 * coordinate recorded with a fake hash and no stored blob still lists (its fake hash matches no marker), while
 * {@link Policy#HIDE_WITHHELD_AND_GONE} is bit-for-bit the serve-parity screen the browse / assets surfaces already
 * pay for their size column.
 *
 * <p><b>Deciding is here; enumerating is not.</b> This type answers "may this ONE name be disclosed?". A surface that
 * must enumerate names drives {@code build.jenesis.repository.walk.ScreenedNames}, the screened-enumeration face that
 * pages a container through the shared bounded primitives and applies these very methods per name, so a listing
 * surface cannot page and then <em>forget</em> to screen. It composes this seam; it never re-decides disclosure.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> An immutable pair of a store and a {@link Publication}, safe to share and to probe
 *       concurrently; every method is a stateless read that keeps no per-call state on the instance.</li>
 *   <li><b>Idempotency / replay.</b> Every method is a pure read that commits nothing, so a repeated or replayed probe
 *       is always safe and always answers from the store's current durable truth.</li>
 *   <li><b>Absence sentinel.</b> {@code null} is never returned or accepted as an answer: an unpublished path is
 *       {@link State#UNPUBLISHED} (not an exception), and every {@code disclosable*} method answers a boolean whose
 *       {@code false} means "do not disclose" - never a null or an empty listing standing in for a verdict.</li>
 *   <li><b>Selection failure.</b> The screen is chosen by {@link Policy}, an enum, so there is no name to misspell and
 *       no silent fallback: a caller cannot select a screen that does not exist. A name a store backend cannot even
 *       resolve is not a selection failure but a screening failure - see clause 7.</li>
 *   <li><b>Streaming.</b> Nothing is materialised but small objects: a pointer body, an existence probe, and - in
 *       {@link #disclosableVersionFolder} alone - one version folder's child names, bounded by {@value #PROBE_CAP}.
 *       No artifact blob is ever opened by a disclosure decision.</li>
 *   <li><b>Tenant scoping.</b> The {@link ArtifactStore} handed to the constructor is the already tenant-scoped store;
 *       every probe composes a key under that scope only, so a screen can never read another tenant's keys, and a
 *       caller must never hand it a root store while screening a tenant's names.</li>
 *   <li><b>Error visibility.</b> A screen may never fail <em>open</em>. A {@link RuntimeException} from a store probe
 *       (an encoding-hostile name a backend cannot resolve) is contained: the name is judged NOT disclosable and the
 *       failure is logged at WARN, so one hostile name neither leaks nor fails a whole listing. A checked
 *       {@link IOException} - a real store outage, an interceptor failing closed - propagates unchanged, so the
 *       calling surface fails visibly instead of serving a listing that silently lost names.</li>
 *   <li><b>Read purity.</b> Store reads only ({@code readVersioned}, {@code exists}, {@code list}); no write, no
 *       external fetch, no cache mutation - a disclosure decision renders durable state and nothing else.</li>
 *   <li><b>Staleness.</b> A live read, never a snapshot: a hold that lands between two probes is honoured by the
 *       second. An enumeration is therefore not point-in-time consistent, which is the safe direction - a name held
 *       mid-listing disappears from the rest of that listing.</li>
 *   <li><b>Lifecycle / ownership.</b> The caller constructs and discards instances; they own no thread, client or
 *       cache. The {@link #ServableNames(ArtifactStore, Publication)} constructor exists so the withheld chain is the
 *       caller's already-discovered {@link PublishInterceptor} list rather than a second, independently discovered
 *       one.</li>
 *   <li><b>Ordering / concurrency.</b> The seam imposes no ordering of its own and is re-entrant; a verdict depends
 *       only on the name and the store's current state, never on discovery order or on which surface asks.</li>
 *   <li><b>Bounded work / cancellation.</b> Each single-name method costs a fixed, small number of store round-trips
 *       (one to five). {@link #disclosableVersionFolder} is the one fan-out and is capped at {@value #PROBE_CAP}
 *       probed leaves, past which it fails CLOSED rather than sampling. Enumerating many names is bounded by the
 *       caller's traversal primitive, not here.</li>
 *   <li><b>Durability / delivery.</b> Nothing is committed and nothing is delivered: this type has no crash window of
 *       its own, and any surface it screens keeps its own commit point.</li>
 * </ol>
 */
public final class ServableNames {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ServableNames.class);

    /** The reserved review subtree name under {@code publish/} - owned here once (today duplicated as an inline
     *  {@code "quarantine"} constant in the free {@code BrowseController}, {@code PublishedAssets}, {@code BrowsePanel}
     *  and the downstream console browse). A held upload's pointer is diverted to {@code publish/quarantine<path>}. */
    public static final String QUARANTINE = "quarantine";

    /** The store root of the served pointer namespace ({@code publish/<request-path> -> <sha256>}) - owned here once
     *  beside {@link #QUARANTINE}, because the two are one convention: a name enumerated under this root is a served
     *  request path (so {@link #state}/{@link #disclosable} decide it), and {@link #QUARANTINE} is the one child of
     *  this root that is stored but never served. Every surface that turns a {@code publish/} key into a request path,
     *  or a request path into a key, means exactly this prefix. */
    public static final String PUBLISHED = "publish";

    /** The checksum and signature suffixes that make a served path a SIDECAR of the artifact beside it - a document
     *  whose whole content is a statement about another path's bytes. Owned here beside {@link #PUBLISHED} because
     *  the two are the same kind of fact: a convention about served paths that every surface must read the same way.
     *
     *  <p>They exist because a hold has to cover them. A gate quarantines the artifact it can screen - a jar, a POM -
     *  and the checksum a publisher uploaded beside it is unclaimed content no inspector has an opinion about, so it
     *  was accepted, pointed at, and served while its subject 404s. That is a disclosure the hold was meant to
     *  prevent, and a specific one: {@code jenesisdemo-2.0.jar.sha1} publishes the exact digest of bytes the operator
     *  withheld, which is enough to confirm a suspected build or to find the artifact somewhere else. It also
     *  publishes the version's existence to any client that lists the folder.
     *
     *  <p>Deliberately not a format question. Every path-addressed ecosystem spells its sidecars this way, the rule
     *  is the same for all of them, and a format that had to remember to hold its own checksums is a format that will
     *  forget. */
    private static final List<String> SIDECAR_SUFFIXES =
            List.of(".md5", ".sha1", ".sha256", ".sha512", ".asc", ".sig");

    /** The path a sidecar describes, or {@code null} when this path is not one. Strips exactly one suffix and never
     *  recurses: {@code x.jar.sha1.md5} names {@code x.jar.sha1}, whose own hold is then read directly, so a chain of
     *  sidecars terminates in one step per read rather than walking. */
    private static String subject(String requestPath) {
        for (String suffix : SIDECAR_SUFFIXES) {
            if (requestPath.length() > suffix.length() && requestPath.endsWith(suffix)) {
                return requestPath.substring(0, requestPath.length() - suffix.length());
            }
        }
        return null;
    }

    /** Whether the path itself is held - the interceptor chain, then the {@link Withheld withheld/<hash>} marker on
     *  the hash its pointer names. The half of the withhold decision that does not consider the subject a sidecar
     *  describes, so {@link #state} and {@link #disclosable} can ask it about both and stay one statement of the rule.
     *  Stats no blob. */
    private boolean held(String requestPath) throws IOException {
        if (publication.withheld(requestPath)) {
            return true;
        }
        Optional<String> hash = publication.blob(requestPath);
        return hash.isPresent() && Withheld.is(store, hash.get());
    }

    /** The number of a version folder's leaves the interceptor chain is probed against in
     *  {@link #disclosableVersionFolder}: a bound so a pathologically wide folder cannot turn one folder's disclosure
     *  decision into an unbounded chain fan-out. The quarantine-pointer probe (a) is a single listing and is not
     *  capped; this caps only the chain leg (b).
     *
     *  <p>Raised well above any legitimate single-version folder: a real Maven version folder holds a handful of
     *  artifacts (main jar + pom + sources + javadoc + classifiers) each with up to five checksum/signature sidecars,
     *  a few dozen leaves at the extreme - so the exact fast path below (probe every leaf when the folder fits the cap)
     *  still covers every genuine release. Only a pathologically wide folder exceeds it, and past the cap
     *  {@link #disclosableVersionFolder} now fails CLOSED (screens the folder) rather than the former fail-OPEN, so an
     *  interceptor-only-withheld leaf beyond the probe bound can no longer leak its version name into maven-metadata. */
    private static final int PROBE_CAP = 512;

    /** The first-class discrimination {@link Publication#located} conflates into an empty {@link Optional}. */
    public enum State {
        /** Published, blob present, not withheld - a {@code GET} would serve it. */
        SERVABLE,
        /** Withheld from serving (an interceptor withholds the path, or a {@code withheld/<hash>} marker retracts the
         *  blob) - a {@code GET} answers 404 though the pointer and possibly the blob still exist. */
        WITHHELD,
        /** Published but the blob it points at is gone (a torn pointer a reconcile repairs) - not withheld. */
        BLOB_GONE,
        /** Nothing is published at the path/key. */
        UNPUBLISHED
    }

    /** What a surface hides. {@link #HIDE_WITHHELD} does ZERO blob-stat I/O (membership surfaces: search,
     *  maven-metadata versions, format version indexes - a fake-hash/no-blob member must keep listing).
     *  {@link #HIDE_WITHHELD_AND_GONE} is serve-parity (browse, {@code /assets}, raw listing) and adds the
     *  {@code blobs/<hash>} existence stat. */
    public enum Policy {
        HIDE_WITHHELD,
        HIDE_WITHHELD_AND_GONE
    }

    private final ArtifactStore store;
    private final Publication publication;

    public ServableNames(ArtifactStore store) {
        this(store, new Publication(store));
    }

    /** Reuse the caller's {@link Publication} so the withheld chain is the caller's interceptor list rather than a
     *  second, independently discovered one - the same explicit seam {@code PublishedAssets} takes. */
    public ServableNames(ArtifactStore store, Publication publication) {
        this.store = store;
        this.publication = publication;
    }

    // ---- publish/-namespace face (Maven, raw, quarantine-pointer holds) ----

    /** Full discrimination of one request path ({@code "/maven/g/a/1/a-1.jar"}), and the decision
     *  {@link Publication#located} is a wrapper over: (1) interceptor chain withheld -&gt; {@link State#WITHHELD};
     *  (2) {@code publish<path>} pointer absent -&gt; {@link State#UNPUBLISHED}; (3) a {@link Withheld withheld/<hash>}
     *  marker on the hash the pointer names -&gt; {@link State#WITHHELD}; (4) the path is a checksum/signature
     *  {@linkplain #subject sidecar} of a held path -&gt; {@link State#WITHHELD}; (5) {@code blobs/<hash>} stat -&gt;
     *  {@link State#SERVABLE} : {@link State#BLOB_GONE}. A probe that throws a {@link RuntimeException} (a hostile
     *  name) fails closed to {@link State#WITHHELD} - never disclosed, never thrown.
     *
     *  <p>Step (3) is the same probe {@link #keyState} makes in the same position, and it is what makes
     *  {@link State#WITHHELD}'s own definition true of this face: a hold has a path half (the
     *  {@code /quarantine<path>} pointer an interceptor reads) and a content half (the marker), and only the second
     *  reaches an alias no hold writer enumerated. It sits BEFORE the blob stat deliberately - a path that is both
     *  withheld and whose blob a collector has since reclaimed must read {@code WITHHELD}, not {@code BLOB_GONE},
     *  or a reconcile consumer repairs a torn pointer back into a served one. The cost is one extra existence probe
     *  on a path that already reads its pointer, and it can only ever hide more: a pointer naming a hash no marker
     *  covers answers exactly as before. */
    public State state(String requestPath) throws IOException {
        return located(requestPath).state();
    }

    /** A path's state and, when it is {@link State#SERVABLE}, the content hash its pointer resolved to - so a serve
     *  that has just decided a path is servable streams {@code blobs/<hash>} without reading the pointer again. */
    public record Location(State state, String hash) {
    }

    public Location located(String requestPath) throws IOException {
        try {
            if (publication.withheld(requestPath)) {
                return new Location(State.WITHHELD, null);
            }
            Optional<String> hash = publication.blob(requestPath);
            if (hash.isEmpty()) {
                return new Location(State.UNPUBLISHED, null);
            }
            if (Withheld.is(store, hash.get())) {
                return new Location(State.WITHHELD, null);
            }
            // A sidecar is held by its subject's hold. Read AFTER the pointer, so a path that is not published pays
            // nothing and still answers UNPUBLISHED - the sidecar question is only ever asked about a path that is
            // otherwise servable.
            String subject = subject(requestPath);
            if (subject != null && held(subject)) {
                return new Location(State.WITHHELD, null);
            }
            return store.exists("blobs/" + hash.get())
                    ? new Location(State.SERVABLE, hash.get())
                    : new Location(State.BLOB_GONE, null);
        } catch (RuntimeException hostile) {
            LOGGER.warn("servable-name probe of {} failed; treating as withheld (fail-closed)", requestPath, hostile);
            return new Location(State.WITHHELD, null);
        }
    }

    /** The policy check, doing only the probes the policy needs: {@link Policy#HIDE_WITHHELD} runs the two withhold
     *  reads - the interceptor chain, then the {@link Withheld withheld/<hash>} marker on the hash the pointer names -
     *  plus, for a {@linkplain #subject sidecar} path only, the same two reads against the subject it describes, and
     *  stats no blob, exactly as {@link #disclosableKey} does for the blobs namespace; an absent pointer discloses
     *  (nothing is published, so there is nothing held to hide, and a membership row recorded with a fake hash keeps
     *  listing because no marker is keyed by it). {@link Policy#HIDE_WITHHELD_AND_GONE} is {@code state() == SERVABLE}.
     *  Fail-closed on a hostile name. */
    public boolean disclosable(String requestPath, Policy policy) throws IOException {
        if (policy == Policy.HIDE_WITHHELD) {
            try {
                if (publication.withheld(requestPath)) {
                    return false;
                }
                Optional<String> hash = publication.blob(requestPath);
                if (hash.isEmpty()) {
                    return true;
                }
                if (Withheld.is(store, hash.get())) {
                    return false;
                }
                String subject = subject(requestPath);
                return subject == null || !held(subject);
            } catch (RuntimeException hostile) {
                LOGGER.warn("withheld-chain probe of {} failed; hiding (fail-closed)", requestPath, hostile);
                return false;
            }
        }
        return state(requestPath) == State.SERVABLE;
    }

    /** Version/leaf-folder disclosure for a generated version index (maven-metadata): the folder is UNDISCLOSABLE iff
     *  it is held - either (a) {@code publish/quarantine<folder>} has &ge;1 child (the core review-pointer
     *  convention every hold writer uses: {@code Publication.screen}'s QUARANTINE branch and the retroactive sweeps
     *  link {@code /quarantine<servedPath>} per served path), or (b) a hold covers any of the
     *  folder's leaves, up to the {@value #PROBE_CAP}-leaf bound past which it fails CLOSED (a folder wider than the
     *  bound is screened, since its unprobed leaves cannot be proven un-held). It never stats a blob, so a fake-hash /
     *  no-blob / non-jar version keeps listing; with the free (empty) chain and no quarantine pointer a folder within
     *  the bound always lists. Fail-closed on a hostile folder name.
     *
     *  <p><b>The one place this seam does not read the marker.</b> Leg (b) probes the chain per leaf and deliberately
     *  does NOT add the per-leaf pointer read plus {@link Withheld withheld/&lt;hash&gt;} probe {@link #state} and
     *  {@link #disclosable} now make, because this is the only fan-out face: it would turn one generated
     *  {@code maven-metadata.xml} into two extra store round-trips per leaf per version, on a read path (&sect;7). It
     *  is not a gap for any hold a writer places today - every retroactive sweep links a {@code /quarantine<path>}
     *  pointer beside the marker for a path that carries a {@code publish/} pointer, so leg (a) already screens the
     *  folder - but it is a genuine residual disagreement for a byte-identical SIBLING coordinate, whose version name
     *  keeps listing while its download now 404s on the marker. Filed as with the cost that decides it, rather
     *  than paid here unmeasured. */
    public boolean disclosableVersionFolder(String folder) throws IOException {
        try {
            // (a) The review-pointer convention: a held version has >=1 /quarantine<servedPath> pointer under it, so
            // any child under publish/quarantine<folder> means at least part of the version is held.
            if (!store.list(Publication.quarantineKey(folder)).isEmpty()) {
                return false;
            }
            // (b) A leaf of the version is held - by the interceptor chain, or by a withheld/<hash> marker on the
            // hash its pointer names. Bounded, and stats no blob. A folder
            // wider than the bound fails CLOSED: it cannot be probed exhaustively without unbounding the chain
            // fan-out, and a fail-OPEN past the bound would leak the version name of an interceptor-only-withheld leaf
            // sitting beyond the probed prefix. The bound is well above any legitimate version folder, so this screens
            // only pathologically wide folders; every real release is probed in full by the exact loop below.
            List<String> leaves = store.list("publish" + folder);
            if (leaves.size() > PROBE_CAP) {
                return false;
            }
            for (String leaf : leaves) {
                // held(), not publication.withheld(): both halves of a hold, which is what D-251 made state() and
                // disclosable() do and left this face without. The chain alone screens every hold a writer places
                // today, because each retroactive sweep links a /quarantine<path> review pointer beside the marker -
                // so leg (a) above already catches those. What it misses is a byte-identical SIBLING coordinate:
                // g:b:1.0 publishing the same bytes as a held g:a:1.0 carries no review pointer of its own and no
                // chain withhold, yet 404s on download because the marker is keyed by content. Its version name kept
                // listing in maven-metadata.xml - the listing/download disagreement this class exists to prevent.
                if (held(folder + "/" + leaf)) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException hostile) {
            LOGGER.warn("version-folder probe of {} failed; hiding (fail-closed)", folder, hostile);
            return false;
        }
    }

    // ---- blobs-namespace face (the withheld/<hash> marker convention) ----

    /** State of a blobs-namespace pointer key ({@code "npm/<n>/tarballs/x.tgz"}, {@code "oci/<n>/tags/<t>"}): pointer
     *  absent -&gt; {@link State#UNPUBLISHED}; pointer content is a hash carrying a {@link Withheld withheld/<hash>}
     *  marker -&gt; {@link State#WITHHELD}; else {@code blobs/<hash>} stat for {@link State#SERVABLE} /
     *  {@link State#BLOB_GONE}. Exactly the decision the blobs-namespace serve read makes - shared, not cloned.
     *  Fail-closed on a hostile key. */
    public State keyState(String pointerKey) throws IOException {
        try {
            Optional<ArtifactStore.Versioned> pointer = store.readVersioned(pointerKey);
            if (pointer.isEmpty()) {
                return State.UNPUBLISHED;
            }
            String hash = hash(pointer.get().content());
            if (Withheld.is(store, hash)) {
                return State.WITHHELD;
            }
            return store.exists("blobs/" + hash) ? State.SERVABLE : State.BLOB_GONE;
        } catch (RuntimeException hostile) {
            LOGGER.warn("blobs-namespace key probe of {} failed; treating as withheld (fail-closed)",
                    pointerKey, hostile);
            return State.WITHHELD;
        }
    }

    /** The policy check for a blobs-namespace key: {@link Policy#HIDE_WITHHELD} reads the pointer and the marker only
     *  (no blob stat - identical cost to the downstream {@code Blobs.withheld} it replaces) and an absent pointer
     *  discloses nothing to hide (matching {@code Blobs.withheld == false}); {@link Policy#HIDE_WITHHELD_AND_GONE} is
     *  {@code keyState() == SERVABLE}. Fail-closed on a hostile key. */
    public boolean disclosableKey(String pointerKey, Policy policy) throws IOException {
        if (policy == Policy.HIDE_WITHHELD) {
            try {
                Optional<ArtifactStore.Versioned> pointer = store.readVersioned(pointerKey);
                if (pointer.isEmpty()) {
                    return true; // no pointer -> nothing withheld to hide, exactly Blobs.withheld's false
                }
                return !Withheld.is(store, hash(pointer.get().content()));
            } catch (RuntimeException hostile) {
                LOGGER.warn("blobs-namespace key withhold probe of {} failed; hiding (fail-closed)",
                        pointerKey, hostile);
                return false;
            }
        }
        return keyState(pointerKey) == State.SERVABLE;
    }

    /**
     * The content hash a stored pointer body names - the one place the seam reads a pointer's dialect, so every face
     * ({@link #keyState}, {@link #disclosableKey}) and every adopter agrees on what {@code withheld/<hash>} is keyed
     * by. A body is either the bare lower-case SHA-256 hex the {@code publish/} and {@code blobs/} pointers carry, or
     * an algorithm-qualified digest reference ({@code sha256:<hex>} - the OCI tag-pointer dialect, and the wire form of
     * every Distribution digest); both denote the same blob, so the qualifier is stripped.
     *
     * <p>This is a <b>disclosure fix, not a convenience</b>: the marker convention is keyed by the bare hex, so a
     * screen that probed {@code withheld/sha256:<hex>} would never match a real marker and would fail <em>open</em> -
     * a held image disclosing its tag through every enumeration surface that screens through {@link #disclosableKey}.
     * Normalising here can only ever hide more, never disclose more: a body that is neither dialect (a torn or
     * hand-edited pointer) still matches no marker, exactly as before.
     */
    public static String hash(byte[] pointerBody) {
        return hash(new String(pointerBody, StandardCharsets.UTF_8));
    }

    /** {@link #hash(byte[])} over an already-decoded pointer body. */
    public static String hash(String pointerBody) {
        String trimmed = pointerBody.trim();
        int colon = trimmed.indexOf(':');
        return colon < 0 ? trimmed : trimmed.substring(colon + 1);
    }

    /** The raw marker probe ({@code store.readVersioned("withheld/" + sha256)}, via {@link Withheld#is}) - the
     *  hash-level face OCI's catalog/tags screen delegates to. Fail-closed (withheld) on a hostile hash. */
    public boolean withheldHash(String sha256) throws IOException {
        try {
            return Withheld.is(store, sha256);
        } catch (RuntimeException hostile) {
            LOGGER.warn("withheld-marker probe of {} failed; treating as withheld (fail-closed)", sha256, hostile);
            return true;
        }
    }

    // ---- the enumeration face lives beside the bounded traversal primitives ----
    //
    // There is deliberately no "decorate my page consumer" helper here any more. A decorator is opt-in: a surface that
    // pages the store itself can always forget to wrap its consumer, which is the disclosure class (C3) this seam
    // exists to end. The screened-enumeration face is build.jenesis.repository.walk.ScreenedNames, which owns the
    // paging - a caller hands it a container and receives ONLY disclosable names, and cannot obtain the raw ones - and
    // routes every per-name verdict back through the methods above. It lives in the walk module because that is where
    // the bounded traversal primitives (BoundedChildren, Trees) live and this module must not depend on them; the
    // disclosure decision stays here, so there is still exactly one screen.

    /** Whether a root child name is the reserved review subtree - the one home of the {@code "quarantine"} test that
     *  today lives inline in four free/downstream enumeration surfaces. */
    public static boolean reviewSubtree(String rootChildName) {
        return QUARANTINE.equals(rootChildName);
    }
}
