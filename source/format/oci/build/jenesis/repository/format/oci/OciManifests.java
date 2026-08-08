package build.jenesis.repository.format.oci;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.store.Withheld;
import tools.jackson.databind.json.JsonMapper;

/**
 * The OCI manifest choke point (EPIC 26): the one place a manifest write - a {@code docker push} PUT, a pull-through
 * proxy fetch, or an import walk - runs the same {@link PublishInterceptor} screen chain a single-body publish passes,
 * mapped onto OCI's native serving model rather than the {@code publish/} namespace.
 *
 * <p>OCI is EPIC 26's structural exception: it {@link OciFormat#screened() opts out} of the single-body ingress edge
 * ({@code ScreenedDispatch}) because a {@code /v2/} push is multi-request - a session of blob uploads then a manifest
 * that references them by digest - so no single request body reaches that edge, and OCI serves by digest straight from
 * {@code blobs/<hex>} and {@code oci/<name>/tags/<tag>}, never through a {@code publish/<path>} pointer the edge screen
 * gates. This helper is OCI's own manifest-level choke point: it runs the shared hosted-publish operation
 * {@link Publication#commit} (the very same {@code ComplianceScreen}/inspector chain, since the operation's one screen
 * discovers the identical interceptors) over the manifest and maps the verdict onto OCI's native
 * {@code withheld/<hex>} marker - the marker the serving path in {@link OciFormat} already reads on both the
 * blob-serve and manifest-serve paths. OCI's own layout is the operation's accepted-layout callback: the media-type
 * sidecar is written through the sidecar seam and the tag pointer and stale-hold clear are <em>declared</em> as the
 * commit's {@link Publication.Visibility}, so nothing OCI serves through exists before the sidecar does.
 *
 * <p>The operation's screen stores the manifest bytes content-addressed at the serving key {@code blobs/<hex>}
 * <em>before</em> the chain runs, so on a non-ACCEPT verdict the marker is load-bearing: without it a rejected manifest
 * would remain pullable by digest straight out of {@code blobs/<hex>}. So ACCEPT lays out OCI's native metadata (the
 * {@code oci/types/<hex>} media-type sidecar, the tag pointer for a tag reference) and clears any stale marker, while
 * QUARANTINE and REJECT write the {@code withheld/<hex>} marker and lay out nothing - a held or rejected manifest then
 * 404s by digest and by tag exactly as a withheld blob does, while its already-uploaded layer blobs stay served raw
 * (layers are out of this choke point's scope, screened bytes by bytes belongs to the manifest that names them).
 *
 * <p>No {@link PublishInterceptor} the core ships claims {@code /v2/} coordinates today (no {@code QualityInspector}
 * inspects OCI), so with the empty discovered chain every manifest ACCEPTs and this is byte-for-byte the raw write it
 * replaced. The live effect is the choke point itself: a deny-list interceptor now bites an OCI coordinate, the
 * after-commit observers and per-verdict metrics fire on a manifest push, and a future Docker/OCI inspector plugged into
 * the same SPI just works here without touching this format - that is the point of routing every manifest write through
 * one screen rather than three raw writes.
 */
final class OciManifests {

    private static final String OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private OciManifests() {
    }

    /** Thrown by {@link #ingest} when the body is not a servable manifest - larger than {@link OciFormat#MAX_MANIFEST}
     *  (4 MiB) or not parseable as a JSON object - so nothing was stored or laid out. Each ingest edge maps it to its own
     *  fail-closed response: a PUT to {@code 400 MANIFEST_INVALID}, a proxy to serve-through-without-caching, an import to
     *  a logged skip. Validating here (before {@code screen} content-addresses the bytes at {@code blobs/<hex>}) keeps an
     *  unparseable manifest from ever being laid out - which would otherwise degrade a later retroactive hold's layer
     *  enumeration to manifest-only, leaving the named layers servable by digest under a standing hold. */
    static final class InvalidManifest extends Exception {
        InvalidManifest(String message) {
            super(message);
        }
    }

    /** The outcome of a screened manifest write: the chain's {@link PublishInterceptor.Disposition} and the SHA-256 hex
     *  the manifest was stored under ({@code blobs/<hex>}) - present whatever the verdict, since {@code screen} stores
     *  the bytes content-addressed before it gates. A push maps the disposition to a protocol code; an import and a
     *  proxy have no client response and only need the layout the helper already applied. */
    record Ingested(PublishInterceptor.Disposition disposition, String hex) {
    }

