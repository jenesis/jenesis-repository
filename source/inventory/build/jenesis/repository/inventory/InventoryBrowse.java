package build.jenesis.repository.inventory;

import module java.base;

import build.jenesis.repository.store.Publication;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Known;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.walk.ScreenedNames;
import build.jenesis.repository.walk.Traversal;

/**
 * The browse / describe subsystem extracted from {@link StoreRepositoryInventory}: the read-only navigation the two
 * consoles' tree browse and artifact-detail views use - the immediate {@code children} under a layout prefix, the
 * format-neutral {@link ArtifactDescriptor} a request path maps to ({@link #describe}), the layout folder a coordinate
 * version occupies ({@link #locate}), and every served request path it currently occupies ({@link #paths}). All are
 * pure reverse mappings through the owning format's {@link ArtifactLayout}/{@link BlobLayout}, reading only the tiny
 * pointers, never an artifact blob. The facade owns the seam - those methods delegate here - and this class shares the
 * facade's discovered-format list and layout lookups rather than duplicating them.
 */
final class InventoryBrowse {

    private final ArtifactStore store;

    /** The one servable-name enumeration screen, composing the facade's {@link build.jenesis.repository.store.Publication}
     *  (so the withheld chain is the deployment's interceptor list) and the {@code withheld/<hash>} marker convention -
     *  the seam the coordinate {@link #disclosable} face and the screened {@link #children(String, int, ServableNames.Policy)}
     *  page route their disclosure decisions through, so a listing and a download can never disagree on what is held. */
    private final ServableNames servableNames;

    InventoryBrowse(ArtifactStore store, ServableNames servableNames) {
        this.store = store;
        this.servableNames = servableNames;
    }

    /** The immediate child names published under a layout prefix (for the console's tree browse). */
    List<String> children(String prefix) {
        return store.list("publish" + prefix);
    }

    /** A bounded page of the immediate child names under a layout prefix - at most {@code limit}, read through the
     *  store's seek-resume paging primitive rather than materialising the whole directory, so a high-fan-out prefix (a
     *  coordinate with hundreds of thousands of timestamped versions) can never build a millions-entry list in heap on
     *  one browse request. The caller reads one past its render cap to detect - and flag - truncation. */
    List<String> children(String prefix, int limit) {
        List<String> names = new ArrayList<>();
        store.page("publish" + prefix, "", limit, names::add);
        return names;
    }

    /**
     * Whether a child node is a container - <b>one child answers it</b>, and the probe stops there.
     *
     * <p>It used to be {@code !store.isEmpty(child)}: a whole-namespace listing to answer an emptiness
     * question, run once per row of the page. A browse of a folder holding twenty coordinates listed twenty
     * namespaces to draw twenty folder icons, and a coordinate with ten thousand versions cost ten thousand names to
     * answer {@code true}. That is the METADATA -&gt; MATERIALISED transition the unsafe-API ratchet's first leg is
     * written to catch, hidden where that leg cannot look: inside a lambda, in an implementation.
     */
    private boolean isContainer(String child) {
        boolean[] any = {false};
        store.page(child, "", 1, _ -> any[0] = true);
        return any[0];
    }

