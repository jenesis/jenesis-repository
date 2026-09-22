package build.jenesis.repository.format.oci.inventory;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.blobs.BlobRoots;
import build.jenesis.repository.format.BlobReferences;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.walk.PagedTreeWalk;
import build.jenesis.repository.walk.Traversal;
import build.jenesis.repository.format.OciTags;
import build.jenesis.repository.format.Checksums;

/**
 * The OCI inventory layout: a capability-only {@link RepositoryFormat} + {@link BlobLayout} that teaches the
 * store-backed inventory OCI's on-store conventions, so a retroactive KEV/license hold on an OCI image withholds every
 * served face (manifest by tag and digest, config, layers) and releases cleanly - the gap Audit-25 #9 flags (the free
 * {@code OciFormat} implements neither {@code ArtifactLayout} nor {@code BlobLayout}, so {@code inventory.paths("oci",…)}
 * / {@code blobHashes("oci",…)} were empty and every hold/release seam no-oped on OCI).
 *
 * <p><b>Never dispatches.</b> {@link #handles} is ALWAYS {@code false}: {@code FormatDispatcher} (and the redirect/
 * staging first-match idioms) serve the FIRST format whose {@code handles} matches in unspecified {@code ServiceLoader}
 * order, so a layout that claimed {@code /v2/} could steal live serving from the real, proxy-capable {@code OciFormat}.
 * This provider is inert on every serving path and exists only to answer the inventory's capability lookups
 * ({@link #describe} / {@link #servedPaths} / {@link #blobKeys} / {@link #blobHashes}), reached through the enterprise
 * inventory's non-handling-{@code BlobLayout} fallback seams. {@link #handle} is therefore unreachable and throws.
 *
 * <p><b>Re-reads the conventions, never reaches into {@code OciFormat}'s module</b> (which exports its package only to
 * its own test): a manifest, config or layer blob is content-addressed at {@code blobs/<hex>}; a tag pointer is
 * {@code oci/<name>/tags/<tag>} whose body is {@code "sha256:" + hex} (NOT bare hex); a manifest's config/layer digests
 * live inside the manifest JSON. The precedent is {@code HoldLifecycle.releaseOci}, which already duplicates these keys.
 * The free <em>SPI</em> is a different matter and is used directly: the reference-scan seam this layout declares is the
 * free {@code BlobReferences}, inherited through {@code BlobRoots} rather than restated.
 *
 * <p><b>Cross-alias &amp; tag-mutability landmines (Audit-25 §6).</b> {@link #blobHashes} marks every referenced digest,
 * the correct egress invariant (the bytes are what is held) - so a KEV hold on one image 404s a shared base layer for
 * every image referencing it while those images' manifests keep serving (a partly-pullable image). On release the
 * cross-alias guard ({@code HoldLifecycle.withheldByAnotherAlias}) consults each still-held sibling's FULL
 * {@link #blobHashes} set - not just its {@code /quarantine} pointer BODY, which for OCI is a manifest digest that never
 * carries a shared LAYER hash (Audit-27 A1-F1) - so releasing image A KEEPS a layer marker a concurrently-held image B
 * still needs; the shared layer stays withheld (for A too, the content-addressed cost) until B releases, closing the
 * prior cross-alias disclosure where B's held layer briefly served. A version keyed by a tag resolves its digest set at
 * sweep/release time, so a corrected re-push to a held tag serves the new bytes until the next converge pass re-marks
 * the tag's current resolution (the {@code /quarantine} review pointer meanwhile keeps the accept path from clearing the
 * hold).
 *
 * <p><b>How an OCI blob is kept alive, and how it is let go - the mechanism, so this paragraph survives the next
 * change to it.</b> Garbage collection is a cycle with two independent halves, and <em>neither of them is this class's
 * {@link #blobHashes}</em>, which serves the hold side alone.
 * <ul>
 *   <li><b>Mark - what a key says is still in use.</b> The neutral mark phase reads a leaf's pointer <em>body</em>
 *       through {@code ServableNames.hash}, which is the seam that owns the two dialects a body may carry; that is why
 *       the {@code sha256:}-prefixed body of an {@code oci/<name>/tags/<tag>} pointer names its manifest blob into the
 *       reference set and a tagged manifest is never condemned. That is <em>all</em> a body-reading scan can
 *       see of OCI: a config or layer digest lives inside the manifest JSON behind no store key at all, and a manifest
 *       pulled only by digest carries no tag pointer, so a scan that reads bodies alone condemns and then deletes a
 *       live image's layers. Closing that needs the mark phase to <em>ask the format that owns the visited key's root
 *       what else that key keeps alive</em> - the {@code BlobReferences} seam, which resolves the manifest
 *       from either key that names one (the tag pointer, or the {@code oci/types/<hex>} media-type sidecar
 *       {@code OciManifests.ingest} writes for EVERY accepted manifest, tagged or not) and lends back the manifest's
 *       own hash, an index's sub-manifests and each one's config, layer and legacy {@code fsLayers} digests. The
 *       collector still parses no format's document; it unions what the format lends into the same shards under the
 *       same bare-hex predicate.</li>
 *   <li><b>Sweep - what an eviction takes away.</b> {@link #blobKeys} is the handle, and since the earlier work it names the
 *       {@code oci/types/<hex>} sidecar as well as the tag pointer, guarded so a sibling tag on the same digest keeps
 *       it. That is what makes the two halves a cycle rather than a ratchet: without it the sidecar outlives the image
 *       and keeps lending its blobs for ever, so an evicted image is <em>retained</em> rather than reclaimed - the safe
 *       direction, but unbounded storage growth.</li>
 * </ul>
 * The two halves meet at one key: the sidecar the mark phase resolves an image from is the object the sweep destroys
 * last. That is the whole invariant, and it holds in one direction only - a sidecar may outlive its image (storage,
 * recoverable) but an image must never outlive its sidecar (its layers, deleted).
 *
 * <p><b>Why nothing here names a pinned version any more.</b> This paragraph was rewritten three times, each
 * time describing whichever gap happened to be open on the day - the pre-blanket exposure, then "configs and
 * layers remain outside the reference set", then "the pinned 0.8.0 core predates the lending seam" - and each rewrite
 * went stale at the next bump, in both directions. A version number is the one fact a comment cannot keep. So the
 * mechanism above is stated as an invariant of the seams and the pin state is not stated at all: whether the installed
 * free core actually lends OCI's references is a <em>runtime</em> property of the deployment's module graph, not of
 * this file, and it is asserted where it can fail - {@code OciReclamationTest} drives a real mark-and-sweep over a real
 * store and fails if a live image's config or layer is collected, or if an evicted one is not. Read that suite, not
 * this sentence, for what today's core does.
 *
 * <p><b>What no version of this covers, deliberately.</b> A manifest that only ever existed by digest has no eviction
 * handle: the {@code /v2/} API exposes no DELETE and a digest reference resolves no pointer key, so
 * {@link #blobKeys} is empty for it and nothing evicts it - permanent content by design, and a collection pass deleting
 * it is the defect rather than a reclamation route to restore. GC is not switched off for OCI either way: a blob
 * no manifest names (an abandoned upload, an orphaned layer) is still condemned and collected. And {@link #blobHashes}
 * stays the hold side's <em>posture</em> alone, never consulted by the collector - it asks the seam the same
 * question the mark phase does and then degrades where the collector refuses, because under-enforcing a hold is
 * safe while under-reporting to a deleter destroys served bytes.
 */
