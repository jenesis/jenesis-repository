package build.jenesis.repository.inventory;

import module java.base;
import build.jenesis.repository.store.Clocks;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.blobs.BlobRoots;
import build.jenesis.repository.format.BlobReferences;
import build.jenesis.repository.format.EcosystemLayout;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.Known;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.cleanup.Release;
import build.jenesis.repository.cleanup.RepositoryInventory;
import build.jenesis.repository.cleanup.RetentionPolicy;
import build.jenesis.repository.metadata.MetadataKey;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Names;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.walk.ArtifactWalk;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.walk.PagedTreeWalk;
import build.jenesis.repository.walk.Traversal;
import build.jenesis.repository.walk.WalkPass;
import build.jenesis.repository.store.SingleFlight;

/**
 * A {@link RepositoryInventory} over the artifact store, keyed by the format-neutral {@code ecosystem} and coordinate
 * a format supplies rather than any layout this code parses. Publish times are recorded in a {@code published/}
 * sidecar (the store's version token is opaque, so the time cleanup orders and ages by is kept explicitly, alongside
 * the format-supplied prerelease flag); enumeration reads those sidecars, and an eviction unpublishes every pointer a
 * version occupies - the paths resolved by the owning format's {@link ArtifactLayout}, including any cross-published
 * mirror it recorded - each removal observed ({@code PublicationObserver.onDeleted}) with the coordinate this
 * eviction already resolved. The now-unreferenced content blobs are reclaimed by the discovered
 * {@code GarbageCollector} (resolved through its provider with the {@link #pointerRoots} this inventory derives from
 * the installed formats), no longer by any enumeration of this class - with no collector installed nothing is ever
 * reclaimed, and the capability surfaces say so. Only versions the repository recorded, through a format that
 * describes its coordinates, are seen.
 *
 * <p><strong>Composed of subsystem collaborators.</strong> This class is the {@link RepositoryInventory} facade:
 * it holds the store and the collaborators each cohesive concern was extracted into - {@link InventoryRecording}
 * (publish/provenance/download recording and the publish-facts point reads), {@link InventoryPins} (force-keeps),
 * {@link InventoryRetention} (the retention-policy object), {@link InventoryBrowse} (children/describe/locate/paths),
 * {@link InventoryReleases} (the release/coordinate enumerations and blobs-namespace queries),
 * {@link InventoryEviction} (the unpublish + derived-row reap), {@link InventoryReconciler} (the §4/§5 convergence
 * sweep), {@link InventoryIdentity} (the rollup identity) and {@link SubtreeSizeRollUp} (the browse-size fold) - and
 * delegates each method to the one that owns it. The shared store-key/codec helpers ({@link #encode}/{@link #decode},
 * the {@code published/}/{@code downloaded/}/{@code pinned/} key builders, {@link #layoutsFor}/{@link #blobLayoutsFor},
 * the fenced {@link #writeVersioned} and the subtree {@link #walk}) live here once and every collaborator reuses them,
 * so the split changed no behaviour and no on-store byte/key layout.
 */
public final class StoreRepositoryInventory implements RepositoryInventory {

    /**
     * The formats this deployment currently installs: the module graph filtered by the deployment's feature toggles
     * at every consultation, so {@code jenreg.<format>=false} means absent here exactly as it does at the serving
     * edge and in the collector's reference lenders - the documented "as if it were not installed". The filter runs
     * per call rather than at class load, so a toggle applied by a settings refresh is honoured live.
     *
     * <p>This alignment is load-bearing for the reclaiming passes. The layout consulted off the raw graph while
     * {@code BlobReferences.installed()} honoured the toggle made a toggled-off format placeable without a lender:
     * {@link #pointerRoots(ArtifactStore)} then answered complete, the mark scanned an OCI tag pointer without
     * expanding the manifest it names, and the layers only that manifest references read as unreferenced - condemned
     * by one sweep and deleted by the next. With the toggle honoured here, such an ecosystem is unplaceable and every
     * reclaiming pass refuses it whole, exactly as for a module that is not on the graph.
     */
    static List<RepositoryFormat> formats() {
        return RepositoryFormat.installed();
    }

    private final Publication publication;
    private final ArtifactStore store;
    private final ArtifactWalk walk;

    /** The consolidated metadata store the publish facts live in. The {@code published} section is the source of
     *  truth and the only one read: the publish instant, the prerelease flag and the pin, with set completeness
     *  back-filled by the reconcile
     *  forward-repair (§5). */
    private final MetadataStore metadata;

    /** The subtree-size roll-up subsystem - its ~450-line CAS-fenced fold lives in a sibling class; the public
     *  {@link #rollUpSizes}/{@link #subtreeSize} seam delegates here. */
    private final SubtreeSizeRollUp rollUp;

    /** The reconcile - the §4/§5 convergence backstop, extracted to a sibling class; the public
     *  {@link #reconcile} seam and the walk's {@link InventoryReconcileConsumer} delegate here. */
    private final InventoryReconciler reconciler;

    /** The maintained rollup identity - the O(1) content digest a whole-repository export's ETag derives from, so a
     *  conditional GET revalidates without the O(#versions) coordinate walk; the {@link #identity}/{@link #rebuildIdentity}
     *  seam delegates here and the mutation paths ({@link #record}/{@link #evict}) fold it. */
    private final InventoryIdentity identity;

    /** The publish/provenance/download recording subsystem and the publish-facts point reads. */
    private final InventoryRecording recording;

    /** The pin (force-keep) subsystem. */
    private final InventoryPins pinning;

    /** The retention-policy storage subsystem. */
    private final InventoryRetention retention;

    /** The browse / describe read subsystem. */
    private final InventoryBrowse browse;

    /** The release / coordinate enumeration subsystem. */
    private final InventoryReleases enumeration;

    /** The eviction (unpublish + derived-row reap) subsystem. */
    private final InventoryEviction eviction;

    public StoreRepositoryInventory(ArtifactStore store) {
        this(store, null);
    }

    /** An inventory whose whole-store enumerations ride the shared artifact walk - the scheduled sweep's view: the
     *  {@link #releases(ReleaseVisitor)} stream becomes a resumable, range-segmented, multi-node-cooperative pass
     *  ({@code walks/retention} in this store) instead of a per-call re-listing, and {@link #rollUpSizes} rides its
     *  own {@code walks/rollup} pass the same way. Hand this to the scheduled sweep only; an on-demand preview or
     *  any caller that must see <em>everything on every call</em> uses the walk-less constructor, whose
     *  enumerations never touch the pass state. */
    public StoreRepositoryInventory(ArtifactStore store, ArtifactWalk walk) {
        this.publication = new Publication(store);
        this.store = store;
        this.walk = walk;
        this.metadata = MetadataProvider.installed().over(store);
        this.identity = new InventoryIdentity(store);
        this.rollUp = new SubtreeSizeRollUp(this, store, walk, publication);
        this.reconciler = new InventoryReconciler(this, store, metadata);
        this.recording = new InventoryRecording(this, store, metadata, identity);
        this.pinning = new InventoryPins(this, store, metadata);
        this.retention = new InventoryRetention(this, store);
        // Construct the servable-name enumeration seam from the store and THIS facade's Publication, so the withheld
        // chain the coordinate/paging screen consults is the deployment's own interceptor list (the ComplianceScreen and
        // staging screens), never a second independently discovered one.
        this.browse = new InventoryBrowse(store, new ServableNames(store, publication));
        this.enumeration = new InventoryReleases(this, store, walk);
        this.eviction = new InventoryEviction(this, store, publication, identity);
    }

    /** Record a published request path by the neutral coordinate the owning format describes - through its
     *  {@link ArtifactLayout} for a {@code publish/}-namespace layout, or its {@link BlobLayout#describe} for a
     *  blobs-namespace one, so npm/PyPI/NuGet-style publishes gain the {@code published/} sidecar the retroactive
     *  enforcement sweeps enumerate. A no-op for a path no descriptive format claims. */
    public void record(String path, Instant published) throws IOException {
        recording.record(path, published);
    }

    /** Record a published request path, folding a {@code local-upload} origin row for {@code originSha256} (the stored
     *  blob's content hash) into the same publish-commit doc mutate as the {@code published} section - one
     *  CAS. A {@code null} sha records no origin row (the non-upload record paths). */
    public void record(String path, Instant published, String originSha256) throws IOException {
        recording.record(path, published, originSha256);
    }

    /** Record a publish from the descriptor, folding a {@code local-upload} origin row for {@code originSha256}. */
    public void record(ArtifactDescriptor descriptor, Instant published, String originSha256) throws IOException {
        recording.record(descriptor, published, originSha256);
    }

    /** Record a publish from the format-neutral descriptor an {@link ArtifactLayout} produced; a no-op for a descriptor
     *  that carries no coordinate (a checksum, generated metadata). */
    public void record(ArtifactDescriptor descriptor, Instant published) throws IOException {
        recording.record(descriptor, published);
    }

    /** Record when a coordinate version was published, defaulting a non-prerelease. */
    public void record(String ecosystem, String coordinate, String version, Instant published) throws IOException {
        recording.record(ecosystem, coordinate, version, published);
    }