    /** A bounded page of the immediate child names with the servable-name screen fused in - see
     *  {@link StoreRepositoryInventory#children(String, int, ServableNames.Policy)}. Drives the screened
     *  enumeration ({@link ScreenedNames#paths}) over the {@code publish/} pointer namespace, so the listing and the
     *  screen are one call rather than a page loop that could be refactored apart from its filter: a directory child
     *  forwards unconditionally (its own leaves carry the screen), the {@code quarantine} root child is suppressed, and
     *  a non-folder leaf forwards only when the seam judges its served request path disclosable under {@code policy}.
     *
     *  <p>The bounds ARE the render window: the scan examines at most {@code limit + 1} stored children in pages of
     *  that same width - so one store round-trip settles a window that is short (drained) or full (one child proves
     *  more remain) - and the take cap delivers at most {@code limit} <em>disclosable</em> names, spending the scan's
     *  one spare examination on a screened-out child so the window still renders full. Truncation is therefore the
     *  primitive's own outcome ({@link Traversal.Result#truncated()}) rather than a raw child count the caller
     *  re-derives, and a withheld/torn leaf (or the suppressed quarantine child) can never shrink the rendered list
     *  below the cap while the directory is reported complete.
     *
     *  <p>A hostile child whose probe throws unchecked is contained by the seam and judged undisclosable, so one bad
     *  name can never fail the page; a store outage (a checked {@link IOException}) fails the whole enumeration rather
     *  than rendering a listing that silently lost names.
     *
     *  <p>On the filesystem store a page is one scan of the folder's siblings whatever its width - the store cannot
     *  seek a directory; the bound is stated at {@code FilesystemArtifactStore.pageListed} - so this page is its
     *  window: one scan per request, the same over a million siblings as over ten. A walk that drains a level pays
     *  one scan per page and takes {@link BoundedChildren#DRAIN_PAGE}; a page that renders one window has nothing
     *  to gain from a wider one. */
    StoreRepositoryInventory.ChildPage children(String prefix, int limit, ServableNames.Policy policy)
            throws IOException {
        String parent = prefix == null ? "" : prefix;
        List<String> names = new ArrayList<>();
        Traversal.Result result = ScreenedNames.paths(servableNames, policy)
                .containers(this::isContainer)
                .scanning(BoundedChildren.bounded().entries(limit + 1).page(limit + 1))
                .take(limit)
                .scan(store, ServableNames.PUBLISHED + parent, (name, _) -> names.add(name));
        return new StoreRepositoryInventory.ChildPage(names, result.truncated());
    }

    /**
     * Published PATHS under {@code prefix} whose name contains {@code query} - the operator's find-tool for the
     * artifacts that have no coordinate to search by.
     *
     * <p>The console's search reads the COORDINATE inventory, which is the right index for a package: a name, a
     * version, a licence facet. A raw upload has none of that - a raw path IS the address - so it was found by browse
     * and by nothing else, and an operator hunting an installer by name got an empty page with no hint that the
     * artifact was sitting one folder away. That is the gap this closes, and it closes it for both deployments:
     * the walk runs whether or not a search index is installed, so an indexed registry and a Lucene-less one answer
     * the same question the same way.
     *
     * <p>Bounded twice over, because this is a tree walk on a request path: at most {@code limit} hits are kept and
     * at most {@code MAX_VISITED} entries are examined, and reaching either reports truncation rather than
     * presenting a clamped list as the whole match set. Screened under {@code policy} at every level, so a held
     * artifact is no more findable here than it is in a listing.
     */
    StoreRepositoryInventory.ChildPage paths(String prefix, String query, int limit, ServableNames.Policy policy)
            throws IOException {
        List<String> hits = new ArrayList<>();
        boolean[] truncated = {false};
        int[] visited = {0};
        walkPaths(prefix == null ? "" : prefix, query, limit, policy, hits, truncated, visited);
        return new StoreRepositoryInventory.ChildPage(hits, truncated[0]);
    }

    /** How many published names one path search may examine - the bound that keeps a search over an enormous
     *  repository a bounded read rather than a full enumeration per request. */
    private static final int MAX_VISITED = 20_000;