public final class OciBlobLayout implements RepositoryFormat, BlobLayout {

    private static final Logger LOGGER = LoggerFactory.getLogger(OciBlobLayout.class);

    @Override
    public String name() {
        // Distinct from the format's "oci" so RepositoryFormat.installed("oci") and the format toggle/listing
        // surfaces stay unambiguous; this provider is never looked up by name.
        return "oci-layout";
    }

    @Override
    public boolean handles(String path) {
        // ALWAYS false - never wins format dispatch, never proxies, never imports (see the class javadoc). A future
        // change to claim /v2/ here re-opens the first-match dispatch race with the real OciFormat; OciBlobLayoutTest
        // pins this to false.
        return false;
    }

    @Override
    public void serve(FormatExchange exchange, ArtifactStore store) {
        throw new IllegalStateException(
                "OciBlobLayout is a capability-only inventory layout (handles() is always false) and never serves a request");
    }

    @Override
    public String ecosystem() {
        // The ecosystem OciManifests.ingest stamps its descriptor with, so the published/oci/... rows, the holds/kev/oci
        // records and this layout all key on one ecosystem string.
        return "oci";
    }

    /**
     * The store-key root this format keeps its pointers and documents under, so the reference scan walks it.
     *
     * <p><b>Load-bearing, never decorative</b> - which is what this comment used to call it, back when the mark phase
     * could not read a {@code sha256:}-prefixed pointer body and every OCI key under this root was equally invisible.
     * It is the sole entry point through which OCI content reaches the mark phase, and the mark phase's whole answer
     * for a visited key is derived from it: the blob that key's own body names (the tag-pointer dialect, read through
     * {@code ServableNames.hash} -), plus whatever the format that owns the key lends back for it through the
     * free {@code BlobReferences} seam - the config, layer and sub-manifest digests that live inside the manifest JSON
     * and that no store key names. Dropping this root does not cost "the tagged manifests": it makes every OCI
     * blob the deployment serves unreachable to the scan, because a key the walk never visits is a key no lender is
     * ever asked about.
     *
     * <p><b>The declaration is the shared seam's, inherited rather than restated</b> ({@code BlobRoots extends
     * BlobReferences}), so the roots the reference scan reads and the roots the collector offers a visited key
     * under are one list by construction, not two lists that agree today. This layout lends nothing itself -
     * {@code OciFormat} owns the manifest dialect and answers for every {@code oci/} key -
     * and the inherited empty default is the correct answer for a format that is not the document's owner.
     *
     * <p>The reference scan never consults {@link #blobHashes}, which is the hold side's derivation and carries the
     * opposite degrade. The sweep half of the cycle is {@link #blobKeys}, which retires a manifest by destroying its
     * last tag pointer and its {@code oci/types/<hex>} sidecar together.
     */
    @Override
    public List<String> blobRoots() {
        return List.of("oci");
    }

