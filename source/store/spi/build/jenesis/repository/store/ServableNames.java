package build.jenesis.repository.store;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.store.Publication;

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
 * things: a {@code /quarantine<path>} pointer holds ONE alias - and the serving pointer at that alias carries a copy
 * of it (the {@code held} token {@link Publication#link} writes onto the body when the review pointer is linked and
 * lifts when it is unpublished), so the serve reads the path half off the one pointer it reads anyway rather than
 * probing a second key - and the marker holds the BYTES wherever they are served. A {@code publish/} face that read
 * only the chain would let a content-addressed hold be escaped by any alias the hold writer's path enumeration did
 * not name - the Maven cross-publish's {@code /module/<name>/<name>.jar} "latest" view is the case: it belongs to no
 * single version, so neither version-addressed {@code paths} overload of the Maven layout reports it and no hold
 * writer links a review pointer at it, yet it points straight at the held blob. Reading the marker here retracts the
 * view for exactly as long as it names those bytes, and re-serves it the moment a republish re-aims it at an unheld
 * version - which is what "latest" means and what a path-keyed hold cannot express.
 *
 * <p><b>Fail-closed by construction.</b> Every store probe this type makes is wrapped so that a name whose probe
 * throws a {@link RuntimeException} - a hostile / non-ASCII key a store backend cannot even
 * {@code resolve} ({@code FilesystemArtifactStore.resolve} does {@code root.resolve(key)} and throws
 * {@link java.nio.file.InvalidPathException} on an encoding-hostile name) - is treated as NOT disclosable and logged,
 * never rethrown. One hostile name in a page can therefore never 500 a whole listing, and it is never disclosed
 * either. Checked {@link IOException}s (an interceptor that fails closed on the publish path, a store I/O failure)
 * propagate exactly as they do through {@link Publication#located}.
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

    private static final Logger LOGGER = LoggerFactory.getLogger(ServableNames.class);

    /** The reserved review subtree name under {@code publish/}, owned here once. A held upload's pointer is diverted to
     *  {@code publish/quarantine<path>}. */
    public static final String QUARANTINE = "quarantine";

    /** The store root of the served pointer namespace ({@code publish/<request-path> -> <sha256>}) - owned here once
     *  beside {@link #QUARANTINE}, because the two are one convention: a name enumerated under this root is a served
     *  request path (so {@link #state}/{@link #disclosable} decide it), and {@link #QUARANTINE} is the one child of
     *  this root that is stored but never served. Every surface that turns a {@code publish/} key into a request path,
     *  or a request path into a key, means exactly this prefix. */
    public static final String PUBLISHED = "publish";

    /** The sidecar suffixes that name a checksum: the client's digest of the file beside it, which signing it again
     *  would not change, unlike a signature, which carries the moment it was made. */
    private static final List<String> CHECKSUM_SUFFIXES = List.of(".md5", ".sha1", ".sha256", ".sha512");

    /** The checksum and signature suffixes that make a served path a SIDECAR of the artifact beside it - a document
     *  whose whole content is a statement about another path's bytes. Owned here beside {@link #PUBLISHED} because
     *  the two are the same kind of fact: a convention about served paths that every surface must read the same way.
     *
     *  <p>They exist because a hold has to cover them. A gate quarantines the artifact it can screen - a jar, a POM -
     *  and the checksum a publisher uploaded beside it is unclaimed content no inspector has an opinion about, so
     *  without this it would be accepted, pointed at, and served while its subject 404s. That is a disclosure the hold
     *  was meant to prevent, and a specific one: {@code jenesisdemo-2.0.jar.sha1} publishes the exact digest of bytes
     *  the operator withheld, which is enough to confirm a suspected build or to find the artifact somewhere else. It
     *  also publishes the version's existence to any client that lists the folder.
     *
     *  <p>Deliberately not a format question. Every path-addressed ecosystem spells its sidecars this way, the rule
     *  is the same for all of them, and a format that had to remember to hold its own checksums is a format that will
     *  forget. A Sigstore bundle is a sidecar for the same reason as a signature: {@code x.jar.sigstore.json} carries
     *  the digest of {@code x.jar} and the identity that signed it, which is everything a hold means to withhold; so
     *  is a registry's attestations document kept beside the artifact it attests. */
    private static final List<String> SIDECAR_SUFFIXES = Stream.concat(CHECKSUM_SUFFIXES.stream(),
            Stream.of(".asc", ".sig", ".sigstore.json", ".attestations.json")).toList();

    /**
     * The path a sidecar describes - a checksum or a signature, a document whose whole content is a statement about
     * another path's bytes - or empty when {@code requestPath} is no sidecar. The one declaration of what a sidecar is:
     * a sidecar is held with its subject, is no file of a version, records nothing against the version and counts no
     * download, and every format and surface asks here rather than keeping a suffix list of its own.
     */
    public static Optional<String> sidecarOf(String requestPath) {
        return Optional.ofNullable(subject(requestPath));
    }

    /** The path a checksum describes, or empty when {@code requestPath} is no checksum: a {@link #sidecarOf sidecar}
     *  whose content is a digest rather than a signature. */
    public static Optional<String> checksumOf(String requestPath) {
        return Optional.ofNullable(stripped(requestPath, CHECKSUM_SUFFIXES));
    }

    /** Whether {@code requestPath} is a sidecar ({@link #sidecarOf}). */
    public static boolean sidecar(String requestPath) {
        return subject(requestPath) != null;
    }

    /** The path a sidecar describes, or {@code null} when this path is not one. Strips exactly one suffix and never
     *  recurses: {@code x.jar.sha1.md5} names {@code x.jar.sha1}, whose own hold is then read directly, so a chain of
     *  sidecars terminates in one step per read rather than walking. */
    private static String subject(String requestPath) {
        return stripped(requestPath, SIDECAR_SUFFIXES);
    }

    /** {@code requestPath} without the one of {@code suffixes} it ends with, or {@code null} when it ends with none. */
    private static String stripped(String requestPath, List<String> suffixes) {
        for (String suffix : suffixes) {
            if (requestPath.length() > suffix.length() && requestPath.endsWith(suffix)) {
                return requestPath.substring(0, requestPath.length() - suffix.length());
            }
        }
        return null;
    }

    /** Whether the path itself is held - the interceptor chain, then the pointer's own hold flag, then the
     *  {@link Withheld withheld/<hash>} marker on the hash its pointer names; and for a path with no serving pointer
     *  to carry the flag (a subject quarantined at publish, whose sidecar arrived and was accepted as unclaimed
     *  content), the {@code /quarantine} review pointer itself, which is the one case the copy cannot answer. The
     *  half of the withhold decision that does not consider the subject a sidecar describes, so {@link #state} and
     *  {@link #disclosable} can ask it about both and stay one statement of the rule. Stats no blob. */
    private boolean held(String requestPath) throws IOException {
        if (publication.withheld(requestPath)) {
            return true;
        }
        Optional<Pointer> pointer = publication.pointer(requestPath);
        if (pointer.isEmpty()) {
            return Publication.reviewPending(store, requestPath);
        }
        return pointer.get().held() || Withheld.is(store, pointer.get().hash());
    }

    /** The number of a version folder's leaves the interceptor chain is probed against in
     *  {@link #disclosableVersionFolder}: a bound so a pathologically wide folder cannot turn one folder's disclosure
     *  decision into an unbounded chain fan-out. The quarantine-pointer probe (a) is a single listing and is not
     *  capped; this caps only the chain leg (b).
     *
     *  <p>Set well above any legitimate single-version folder: a real Maven version folder holds a handful of
     *  artifacts (main jar + pom + sources + javadoc + classifiers) each with up to five checksum/signature sidecars,
     *  a few dozen leaves at the extreme - so the exact fast path below (probe every leaf when the folder fits the cap)
     *  still covers every genuine release. Only a pathologically wide folder exceeds it, and past the cap
     *  {@link #disclosableVersionFolder} fails CLOSED (screens the folder) rather than open, so an
     *  interceptor-only-withheld leaf beyond the probe bound can never leak its version name into maven-metadata. */
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
     *  (2) {@code publish<path>} pointer absent -&gt; {@link State#UNPUBLISHED}; (3) the pointer's own
     *  {@link Pointer#held() hold flag} - the copy of the {@code /quarantine<path>} review pointer that
     *  {@link Publication#link} writes onto the serving pointer - -&gt; {@link State#WITHHELD}; (4) a
     *  {@link Withheld withheld/<hash>} marker on the hash the pointer names -&gt; {@link State#WITHHELD}; (5) the
     *  path is a checksum/signature {@linkplain #subject sidecar} of a held path -&gt; {@link State#WITHHELD}; (6)
     *  {@code blobs/<hash>} stat -&gt; {@link State#SERVABLE} : {@link State#BLOB_GONE}. A probe that throws a
     *  {@link RuntimeException} (a hostile name) fails closed to {@link State#WITHHELD} - never disclosed, never
     *  thrown.
     *
     *  <p>Step (4) is the same probe {@link #keyState} makes in the same position, and it is what makes
     *  {@link State#WITHHELD}'s own definition true of this face: a hold has a path half (the
     *  {@code /quarantine<path>} pointer an interceptor reads) and a content half (the marker), and only the second
     *  reaches an alias no hold writer enumerated. It sits BEFORE the blob stat deliberately - a path that is both
     *  withheld and whose blob a collector has since reclaimed must read {@code WITHHELD}, not {@code BLOB_GONE},
     *  or a reconcile consumer repairs a torn pointer back into a served one. The cost is one extra existence probe
     *  on a path that already reads its pointer, and it can only ever hide more: a pointer naming a hash no marker
     *  covers answers as it would without the probe. */
    public State state(String requestPath) throws IOException {
        Location location = located(requestPath);
        if (location.state() != State.SERVABLE) {
            return location.state();
        }
        // The stat the serve does not pay: an enumeration face asking for serve parity (browse, the raw listing,
        // /assets) still distinguishes a torn pointer from a servable one, because it lists rather than opens.
        return store.exists("blobs/" + location.hash()) ? State.SERVABLE : State.BLOB_GONE;
    }

    /** Whether the interceptor chain withholds {@code requestPath} - the hold probe alone, for a caller that already
     *  holds the pointer's hash and checks the {@link Withheld} marker itself, as the rebuild pass does per object. */
    public boolean heldByChain(String requestPath) throws IOException {
        return publication.withheld(requestPath);
    }

    /** Where a request path stands, and for a {@link State#SERVABLE} one the hash its pointer names and the length
     *  the pointer records - so a serve sets its {@code Content-Length} without a stat and opens the blob for the
     *  bytes, the open being what proves the blob present. {@code -1} where the pointer records no length (until the
     *  rebuild walk backfills it), which a serve answers without a {@code Content-Length}. */
    public record Location(State state, String hash, long size) {
    }

    public Location located(String requestPath) throws IOException {
        try {
            // A path this node recently read and found unpublished is unpublished still, from memory: nothing is
            // published, so there is nothing to withhold, and neither the interceptor probe nor the pointer read is
            // paid. The memory is the store's - only a store a composition decorated remembers, every write through
            // it forgets the key, and the ttl bounds what another node's publish can look like from here.
            Optional<MissMemory> memory = NodeMemoStore.misses(store);
            String pointerKey = "publish" + requestPath;
            if (memory.isPresent() && memory.get().remembered(store, pointerKey)) {
                return new Location(State.UNPUBLISHED, null, -1L);
            }
            long mark = memory.map(MissMemory::mark).orElse(0L);
            if (publication.withheld(requestPath)) {
                return new Location(State.WITHHELD, null, -1L);
            }
            Optional<Pointer> pointer = publication.pointer(requestPath);
            if (pointer.isEmpty()) {
                memory.ifPresent(remembering -> remembering.remember(store, pointerKey, mark));
                return new Location(State.UNPUBLISHED, null, -1L);
            }
            if (pointer.get().held()) {
                // The path half of a hold, read off the pointer the serve reads anyway: the /quarantine review pointer
                // that placed it is the queue and the authority, and this flag is its copy on the serving pointer
                // (Publication.link writes it, unpublish lifts it, the rebuild walk reconciles the two), so a
                // download does not probe a second key for it.
                return new Location(State.WITHHELD, null, -1L);
            }
            String hash = pointer.get().hash();
            if (Withheld.is(store, hash)) {
                return new Location(State.WITHHELD, null, -1L);
            }
            // A sidecar is held by its subject's hold. Read AFTER the pointer, so a path that is not published pays
            // nothing and still answers UNPUBLISHED - the sidecar question is only ever asked about a path that is
            // otherwise servable.
            String subject = subject(requestPath);
            if (subject != null && held(subject)) {
                return new Location(State.WITHHELD, null, -1L);
            }
            // The blob's length comes off the pointer, never off the blob: a serve sets its Content-Length from it
            // and opens the blob for the bytes, and that open is what proves the blob present (a pointer whose blob
            // is gone answers a clean 404 from the open, never a truncated 200). Step (6) of state()'s javadoc - the
            // stat - is therefore the enumeration faces' alone, in state(); this location does not pay it.
            return new Location(State.SERVABLE, hash, pointer.get().size());
        } catch (RuntimeException hostile) {
            LOGGER.warn("servable-name probe of {} failed; treating as withheld (fail-closed)", requestPath, hostile);
            return new Location(State.WITHHELD, null, -1L);
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
                Optional<Pointer> pointer = publication.pointer(requestPath);
                if (pointer.isEmpty()) {
                    return true;
                }
                if (pointer.get().held() || Withheld.is(store, pointer.get().hash())) {
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
     *  bound is screened, since its unprobed leaves cannot be proven un-held). The bound holds over the <em>read</em>
     *  as well as the probing: the leaf names are paged one past the cap, so a pathologically wide folder is rejected
     *  without ever being materialised. It never stats a blob, so a fake-hash / no-blob / non-jar version keeps
     *  listing; with an empty chain and no quarantine pointer an unheld folder within the bound always lists.
     *  Fail-closed on a hostile folder name.
     *
     *  <p>Leg (b) asks both halves of a hold per leaf - the chain, the pointer's hold flag and the
     *  {@link Withheld withheld/&lt;hash&gt;} marker - exactly as {@link #state} and {@link #disclosable} do. Leg (a)
     *  already screens every version a retroactive sweep holds, since each sweep links a {@code /quarantine<path>}
     *  pointer beside the marker; the marker is what screens a byte-identical SIBLING coordinate, which carries no
     *  review pointer of its own yet 404s on download. The cost is a pointer read and a marker probe per leaf,
     *  bounded by {@value #PROBE_CAP}. */
    public boolean disclosableVersionFolder(String folder) throws IOException {
        try {
            // (a) The review-pointer convention: a held version has >=1 /quarantine<servedPath> pointer under it, so
            // any child under publish/quarantine<folder> means at least part of the version is held.
            if (!store.isEmpty(Publication.quarantineKey(folder))) {
                return false;
            }
            // (b) A leaf of the version is held - by the interceptor chain, or by a withheld/<hash> marker on the
            // hash its pointer names. Bounded, and stats no blob. A folder wider than the bound fails CLOSED: it
            // cannot be probed exhaustively without unbounding the fan-out, and a fail-OPEN past the bound would leak
            // the version name of a held leaf beyond the probed prefix. One more than the cap is read rather than the
            // folder, so a pathologically wide folder is rejected without being materialised.
            List<String> leaves = new ArrayList<>();
            store.page("publish" + folder, "", ArtifactStore.oneMoreThan(PROBE_CAP), leaves::add);
            if (leaves.size() > PROBE_CAP) {
                return false;
            }
            for (String leaf : leaves) {
                // held(), not publication.withheld(): both halves of a hold, as state() and disclosable() read them.
                // A byte-identical SIBLING coordinate - g:b:1.0 publishing the same bytes as a held g:a:1.0 - carries
                // no review pointer and no chain withhold, yet 404s on download because the marker is keyed by
                // content; only the marker keeps its version name out of maven-metadata.xml.
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
     *  (no blob stat) and an absent pointer
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
     * <p>This normalisation is a <b>disclosure guard, not a convenience</b>: the marker convention is keyed by the bare
     * hex, so a screen that probed {@code withheld/sha256:<hex>} would never match a real marker and would fail
     * <em>open</em> - a held image disclosing its tag through every enumeration surface that screens through
     * {@link #disclosableKey}. Normalising here can only ever hide more, never disclose more: a body that is neither
     * dialect (a torn or hand-edited pointer) still matches no marker.
     */
    public static String hash(byte[] pointerBody) {
        return parse(pointerBody).hash();
    }

    /** {@link #hash(byte[])} over an already-decoded pointer body. */
    public static String hash(String pointerBody) {
        return parse(pointerBody).hash();
    }

    /**
     * What a serving pointer's body says: the content hash it names, the blob's stored length where the writer
     * recorded one - {@code -1} where it did not - and whether the path is {@linkplain #held held} from serving.
     *
     * <p>The body of a {@code publish/} or blobs-namespace pointer is the lower-case SHA-256 hex followed by a space
     * and the blob's length in decimal bytes: {@code <hash> <length>}. The length is a pure
     * function of an immutable hash - no authority moves, nothing can go stale - and recording it costs no write,
     * the pointer being written anyway; reading it is what lets a download set its {@code Content-Length} without
     * a stat of the blob, and a {@code HEAD} answer without touching the blob at all. A pointer without a length
     * parses with {@code -1}: it is served without a length (a chunked body, a {@code HEAD}
     * without {@code Content-Length}) until the reconcile pass regenerates it with one, and is never read through a
     * stat on the request path - a fallback there would be the very read the length exists to remove. The OCI
     * tag-pointer dialect ({@code sha256:<hex>}) carries no length and never will; its blobs are served by digest
     * through the Distribution API, which has its own length.
     *
     * <p>A {@code publish/} pointer may end in the token {@value #HELD}: {@code <hash> <length> held}. It is the
     * path half of a hold, copied onto the serving pointer from the {@code /quarantine<path>} review pointer that
     * placed it - written when that review pointer is linked, lifted when it is unpublished, and brought back into
     * line by the rebuild walk should a crash separate the two writes - so a serve answers 404 for a held path off
     * the one pointer it reads anyway, rather than probing the review pointer as a second key on every download.
     * The review pointer stays the queue and the authority (the review screens, the hold lifecycle and the miss-path
     * guard read it); the flag is the read path's copy of it and nothing decides from the flag alone but a serve.
     * A body carrying only the hash and the token ({@code <hash> held}) is a held pointer whose length was never
     * recorded; token order after the hash is not significant.
     */
    public record Pointer(String hash, long size, boolean held) {

        /** The token that marks a serving pointer's path as held from serving - see the record's javadoc. */
        public static final String HELD = "held";

        /** The body {@link Publication#link} and its blobs-namespace twin write for this hash and length, not held:
         *  the hash alone where the length is unknown. */
        public static byte[] render(String hash, long size) {
            return render(hash, size, false);
        }

        /** {@link #render(String, long)} with the path's hold flag, the form a {@code publish/} pointer under a
         *  {@code /quarantine} review pointer is written in. */
        public static byte[] render(String hash, long size, boolean held) {
            StringBuilder body = new StringBuilder(hash);
            if (size >= 0) {
                body.append(' ').append(size);
            }
            if (held) {
                body.append(' ').append(HELD);
            }
            return body.toString().getBytes(StandardCharsets.UTF_8);
        }

        /** This pointer's body with its hold flag set to {@code held} and nothing else changed - the one rewrite a
         *  hold or a release makes to a serving pointer. */
        public byte[] render(boolean held) {
            return render(hash, size, held);
        }
    }

    /** {@link #parse(String)} over a pointer's stored bytes. */
    public static Pointer parse(byte[] pointerBody) {
        return parse(new String(pointerBody, StandardCharsets.UTF_8));
    }

    /**
     * The one place a pointer's dialect is read - see {@link Pointer} for the shapes. A body that is none of them (a
     * torn or hand-edited pointer) yields whatever its first token is as the hash and no length, which matches no
     * marker and stats no blob: it can only ever hide more, never disclose more.
     */
    public static Pointer parse(String pointerBody) {
        String[] tokens = pointerBody.trim().split("\\s+");
        String first = tokens[0];
        int colon = first.indexOf(':');
        String hash = colon < 0 ? first : first.substring(colon + 1);
        long size = -1L;
        boolean held = false;
        for (int index = 1; index < tokens.length; index++) {
            if (tokens[index].equals(Pointer.HELD)) {
                held = true;
            } else if (size < 0) {
                try {
                    size = Math.max(-1L, Long.parseLong(tokens[index]));
                } catch (NumberFormatException notALength) {
                    size = -1L;
                }
            }
        }
        return new Pointer(hash, size, held);
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
    // There is deliberately no "decorate my page consumer" helper here. A decorator is opt-in: a surface that
    // pages the store itself can always forget to wrap its consumer, which is the disclosure class this seam
    // exists to end. The screened-enumeration face is build.jenesis.repository.walk.ScreenedNames, which owns the
    // paging - a caller hands it a container and receives ONLY disclosable names, and cannot obtain the raw ones - and
    // routes every per-name verdict back through the methods above. It lives in the walk module because that is where
    // the bounded traversal primitives (BoundedChildren, Trees) live and this module must not depend on them; the
    // disclosure decision stays here, so there is still exactly one screen.

    /** Whether a root child name is the reserved review subtree - the one home of the {@code "quarantine"} test. */
    public static boolean reviewSubtree(String rootChildName) {
        return QUARANTINE.equals(rootChildName);
    }

    /**
     * A browse prefix confined to the served tree: every unsafe segment - empty, {@code .}, {@code ..}, or carrying a
     * backslash - is dropped, since the store normalises {@code publish/../blobs} to {@code blobs}, and so is a
     * leading {@linkplain #reviewSubtree review subtree} segment, which a browse never opens (a deeper
     * {@code quarantine} is an ordinary segment). Answers a leading-slash path, or {@code ""} for the root.
     */
    public static String safePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return "";
        }
        StringBuilder safe = new StringBuilder();
        for (String segment : prefix.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..") || segment.indexOf('\\') >= 0) {
                continue;
            }
            if (safe.isEmpty() && reviewSubtree(segment)) {
                continue;
            }
            safe.append('/').append(segment);
        }
        return safe.toString();
    }

    /**
     * Whether {@code value} is exactly what a SHA-256 digest renders as: sixty-four lower-case hex characters and
     * nothing else.
     *
     * <p>This is the shape rule behind every content-addressed key - a {@code blobs/<hex>} object, an OCI
     * {@code sha256:<hex>} reference, a pointer body naming a blob - and it is a refusal, not a parse: a value that is
     * not this shape (a tag typo, a {@code ..}-laced reference, a format's small non-hash marker under the same root)
     * must never be spliced into a store key, where it would resolve to a neighbouring key space rather than fail.
     * The rule lives here, once, so that every module citing it makes a call rather than carrying a copy.
     */
    public static boolean isSha256Hex(String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        for (int index = 0; index < 64; index++) {
            char character = value.charAt(index);
            if ((character < '0' || character > '9') && (character < 'a' || character > 'f')) {
                return false;
            }
        }
        return true;
    }
}