    /**
     * Store the manifest, screen it against its neutral {@code oci} coordinate, and map the verdict onto OCI's native
     * serving model. On {@code ACCEPT} write the media-type sidecar, link the tag pointer (a tag reference only), clear
     * any stale {@code withheld/<hex>} marker and fire the after-commit observers; on {@code QUARANTINE}/{@code REJECT}
     * write the {@code withheld/<hex>} marker the serving path reads and lay out nothing.
     */
    static Ingested ingest(String name, String reference, byte[] content, String mediaTypeOrNull, ArtifactStore store)
            throws IOException, InvalidManifest {
        // Validate the manifest is a servable manifest BEFORE screen() content-addresses it at blobs/<hex>: screen
        // stores the bytes before the chain runs, so a rejected-here body is never laid out and can never later need an
        // un-enumerable hold (OciBlobLayout.blobHashes degrades a malformed/over-cap manifest to manifest-only, leaving
        // its named layers servable by direct digest under a standing KEV/license hold). The size check is load-bearing:
        // the PUT and import edges pre-cap at MAX_MANIFEST, but the PROXY edge feeds a body bounded only by the 64 MiB
        // fetch cap, so a > 4 MiB proxied manifest can reach here. isObject() (not merely "parses") is the servable-
        // manifest shape - a top-level array/scalar has no config/layers to enumerate, the same degrade the parse guard
        // prevents. A hostile / non-JSON body is contained, never thrown as a raw Jackson RuntimeException out of ingest.
        if (content.length > OciFormat.MAX_MANIFEST) {
            throw new InvalidManifest("manifest exceeds the " + OciFormat.MAX_MANIFEST + "-byte limit");
        }
        if (!parsesAsJsonObject(content)) {
            throw new InvalidManifest("manifest is not a parseable JSON object");
        }
        String path = "/v2/" + name + "/manifests/" + reference;
        // The neutral descriptor other formats build for the edge: ecosystem oci, coordinate the image name, version
        // the reference, path the request path - what a deny-list interceptor keys on and a metric/observer records.
        ArtifactDescriptor descriptor =
                new ArtifactDescriptor("oci", name, reference, path, mediaTypeOrNull, false, null, -1L);
        // The one hosted-publish choreography (Publication.commit): the manifest is stored content-addressed at
        // blobs/<hex> (buffering the manifest - small metadata - is fine; a layer blob would never be buffered here),
        // the discovered chain runs once, and on ACCEPT the layout below writes OCI's own native metadata. No publish/
        // pointer is linked: OCI owns its own layout, declared as the operation's Visibility so the media-type sidecar
        // is written before anything makes the manifest servable, and the after-commit observers fire only once it is.
        Publication.Commit commit = new Publication(store).commit(descriptor, new ByteArrayInputStream(content),
                // Last-writer-wins: an OCI tag is mutable by protocol and a by-digest re-push is the same bytes.
                Publication.Republish.overwrite(),
                accepted -> {
                    // The media-type sidecar is a parse result, not a serving surface - written first, through the
                    // sidecar seam, which refuses a publish/ key so a pointer can never be smuggled in ahead of it.
                    accepted.sidecar("oci/types/" + accepted.hash(), (mediaTypeOrNull == null
                            ? OCI_MANIFEST : mediaTypeOrNull).getBytes(StandardCharsets.UTF_8));
                    return Publication.Visibility
                            .through((hex, target) -> {
                                if (!reference.startsWith("sha256:")) {
                                    OciFormat.linkTag(target, "oci/" + name + "/tags/" + reference, "sha256:" + hex);
                                }
                            })
                            .andThrough((hex, target) -> clearStaleHold(target, path, hex));
                });
        String hex = commit.hash();
        if (commit.disposition() != PublishInterceptor.Disposition.ACCEPT) {
            // Load-bearing: the screen already stored the bytes at the serving key blobs/<hex>, so without this marker
            // the withheld manifest would be pullable by digest. Routing through Withheld.mark (rather than a raw
            // withheld/<hex> write) joins the OCI choke point to the withhold-change feed and the one marker idiom;
            // the disposition body is dropped (marker presence is the signal, never read). No sidecar, no tag link.
            Withheld.mark(store, hex);
        }
        return new Ingested(commit.disposition(), hex);
    }