    private void walkPaths(String prefix, String query, int limit, ServableNames.Policy policy,
                           List<String> hits, boolean[] truncated, int[] visited) throws IOException {
        if (hits.size() >= limit || visited[0] >= MAX_VISITED) {
            truncated[0] = true;
            return;
        }
        List<String> children = new ArrayList<>();
        Traversal.Result page = ScreenedNames.paths(servableNames, policy)
                .containers(this::isContainer)
                .scan(store, ServableNames.PUBLISHED + prefix, (name, _) -> children.add(name));
        if (page.truncated()) {
            truncated[0] = true;
        }
        for (String child : children) {
            visited[0]++;
            // Always slash-joined: PUBLISHED is a bare prefix, and browse's own paging passes a parent that already
            // carries its leading separator. Composing without one scans "publishraw" and finds nothing.
            String path = prefix + "/" + child;
            if (isContainer(ServableNames.PUBLISHED + path)) {
                walkPaths(path, query, limit, policy, hits, truncated, visited);
            } else if (query.isEmpty() || child.contains(query) || path.contains(query)) {
                if (hits.size() >= limit) {
                    truncated[0] = true;
                    return;
                }
                hits.add(path);
            }
            if (visited[0] >= MAX_VISITED) {
                truncated[0] = true;
                return;
            }
        }
    }

    /** Whether a published coordinate version may be disclosed by a name-enumeration surface under {@code policy} - see
     *  {@link StoreRepositoryInventory#disclosable(String, String, String, ServableNames.Policy)}. A
     *  {@code publish/}-namespace ecosystem (Maven, the test layout) is screened through
     *  {@link ServableNames#disclosableVersionFolder} over the STORE-FREE {@link ArtifactLayout#paths(String, String)}
     *  folder(s), so search never opens a blob; a blobs-namespace ecosystem (npm/PyPI/NuGet/...) is withheld iff any of
     *  its {@link BlobLayout#blobKeys} resolves to a withheld hash (the sweeps mark every hash of a held version); an
     *  ecosystem no installed layout can place at all falls back to the durable, coordinate-keyed
     *  {@link HoldMarkers hold records}, because neither of those faces could be asked. Bounded small-object
     *  reads only, never a blob-content open. */
    boolean disclosable(String ecosystem, String coordinate, String version, ServableNames.Policy policy)
            throws IOException {
        List<ArtifactLayout> layouts = StoreRepositoryInventory.layoutsFor(ecosystem);
        List<BlobLayout> blobLayouts = StoreRepositoryInventory.blobLayoutsFor(ecosystem);
        if (layouts.isEmpty() && blobLayouts.isEmpty()) {
            // Both faces below are layout-resolved: the publish/-namespace one needs the version FOLDER to probe its
            // /quarantine pointer and withheld leaves, the blobs-namespace one needs the version's content HASHES to
            // probe the withheld/<hash> markers. With no installed format for the ecosystem neither question could be
            // asked, and this used to read that silence as "nothing withholds it" and disclose the name - so removing a
            // format module published, by name, every version its enforcement sweeps were holding, through search, the
            // console browse, the /api/lifecycle listing and the forwarding queue. That is a disclosure, not a
            // bookkeeping loss: a withhold that leaks cannot be undone by reinstalling the module.
            //
            // The screen falls back to the one statement of a hold that survives a format's absence: the durable
            // holds/<kind>/<eco>/<coord>/<ver> records, which are keyed by the coordinate and answer with no format,
            // no provider and no discovery at all (and the reason HoldMarkers owns that space here). So a
            // retroactively held version stays withheld, and the orphaned-format contract is kept exactly as the owner
            // asked for it: a version of an ecosystem no installed format serves is still LISTED - rendered as an
            // orphan, named as such - as long as nothing holds it. Membership stays the only truth about EXISTENCE
            // there; it was never the answer to "may this name be disclosed".
            return coordinateKeyedDisclosable(ecosystem, coordinate, version);
        }
        // publish/-namespace face: the version folder is undisclosable iff it is held (a /quarantine<folder> pointer or a
        // withheld leaf). The store-free paths() overload is used deliberately - a search must not open a blob.
        boolean examined = false;
        for (ArtifactLayout layout : layouts) {
            for (String folder : layout.paths(coordinate, version)) {
                examined = true;
                if (!servableNames.disclosableVersionFolder(folder)) {
                    return false;
                }
            }
        }
        // blobs-namespace face: undisclosable iff any of the version's content hashes is withheld (under HIDE_WITHHELD
        // this is exactly the withheld/<hash> marker read the format serve path makes, no blob stat). Routed through the
        // layout's blobHashes(...) + the bare-hex withheldHash marker face rather than disclosableKey(pointerKey): for a
        // bare-hex format the two are identical (blobHashes IS the pointer body), but OCI's tag pointer body is
        // sha256:<hex>, so disclosableKey would probe withheld/sha256:<hex> and never match the bare-hex marker - a held
        // image would then disclose through search/console. blobHashes yields OCI's bare manifest/config/layer digests,
        // which the marker face matches correctly.
        for (BlobLayout blobLayout : blobLayouts) {
            for (String hash : blobLayout.blobHashes(coordinate, version, store)) {
                examined = true;
                if (policy == ServableNames.Policy.HIDE_WITHHELD_AND_GONE && !store.exists("blobs/" + hash)) {
                    return false;   // serve-parity: a held-OR-gone content hash hides the version (keyState==SERVABLE)
                }
                if (servableNames.withheldHash(hash)) {
                    return false;   // any withheld content hash of the version hides it (the bare-hex marker face)
                }
            }
        }
        // A layout is installed but neither face could name anything of this version, which is a THIRD state - not
        // "nothing withholds it". It is what a layout whose path mapping is configured per repository answers when it
        // is asked purely (the store-free overload above cannot read that configuration), and what any layout answers
        // for a coordinate it cannot place. Reading that silence as a disclosure is the same defect the absent-format
        // branch above was written to close, arriving through a different door: a withheld version would publish its
        // own name through search, the browse, the lifecycle listing and the forwarding queue, and a leaked name
        // cannot be unleaked. So the same coordinate-keyed backstop answers here, for the same reason.
        return examined || coordinateKeyedDisclosable(ecosystem, coordinate, version);
    }

