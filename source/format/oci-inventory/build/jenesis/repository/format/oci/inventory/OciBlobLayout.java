package build.jenesis.repository.format.oci.inventory;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.blobs.BlobRoots;
import build.jenesis.repository.format.BlobReferences;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.format.OciTags;
import build.jenesis.repository.format.OciTagIndex;
import build.jenesis.repository.format.Checksums;

/**
 * The OCI inventory layout: a capability-only {@link RepositoryFormat} and {@link BlobLayout} that teaches the
 * store-backed inventory OCI's on-store conventions, so a retroactive KEV or licence hold on an image withholds every
 * served face (manifest by tag and digest, config, layers) and releases cleanly. {@code OciFormat} implements neither
 * layout SPI itself.
 *
 * <p><b>Never dispatches.</b> {@link #handles} is always {@code false}: {@code FormatDispatcher} and the first-match
 * idioms serve the first format whose {@code handles} matches in unspecified discovery order, so a layout claiming
 * {@code /v2/} could steal serving from {@code OciFormat}. This provider only answers the inventory's capability
 * lookups ({@link #describe}, {@link #servedPaths}, {@link #blobKeys}, {@link #blobHashes}) through its non-handling
 * {@code BlobLayout} fallbacks; {@link #handle} is unreachable and throws.
 *
 * <p><b>Store-key conventions, not {@code OciFormat}'s module</b> (which exports only to its own test): a manifest,
 * config or layer blob is at {@code blobs/<hex>}; a tag pointer is {@code oci/<name>/tags/<tag>} whose body is
 * {@code "sha256:" + hex}; a manifest's config and layer digests live inside the manifest JSON, which this class never
 * parses - the free {@code BlobReferences} seam, inherited through {@code BlobRoots}, answers for it.
 *
 * <p><b>Cross-alias and tag mutability.</b> {@link #blobHashes} marks every referenced digest, because the bytes are
 * what is held - so a hold on one image withholds a shared base layer for every image using it while their manifests
 * keep serving. On release the cross-alias guard ({@code HoldLifecycle.withheldByAnotherAlias}) consults each
 * still-held sibling's full {@link #blobHashes} set, not its {@code /quarantine} pointer body (a manifest digest that
 * never carries a shared layer hash), so releasing image A keeps a layer marker held image B still needs. A version
 * keyed by a tag resolves its digests at sweep and release time, so a re-push to a held tag serves the new bytes until
 * the next pass re-marks it; the {@code /quarantine} review pointer meanwhile keeps the hold open.
 *
 * <p><b>How an OCI blob is kept alive and let go.</b> Collection has two halves, and neither is {@link #blobHashes},
 * which serves holds alone.
 * <ul>
 *   <li><b>Mark.</b> The mark phase reads a pointer's body through {@code ServableNames.hash}, so a tag pointer's
 *       {@code sha256:} body names its manifest blob. A config or layer digest sits inside the manifest behind no key,
 *       and a manifest pulled by digest has no tag pointer, so a body-only scan would delete a live image's layers. The
 *       mark phase therefore asks the format owning the visited key's root what it keeps alive -
 *       {@code BlobReferences}, which resolves the manifest from either key naming it (the tag pointer, or the
 *       {@code oci/.types/<hex>} media-type sidecar {@code OciManifests.ingest} writes for every accepted manifest) and
 *       lends the manifest, an index's sub-manifests and each one's config, layer and legacy {@code fsLayers}
 *       digests.</li>
 *   <li><b>Sweep.</b> {@link #blobKeys} names the sidecar as well as the tag pointer, guarded so a sibling tag on the
 *       same digest keeps it; otherwise the sidecar would keep lending an evicted image's blobs for ever.</li>
 * </ul>
 * The halves meet at one key, and the invariant runs one way: a sidecar may outlive its image (storage, recoverable),
 * but an image must never outlive its sidecar (its layers, deleted). Whether the installed core lends OCI's references
 * is a property of the deployment's module graph, asserted by a real mark-and-sweep over a real store.
 *
 * <p><b>What retention does not cover.</b> A manifest that only ever existed by digest has no eviction handle -
 * {@link #blobKeys} is empty for it, since it may be one platform of an index a live tag serves. Only a client's own
 * {@code DELETE} retires it, through {@link #removalKeys}. A blob no manifest names (an abandoned upload, an orphaned
 * layer) is still collected. {@link #blobHashes} is never consulted by the collector: it asks the same seam but
 * degrades where the collector refuses, because under-enforcing a hold is safe while under-reporting to a deleter
 * destroys bytes.
 */
public final class OciBlobLayout implements RepositoryFormat, BlobLayout {

    private static final Logger LOGGER = LoggerFactory.getLogger(OciBlobLayout.class);