    /**
     * Clear a stale hold: an identical manifest previously withheld and now accepted (a lifted advisory, a re-push
     * after a rule change) must serve again - the marker keyed by content hash is the only thing retracting
     * {@code blobs/<hex>}, so clearing it is a visibility write and belongs in the accepted layout's declared
     * visibility, beside the tag link and before the observers.
     *
     * <p>Do NOT clear it while a retroactive hold's review pointer still stands on this path (&sect;6 Q-D): a
     * screen-time ACCEPT must not tear down a standing KEV/license/reachability hold on the same bytes. The chain probe
     * ({@code ServableNames.disclosable} under {@code HIDE_WITHHELD} - the {@code /quarantine<path>} review pointer
     * face, no blob stat) narrows the clear; where the OCI request path carries no such pointer the guard passes and
     * the clear happens exactly as before, so it is safe either way. The same-path probe reads only THIS path's
     * pointer: a byte-identical sibling image held under a DIFFERENT alias keeps its own {@code /quarantine} pointer
     * body == hex, which the same-path probe never sees, so an accepted re-push under a released alias would
     * un-withhold the still-held sibling. The cross-alias scan ({@code Publication.quarantineAliasExists} - the
     * downstream release paths' {@code withheldByAnotherAlias} proof, homed in the free store that owns the
     * {@code /quarantine} convention) closes that: clear only when no OTHER live quarantine pointer outside this
     * manifest's own served path still holds the hash. Fail-closed - it only ever NARROWS the clear, so worst case a
     * marker that should clear waits for the review flow.
     */
    private static void clearStaleHold(ArtifactStore store, String path, String hex) throws IOException {
        if (new ServableNames(store).disclosable(path, ServableNames.Policy.HIDE_WITHHELD)
                && !new Publication(store).quarantineAliasExists(hex, Set.of(path))) {
            Withheld.clear(store, hex);
            // The guard above is a read-then-clear: a concurrent enforce sweep (KEV/license/reachability) that
            // links a /quarantine pointer for this hash AFTER the guard read but before this clear would leave
            // that hold's still-live claim with its content-addressed marker gone - and the OCI serve gate keys
            // withheld on the MARKER, so the held image would disclose for up to one enforce interval. This is
            // the request-path twin of the reconcile-vs-enforce race #207 closed on the WithheldReconcileTask
            // path with a post-clear re-verify. Re-run the FULL guard face against fresh truth now that the clear
            // has landed and RE-MARK if a hold reappeared on the hash by ANY route - covering both landing sites:
            //  - the pointer face (quarantineAliasExists with an EMPTY exclusion set): a /quarantine pointer for
            //    the hash on ANY served path, including THIS manifest's own path. The earlier reverify re-ran the
            //    cross-alias probe with `path` EXCLUDED (Set.of(path)) - mirroring the guard, which relies on the
            //    interceptor face below to cover this path - so a same-path enforce that links /quarantine<path>
            //    in the window was invisible to it and the marker stayed wrongly cleared (Audit-28 A5-F1). The
            //    empty exclusion catches the same-path pointer too, and it stays testable without an interceptor.
            //    (A clean ACCEPT writes no /quarantine<path> pointer, so the no-race case still finds nothing.)
            //  - the interceptor face (!disclosable(path)): a downstream deployment's hold interceptor withholds
            //    the path directly; in the core, with no interceptor, this leg is inert and the pointer scan
            //    carries the proof.
            // The marker is absent post-clear, so Withheld.mark's atomic-create CAS lands and the hold is
            // re-established. The no-hold case (the free-standing rule-change re-push that clears a stale REJECT
            // marker) finds neither face and the marker stays cleared. Per-entry hostile pointers are contained
            // inside the seam faces; a genuine store IOException propagates (fail-closed, exactly as the guard
            // read does) rather than re-marking on an error.
            if (!new ServableNames(store).disclosable(path, ServableNames.Policy.HIDE_WITHHELD)
                    || new Publication(store).quarantineAliasExists(hex, Set.of())) {
                Withheld.mark(store, hex);
            }
        }
    }

    /** Whether the bytes parse as a JSON object - the shape every OCI manifest and image index takes, the shape whose
     *  {@code config}/{@code layers}/{@code manifests} a hold's layer enumeration reads. A parse failure or a non-object
     *  top level (array/scalar) is contained here rather than thrown as a raw Jackson {@code RuntimeException}. */
    private static boolean parsesAsJsonObject(byte[] content) {
        try {
            return JSON.readTree(new String(content, StandardCharsets.UTF_8)).isObject();
        } catch (RuntimeException notJson) {
            return false;
        }
    }
}