    /**
     * The disclosure screen that needs no format at all: whether anything holds this coordinate version, asked of
     * the two durable records that are keyed by the coordinate rather than by a request path.
     *
     * <p>It is the answer whenever the layout-resolved faces could not be asked - the ecosystem has no installed
     * format, or the installed one could name no folder and no hash for this version. Both used to be read as
     * "nothing withholds it", and that is a disclosure rather than a bookkeeping loss: removing a format module
     * published, by name, every version its enforcement sweeps were holding, through search, the console browse, the
     * lifecycle listing and the forwarding queue, and a withhold that leaks cannot be undone by reinstalling the
     * module.
     *
     * <p>Two records, because one hold leaves no trace in the other. A retroactive enforcement sweep writes
     * {@code holds/<kind>/<eco>/<coord>/<ver>}, which answers with no format, no provider and no discovery at all -
     * and is the reason {@link HoldMarkers} owns that space. A PUBLISH-TIME gate hold writes no such record: it is a
     * screen verdict rather than a sweep's, so its only durable trace is the {@code /quarantine<path>} review pointer
     * and the log row, both keyed by a request path a deployment in this state cannot turn back into a coordinate.
     * {@link HeldSubjects} is the reverse index that reaches them - written when the hold was placed, while the
     * format was installed - so a coordinate can name its own held paths and probe them. The pointer, not the record,
     * is what withholds: a row whose hold has since ended probes absent and discloses, so a reclaim lost to a crash
     * costs a point read rather than hiding a name for good.
     *
     * <p>What it deliberately does not decide is EXISTENCE. A version of an ecosystem no installed format serves is
     * still listed - rendered as an orphan and named as such - as long as nothing holds it. Membership was never the
     * answer to "may this name be disclosed", and this is not the answer to "is it there".
     */
    private boolean coordinateKeyedDisclosable(String ecosystem, String coordinate, String version)
            throws IOException {
        if (HoldMarkers.anyHeld(store, ecosystem, coordinate, version)) {
            return false;
        }
        for (String held : HeldSubjects.paths(store, ecosystem, coordinate, version)) {
            if (store.readVersioned(Publication.quarantineKey(held)).isPresent()) {
                return false;
            }
        }
        return true;
    }