    @Override
    public String name() {
        // Distinct from the format's "oci", so RepositoryFormat.installed("oci") and the format toggles stay
        // unambiguous.
        return "oci-layout";
    }

    @Override
    public boolean offered() {
        return false;
    }

    @Override
    public boolean handles(String path) {
        // Always false: never dispatches, proxies or imports (see the class javadoc); OciBlobLayoutTest pins it.
        return false;
    }

    @Override
    public void serve(FormatExchange exchange, ArtifactStore store) {
        throw new IllegalStateException(
                "OciBlobLayout is a capability-only inventory layout (handles() is always false) and never serves a request");
    }

    @Override
    public String ecosystem() {
        // The ecosystem OciManifests.ingest stamps its descriptor with, so inventory rows, hold records and this layout
        // share one key.
        return "oci";
    }

    /**
     * The store-key root this format keeps its pointers and documents under, so the reference scan walks it.
     *
     * <p><b>Load-bearing.</b> It is the only way OCI content reaches the mark phase: a visited key's answer is the blob
     * its own body names plus whatever the owning format lends through {@code BlobReferences} - the config, layer and
     * sub-manifest digests no store key names. Without this root no OCI blob is reachable to the scan, because a key
     * the walk never visits is one no lender is asked about.
     *
     * <p>The declaration is inherited ({@code BlobRoots extends BlobReferences}), so the roots the scan reads and the
     * roots the collector offers a key under are one list. This layout lends nothing itself: {@code OciFormat} owns the
     * manifest dialect and answers for every {@code oci/} key.
     */
    @Override
    public List<String> blobRoots() {
        return List.of("oci");
    }

    /**
     * The store keys an eviction or discard of one image version deletes: the tag pointer, and the manifest's
     * {@code oci/.types/<hex>} sidecar when this version is the last live tag holding it. A digest reference names no
     * pointer (one {@code withheld/<hex>} marker retracts it, which {@code discardBlobs} never lifts), so it is empty;
     * a client's {@code DELETE} by digest goes through {@link #removalKeys}.
     *
     * <p><b>Why the sidecar is an eviction key.</b> {@code BlobReferences} lends the scan an image's blobs from either
     * key naming its manifest - the tag pointer or the sidecar, a digest-only image's only durable record. An evicted
     * image whose sidecar stayed would keep its blobs marked for ever, so deleting the tag pointer alone is not an
     * eviction. The pull path also reads the sidecar for a manifest's media type, which is the second reason for the
     * guard below.
     *
     * <p><b>The sibling-tag guard.</b> Two tags may resolve to one manifest digest, and the sidecar is keyed by the
     * digest alone, so it is returned only when no other live tag pointer under {@code oci/} resolves to the same hex -
     * the shape of {@code HoldLifecycle.withheldByAnotherAlias}. It fails closed: anything unreadable keeps the
     * sidecar, because keeping it wastes one small object while deleting it early un-marks a live sibling's layers and
     * strips its media type.
     *
     * <p><b>Cost.</b> The digest-to-tags index and each named tag's pointer - the manifest's own tags, not the
     * repository's. A digest reference and a dead tag pointer answer before the index, and a manifest with no sidecar
     * costs one {@code exists}.
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
        if (hex == null || !store.exists("oci/.types/" + hex)) {
            return List.of(key);                // no sidecar to retire (or a body that names no manifest)
        }
        if (sharedByAnotherTag(key, hex, store)) {
            return List.of(key);                // a sibling alias still needs the sidecar - delete less
        }
        return List.of(key, "oci/.types/" + hex);
    }

    /** {@link #blobKeys}, plus, for a digest reference, the manifest's {@code oci/.types/<hex>} sidecar when no live
     *  tag pointer still names it. A client deleting a manifest by digest has already removed the tags naming it, and
     *  asks for the manifest itself; a retention eviction of the same row takes nothing, since a digest-pushed manifest
     *  is typically one platform of an index a live tag serves. The sibling guard is the tag eviction's, witnessed by
     *  the sidecar itself. */
    @Override
    public List<String> removalKeys(String coordinate, String version, ArtifactStore store) throws IOException {
        if (!version.startsWith("sha256:")) {
            return blobKeys(coordinate, version, store);
        }
        String hex = hex(version);
        if (hex == null || !isImageName(coordinate)) {
            return List.of();
        }
        String sidecar = "oci/.types/" + hex;
        if (!store.exists(sidecar) || sharedByAnotherTag(sidecar, hex, store)) {
            return List.of();
        }
        return List.of(sidecar);
    }