    /** Record when a coordinate version was published - the timestamp cleanup orders and ages by, plus the
     *  format-supplied prerelease flag the prerelease-expiry rule reads. */
    public void record(String ecosystem, String coordinate, String version, boolean prerelease, Instant published)
            throws IOException {
        recording.record(ecosystem, coordinate, version, prerelease, published);
    }

    /** Record a publish, folding a {@code local-upload} origin row for {@code originSha256} into the same doc mutate as
     *  the {@code published} section. A {@code null} sha records no origin row. */
    public void record(String ecosystem, String coordinate, String version, boolean prerelease, Instant published,
                       String originSha256) throws IOException {
        recording.record(ecosystem, coordinate, version, prerelease, published, originSha256);
    }

    /** Record a coordinate version's provenance summary at publish: whether its inbound attestation
     *  verified and bound to this artifact, and the SHA-256 it bound - the durable, GUI-facing summary that points at
     *  the content-keyed attestation cache without duplicating it. A no-op when the consolidated metadata store is
     *  absent (graceful, §3), since the summary has nowhere to live. */
    public void recordProvenance(String ecosystem, String coordinate, String version, boolean verified, String sha256)
            throws IOException {
        recording.recordProvenance(ecosystem, coordinate, version, verified, sha256);
    }

    /** Record when a coordinate version was last downloaded - the timestamp the not-downloaded-for rule ages by,
     *  written best-effort off the request path. A marker-less version is treated as never-downloaded-since-publish,
     *  which biases toward deletion, not away from it. */
    /** One publish's inventory facts in one write - see {@link Recording} - for the coordinate and version an
     *  installed format describes {@code path} to; empty when none describes it that far. */
    public Optional<Recording> recording(String path, Instant published) {
        return recording.recording(path, published);
    }

    /** As {@link #recording(String, Instant)}, for a coordinate the caller already holds - the inspected subject of
     *  a publish whose request path carries no version. */
    public Recording recording(String ecosystem, String coordinate, String version, boolean prerelease,
                               Instant published) {
        return recording.recording(ecosystem, coordinate, version, prerelease, published);
    }

    public void recordDownload(String ecosystem, String coordinate, String version, Instant when) throws IOException {
        recording.recordDownload(ecosystem, coordinate, version, when);
    }

    /** Record {@code delta} downloads of a coordinate version, the newest at {@code last}, in one compare-and-set on
     *  the version's document ({@link DownloadsSection}) - the download tracker's flush, once per flush interval. */
    public void recordDownloads(String ecosystem, String coordinate, String version, long delta, Instant last)
            throws IOException {
        recording.recordDownloads(ecosystem, coordinate, version, delta, last);
    }

    /** How often a coordinate version was downloaded and when it last was, or empty where nothing was recorded or
     *  the consolidated store is not installed. */
    public Optional<DownloadsSection.Facts> downloads(String ecosystem, String coordinate, String version)
            throws IOException {
        return recording.downloads(ecosystem, coordinate, version);
    }

    /** What a coordinate version's manifest declared it depends on, as its inspector read it at publish, or empty
     *  where nothing was recorded - a format whose inspector reads no dependency list, a version published before
     *  they were recorded, or no consolidated store. An empty list is a manifest that declared none. */
    public Optional<List<DependencySection.Declared>> dependencies(String ecosystem, String coordinate,
                                                                   String version) throws IOException {
        return recording.dependencies(ecosystem, coordinate, version);
    }

    /** When a coordinate version was recorded as published - the {@code published/} sidecar's instant - or empty if no
     *  sidecar exists, so a non-retroactive backstop (forwarding self-repair) can compare a publication against a
     *  watermark without enumerating every {@link Release}. Reads only the tiny sidecar, never an artifact blob. */
    public Optional<Instant> publishedAt(String ecosystem, String coordinate, String version) throws IOException {
        return recording.publishedAt(ecosystem, coordinate, version);
    }

    /** The day a coordinate version was last downloaded, or empty if never - the worker's same-day skip uses it. */
    public Optional<Instant> lastDownloaded(String ecosystem, String coordinate, String version) throws IOException {
        return recording.lastDownloaded(ecosystem, coordinate, version);
    }

    /** Pin a coordinate version - mark it force-kept, immune to every retention rule. The pin is a field
     *  of the document's {@code published} section (preserving the publish instant/prerelease), so it evicts with the
     *  version's document instead of dangling as its own {@code pinned/} sidecar. */
    public void pin(String ecosystem, String coordinate, String version) throws IOException {
        pinning.pin(ecosystem, coordinate, version);
    }

    /** Remove a coordinate version's pin, returning it to the retention rules. */
    public void unpin(String ecosystem, String coordinate, String version) throws IOException {
        pinning.unpin(ecosystem, coordinate, version);
    }

    /** The pinned coordinate versions, as {@code ecosystem:coordinate:version} - the ecosystem carried explicitly so
     *  an unpin names it rather than assuming one. */
    public List<String> pins() {
        return pinning.pins();
    }

    /** The pinned coordinate versions, decoded into their parts - so a caller renders or unpins one without
     *  re-parsing a joined string whose coordinate may itself contain the separator. */
    public List<Pin> pinned() {
        return pinning.pinned();
    }

    /** One pinned coordinate version: the ecosystem carried explicitly so an unpin names it rather than assuming one. */
    public record Pin(String ecosystem, String coordinate, String version) {
    }

    /** The immediate child names published under a layout prefix (for the console's tree browse). */
    public List<String> children(String prefix) {
        return browse.children(prefix);
    }

    /** A bounded page of the immediate children under a layout prefix - at most {@code limit}, so a high-fan-out prefix
     *  never materialises the whole directory in heap on one browse request (the {@code /api/browse} bound). */
    public List<String> children(String prefix, int limit) {
        return browse.children(prefix, limit);
    }

    /** A bounded page of the immediate children with the servable-name screen fused in: pages one level, forwards
     *  directory children unconditionally (their own leaves carry the screen), screens each non-folder leaf through the
     *  {@link ServableNames} seam under {@code policy}, and suppresses the reserved {@code quarantine} review subtree at
     *  the root. This is the overload the P-E3 console/REST browse (and the P-E4 ratchet) use so a held or torn leaf
     *  never appears in an enumeration; a child whose probe throws is skipped (fail-closed), so one hostile name in a page
     *  can never fail the whole listing.
     *
     *  <p>Returns a {@link ChildPage}: the screened names AND whether stored children remain past this window. The
     *  truncation flag is the screened-enumeration primitive's own outcome, not a raw child count the caller
     *  re-derives - so a screened-out leaf can never shrink the rendered list below the render cap while the directory
     *  is claimed complete. Ask for exactly the render cap and render {@code names()} as-is. */
    public ChildPage children(String prefix, int limit, ServableNames.Policy policy) throws IOException {
        return browse.children(prefix, limit, policy);
    }

    /** Published paths matching {@code query} - the find-tool for artifacts with no coordinate to search by. See
     *  {@code InventoryBrowse#paths}. */
    public ChildPage paths(String prefix, String query, int limit, ServableNames.Policy policy) throws IOException {
        return browse.paths(prefix, query, limit, policy);
    }

    /** A screened page of immediate children: the {@code names} a listing renders (folders and disclosable leaves, the
     *  quarantine root child and any withheld/torn leaf already dropped), at most the requested window wide, plus
     *  {@code truncated} - whether the bounded screened scan proved stored children remain past the window. Truncation
     *  is the primitive's outcome rather than a post-screen list length, so a withheld or torn leaf never makes an
     *  incomplete directory look complete. */
    public record ChildPage(List<String> names, boolean truncated) {
    }

    /** Whether a published coordinate version may be disclosed by a name-enumeration surface (search, a coordinate
     *  picker, a version index) under {@code policy}. A {@code publish/}-namespace ecosystem is screened through
     *  {@link ServableNames#disclosableVersionFolder} over the store-free {@link ArtifactLayout#paths(String, String)}
     *  folder(s) - never the store-reading overload, so search opens no blob; a blobs-namespace ecosystem is withheld iff
     *  any of its {@link BlobLayout#blobKeys} resolves to a withheld hash (the retroactive sweeps mark every hash of a
     *  held version); an ecosystem <em>no installed format can place at all</em> is screened against the durable,
     *  coordinate-keyed {@link HoldMarkers hold records} instead, because both of those faces are layout-resolved and
     *  neither could be asked - reading that silence as "nothing withholds it" let removing a format module disclose,
     *  by name, every version its sweeps were holding. Both other contracts are unchanged: under an installed
     *  layout a coordinate recorded with no blob stored is still disclosable (the ghost-coordinate contract), and a
     *  version of an ecosystem no installed format serves is still listed, rendered as an orphan, as long as nothing
     *  holds it. Under {@link ServableNames.Policy#HIDE_WITHHELD} this stats no blob at all: it reads only the tiny
     *  quarantine pointers, {@code withheld/<hash>} markers and {@code holds/} records. */
    public boolean disclosable(String ecosystem, String coordinate, String version, ServableNames.Policy policy)
            throws IOException {
        return browse.disclosable(ecosystem, coordinate, version, policy);
    }