    /** Whether the format serving this path has no coordinate concept at all - see
     *  {@link StoreRepositoryInventory#pathAddressed}. */
    boolean pathAddressed(String path) {
        boolean served = false;
        for (RepositoryFormat format : StoreRepositoryInventory.formats()) {
            if (!format.handles(path)) {
                continue;
            }
            served = true;
            if (format instanceof ArtifactLayout || format instanceof BlobLayout) {
                return false;
            }
        }
        return served;
    }

    /** The format-neutral {@link ArtifactDescriptor} the owning format's {@link ArtifactLayout} maps a request path
     *  to - see {@link StoreRepositoryInventory#describe}. */
    Optional<ArtifactDescriptor> describe(String path) {
        for (RepositoryFormat format : StoreRepositoryInventory.formats()) {
            if (!format.handles(path)) {
                continue;
            }
            // A dual-layout format resolves its publish/-namespace path through ArtifactLayout and its
            // blobs-namespace served path through BlobLayout; a pure blobs-namespace format (npm/PyPI/NuGet/
            // RubyGems/Debian/Go) resolves only through BlobLayout. Consult both so a blobs-namespace served
            // path resolves to its coordinate too - the seam the release path (clearVersionWithholds), the
            // licenses/findings sidecars and reconcile need to reach a hold on those formats.
            if (format instanceof ArtifactLayout layout) {
                // The repository-scoped overload: this store is the repository the path was addressed to, and a
                // layout configured per repository resolves no coordinate without it.
                Optional<ArtifactDescriptor> described = layout.describe(path, store);
                if (described.isPresent()) {
                    return described;
                }
            }
            if (format instanceof BlobLayout layout) {
                Optional<ArtifactDescriptor> described = layout.describe(path);
                if (described.isPresent()) {
                    return described;
                }
            }
        }
        // Fallback for a capability-only BlobLayout provider whose handles() is false: the OCI inventory layout must
        // never claim a /v2/ path in FormatDispatcher (that would steal live serving from the real, proxy-capable OCI
        // format in unspecified ServiceLoader order), yet the inventory must still resolve /v2/<name>/manifests/<ref> to
        // its ("oci", name, ref) coordinate so the describe-dependent seams (HoldLifecycle release/discard/clearVersion-
        // Withholds, HoldReleaseObserver laundering guard, the record(path) worklist row) reach an OCI hold. Consult
        // every non-handling BlobLayout after the handles-gated pass, accepting only a descriptor whose ecosystem the
        // layout itself owns - so a future lax parser cannot mis-describe a foreign path, and today only the OCI layout
        // ever matches a /v2/ path.
        for (RepositoryFormat format : StoreRepositoryInventory.formats()) {
            if (format.handles(path) || !(format instanceof BlobLayout layout)) {
                continue;
            }
            Optional<ArtifactDescriptor> described = layout.describe(path);
            if (described.isPresent() && layout.ecosystem().equals(described.get().ecosystem())) {
                return described;
            }
        }
        return Optional.empty();
    }

    /** The request-path folder a coordinate version occupies - see {@link StoreRepositoryInventory#locate}. */
    String locate(String ecosystem, String coordinate, String version) {
        // A browse link is one folder, so where an ecosystem is served through several layouts this takes the first
        // that resolves one. The order is the installed set's, which is name-ordered rather than discovery-ordered,
        // so the link a deployment renders is stable across its nodes and restarts - the property that matters for a
        // link. Every other seam on this class unions instead, because for them a first answer would be a wrong one.
        for (ArtifactLayout layout : StoreRepositoryInventory.layoutsFor(ecosystem)) {
            List<String> paths = layout.paths(coordinate, version);
            if (!paths.isEmpty()) {
                return paths.getFirst();
            }
        }
        return "";
    }