    /**
     * Whether a live tag pointer other than {@code witness} resolves to the manifest {@code hex} - the guard that keeps
     * {@code oci/.types/<hex>} while any sibling tag serves the manifest. {@code witness} is a key the caller just
     * read: the tag pointer being evicted, or the sidecar when a manifest is removed by digest.
     *
     * <p>The answer comes from the digest-to-tags index ({@link OciTagIndex}), each tag confirmed by reading its
     * pointer; the index is a superset of the live tags by its write order.
     *
     * <p><b>Fail-closed, and self-checking.</b> A store failure answers {@code true} - the sidecar could not be proved
     * unshared - rather than throwing, so the rest of the eviction (the tag pointer that stops serving) still happens.
     * A tag-pointer witness must appear in the index: a tag linked before the index existed is missing from it, as its
     * siblings may be, so its absence keeps the sidecar.
     */
    private static boolean sharedByAnotherTag(String witness, String hex, ArtifactStore store) {
        try {
            OciTagIndex.Tag own = OciTagIndex.tag(witness);
            List<OciTagIndex.Tag> entered = OciTagIndex.entered(store, hex);
            if (own != null && !entered.contains(own)) {
                return withheld(witness, hex, "the digest-to-tags index has no entry for " + witness
                        + ", so it predates the tag and cannot speak for its siblings");
            }
            for (OciTagIndex.Tag tag : entered) {
                if (!tag.equals(own) && OciTagIndex.names(store, tag, hex)) {
                    return true;
                }
            }
            return false;
        } catch (IOException | RuntimeException unreadable) {
            return withheld(witness, hex, "the digest-to-tags index could not be read: " + unreadable);
        }
    }

    /** Keep the sidecar and log why, so "retained rather than reclaimed" is never silent. Always answers
     *  {@code true}. */
    private static boolean withheld(String own, String hex, String why) {
        LOGGER.warn("Could not prove the OCI manifest sidecar oci/.types/{} unshared while evicting {}, so it is kept: {}. "
                + "Reclamation of that manifest is deferred to the next eviction of one of its tags; nothing that "
                + "serves is affected.", hex, own, why);
        return true;
    }