    /** Whether a search-index hit - a {@code coordinate:version} display string, the form the Lucene leg returns and the
     *  substring scan builds - may be disclosed under {@code policy}. Resolves the hit's ecosystem by the same bounded
     *  top-level {@code published/} probe the console search uses to place a hit, then screens it through
     *  {@link #disclosable(String, String, String, ServableNames.Policy)}. The {@code coordinate:version} split is
     *  probed at every colon right-to-left (not just the last), so a digest-pinned OCI display {@code <name>:sha256:<hex>}
     *  places on its {@code (<name>, sha256:<hex>)} split and screens rather than mis-splitting to {@code (<name>:sha256,
     *  <hex>)} and leaking (A26-F5); the first split some ecosystem places wins. A hit no installed ecosystem places on
     *  any split is disclosable, since membership is the only truth there (the ghost-coordinate contract). The one eco-resolution the
     *  console {@code RepositoryBrowse} and the REST {@code /api/search} share, so both search surfaces screen a held
     *  {@code coordinate:version} identically (A6-F2) rather than each re-implementing it. Reads
     *  only the small {@code published/} sidecars and, under {@link ServableNames.Policy#HIDE_WITHHELD}, the tiny
     *  quarantine pointers / {@code withheld/<hash>} markers - never an artifact blob.
     *
     *  <p>A colon-less {@code display} (a bare name carrying no {@code :version}) is rejected with
     *  {@link IllegalArgumentException}: this is the {@code coordinate:version} face, and a name-level surface must
     *  screen through {@code ServableNames} instead. Historically a colon-less argument returned {@code true}
     *  unconditionally (the fail-open, where the right-to-left split loop never ran and the method fell
     *  through to the ghost-coordinate {@code return true}); no live caller passes a bare name (every caller supplies a
     *  {@code coordinate + ":" + version} or an already-versioned {@code group:name:version}), so the fail-open default
     *  is closed here rather than left to leak. The colon-BEARING ghost-coordinate contract is preserved: a
     *  {@code coordinate:version} display no installed ecosystem places on any split still discloses (membership is the
     *  only truth there). Accepted residual: a cross-ecosystem {@code coordinate:version} collision resolves
     *  to the first ecosystem whose {@code published/} rows place the split, so two ecosystems that share an identical
     *  {@code coordinate:version} are screened by whichever the probe reaches first. */
    /**
     * Whether a search hit may be disclosed, whichever of the two shapes it is.
     *
     * <p>A {@code coordinate:version} display goes to {@link #disclosableDisplay}; a served request path goes to
     * {@link ServableNames#disclosable}, the name-level face that one refuses to be. The discriminator is the
     * leading {@code /}: a request path always carries one and a {@code coordinate:version} display never does.
     * Sending a path to the display face is not a near miss - it throws on a colon-less argument by design, having
     * once returned {@code true} unconditionally for one, which is the fail-open it was closed against.
     */
    public boolean disclosableHit(String hit, ServableNames.Policy policy) throws IOException {
        return hit.startsWith("/") ? new ServableNames(store).disclosable(hit, policy) : disclosableDisplay(hit, policy);
    }

    public boolean disclosableDisplay(String display, ServableNames.Policy policy) throws IOException {
        if (display.indexOf(':') < 0) {
            throw new IllegalArgumentException("disclosableDisplay is the coordinate:version screening face and requires "
                    + "a coordinate:version display, not the bare name \"" + display + "\": a name-level surface must "
                    + "screen through ServableNames, not this face. A colon-less argument once returned "
                    + "disclosable=true unconditionally, failing open; it now fails closed by throwing.");
        }
        SortedSet<String> ecosystems = ecosystems();
        for (int split = display.lastIndexOf(':'); split >= 0; split = display.lastIndexOf(':', split - 1)) {
            String coordinate = display.substring(0, split);
            String version = display.substring(split + 1);
            for (String ecosystem : ecosystems) {
                if (publishedAt(ecosystem, coordinate, version).isPresent()) {  // a point read, never the version list
                    return disclosable(ecosystem, coordinate, version, policy);
                }
            }
        }
        return true;
    }

    /** The format-neutral {@link ArtifactDescriptor} the owning format's {@link ArtifactLayout} maps a request path
     *  to - the seam the console's artifact detail reads a path's ecosystem/coordinate/version through, from the path
     *  alone (no content read). Empty when no descriptive format claims the path; the returned descriptor may itself
     *  carry no coordinate (a checksum, generated metadata), in which case only its ecosystem is set. */
    public Optional<ArtifactDescriptor> describe(String path) {
        return browse.describe(path);
    }