    /** Every served request path a coordinate version currently occupies - see {@link StoreRepositoryInventory#paths}. */
    List<String> paths(String ecosystem, String coordinate, String version) throws IOException {
        return knownPaths(ecosystem, coordinate, version).unknownAsAbsent(
                        "this is the additive listing face. Its callers are the retroactive enforcement sweeps, which "
                                + "WITHHOLD the paths they are handed, the reachability/audit jar picks, the "
                                + "screen's own-path check and the console's path render - at every one of them a "
                                + "version no installed format can place yields fewer paths to act on, never a hold "
                                + "lifted, a derived row deleted or a name disclosed. Every caller that reads an "
                                + "EMPTY list as a fact ABOUT THE VERSION rather than as a listing takes "
                                + "knownPaths and writes the Unknown arm where it stands.")
                .orElse(List.of());
    }

    /** The three-valued form - see {@link StoreRepositoryInventory#knownPaths}. */
    /**
     * The most served paths one version may be enumerated over on a request path, past which the answer is UNKNOWN.
     *
     * <p>The same bound {@code ServableNames}' version-folder probe uses, and for the same reason: a version folder
     * is far smaller than this in every legitimate case, so the cap screens only pathological ones. What differs is
     * what the two do at the bound - the probe hides the version, and this reports that it cannot enumerate, which
     * every caller reads as "assume the worst". For the quarantine paths that reach here, assuming the worst means
     * leaving a hold in place, which is the safe direction. The cost is that a version this wide cannot be released
     * without an operator intervening, and that is the trade being taken.
     */
    private static final int PATH_CAP = 512;

    Known<List<String>> knownPaths(String ecosystem, String coordinate, String version) throws IOException {
        List<String> paths = new ArrayList<>();
        boolean asked = false;
        for (ArtifactLayout layout : StoreRepositoryInventory.layoutsFor(ecosystem)) {
            for (String prefix : layout.paths(coordinate, version, store)) {
                asked = true;   // a layout that resolves no prefix for this coordinate was never really asked
                List<String> children = new ArrayList<>();
                store.page("publish" + prefix, "", ArtifactStore.oneMoreThan(PATH_CAP - paths.size()), children::add);
                if (paths.size() + children.size() > PATH_CAP) {
                    // Past the bound this answers UNKNOWN rather than a truncated list, and the difference matters:
                    // every caller treats unknown as "cannot enumerate, so assume the worst", which for a hold means
                    // keeping the withhold standing. A truncated list would instead read as the complete set of the
                    // version's paths and quietly release a version whose remaining paths were never looked at.
                    return Known.uninstalled("the version " + ecosystem + " " + coordinate + ":" + version
                            + " serves more than " + PATH_CAP + " paths, which cannot be enumerated within the "
                            + "bound a request is allowed - so it is treated as un-enumerable rather than as the "
                            + "first " + PATH_CAP + " of them");
                }
                for (String child : children) {
                    paths.add(prefix + "/" + child);
                }
            }
        }
        // A pure blobs-namespace format (npm/PyPI/NuGet/RubyGems/Debian/Go) has no ArtifactLayout, so its served paths
        // never appear in the publish/ tree above; its BlobLayout maps the version back to the request paths it serves,
        // the seam the retroactive hold links a /quarantine<servedPath> review handle at. A dual-layout format leaves
        // servedPaths at the empty default - it is already enumerated through its ArtifactLayout paths above.
        for (BlobLayout blobLayout : StoreRepositoryInventory.blobLayoutsFor(ecosystem)) {
            asked = true;
            paths.addAll(blobLayout.servedPaths(coordinate, version, store));
        }
        return asked
                ? Known.known(List.copyOf(paths))
                : Known.uninstalled("no installed format can place " + ecosystem + " " + coordinate + ":" + version
                        + " - neither an ArtifactLayout that resolves a layout prefix for the coordinate nor a "
                        + "BlobLayout that owns the ecosystem - so the request paths this version serves under "
                        + "cannot be enumerated at all");
    }
}