    /** The request path this image version occupies - one review handle per live version, the tagged or digest manifest
     *  path. Serving retraction for every alias comes from the content-addressed {@code withheld/<hex>} markers, so the
     *  review pointer only provides queue visibility and the cross-alias guard. */
    @Override
    public List<String> servedPaths(String coordinate, String version, ArtifactStore store) throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            return List.of();   // the shared per-part coordinate screen, beside blobKeys' own name/tag screen
        }
        Optional<String> hex = manifestHex(coordinate, version, store);
        if (hex.isEmpty() || !store.exists("blobs/" + hex.get())) {
            return List.of();
        }
        return List.of("/v2/" + coordinate + "/manifests/" + version);
    }

    /** The coordinate a manifest request path carries: {@code /v2/<name>/manifests/<ref>} maps to
     *  {@code ("oci", name, ref)} under the format's image-name, tag and digest validation. Every other {@code /v2/}
     *  path names no version and is empty, as the {@link BlobLayout#describe} contract prescribes. */
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
     * The content hashes an OCI image version serves from {@code blobs/} - what a retroactive withhold marks and a
     * name-enumeration screen probes. A tag pointer's body is {@code sha256:<hex>} and the config and layer digests sit
     * inside the manifest, so this derives the set from the manifest, in order:
     * <ol>
     *   <li>the manifest hex first - {@code blobHashes().getFirst()} is the {@code /quarantine} review handle's link
     *       target, which keeps {@code withheldByAnotherAlias} meaningful;</li>
     *   <li>for an image index, each sub-manifest digest and its config and layers, expanded with a work-list so a
     *       hostile nested index cannot overflow the stack;</li>
     *   <li>the config digest, each layer digest and each legacy {@code fsLayers} blobSum.</li>
     * </ol>
     * <p><b>One derivation.</b> The manifest is not parsed here: the free {@code OciFormat.references} answers the same
     * question from the same bytes for the collector, and two derivations that disagree either delete a held image's
     * live blob or serve a layer through a hold. This resolves the manifest hex and hands the seam the
     * {@code oci/.types/<hex>} key, which names the manifest tagged or not.
     *
     * <p><b>Opposite failure postures.</b> The seam throws for a present but unenumerable manifest (its clause 3: a
     * short list handed to a deleter is data loss), while the callers here - a console browse, a KEV sweep, a release -
     * would let one corrupt manifest fail a whole pass. So this catches exactly {@link BlobReferences.Unresolvable},
     * degrades to the manifest hex and logs a warning naming {@code discard}, and lets a plain {@link IOException}
     * propagate rather than silently under-enforce. A degraded sub-manifest stays silent, since an index entry may
     * legitimately point at a layer.
     *
     * <p><b>No installed OCI format</b> degrades the same way: this module does not require {@code format.oci}, so it
     * loads without OCI serving, and {@link #lender()} resolves the lender per call.
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
            // The sidecar key names this manifest tagged or not, and the seam reads the hex off the key.
            references = lender.references("oci/.types/" + hex, store);
        } catch (BlobReferences.Unresolvable unenumerable) {
            // Only this refusal: a store outage keeps propagating. Throwing would fail the whole streamed sweep.
            return degraded(coordinate, version, hex, unenumerable.getMessage());
        }
        // A key naming a manifest by digest cannot honestly lend nothing, so empty is the same "cannot enumerate".
        return references.isEmpty()
                ? degraded(coordinate, version, hex, "the installed OCI format lends nothing for that manifest's "
                        + "sidecar key, so its config and layer digests are not enumerable here")
                : references;
    }

    /** The manifest-only answer plus the warning that keeps it from being silent - {@link #blobHashes}' one degrade,
     *  whatever its cause. It under-enforces a hold, the safe direction here. */
    private static List<String> degraded(String coordinate, String version, String hex, String why) {
        LOGGER.warn("OCI hold/enumeration for {}:{} degraded to manifest-only ({}): {} - consider discard",
                coordinate, version, hex, why);
        return List.of(hex);
    }

    /** The installed format owning the {@code oci/} manifest dialect, or {@code null} when none is installed - resolved
     *  through {@link BlobReferences#installed()}, as the collector's mark phase resolves its lenders. This layout is a
     *  lender too ({@link BlobRoots} extends the seam) but lends nothing, so it is filtered out by type. Resolved per
     *  call, so a format switched off ({@code jenrepo.oci=false}) stops lending at once. */
    private static BlobReferences lender() {
        for (BlobReferences lender : BlobReferences.installed()) {
            if (!(lender instanceof BlobRoots) && lender.blobRoots().contains("oci")) {
                return lender;
            }
        }
        return null;
    }

    /** Resolve an image reference to the manifest hex: a digest reference is the hex; a tag reference reads the
     *  {@code oci/<name>/tags/<tag>} pointer and strips {@code sha256:}. Empty when malformed or the pointer is absent
     *  or does not name a real digest. */
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

    /** The {@code blobs/<hex>} key a manifest or blob request path serves from: a digest reference names it, a tag
     *  reference through its pointer. The signature screen reads an image's cosign signature artifact through it - the
     *  {@code sha256-<hex>.sig} manifest by tag, then each payload blob by digest. */
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

    /** The bare lower-case 64-hex digest of a {@code sha256:<hex>} or bare reference, or {@code null} when it is not
     *  a real sha256 digest - so a tag typo or a {@code ..}-laced reference never becomes a hash. */
    private static String hex(String digest) {
        if (digest == null) {
            return null;
        }
        int colon = digest.indexOf(':');
        String hex = colon < 0 ? digest : digest.substring(colon + 1);
        return Checksums.isSha256Hex(hex) ? hex : null;
    }

    /** The image and tag a stored pointer names, from {@link #tagPointer}'s grammar, so the shared inventory back-fill
     *  can record a tagged image. The descriptor carries the coordinate only: a pointer's size and hash belong to the
     *  blob it names. */
    @Override
    public Optional<ArtifactDescriptor> describePointer(String key) {
        String[] tagged = tagPointer(key);
        return tagged == null
                ? Optional.empty()
                : Optional.of(new ArtifactDescriptor("oci", tagged[0], tagged[1], key, null, false, null, 0L));
    }

    /**
     * The {@code (image name, tag)} a stored {@code oci/} key names when it is a tag pointer, or {@code null}. A tag
     * pointer is {@code oci/<name>/tags/<tag>}: the first {@code tags} segment ends the name and exactly one segment
     * follows. The format's own spaces under {@code oci/} begin with a dot, which no image name may, and both parts are
     * screened by the serving path's Distribution grammar, so a hostile key never decodes into a coordinate this format
     * would not have written.
     */
    static String[] tagPointer(String key) {
        if (!key.startsWith("oci/")) {
            return null;                        // the root itself, were it ever a stored key, names no image
        }
        String[] segments = key.substring("oci/".length()).split("/");
        if (segments.length < 3 || segments[0].startsWith(".")) {
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
     * Distribution grammar's refusal of an empty segment - the screen {@code OciFormat} applies at the request door.
     *
     * <p>It asks the store for its rule rather than restating it: {@link #blobKeys} composes keys handed to eviction,
     * which deletes, and {@code delete} is not screened by {@link ArtifactStore#key} as a write is, so this is the seam
     * that must refuse a control-bearing name.
     */
    private static boolean isImageName(String name) {
        if (name.isEmpty() || !ArtifactStore.traversalFree(name)) {
            return false;
        }
        for (String segment : name.split("/", -1)) {
            if (segment.isEmpty() || segment.startsWith(".")) {
                return false;
            }
        }
        return true;
    }
}