    /**
     * A coordinate version as the layout owning its ecosystem keys it, or empty when no installed layout can place it.
     *
     * <p>An inspector reads a coordinate out of an artifact as the artifact spells it - a {@code .nuspec}'s
     * {@code <id>Demo</id>}, a wheel's {@code Name: Demo_Pkg} - while the layout serves and keys the same version by
     * its own normal form, lower-cased or otherwise normalised. A fact recorded under the first spelling lands in a
     * version document no read ever resolves to, since every read goes through the layout. So the spelling is asked of
     * the layout: the path it would serve the version at, described back. Store-free on both halves, which is what lets
     * it run inside a screen, before the publish it is screening has linked anything.
     */
    public Optional<Coordinate> canonical(String ecosystem, String coordinate, String version) {
        for (RepositoryFormat format : formats()) {
            if (!(format instanceof BlobLayout layout) || !layout.ecosystem().equals(ecosystem)) {
                continue;
            }
            for (String path : layout.servedPaths(coordinate, version)) {
                Optional<ArtifactDescriptor> described = layout.describe(path);
                if (described.isPresent() && described.get().coordinate() != null
                        && described.get().version() != null) {
                    return Optional.of(new Coordinate(described.get().ecosystem(), described.get().coordinate(),
                            described.get().version()));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Whether this served path is <em>path-addressed</em>: the format serving it has no coordinate concept at all,
     * so nothing will ever describe it to a coordinate and the path is the only name it has.
     *
     * <p>Asked of the FORMAT rather than of {@link #describe}, and the difference is not academic. That face is
     * empty in two unrelated cases - a format claims the path but cannot parse it, and no format claims it at all -
     * so a caller reading "no coordinate came back" as "path-addressed" also catches every malformed path under a
     * layout, and every checksum beside a coordinate. Measured: a rebuild filtered that way indexed a second
     * document for every artifact whose test fixture used a synthetic path, and thirty-one suites said so.
     *
     * <p>A format that IS an {@link ArtifactLayout} or a {@link BlobLayout} owns a coordinate space, so a path of
     * its own that resolves to nothing is malformed rather than coordinate-less. One that is neither - the raw
     * format is the case this exists for - never had a coordinate to lose.
     */
    public boolean pathAddressed(String path) {
        return browse.pathAddressed(path);
    }

    /** The request-path folder a coordinate version occupies, resolved through the owning format's
     *  {@link ArtifactLayout} - the same reverse mapping an eviction uses - so the console's in-repo search can
     *  navigate a coordinate hit into the browse tree at that folder. The first of the format's paths (its primary
     *  layout, ahead of any cross-published mirror); empty when no installed format claims the ecosystem or the
     *  coordinate maps nowhere. Resolves through the store-free {@link ArtifactLayout#paths(String, String)} overload
     *  so this read path never opens an artifact blob. */
    public String locate(String ecosystem, String coordinate, String version) {
        return browse.locate(ecosystem, coordinate, version);
    }

    /** Every served request path a coordinate version currently occupies - the leaf {@code publish/} pointers under
     *  the layout folder(s) the owning format's {@link ArtifactLayout} maps it to, including any cross-published
     *  mirror it recorded - the same reverse mapping an {@link #evict} walks, but read rather than removed. Reads only
     *  the tiny pointers, never an artifact blob; empty when no installed format claims the ecosystem or the coordinate
     *  maps to no live pointer. */
    public List<String> paths(String ecosystem, String coordinate, String version) throws IOException {
        return browse.paths(ecosystem, coordinate, version);
    }

    /**
     * {@link #paths} made three-valued, for the callers that read its emptiness as a <em>fact about the version</em>
     * rather than as a listing. {@link Known.Present} with the served paths some installed format enumerated -
     * possibly none of them, which is then a real answer; {@link Known.Unknown} ({@link Known.Cause#UNINSTALLED}) when
     * nothing could be asked at all, because no installed {@link ArtifactLayout} resolves a layout prefix for the
     * coordinate and no installed {@link BlobLayout} owns its ecosystem. Exactly the line the reconcile sweep's
     * liveness draws: a layout that resolves no path leaves the question unasked the same way an absent one
     * does, and "I could not ask" must never share an outcome with "there is nothing there" - a guard that reads the
     * second from the first releases a hold, or destroys the state the remaining held paths of a version are reviewed
     * against, because a module is absent.
     */
    public Known<List<String>> knownPaths(String ecosystem, String coordinate, String version) throws IOException {
        return browse.knownPaths(ecosystem, coordinate, version);
    }

    /** The retention policy stored for this repository, or empty if none has been set. A stored policy that cannot
     *  be parsed fails loudly (a contained {@link IOException}) rather than reading as "no policy". */
    public Optional<RetentionPolicy> readRetention() throws IOException {
        return retention.readRetention();
    }

    /** Store this repository's retention policy. */
    public void writeRetention(RetentionPolicy policy) throws IOException {
        retention.writeRetention(policy);
    }

    /** Compare-and-set a small pointer under {@link Retries}: re-read the opaque version token and write against it,
     *  so a concurrent writer's conflict is a retry rather than a silently lost update. These pointers (publish time,
     *  last-download, pin, retention policy) are load-bearing, so a lost update is a durable drift and the exhaustion
     *  throws. Package-private so the extracted subsystems commit their small objects through the same fenced write. */
    void writeVersioned(String key, byte[] value) throws IOException {
        Retries.update(store, key, current -> value);
    }

    /** The root of the newest-first release index every publish is recorded into, beside {@link #publishedRoot()}:
     *  a pass or hook that records a publish writes here too and declares it. */
    public static String recentRoot() {
        return RecentReleases.ROOT;
    }

    /** The key-space the published-set enumeration walks: the consolidated {@code meta} documents, whose
     *  {@code published} section is membership, in the {@code <root>/<eco>/<enc(coord)>/<version>} shape. Named here
     *  so an external ecosystem enumeration (the console browse, the attribution export) lists the root the
     *  inventory writes. */
    public static String publishedRoot() {
        return MetadataKey.PREFIX;
    }

    /** One published coordinate version and its publish facts, resolved from a walked {@link #publishedRoot} key. */
    record PublishedAt(String ecosystem, String coordinate, String version, PublishedSection.Facts facts) {
    }

    /** The publish facts of one coordinate version as a point read, with the sweep-window fallback. Package-visible
     *  so {@link LicenseInventory} guards its rollup re-fold on the same "is this a published member" question. */
    Optional<PublishedSection.Facts> publishedFacts(String ecosystem, String coordinate, String version)
            throws IOException {
        return recording.publishedFacts(ecosystem, coordinate, version);
    }

    /** See {@link InventoryRecording#membership}. Package-private so the extracted {@link InventoryReconciler} - the
     *  one sweep that deletes on a non-membership - and the eviction's rollup fold-out judge through the same
     *  three-valued read. */
    Known<PublishedSection.Facts> membership(String ecosystem, String coordinate, String version) throws IOException {
        return recording.membership(ecosystem, coordinate, version);
    }

    /** Whether any version of a coordinate is still a published member - the "is this the last version" test the
     *  per-coordinate health reclamation keys off, after the evicted version's own document/sidecar is gone. */
    boolean anyPublishedVersion(String ecosystem, String coordinate) throws IOException {
        return enumeration.anyPublishedVersion(ecosystem, coordinate);
    }

    @Override
    public Collection<Release> releases() throws IOException {
        return enumeration.releases();
    }

    /** The release a row under the published root records, for the walk consumer that judges rows one at a time:
     *  empty for a key that is not a version's row. */
    public Optional<Release> release(String rowKey) throws IOException {
        return Optional.ofNullable(enumeration.release(rowKey));
    }

    /**
     * Stream every published release, grouped by coordinate as the {@link RepositoryInventory} contract asks -
     * without a walk, a depth-first stream of the {@code published/} key tree (O(depth) memory, grouped by
     * construction); constructed with the shared {@link ArtifactWalk}, this caller's share of the resumable
     * {@code walks/retention} pass instead. A resumed pass deliberately does <em>not</em> re-deliver what a crashed
     * worker already processed, and a pass whose remaining segments a live worker still holds returns without them -
     * the retention sweep's semantics, not a full listing; a caller that needs everything on every call uses
     * {@link #releases()} or a walk-less inventory.
     */
    @Override
    public void releases(ReleaseVisitor visitor) throws IOException {
        enumeration.releases(visitor);
    }

    /**
     * Stream every published release over the caller's share of the resumable, range-segmented
     * {@code walks/<consumer>} pass - the shared-walk enumeration for a scheduled surface that owns a pass of its
     * own (the search-index rebuild) instead of the retention sweep's. Delivery is the walk's, not a full listing:
     * the returned pass says which segments this call saw, so a consumer that must see everything judges
     * {@code complete()} (and its own share of the segments) before acting on the stream.
     */
    public WalkPass releases(ArtifactWalk walk, String consumer, ReleaseVisitor visitor) throws IOException {
        return enumeration.releases(walk, consumer, visitor);
    }

    /**
     * Both of the repository's key spaces over ONE pass: the {@code published/} rows a release is read from, and the
     * {@code publish/} pointers a served request path IS.
     *
     * <p>They are walked together rather than in two passes because a second whole-store enumeration is the most
     * expensive thing a background feature can add, and the walk already takes a list of roots. A consumer that
     * needs both - the search index, whose coordinate documents come from the first and whose path-addressed
     * documents can only come from the second - therefore pays one enumeration, not two.
     *
     * <p>Adding a root to an existing consumer's pass is safe by construction: a pass already in flight resumes on
     * the roots its own manifest recorded and finishes under that plan, and the next generation adopts the caller's.
     * The cost of the change is one pass's delay before the new root is seen, never a partial or mixed pass.
     */
    public WalkPass releases(ArtifactWalk walk, String consumer, ReleaseVisitor releases, ServedPathVisitor paths)
            throws IOException {
        return enumeration.releases(walk, consumer, releases, paths);
    }

    /** Stream every served request path - the enumeration a rebuild uses where no walk is installed to ride. */
    public void servedPaths(ServedPathVisitor visitor) throws IOException {
        enumeration.servedPaths(visitor);
    }

    /** A visitor over served request paths, each the path a client fetches ({@code /raw/notes/x.txt}) rather than
     *  the pointer key that stores it. */
    @FunctionalInterface
    public interface ServedPathVisitor {
        void accept(String requestPath) throws IOException;
    }

    /** Every published coordinate version as its neutral {@code (ecosystem, coordinate, version)} triple, read from the
     *  {@code published/} key tree alone - no publish-time, last-download or pin sidecar is opened. The cheap
     *  enumeration a request path that needs only the coordinates uses instead of {@link #releases()}. */
    public List<Coordinate> coordinates() {
        return enumeration.coordinates();
    }

    /** Stream every published coordinate to {@code visitor} without buffering the whole set - the O(#versions)
     *  enumeration a rollup folds over (the identity digest), so a repository with millions of versions never
     *  materialises them all in heap the way {@link #coordinates()} does. Same membership as the buffered form. */
    public void coordinates(CoordinateVisitor visitor) throws IOException {
        enumeration.coordinates(visitor);
    }

    /** A visitor over the streamed published coordinates of {@link #coordinates(CoordinateVisitor)}, allowed the
     *  per-member store I/O the identity fold does (reading a version's declared-license fingerprint). */
    @FunctionalInterface
    public interface CoordinateVisitor {
        void accept(Coordinate coordinate) throws IOException;
    }

    /** A published coordinate version as its format-neutral triple, without the publish-time / pin metadata a full
     *  {@link Release} carries - the shape a coordinate-only request path needs. */
    public record Coordinate(String ecosystem, String coordinate, String version) {
    }

    /** The published releases of a single coordinate - the sibling versions the console's artifact-detail view lists -
     *  read from just that coordinate's version folder rather than the whole {@link #releases()} tree. Reads only the
     *  tiny sidecars, never an artifact blob; empty when the coordinate has no published version. */
    public List<Release> versions(String ecosystem, String coordinate) throws IOException {
        return enumeration.versions(ecosystem, coordinate);
    }

    /**
     * One bounded page of a coordinate's published versions, in version-key order, resumable from the bare version
     * name the previous page ended on ({@code null} from the top): the face a screen reads through, since a
     * coordinate's version set grows without bound and {@link #versions(String, String)} materialises all of it.
     * Reads at most {@code limit + 1} names and one publish document per version returned.
     */
    /** One coordinate version as a point read - its publish facts, pin and download marker - or empty when it is
     *  not a published member. Never a listing of the coordinate's versions. */
    public Optional<Release> release(String ecosystem, String coordinate, String version) throws IOException {
        return enumeration.release(ecosystem, coordinate, version);
    }

    public ReleasePage versions(String ecosystem, String coordinate, String after, int limit) throws IOException {
        return enumeration.versions(ecosystem, coordinate, after, limit);
    }

    /**
     * The most recently published releases of the repository, newest first, one bounded page at a time - read from
     * the newest-first index a publish writes, never from a walk of the publish facts. A release the index names
     * that no longer has a publish document is skipped; whether it is still served is the caller's disclosure
     * check, which is why a caller asks for a few more than it shows.
     */
    public ReleasePage recent(String after, int limit) throws IOException {
        return enumeration.recent(after, limit);
    }

    /** A bounded page of releases and the key to continue from - {@code null} when the page was the last. */
    public record ReleasePage(List<Release> releases, String next) {
    }

    /**
     * The ecosystems the repository holds a published release of: the first level of the publish facts, a handful
     * of names paged to exhaustion - never a walk of the coordinates beneath them.
     */
    public SortedSet<String> ecosystems() throws IOException {
        return ecosystems(store);
    }

    /** {@link #ecosystems()} over any repository-scoped store - the console's browse asks the same question of the
     *  repository it is showing, and used to answer it with a second copy of this loop and this stride. */
    public static SortedSet<String> ecosystems(ArtifactStore store) throws IOException {
        SortedSet<String> ecosystems = new TreeSet<>();
        Names names = Names.over(store, publishedRoot(), ECOSYSTEM_STRIDE);
        for (String name = names.next(); name != null; name = names.next()) {
            ecosystems.add(name);
        }
        return ecosystems;
    }

    private static final int ECOSYSTEM_STRIDE = 64;

    /** The content hashes a version's blobs-namespace pointers resolve to - each {@code BlobLayout} format's own
     *  pointer keys for the version, read (never deleted) - the set a retroactive withhold marks under the
     *  {@code withheld/<hash>} convention. Empty for a publish-namespace-only ecosystem (Maven) or one whose format is
     *  not installed. */
    public List<String> blobHashes(String ecosystem, String coordinate, String version) throws IOException {
        return enumeration.blobHashes(ecosystem, coordinate, version);
    }

    /**
     * Every content hash a live coordinate version claims: the blobs-namespace hashes its {@link BlobLayout} resolves
     * ({@link #blobHashes}), and the hashes the {@code publish/} pointers of a version served through an
     * {@link ArtifactLayout} (Maven, the module view) name. The withhold-marker backstop judges a stranded marker by
     * this claim, and the blobs-namespace answer alone left a {@code publish/}-served version unable to make it: a
     * marker on its bytes - written for a byte-identical blobs-namespace alias that has since gone - kept it
     * withheld for good, with nothing holding it and nothing an operator could see. Read from the small pointers,
     * never a blob body; a pointer whose body is not a bare content hash claims nothing.
     */
    public List<String> claimedHashes(String ecosystem, String coordinate, String version) throws IOException {
        List<String> hashes = new ArrayList<>(blobHashes(ecosystem, coordinate, version));
        if (!layoutsFor(ecosystem).isEmpty()) {
            for (String path : browse.paths(ecosystem, coordinate, version)) {
                store.readVersioned("publish" + path)
                        .map(pointer -> ServableNames.hash(pointer.content()))
                        .filter(BARE_HASH.asMatchPredicate())
                        .ifPresent(hashes::add);
            }
        }
        return List.copyOf(hashes);
    }

    /** A pointer body that is a content hash - the shape {@code Publication.link} writes. */
    private static final Pattern BARE_HASH = Pattern.compile("[0-9a-f]{64}");

    /** Whether this ecosystem serves its artifacts from the shared {@code blobs/} namespace (an installed
     *  {@link BlobLayout} owns it) rather than only through {@code publish/} pointers - the seam a release/discard
     *  primitive consults to tell a pointer-less blobs-namespace hold from a publish-time Maven hold. */
    public boolean servesFromBlobs(String ecosystem) {
        return enumeration.servesFromBlobs(ecosystem);
    }

    /** Evict a blobs-namespace version's served content on discard: delete every blob pointer key the version holds so
     *  serving {@code 404}s and no later enforce pass re-holds a version with no blobs, and lift the withhold markers on
     *  those hashes. This is the destroy leg a blobs-namespace discard needs - the counterpart of unpublishing a
     *  {@code publish/} release pointer. A no-op for an ecosystem with no installed {@link BlobLayout}. */
    public void discardBlobs(String ecosystem, String coordinate, String version) throws IOException {
        eviction.discardBlobs(ecosystem, coordinate, version);
    }

    /**
     * Destroy a coordinate version: unpublish every pointer it occupies and reap its derived per-version rows.
     *
     * <p><b>Refused, loudly, when this deployment cannot enumerate the version's pointers</b> - no installed
     * {@link ArtifactLayout} resolves a layout prefix for the coordinate and no installed {@link BlobLayout} owns its
     * ecosystem. Which keys a version's pointers live under is layout knowledge and nothing durable records it,
     * so with the owning module absent the unpublish legs silently found nothing while every derived-row delete under
     * them ran: the artifact kept serving with its publish facts, pin, licenses, meta document and hold-override
     * markers destroyed. There is no "do the whole thing including the pointers" to choose here - the pointers are
     * precisely what cannot be named - so the only answers were a half-done destroy and a refusal, and a destroy is the
     * irreversible act. The refusal is a named {@link IOException}: the on-demand sweep answers it to the operator who
     * asked, the scheduled pass logs it per repository and moves on to the next one, and neither leaves a version
     * evicted-but-serving.
     */
    @Override
    public void evict(Release release) throws IOException {
        eviction.evict(release);
    }

    /** Reclaim a re-heatable cached fallback blob under quota/disk pressure while retaining its {@code origin} and
     *  {@code verdict} meta-document sections: the bytes are discarded (pointers
     *  unpublished, the blob garbage-collected) but the audit records survive, so a pull-through can re-heat the entry
     *  (§5). Returns {@code true} when the blob was reclaimed; {@code false} when the version is <b>not</b> re-heatable -
     *  a {@code local-upload}-origin blob is system-of-record and is never cache-evicted, and a version with no origin
     *  record is not a recognised cache entry - in which case nothing is touched. Like {@link #evict}, it is
     *  <b>refused</b> with a named {@link IOException} when no installed format can place the ecosystem: every
     *  {@code false} above is a decision read off a durable record, and folding "I could not tell which pointers this
     *  version occupies" into the same answer would make such an entry silently unreclaimable while this method
     *  reported {@code true} - "bytes reclaimed" - with the format's pointers still standing. */
    public boolean reclaimFallbackCache(String ecosystem, String coordinate, String version) throws IOException {
        return eviction.reclaimFallbackCache(ecosystem, coordinate, version);
    }

    /**
     * The pointer roots a reference-judging pass must read - the {@code publish/} namespace (Maven, the
     * raw layout) plus every installed blobs-namespace format's declared {@link BlobRoots#blobRoots() roots} (npm,
     * PyPI, Cargo, ...). Which namespaces hold serving pointers is layout knowledge {@code GarbageCollector}
     * and {@code RebuildPass} deliberately do not have, so their callers all derive it here, from the one
     * discovered format list - a root missing from this union would make its pointers invisible to the reference scan.
     *
     * <p><b>This union is only ever as complete as the installed format list, so it is not what a DESTRUCTIVE pass may
     * be handed</b>. A blobs-namespace format whose module is off the graph declares no roots here, its
     * pointers are then never read by the mark phase, and the sweep reclaims - deletes - the artifact bytes they still
     * name. Every reclaiming caller therefore takes {@link #pointerRoots(ArtifactStore)}, which says in the same breath
     * whether this deployment can enumerate everything the store holds; a non-reclaiming reader (the rebuild pass,
     * which only re-derives from what it can see) is served correctly by this list.
     */
    /** The roots of the per-coordinate derived rows a walk's {@code DERIVED} family enumerates: the override records
     *  and the pins - what the reconcile's derived-row leg judges. */
    public static List<String> derivedRoots() {
        return List.of(OverrideRecords.ROOT, PINNED);
    }

    public static List<String> pointerRoots() {
        // The one computation of this set, not a second one filtered differently. The two used to be
        // derived independently - this asked the discovered formats which wore BlobRoots, while the collector
        // asked BlobReferences.installed() - and a divergence would have the walk enumerate less than the
        // collector judges, which condemns and then deletes what the walk did not see.
        return BlobReferences.pointerRoots();
    }

    /**
     * The ecosystems this store durably records that no installed format can place - the set behind a
     * {@link #pointerRoots(ArtifactStore)} refusal, exposed so an operator surface can name them and offer the
     * explicit way out. Empty on a healthy deployment.
     */
    public static SortedSet<String> unplaceableEcosystems(ArtifactStore store) throws IOException {
        SortedSet<String> unjudgeable = new TreeSet<>();
        ECOSYSTEMS.scan(store, MetadataKey.PREFIX, ecosystem -> {
            if (!placeable(ecosystem)) {
                unjudgeable.add(ecosystem);
            }
        });
        return unjudgeable;
    }

    /**
     * Forget one ecosystem's durable records - the operator's explicit retirement of data whose format is gone,
     * and the way out of the refusal every reclaiming pass answers an unplaceable ecosystem with. Deletes the
     * ecosystem's slices of the record spaces ({@code meta}, {@code pinned}), after which the ecosystem no longer
     * appears in the published index: the collector judges the repository again, the format's now-unreferenced content
     * blobs are ordinary garbage it reclaims, and the stray pointers and listings that remain are reaped by the format
     * module's own manifest purge.
     *
     * <p>Refused while any installed format still places the ecosystem: forgetting a live ecosystem's records
     * would orphan data a format is actively serving. The caller audits; this only deletes.
     *
     * @return how many record objects were deleted
     */
    /**
     * Refuse an ecosystem an installed format still places, without deleting anything.
     *
     * <p>Separate from the deletion because the two answer to different callers. The retirement itself is long
     * enough to belong on a background pass, but this refusal is the operator's own mistake and has to reach them
     * while they are still looking at the button - reported as a failed background job it would read as though the
     * retirement had been attempted and gone wrong, when in fact it was never allowed to start.
     */
    public void refuseIfPlaceable(String ecosystem) throws IOException {
        if (placeable(ecosystem)) {
            throw new IllegalStateException("Ecosystem '" + ecosystem + "' is refused: an installed format still "
                    + "places its coordinates, so its records are live - disable or remove the format first, or "
                    + "evict the versions through retention instead.");
        }
    }

    public long forgetEcosystem(String ecosystem) throws IOException {
        refuseIfPlaceable(ecosystem);
        long removed = 0;
        for (String root : List.of(MetadataKey.PREFIX, PINNED)) {
            removed += forget(root + "/" + ArtifactStore.segment(ecosystem));
        }
        return removed;
    }

    /** Delete every object under {@code prefix}, in bounded pages; an absent prefix deletes nothing. */
    private long forget(String prefix) throws IOException {
        long removed = 0;
        while (true) {
            List<String> page = new ArrayList<>();
            store.scan(prefix, "", 500, listed -> page.add(listed.key()));
            if (page.isEmpty()) {
                return removed;
            }
            for (String key : page) {
                store.delete(key);
                removed++;
            }
        }
    }

    /**
     * The pointer roots of {@link #pointerRoots()} <em>together with</em> the ecosystems this store durably records
     * that no installed format can place at all - the three-valued form every reclaiming pass must judge from
     * (the liveness rule, applied to the one act that is irreversible).
     *
     * <p>The {@code GarbageCollector} sweeps the whole {@code blobs/} namespace against the roots it is handed;
     * there is no way, through that seam, to spare one root's subtree. So an incomplete root set is not a degraded
     * scan, it is a licence to delete: with a format module absent its pointers are invisible, every blob it serves
     * reads as unreferenced, and the confirming pass deletes the artifact bytes. Deletion is the one unrecoverable
     * act in this product, so the answer must be refusal, the refusal has to be <em>said</em> here, where the layout
     * knowledge is, and it has to be <em>enforced</em> at the deletion - which is why this hands back a
     * {@link Known}{@code <List<String>>} rather than a list plus a flag a caller must remember to read. A
     * {@link Known.Unknown} reaches {@code GarbageCollector.plan}/{@code collect} unchanged and the pass refuses
     * itself, reporting the cause through {@code GcPlan.refusal()}; there is no longer a pre-check for a caller to
     * forget.
     *
     * <p>Judged from the durable record rather than from discovery, exactly as the hold records and the reconcile
     * sweep judge theirs: the published set's own first level ({@link #publishedRoot}) names every ecosystem this
     * repository has content for. An ecosystem no installed format owns is unjudgeable: it may serve perfectly well
     * through a module that is currently uninstalled, and nothing here can name the roots its pointers live under. One
     * bounded first-level listing, no walk.
     */
    public static Known<List<String>> pointerRoots(ArtifactStore store) throws IOException {
        SortedSet<String> unjudgeable = new TreeSet<>();
        String root = MetadataKey.PREFIX;
        // Through the bounded primitive, and a truncated enumeration is refused rather than returned: a short
        // ecosystem list reads as "nothing unaccounted for", which is precisely the answer that lets the sweep
        // delete. The level is one segment per ecosystem, so no upload can grow it and the caps are never near.
        Traversal.Result result = ECOSYSTEMS.scan(store, root, ecosystem -> {
            if (!placeable(ecosystem)) {
                unjudgeable.add(ecosystem);
            }
        });
        if (result.truncated()) {
            throw new IOException("the " + root + "/ ecosystem index did not enumerate whole ("
                    + result.delivered() + " delivered); refusing to answer, because a short ecosystem list reads "
                    + "as 'every root is accounted for' and lets a collection reclaim what it cannot see");
        }
        if (!unjudgeable.isEmpty()) {
            return Known.uninstalled("garbage collection is skipped: no installed format can place the ecosystem(s) "
                    + unjudgeable + ", so their serving pointers are invisible to the reference scan and their "
                    + "artifact bytes would be reclaimed as unreferenced; install the format module(s) again, or "
                    + "purge the ecosystem's data explicitly, before collecting");
        }
        return Known.known(pointerRoots());
    }

    /** The bounds on the published set's ecosystem index - one segment per ecosystem this repository holds content
     *  for, so no upload can grow it. Declared and refused loudly rather than left to a raw level listing, for the
     *  reason {@code HoldMarkers.KINDS} is: a short answer here is a licence to delete. */
    private static final BoundedChildren ECOSYSTEMS = BoundedChildren.bounded();

    /**
     * Whether some installed format can place this ecosystem's coordinates, and so declares the roots their pointers
     * live under: a {@code publish/}-namespace {@link ArtifactLayout} (whose root is always in the union), or a
     * format that both lends blob roots ({@link BlobReferences}) and says which ecosystem those coordinates are
     * ({@link EcosystemLayout}) - matched on those two rather than on {@link BlobLayout}, so a roots-only format
     * (declared, deliberately, without coordinate-scoped enforcement) counts as placeable.
     *
     * <p><b>Decided 2026-09-10, and the deferral it replaces was asking for something not expressible.</b> This
     * used to be exactly the union's own filter: {@link #pointerRoots()} read its blob roots off {@link BlobRoots}
     * too. The union now reads them off {@link BlobReferences}, the one computation, and the note here
     * said the fix was to widen this test to {@code BlobReferences} as well.
     *
     * <p>It is not, because <b>{@code BlobReferences} carries no ecosystem</b>. It is the blob-lending seam and
     * nothing more; {@code ecosystem()} arrives with {@link EcosystemLayout}, which {@link BlobRoots} extends and a
     * bare lender does not. There is nothing to compare an ecosystem against on a plain {@code BlobReferences}, so
     * the stated widening could not have been written.
     *
     * <p>The case it worried about is also unreachable, for a reason worth writing down rather than re-deriving:
     * the one root-lending format that does not wear {@code BlobRoots} - the {@code OciFormat} - declares no
     * ecosystem <em>at all</em>. Nothing it publishes records one, so no ecosystem of its arrives in the set this
     * test is asked about, and there is no refusal for it to cause.
     *
     * <p>What is real is the shape of a <em>future</em> format that lends roots and declares an ecosystem without
     * being a {@code BlobRoots}: its roots would be scanned while its ecosystem read as unplaceable, and the
     * collector would refuse over content it can in fact account for. So the test matches on exactly the two
     * things it means - it lends roots, and it says which ecosystem those coordinates are - which is strictly
     * wider than {@code BlobRoots} (that interface is their conjunction) and admits nothing whose pointers the
     * scan does not already enumerate. A roots-only format still counts as placeable, deliberately, as before.
     */
    private static boolean placeable(String ecosystem) {
        if (!layoutsFor(ecosystem).isEmpty()) {
            return true;
        }
        for (RepositoryFormat format : formats()) {
            if (format instanceof BlobReferences && format instanceof EcosystemLayout declared
                    && declared.ecosystem().equals(ecosystem)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a pointer's content is the lower-case SHA-256 hex a blob pointer names - the only shape carried into
     *  a removal descriptor's blob identity, so a format's small timestamp or revision marker under the same root never
     *  masquerades as a hash. Package-private so the extracted subsystems test a pointer the same way. */
    static boolean hash(String value) {
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

    /**
     * Rebuild the publish facts from the {@code publish/} pointer tree in both directions, so a crash that left derived
     * state drifting converges on the next sweep. The forward leg recreates a missing section from the owning format's
     * {@link ArtifactLayout} descriptor (timestamped at {@code now}, the conservative publish instant); the reverse leg
     * removes an orphan whose coordinate has no live pointer left, and the same orphan rule then sweeps the derived
     * per-version key spaces. Reads only the tiny pointers and sidecars, never an artifact blob, and commits through the
     * store's compare-and-set. Idempotent. Each leg rides the shared {@link ArtifactWalk} as its own pass; the returned
     * counts are what <em>this</em> call restored and removed. The sweep itself lives in {@link InventoryReconciler},
     * which this method delegates to before healing the rollup identity from the converged set.
     */
    /** The reconciler, for the walk consumer that judges one key at a time. */
    InventoryReconciler reconciler() {
        return reconciler;
    }

    public Reconciliation reconcile(ArtifactWalk walk, Instant now) throws IOException {
        Reconciliation reconciliation = reconciler.reconcile(walk, now);
        // §5 self-heal: with the published/ and licenses/ spaces converged, recompute the rollup identity from that
        // truth, so any drift from a lost incremental fold (a crash between a sidecar write and its fold, or a
        // double-applied concurrent one) is repaired - the identity converges on the same schedule the sidecars do.
        rebuildIdentity();
        return reconciliation;
    }

    /** The repository's rolled-up inventory identity as a lowercase-hex digest - the XOR of a member digest over every
     *  published coordinate version, each member folding that version's declared-license fingerprint. A single O(1)
     *  small-object read a whole-repository export's ETag derives from, so an {@code If-None-Match} revalidation of the
     *  SBOM / attribution {@code NOTICE} answers {@code 304} BEFORE the O(#versions) coordinate walk that assembles the
     *  document (§4/§7). Built once, lazily, when no accumulator exists yet, then maintained incrementally by
     *  publish / eviction / license-record and rebuilt authoritatively by {@link #reconcile}. */
    public String identity() throws IOException {
        Optional<byte[]> current = identity.current();
        byte[] accumulator = current.isPresent() ? current.get() : rebuildIdentityOnce();
        return HexFormat.of().formatHex(accumulator);
    }

    /** The lazy rebuilds in flight in this process, one per store subspace, so that readers arriving while one is
     *  under way share it rather than each streaming the coordinate set again. */
    private static final SingleFlight<Object, byte[]> REBUILDING = new SingleFlight<>();

    /**
     * {@link #rebuildIdentity()} single-flighted per store: the first reader to find the rollup absent rebuilds it and
     * every reader that arrives while it does waits for that rebuild's accumulator instead of starting its own.
     * Nothing did this before, and the identity-rebuild canary measured what that cost: fifty conditional reads
     * arriving at once against an absent rollup each streamed the whole coordinate set, fifty rebuilds' worth of
     * store operations for one answer. A rebuild that fails hands its failure to the readers that waited on it; a
     * rebuild that outlives the stale horizon leaves a waiting reader to rebuild for itself.
     */
    private byte[] rebuildIdentityOnce() throws IOException {
        return switch (REBUILDING.run(store.identity(), this::rebuildIdentity,
                Duration.ofMinutes(InventoryIdentity.STALE_MINUTES))) {
            case SingleFlight.Led<byte[]> led -> led.value();
            case SingleFlight.Followed<byte[]> followed -> followed.value();
            case SingleFlight.Failed<byte[]> failed -> throw failure(failed.failure());
            case SingleFlight.Overdue<byte[]> _ -> rebuildIdentity();
        };
    }

    /** What a reader that waited on another reader's rebuild is told when that rebuild failed: its own
     *  {@link IOException}, or the failure wrapped as one. */
    private static IOException failure(Throwable shared) {
        return shared instanceof IOException io ? io
                : new IOException("the inventory identity rebuild this reader waited on failed", shared);
    }

    /** How many attempts a rebuild makes before settling for the walk alone, and therefore how many walks it may
     *  make: an attempt is lost when a fold exhausted its retries and dropped the stamped rollup under it, when a
     *  peer judged the stamp stale and took it over, or when a fold handed the walk a member while it ran.
     *
     *  <p>It bounds the WALKS rather than the claims, which is the expensive thing: one walk is a read per member,
     *  33,691 of them measured over the slowest emulator. Written first as two nested loops - rounds outside,
     *  walks within a round - it bounded neither, and a rebuild meeting both kinds of loss could walk ten times
     *  where the shape before the handoff counter walked four. One budget spent by whichever loss occurs keeps the
     *  worst case where it was. */
    private static final int REBUILD_ROUNDS = 3;

    private static final System.Logger REBUILD_LOGGER = System.getLogger(StoreRepositoryInventory.class.getName());

    /**
     * Recompute the rollup accumulator from the live published set and store it - the one-time O(#versions) fold the
     * lazy {@link #identity} read runs on an absent accumulator, and the authoritative recompute {@link #reconcile}
     * runs to heal any incremental drift. Reads each version's publish facts and its declared-license section in one
     * document read, never an artifact blob.
     *
     * <p>Publishes keep landing while the walk runs, and each member is folded exactly once: the rebuild
     * {@linkplain InventoryIdentity#begin stamps} the rollup with a boundary instant, the walk folds every member
     * published at or before it, a publish after it folds itself into the stamped object, and the
     * {@linkplain InventoryIdentity#settle settle} combines the two under compare-and-set. Before this a rebuild in
     * flight was a window in which every fold was a no-op - the rollup was absent - and the walk stored what it had
     * seen, so a publish landing during the walk was folded by nobody until the next reconcile; the identity-drift
     * canary's second leg drops the rollup in the middle of a storm and measured exactly that. A round the storm
     * defeats (an exhausted fold dropped the stamped object, a peer took it over) is claimed again, and the walk is
     * run again when a fold declines a member to it WHILE it is enumerating - the classification is on the member's
     * publish instant and the walk's coverage is on where the member's key sorts, so neither half can say afterwards
     * whether that member was folded, and a second walk over the same boundary, replacing the first's digest rather
     * than combining with it, covers it for certain. The {@linkplain InventoryIdentity#handedOver handoff counter}
     * read before the walk and required unchanged at the settle is what makes that case visible;
     * {@code InventoryIdentity} carries the argument.
     *
     * <p>Both losses spend from ONE budget of {@value #REBUILD_ROUNDS} attempts, so a rebuild walks at most that
     * many times whatever mix of them it meets; after that the walk's own digest is stored as it stands, a bounded
     * drift the next reconcile heals, and the log says so. A rebuild already in flight on a peer is waited for,
     * not duplicated.
     */
    public byte[] rebuildIdentity() throws IOException {
        InventoryIdentity.Rebuild claim = null;
        for (int attempt = 0; attempt < REBUILD_ROUNDS; attempt++) {
            if (claim == null) {
                Instant now = Clocks.now();
                Optional<InventoryIdentity.Rebuild> begun = identity.begin(now);
                if (begun.isEmpty()) {
                    Optional<byte[]> theirs = identity.await(now);
                    if (theirs.isPresent()) {
                        return theirs.get();
                    }
                    continue;
                }
                claim = begun.get();
                identity.grace();                        // once per claim: the grace is the boundary's, not a walk's
            }
            // Read what the rebuild has been handed BEFORE walking, so a decline that lands while the walk runs is
            // a different value at the settle. Empty means the stamped object is no longer this rebuild's - an
            // exhausted fold dropped it, or a peer judged it stale - so the next attempt claims again.
            Optional<Integer> handed = identity.handedOver(claim);
            if (handed.isEmpty()) {
                claim = null;
                continue;
            }
            Optional<byte[]> settled = identity.settle(claim, walkMembers(claim.boundary()), handed.get());
            if (settled.isPresent()) {
                return settled.get();
            }
        }
        byte[] walked = walkMembers(null);
        identity.set(walked);
        REBUILD_LOGGER.log(System.Logger.Level.WARNING, "the inventory identity rebuild lost " + REBUILD_ROUNDS
                + " attempts to concurrent publishes - its stamped object was dropped or taken over, or every walk "
                + "it made was handed a member while it ran; the walk's digest is stored as it stands and the next "
                + "reconcile heals what it misses");
        return walked;
    }

    /** The XOR of the member digest over every published version - all of them when {@code boundary} is null,
     *  otherwise those published at or before it, the rest being folded by their own publishes - streamed rather than
     *  buffered: a repository with millions of published versions would otherwise materialise every member in heap on
     *  the lazy {@code If-None-Match} revalidation path this backs. The fold holds only the fixed-width accumulator. */
    private byte[] walkMembers(Instant boundary) throws IOException {
        byte[] accumulator = new byte[InventoryIdentity.WIDTH];
        // Two counters and nothing else. The walk is one half of the never-both-never-neither invariant - it folds
        // a member published at or before the boundary and declines one published after it, on the belief that the
        // member's own publish folded that one - and a member lost between the two halves is lost in silence.
        //
        // What they answer is WHERE to look, which is worth more than it costs. The enumeration saw folded+declined
        // members; a scenario that published a known number and finds fewer here knows the loss is upstream of this
        // classification entirely, in what the enumeration delivered. One that finds them all accounted for and
        // still reads a wrong rollup knows the classification or the settle is where to look, and can stop
        // suspecting the fold.
        //
        // Counted rather than collected, and that distinction is the whole reason this exists. Collecting the
        // declined digests - even as raw bytes, even formatted once at the end - suppressed the race it was
        // measuring: the mismatch went from about one run in four to none in fifty, and came back when the probe
        // was removed. Two int increments do not perturb the window; a list that grows inside it does.
        int[] seen = {0, 0};
        enumeration.members(member -> {
            if (boundary != null && member.published().isAfter(boundary)) {
                seen[1]++;
                return;                                  // folded by its own publish into the stamped rollup
            }
            seen[0]++;
            byte[] digest = InventoryIdentity.member(member.ecosystem(), member.coordinate(), member.version(),
                    LicenseSection.fingerprintOf(member.licenses()));
            for (int index = 0; index < accumulator.length; index++) {
                accumulator[index] ^= digest[index];
            }
        });
        REBUILD_LOGGER.log(System.Logger.Level.DEBUG, () -> "the identity walk folded " + seen[0]
                + " members and declined " + seen[1] + " past the boundary " + boundary
                + " to their own publishes; the enumeration delivered " + (seen[0] + seen[1]));
        return accumulator;
    }

    /** One published version as the identity walk reads it: the triple, its publish instant and the declared-license
     *  set recorded for it (empty when none has been), all from one document read. */
    record Member(String ecosystem, String coordinate, String version, Instant published,
                  Optional<List<LicenseInventory.Declared>> licenses) {
    }

    @FunctionalInterface
    interface MemberVisitor {
        void accept(Member member) throws IOException;
    }

    /** The outcome of a {@link #reconcile} pass: how many missing sidecars were rebuilt from live pointers, how
     *  many orphan sidecars (whose pointers are gone) were removed, and how many derived rows (download markers,
     *  license records, hold overrides) of no-longer-published versions were swept with them. */
    public record Reconciliation(int restored, int removed, int derived) {
    }

    /**
     * Recompute every browse folder's total subtree size - the sum of the recorded sizes of the artifact blobs
     * published anywhere beneath it - and commit each as a small cached roll-up object, so the console's browse reads
     * a folder's size with one direct key lookup ({@link #subtreeSize}) instead of re-walking the tree on every
     * request. Returns the whole repository's total. The retention sweep calls this after garbage collection so the
     * cached sizes reflect the post-sweep state; nothing recomputes on a read. The subsystem itself lives in
     * {@link SubtreeSizeRollUp}, which this method delegates to.
     */
    public long rollUpSizes() throws IOException {
        return rollUp.rollUpSizes();
    }

    /** The cached rolled-up subtree size of a browse folder - the sum of the sizes of the artifacts published beneath
     *  it, as last computed by {@link #rollUpSizes} - or empty when no roll-up has been computed for it yet. A single
     *  direct key read against a small object: a browse never recomputes the tree. Delegates to
     *  {@link SubtreeSizeRollUp}. */
    public OptionalLong subtreeSize(String path) throws IOException {
        return rollUp.subtreeSize(path);
    }

    /** Every installed format that maps this ecosystem's coordinates back to layout paths, matched on the format's
     *  own {@link ArtifactLayout#ecosystem()}. Package-private so the extracted subsystems judge pointer liveness
     *  through the same discovered-format lookup.
     *
     *  <p><b>All of them, unioned - an ecosystem is a vocabulary rather than an owner.</b> The value a format
     *  declares is the name a vulnerability database uses for a coordinate space, not the identity of a layout, so
     *  several installed formats may legitimately declare one: an Ivy repository and a Maven one both address
     *  {@code org:name:revision} and are both {@code Maven} to OSV. The union is exact rather than approximate
     *  because they map the same coordinate and the same version.
     *
     *  <p>This javadoc used to say the opposite - that a composition with two such formats "refuses to start
     *  ({@code RepositoryFormat.installed})" and that "at most one format can match" - and offered that as the
     *  reason taking a single match was safe. There is no such check in {@code installed}, and there never was one
     *  that this could have relied on; the method has returned a list for as long as it has had this name. The
     *  rationale outlived the design it was defending, which is worth knowing because of what it was defending:
     *  the paths returned here are what an eviction DELETES under, so a discovery-order winner would let two nodes
     *  sweep one store differently. Unioning is what makes that impossible, not a start-up refusal. */
    static List<ArtifactLayout> layoutsFor(String ecosystem) {
        List<ArtifactLayout> layouts = new ArrayList<>();
        for (RepositoryFormat format : formats()) {
            if (format instanceof ArtifactLayout layout && layout.ecosystem().equals(ecosystem)) {
                layouts.add(layout);
            }
        }
        return List.copyOf(layouts);
    }

    /**
     * The served request paths a blobs-namespace coordinate version <b>would</b> occupy, derived from the coordinate
     * alone with no store read at all - {@link BlobLayout#servedPaths(String, String)} routed through the one
     * discovery this class owns. Empty when no installed {@link BlobLayout} owns the ecosystem, or when the format
     * does not derive its served paths purely (the default, and the honest answer for a format whose path carries a
     * recorded filename rather than a computed one).
     *
     * <p>It answers a different question from {@link #paths}, and the difference is the whole point:
     * {@code paths} says where a version <em>is</em> served and therefore reads pointers, while this says where the
     * version an in-flight publish is about is <em>going</em> to be served. The gate needs the second at screen time,
     * before any layout has run, so a hold it records for a format whose coordinate lives inside the artifact can be
     * keyed on the package rather than on the one push endpoint every push of that format shares.
     */
    public static List<String> plannedPaths(String ecosystem, String coordinate, String version) {
        if (ecosystem == null || coordinate == null || version == null) {
            return List.of();
        }
        List<String> planned = new ArrayList<>();
        for (BlobLayout layout : blobLayoutsFor(ecosystem)) {
            planned.addAll(layout.servedPaths(coordinate, version));
        }
        return List.copyOf(planned);
    }

    /** The installed format that owns this ecosystem and stores its artifacts in the shared {@code Blobs} namespace
     *  (npm, PyPI, Cargo, ...), matched on the format's own {@link BlobLayout#ecosystem()}. Package-private so the
     *  extracted subsystems share the same blobs-namespace liveness lookup. At most one format can match, for the
     *  reason {@link #layoutsFor} gives. */
    /** Every installed blobs-namespace layout, whatever ecosystem it declares - the enumeration a repair that works
     *  back from a stored pointer needs, since the ecosystem is what it is trying to find out. */
    static List<BlobLayout> blobLayouts() {
        List<BlobLayout> layouts = new ArrayList<>();
        for (RepositoryFormat format : formats()) {
            if (format instanceof BlobLayout blobLayout) {
                layouts.add(blobLayout);
            }
        }
        return List.copyOf(layouts);
    }

    static List<BlobLayout> blobLayoutsFor(String ecosystem) {
        List<BlobLayout> layouts = new ArrayList<>();
        for (RepositoryFormat format : formats()) {
            if (format instanceof BlobLayout blobLayout && blobLayout.ecosystem().equals(ecosystem)) {
                layouts.add(blobLayout);
            }
        }
        return List.copyOf(layouts);
    }

    /** The pin index's root: one marker per pinned version, so the pins are enumerated from this small namespace
     *  rather than by reading a flag out of every document. This class composes every key of it and is the only place
     *  its spelling is written; the reconcile sweep that reaps a stale marker and the manifest that declares the root
     *  name this constant, because a reaper composing its own spelling reaps a key no writer wrote. */
    static final String PINNED = "pinned";

    /** Package-private so the extracted subsystems read and write a marker under the identical key. */
    static String pinnedKey(String ecosystem, String coordinate, String version) {
        return PINNED + "/" + ArtifactStore.segment(ecosystem) + "/" + encode(coordinate)
                + "/" + ArtifactStore.segment(version);
    }

    /** Encode a coordinate to a single path segment, so a {@code group:artifact} or a scoped package name never splits
     *  the {@code <ecosystem>/<coordinate>/<version>} key. The ecosystem and version segments are instead validated
     *  traversal-free ({@link ArtifactStore#segment}) where the keys are built. Package-private so the extracted
     *  subsystems key their objects and decode their sidecar coordinates the same way. */
    static String encode(String coordinate) {
        return URLEncoder.encode(coordinate, StandardCharsets.UTF_8);
    }

    public static String decode(String segment) {
        return URLDecoder.decode(segment, StandardCharsets.UTF_8);
    }

    /** A visitor over the leaf keys of a subtree {@link #walk}, allowed to do the per-leaf store I/O a sweep needs. */
    @FunctionalInterface
    interface KeyVisitor {
        void visit(String leaf) throws IOException;
    }

    /** The bounds the sidecar sweeps descend a subtree under. Every caller here is a <em>complete</em> enumeration -
     *  the release list, the coordinate census, the pin sweep: a key it does not report is a version that stops being
     *  retained, evicted or pinned, so a short answer would be a wrong one rather than a page of a right one. The
     *  entry cap is therefore only a per-call continuation, followed to exhaustion by {@link #walk}, and what really
     *  bounds the sweep is the step budget - one {@link ArtifactStore#exists} probe per opened node - which raises a
     *  named {@link build.jenesis.repository.walk.TraversalException} instead of answering short. Depth stays at the
     *  primitive's {@link ArtifactStore#MAX_SEGMENTS} default, so a key deeper than any the store accepts
     *  fails <em>by name</em> rather than being skipped.
     *
     *  <p>It pages at {@link BoundedChildren#DRAIN_PAGE} rather than the primitive's default, because every caller
     *  here drains and a filesystem cannot seek a directory: each page rescans the container, so a sweep of N names
     *  costs N/page scans of N. That is not theoretical at the sizes this sweep reaches - the level under
     *  {@code meta/<ecosystem>} holds one child per coordinate, so a repository with a million coordinates is a
     *  million-entry directory, and the OCI tag canary measured the identical shape at 834 s a thousand names to a
     *  page against 188 s at ten thousand. */
    private static final PagedTreeWalk SIDECARS =
            PagedTreeWalk.bounded().steps(5_000_000).page(BoundedChildren.DRAIN_PAGE);

    /** Walk the key subtree under {@code root} in path order, streaming each stored leaf key to {@code visitor} as it
     *  is reached rather than materialising the whole key set into a {@code List} first - the shared bounded,
     *  iterative, paged descent ({@link PagedTreeWalk}), so a wide level is paged rather than listed whole and
     *  an attacker-shaped key depth cannot overflow a thread stack. The per-call entry cap is a continuation this
     *  method follows to exhaustion, so the sweep stays complete; the step and depth caps have no continuation and
     *  surface as a {@link build.jenesis.repository.walk.TraversalException}. Package-private so the extracted
     *  enumeration and pin subsystems stream the same subtree. */
    void walk(String root, KeyVisitor visitor) throws IOException {
        String cursor = null;
        while (true) {
            Traversal.Result result = SIDECARS.walk(store, root, cursor, visitor::visit);
            if (result.exhausted()) {
                return;
            }
            cursor = result.cursor().orElseThrow();
        }
    }
}