    /**
     * The store keys an eviction or discard of one image version deletes - the tag pointer, and the manifest's
     * {@code oci/types/<hex>} media-type sidecar when this version is the last live tag holding it.
     *
     * <p>A tag reference maps to its {@code oci/<name>/tags/<tag>} pointer; a digest reference names no pointer key at
     * all (content-addressed - one {@code withheld/<hex>} marker retracts it, which {@code discardBlobs} deliberately
     * never lifts), so it stays empty and a digest-only manifest has no eviction handle. That is the {@code /v2/}
     * API's shape rather than an oversight here: it exposes no DELETE, so a manifest that was only ever pulled by
     * digest is permanent content by design.
     *
     * <p><b>Why the sidecar is an eviction key at all.</b> The {@code BlobReferences} seam lends the reference
     * scan the blobs an image keeps alive, resolved from either of the two keys that name a manifest: the tag pointer,
     * and {@code oci/types/<hex>} - the sidecar {@code OciManifests.ingest} writes for EVERY accepted manifest, which
     * is a digest-only image's only durable record. That closes a live-data-loss hole - a pass condemning and
     * then deleting a live image's config and layers
     * - but opens a storage one at the other end unless something retires the sidecar: an evicted image would stay
     * marked for ever by a sidecar whose image no longer exists, <em>retained</em> rather than reclaimed, and OCI
     * storage would grow without bound. Deleting the tag pointer alone is therefore not an eviction. The sidecar is
     * also what the pull path reads to answer a manifest's media type verbatim, which is the second reason the guard
     * below is not decoration - and the one that already bites at the pinned core.
     *
     * <p><b>The sibling-tag guard.</b> Two tags legitimately resolve to one manifest digest ({@code :1.4.2} and
     * {@code :latest} after a re-tag, or the same image pushed under a second name), and the sidecar is keyed by that
     * digest alone - one object shared by every alias. So the sidecar is returned only when NO other live tag pointer,
     * anywhere under {@code oci/}, resolves to the same hex. This is {@code HoldLifecycle.withheldByAnotherAlias}'s
     * shape one seam over: an alias-scoped scan that keeps a shared object standing until the last holder goes, and
     * that fails <em>closed</em> - anything it cannot read leaves the sidecar in place, because retaining it wastes one
     * small object while deleting it early un-marks a live sibling image's layers and strips its served media type.
     *
     * <p><b>Cost.</b> There is no digest-to-tags index to consult, so proving a manifest unaliased is a descent of the
     * {@code oci/} tag space - bounded and paged, short-circuiting on the first alias, but not free. That is a price an
     * eviction can pay and a per-version <em>probe</em> cannot, so the two cheap decisions come first: a digest
     * reference and a dead tag pointer both answer before any listing, and a manifest with no sidecar at all (an image
     * stored before the sidecar existed) costs one {@code exists}. The remaining caller that used to spend this on
     * every published row - {@code InventoryReconciler.removeOrphanPublished} - now asks only when it can act on the
     * answer.
     */
    @Override
    public List<String> blobKeys(String coordinate, String version, ArtifactStore store) throws IOException {
        if (version.startsWith("sha256:") || !OciTags.isTag(version) || !isImageName(coordinate)) {
            return List.of();
        }
        String key = "oci/" + coordinate + "/tags/" + version;
        Optional<ArtifactStore.Versioned> pointer = store.readVersioned(key);
        if (pointer.isEmpty()) {
            return List.of();                   // not a live tag pointer: this version occupies no key to destroy
        }
        String hex = hex(new String(pointer.get().content(), StandardCharsets.UTF_8).trim());
        if (hex == null || !store.exists("oci/types/" + hex)) {
            return List.of(key);                // no sidecar to retire (or a body that names no manifest)
        }
        if (sharedByAnotherTag(key, hex, store)) {
            return List.of(key);                // a sibling alias still needs the sidecar - delete less
        }
        return List.of(key, "oci/types/" + hex);
    }

    /** The bounds the sibling-tag scan descends {@code oci/} under. The step budget is what really bounds it (one
     *  {@link ArtifactStore#exists} probe per opened node); the entry cap is a per-call continuation the loop below
     *  follows to the end, never a shortened answer - a truncated scan that reported "unshared" would delete a sidecar
     *  a tag past the cap still holds. Depth stays at the default {@link ArtifactStore#MAX_SEGMENTS} ceiling, which
     *  every key the store's own write path would accept fits inside. */
    private static final PagedTreeWalk ALIASES = PagedTreeWalk.bounded().steps(5_000_000).page(BoundedChildren.DRAIN_PAGE);

    /**
     * Whether a live tag pointer OTHER than {@code own} resolves to the manifest {@code hex} - the cross-alias guard
     * that keeps {@code oci/types/<hex>} standing while any sibling tag still serves that manifest.
     *
     * <p>The scan is the shared bounded tree walk over {@code oci/} (the earlier primitive, iterative and paged, so an
     * attacker-shaped multi-segment image name cannot overflow a stack and a wide level is never listed whole),
     * following its own cursor to exhaustion and short-circuiting on the first alias found - the cancellation the
     * primitive documents. Only tag pointers are read, judged by {@link #tagPointer}, so the format's sidecar and
     * upload spaces cost a name test rather than a store read.
     *
     * <p><b>Fail-closed, and self-checking.</b> A store failure, a hostile key that trips a traversal bound, or any
     * other unreadable state answers {@code true}: the guard could not <em>prove</em> the sidecar unshared, and the
     * mandated direction is to mark more and delete less. It is deliberately not a throw - the rest of the eviction
     * (destroying the tag pointer, which is what stops this version serving) must still happen, and leaving one small
     * sidecar behind is inert storage that the next eviction of a sibling re-evaluates, where deleting it wrongly
     * un-marks a live image's layers for the next collection pass and strips a sibling's served media type. The WARN is
     * what keeps that degrade from being silent.
     *
     * <p>An exception is not the only way an enumeration can fail, and here the other way is the dangerous one:
     * {@link ArtifactStore#list} throws nothing in the SPI, so a real backend outage <em>degrades to an empty
     * listing</em> - and an empty listing is indistinguishable, to a "did anyone else claim this hash" question, from
     * a repository with no other tags at all. Answering "unshared" there would delete a sidecar every alias still
     * needs, on a store hiccup. So the scan validates itself against a key it already knows is there: it must have
     * been handed {@code own}, the very tag pointer this eviction read a moment ago. A descent that did not deliver it
     * enumerated something other than the live tag space and is refused. The check is conservative in the safe
     * direction only - a tag pointer that also carries child keys is not a leaf, so it is not delivered, and this
     * simply keeps the sidecar.
     */
    private static boolean sharedByAnotherTag(String own, String hex, ArtifactStore store) {
        boolean[] sawOwn = {false};
        try {
            String cursor = null;
            while (true) {
                Traversal.Result result = ALIASES.walk(store, "oci", cursor, leaf -> {
                    if (leaf.equals(own)) {
                        sawOwn[0] = true;       // the liveness check: this descent really did reach the tag space
                        return;
                    }
                    if (tagPointer(leaf) == null) {
                        return;                 // a sidecar, staged upload chunks, or a key deeper than a tag pointer
                    }
                    Optional<ArtifactStore.Versioned> alias = store.readVersioned(leaf);
                    if (alias.isPresent()
                            && hex.equals(hex(new String(alias.get().content(), StandardCharsets.UTF_8).trim()))) {
                        throw SHARED;           // the documented cancellation: stop the descent on the first hit
                    }
                });
                if (result.exhausted()) {
                    break;
                }
                cursor = result.cursor().orElseThrow();
            }
        } catch (SharedAlias _) {
            return true;
        } catch (IOException | RuntimeException unreadable) {
            return withheld(own, hex, "the tag-space descent failed: " + unreadable);
        }
        return sawOwn[0] ? false : withheld(own, hex, "the tag-space descent never delivered that tag pointer itself, "
                + "so it did not enumerate the live tag space (a listing that degraded to empty reads exactly like a "
                + "repository with no other tags)");
    }

    /** Keep the sidecar and say why - the one place the guard's fail-closed degrade is recorded, so "retained rather
     *  than reclaimed" is never a silent outcome. Always answers {@code true} ("treat as shared"). */
    private static boolean withheld(String own, String hex, String why) {
        LOGGER.warn("Could not prove the OCI manifest sidecar oci/types/{} unshared while evicting {}, so it is kept: {}. "
                + "Reclamation of that manifest is deferred to the next eviction of one of its tags; nothing that "
                + "serves is affected.", hex, own, why);
        return true;
    }

    /** The cancellation signal {@link #sharedByAnotherTag}'s descent stops on - an {@link IOException} because that is
     *  the cancellation hook {@link PagedTreeWalk} documents, and caught immediately at the call site. Stackless and
     *  shared: it is control flow, not a failure, and it is never surfaced. */
    private static final class SharedAlias extends IOException {
        private static final long serialVersionUID = 1L;

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;                        // control flow, not a failure - there is no stack worth capturing
        }
    }

    private static final SharedAlias SHARED = new SharedAlias();

    /**
     * The request path this image version currently occupies - deliberately ONE review handle per version (the tagged or
     * digest-referenced manifest path), when the version is live (its manifest blob is stored). Serving retraction for
     * every alias (the tag, the digest, each layer) comes from the content-addressed {@code withheld/<hex>} markers, not
     * from per-path pointers - one marker retracts every alias - so the review pointer's only jobs are queue visibility
     * and the cross-alias guard, and one handle per held version keeps the review queue and
     * {@code othersStillHeld}/{@code withheldByAnotherAlias} semantics simple.
     */
    @Override
    public List<String> servedPaths(String coordinate, String version, ArtifactStore store) throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            return List.of();   // the shared per-part coordinate screen (T-202b), beside blobKeys' own name/tag screen
        }
        Optional<String> hex = manifestHex(coordinate, version, store);
        if (hex.isEmpty() || !store.exists("blobs/" + hex.get())) {
            return List.of();
        }
        return List.of("/v2/" + coordinate + "/manifests/" + version);
    }

    /**
     * The format-neutral coordinate a manifest request path carries: {@code /v2/<name>/manifests/<ref>} maps to
     * {@code ("oci", name, ref)} with the same image-name / tag / digest-hex validation the format applies. Every
     * other {@code /v2/} path - a blob, {@code _catalog}, {@code tags/list}, an upload - names no version and returns
     * empty (the honest degrade the {@link BlobLayout#describe} contract prescribes). This is what the enterprise
     * inventory's non-handling-{@code BlobLayout} fallback consults for an OCI hold's describe-dependent seams.
     */
    @Override
    public Optional<ArtifactDescriptor> describe(String path) {
        if (!path.startsWith("/v2/")) {
            return Optional.empty();
        }
        int manifests = path.indexOf("/manifests/");
        if (manifests < "/v2/".length()) {
            return Optional.empty();
        }
        String name = path.substring("/v2/".length(), manifests);
        String reference = path.substring(manifests + "/manifests/".length());
        if (!isImageName(name)) {
            return Optional.empty();
        }
        boolean digest = reference.startsWith("sha256:");
        if (digest ? hex(reference) == null : !OciTags.isTag(reference)) {
            return Optional.empty();
        }
        return Optional.of(new ArtifactDescriptor("oci", name, reference, path, null, false, null, -1L));
    }

    /**
     * The content hashes an OCI image version serves from {@code blobs/} - the set a retroactive withhold marks and a
     * name-enumeration screen probes. OCI cannot express this through {@link #blobKeys} (its tag pointer body is
     * {@code sha256:<hex>}, not bare hex, and its config/layer digests live inside the manifest JSON behind no pointer
     * key), so this overrides the {@link BlobLayout#blobHashes} default to derive the set from the manifest itself,
     * collecting, in order:
     * <ol>
     *   <li>the manifest hex FIRST - it becomes {@code blobHashes().getFirst()}, the {@code /quarantine} review handle's
     *       link target, so the pointer body is the per-image manifest digest and {@code withheldByAnotherAlias} stays
     *       meaningful;</li>
     *   <li>for an image index, each sub-manifest digest, recursed with an explicit work-list and an emitted set (never
     *       recursion - a hostile nested index must not overflow the sweep's stack), each sub-manifest then contributing
     *       its own config/layers;</li>
     *   <li>the config digest, each layer digest and each legacy {@code fsLayers} blobSum - bare hex, validated.</li>
     * </ol>
     * <p><b>One derivation, not two</b>. This used to walk the manifest JSON itself - a second work-list
     * expansion of indexes, a second digest validator, a second hard-coded manifest cap - beside the free
     * {@code OciFormat.references}, which answers the identical question from the identical stored bytes for the
     * collector. Two homes for one answer is the shape that produces a data-loss bug the day they disagree: a hash the
     * hold knows and the scan does not is a live blob the next pass deletes out from under a held image, and one the
     * scan knows and the hold does not is a layer serving through a hold that reports itself enforced. So the manifest
     * dialect stays with the format that owns it and this method only resolves the image's manifest hex and hands the
     * free seam the {@code oci/types/<hex>} key that names it - the sidecar {@code OciManifests.ingest} writes for
     * every accepted manifest, which is a key the seam answers for whether or not the image is tagged.
     *
     * <p><b>What the delegation had to wait for, and what it costs.</b> The two sides carry <b>opposite failure
     * postures by design</b>: a present-but-unenumerable manifest makes the seam THROW (its contract clause 3 - a
     * short list handed to a deleter is data loss), while the callers here are a console browse, a KEV sweep and a
     * release path, where a throw turns one corrupt legacy manifest into a repository whose whole enforcement pass
     * fails. That is why this could not simply call the seam until free named its refusal: catching {@link IOException}
     * would convert every store hiccup into a silently under-enforced hold. It now catches exactly
     * {@link BlobReferences.Unresolvable} - "these bytes will never parse", no retry changes it - degrades to the
     * manifest hex it is sure of and WARNs so an operator can {@code discard} it, and lets a plain {@link IOException}
     * (the store failing) propagate as it always did.
     *
     * <p><b>The degrade is symmetric now, where it used to be silent on one side.</b> The old walk WARNed only when the
     * root had been resolved from a tag pointer, on the argument that only a tag pointer's target is contractually a
     * manifest; a digest-rooted unparseable manifest degraded without a word. The seam raises {@code Unresolvable}
     * for the root of either key - the sidecar's target is contractually a manifest too, since ingest wrote it - so
     * both now WARN. A degraded SUB-manifest of an index still stays silent on both sides (a hostile index entry may
     * legitimately point at a layer blob, which has no children to lose).
     *
     * <p><b>No installed OCI format is the same degrade.</b> This layout deliberately does not {@code require} the free
     * {@code format.oci} module - it is a capability-only provider that must load in a deployment that ships no OCI
     * serving at all - so the lender is resolved at call time through {@link #lender()}. When none is installed the
     * answer is the manifest hex alone, WARNed: "no lender installed" and "the lender cannot enumerate this document"
     * have identical consequences for the hold side (the layers are not enumerable, so the hold marks the manifest and
     * they keep serving by digest), and saying so is what keeps either from being silent.
     */
    @Override
    public List<String> blobHashes(String coordinate, String version, ArtifactStore store) throws IOException {
        Optional<String> root = manifestHex(coordinate, version, store);
        if (root.isEmpty()) {
            return List.of();
        }
        String hex = root.get();
        BlobReferences lender = lender();
        if (lender == null) {
            return degraded(coordinate, version, hex, "no installed format lends OCI references - the deployment "
                    + "ships no OCI format, so nothing here can read the manifest's config and layer digests");
        }
        List<String> references;
        try {
            // The sidecar key rather than the tag pointer: it names this manifest whether or not the image is tagged,
            // and the seam resolves the hex straight out of the key without a second store read.
            references = lender.references("oci/types/" + hex, store);
        } catch (BlobReferences.Unresolvable unenumerable) {
            // Exactly this one, never a bare IOException: a store outage must keep propagating rather than becoming a
            // silently under-enforced hold. Alarm and degrade - blobHashes runs in the streamed sweep loop, and
            // throwing would DoS the whole repository pass.
            return degraded(coordinate, version, hex, unenumerable.getMessage());
        }
        // Empty is not an answer this key can honestly have - it names a manifest by digest - so it reads as the same
        // "cannot enumerate" the two degrades above report, and never as an image that keeps no blob alive.
        return references.isEmpty()
                ? degraded(coordinate, version, hex, "the installed OCI format lends nothing for that manifest's "
                        + "sidecar key, so its config and layer digests are not enumerable here")
                : references;
    }

    /** The manifest-only answer plus the WARN that keeps it from being silent - the one degrade
     *  {@link #blobHashes} has, whichever of its three causes produced it. It under-enforces a hold (the layers keep
     *  serving by digest), which is the safe direction for this side and exactly why it is not a throw. */
    private static List<String> degraded(String coordinate, String version, String hex, String why) {
        LOGGER.warn("OCI hold/enumeration for {}:{} degraded to manifest-only ({}): {} - consider discard",
                coordinate, version, hex, why);
        return List.of(hex);
    }

    /**
     * The installed format that owns the {@code oci/} manifest dialect, or {@code null} when the deployment
     * ships none - resolved through {@link BlobReferences#installed()}, the same static the collector's mark phase
     * resolves its lenders with, so the hold side asks the object the collector really asks.
     *
     * <p>A blob layout is generally a lender too ({@link BlobRoots} extends the seam) and this one declares
     * the {@code oci/} root itself, so the inventory side is filtered out by its type: it lends nothing, because
     * {@code OciFormat} owns the document's dialect. Resolved per call rather than latched, so a format an operator switched
     * off ({@code jenreg.oci=false}) stops lending immediately, exactly as it stops serving.
     */
    private static BlobReferences lender() {
        for (BlobReferences lender : BlobReferences.installed()) {
            if (!(lender instanceof BlobRoots) && lender.blobRoots().contains("oci")) {
                return lender;
            }
        }
        return null;
    }

    /** Resolve an image reference to the manifest hex: a digest reference is the hex itself; a tag reference reads the
     *  {@code oci/<name>/tags/<tag>} pointer and strips its {@code sha256:} prefix (the {@code OciFormat.linkTag}
     *  shape). Empty when the reference is malformed or the tag pointer is absent / does not resolve to a real digest. */
    private static Optional<String> manifestHex(String coordinate, String version, ArtifactStore store)
            throws IOException {
        if (version.startsWith("sha256:")) {
            return Optional.ofNullable(hex(version));
        }
        if (!OciTags.isTag(version) || !isImageName(coordinate)) {
            return Optional.empty();
        }
        Optional<ArtifactStore.Versioned> pointer = store.readVersioned("oci/" + coordinate + "/tags/" + version);
        if (pointer.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(hex(new String(pointer.get().content(), StandardCharsets.UTF_8).trim()));
    }

    /** The bare lower-case 64-hex digest of a {@code sha256:<hex>} (or already-bare) reference, or {@code null} when it
     *  is not a real sha256 digest - so a tag typo or a {@code ..}-laced reference never becomes a hash. */
    /**
     * The {@code blobs/<hex>} key a manifest or blob request path serves from: a digest reference names it outright,
     * a tag reference through its pointer. This is what lets the signature screen read an image's cosign signature
     * artifact beside the manifest it judges - the {@code sha256-<hex>.sig} manifest by its tag, then each payload
     * blob by digest - through the same key resolution a pull performs.
     */
    @Override
    public Optional<String> servingKey(String requestPath, ArtifactStore store) throws IOException {
        if (!requestPath.startsWith("/v2/")) {
            return Optional.empty();
        }
        int blobs = requestPath.indexOf("/blobs/");
        if (blobs >= "/v2/".length()) {
            if (requestPath.contains("/blobs/uploads")) {
                return Optional.empty();
            }
            String name = requestPath.substring("/v2/".length(), blobs);
            String hex = hex(requestPath.substring(blobs + "/blobs/".length()));
            return isImageName(name) && hex != null ? Optional.of("blobs/" + hex) : Optional.empty();
        }
        Optional<ArtifactDescriptor> described = describe(requestPath);
        if (described.isEmpty()) {
            return Optional.empty();
        }
        return manifestHex(described.get().coordinate(), described.get().version(), store)
                .map(hex -> "blobs/" + hex);
    }

    private static String hex(String digest) {
        if (digest == null) {
            return null;
        }
        int colon = digest.indexOf(':');
        String hex = colon < 0 ? digest : digest.substring(colon + 1);
        return Checksums.isSha256Hex(hex) ? hex : null;
    }

    /** The reserved children at the {@code oci/} root that are never image-name segments - this format's sidecar and
     *  upload spaces, mirroring the {@code OciFormat.DirCursor.reserved} rule so no traversal of the {@code oci/}
     *  tree ever mistakes a sidecar for an image. */
    static final Set<String> RESERVED = Set.of("types", "uploads", "upload-sessions");

    /**
     * The {@code (image name, tag)} a stored {@code oci/} key names when that key is a tag pointer, or {@code null}
     * when it is not one - the store-key half of this format's conventions, held here beside {@link #isImageName} and
     * {@link OciTags#isTag} because a tag pointer's shape is layout knowledge and every traversal of the
     * {@code oci/} tree needs exactly this decision.
     *
     * <p>A tag pointer is {@code oci/<name>/tags/<tag>}: the FIRST {@code tags} segment ends the image name (the free
     * {@code OciFormat.DirCursor.reserved} rule reserves {@code tags} at every level, so no image name contains one)
     * and exactly one segment may follow it - a deeper key under a tag is not a pointer. The format's sidecar spaces at
     * the {@code oci/} root ({@link #RESERVED}) are never image names, and both derived parts are screened through the
     * same Distribution grammar the serving path applies, so a hostile key can never be decoded into a coordinate this
     * format would not itself have written.
     */
    /**
     * The image and tag a stored pointer names - this format's answer to the one direction {@link BlobLayout} did
     * not have, and the first implementation of it.
     *
     * <p>It is exactly {@link #tagPointer}, which the OCI back-fill has judged deliveries by since it was written:
     * the same grammar, the same refusal of a key deeper than {@code oci/<name>/tags/<tag>}, and the same screening
     * of both derived parts, so a hostile key cannot decode into a coordinate this format would not have written.
     * Promoting it to the seam is what lets one repair serve every format that can answer, instead of one repair
     * per format that parses its own keys.
     *
     * <p>The descriptor carries the coordinate and nothing measured: a pointer's size and hash belong to the blob
     * it names, and this derives from the key alone.
     */
    @Override
    public Optional<ArtifactDescriptor> describePointer(String key) {
        String[] tagged = tagPointer(key);
        return tagged == null
                ? Optional.empty()
                : Optional.of(new ArtifactDescriptor("oci", tagged[0], tagged[1], key, null, false, null, 0L));
    }

    static String[] tagPointer(String key) {
        if (!key.startsWith("oci/")) {
            return null;                        // the root itself, were it ever a stored key, names no image
        }
        String[] segments = key.substring("oci/".length()).split("/");
        if (segments.length < 3 || RESERVED.contains(segments[0])) {
            return null;
        }
        for (int index = 1; index < segments.length; index++) {
            if (!segments[index].equals("tags")) {
                continue;
            }
            if (index + 2 != segments.length) {
                return null;                    // a key deeper than oci/<name>/tags/<tag> is not a tag pointer
            }
            String name = String.join("/", Arrays.copyOfRange(segments, 0, index));
            String tag = segments[index + 1];
            return isImageName(name) && OciTags.isTag(tag) ? new String[] {name, tag} : null;
        }
        return null;
    }

    /**
     * Whether an image name may address an {@code oci/<name>/...} store key: the store's own path rule, plus the
     * one thing that rule allows on purpose and the Distribution grammar does not - an empty segment. The same screen
     * the {@code OciFormat} applies at the request door, stated the same way so the two cannot answer differently
     * about one name.
     *
     * <p>It used to restate the rule instead of asking for it - {@code .}, {@code ..} and a backslash, spelled out
     * here and again in the format - and the restatement was a character behind: a control-bearing name passed, and
     * {@link #blobKeys} composed a live pointer key out of it. That key is handed to <b>eviction, which deletes</b>,
     * and {@code delete} is not screened by {@link ArtifactStore#key} the way a write is, so the one seam that had to
     * refuse the name was this one. Two copies of a security rule is the &sect;2 shape, and this is what it costs when
     * the copies drift (D-288).
     */
    private static boolean isImageName(String name) {
        if (name.isEmpty() || !ArtifactStore.traversalFree(name)) {
            return false;
        }
        for (String segment : name.split("/", -1)) {
            if (segment.isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
