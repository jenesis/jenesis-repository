package build.jenesis.repository.format.oci;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Known;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.store.Withheld;

/**
 * The one place a manifest write - a push, a pull-through fetch or an import - runs the {@link PublishInterceptor}
 * chain, mapped onto OCI's own serving model.
 *
 * <p>OCI {@linkplain OciFormat#screened() opts out} of the single-body ingress edge: a push is a session of blob
 * uploads then a manifest naming them by digest, and OCI serves straight from {@code blobs/<hex>} and
 * {@code oci/<name>/tags/<tag>} rather than through a {@code publish/} pointer. So the manifest runs through
 * {@link Publication#commit}, whose screen discovers the same interceptors, with OCI's layout as the accepted-layout
 * callback: the media-type sidecar first, then the tag pointer and stale-hold clear declared as the commit's
 * {@link Publication.Visibility}.
 *
 * <p>The screen stores the bytes at {@code blobs/<hex>} before the chain runs, so on a held or rejected verdict the
 * {@code withheld/<hex>} marker is what keeps the manifest from serving by digest; nothing else is laid out. Layer
 * blobs are outside this choke point: the manifest that names them is what is screened.
 */
final class OciManifests {

    private static final Logger LOGGER = LoggerFactory.getLogger(OciManifests.class);

    private static final String OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";

    private OciManifests() {
    }

    /** Thrown by {@link #ingest} when the body is larger than {@link OciFormat#MAX_MANIFEST} or not a JSON object, so
     *  nothing was stored: a PUT answers {@code 400 MANIFEST_INVALID}, a proxy serves through without caching, an
     *  import logs and skips. A stored manifest whose layers cannot be enumerated would leave them servable under a
     *  later hold. */
    static final class InvalidManifest extends Exception {
        InvalidManifest(String message) {
            super(message);
        }
    }

    /** Where a manifest written by tag came from, recorded beside the tag: a tag relayed from the upstream a proxy
     *  fills from is resolved there again once the node forgets it, while one pushed here - by a push or an import -
     *  keeps answering as it stands. */
    enum Origin {

        PUSHED {
            @Override
            void record(ArtifactStore store, String name, String tag) throws IOException {
                String marker = relayed(name, tag);
                if (store.exists(marker)) {
                    store.delete(marker);
                }
            }
        },

        RELAYED {
            @Override
            void record(ArtifactStore store, String name, String tag) throws IOException {
                store.write(relayed(name, tag), InputStream.nullInputStream());
            }
        };

        abstract void record(ArtifactStore store, String name, String tag) throws IOException;
    }

    /** The marker saying the tag {@code tag} of image {@code name} was relayed from the upstream. */
    static String relayed(String name, String tag) {
        return "oci/.relayed/" + name + "/" + tag;
    }

    /** The chain's {@link PublishInterceptor.Disposition}, the hex the manifest is stored under, present whatever
     *  the verdict, and what a held or refused client is told ({@code Publication.explanation}). */
    record Ingested(PublishInterceptor.Disposition disposition, String hex, Optional<String> subject,
                    String explanation) {
    }

    /**
     * Store the manifest, screen it against its neutral {@code oci} coordinate, and map the verdict onto OCI's native
     * serving model. On {@code ACCEPT} write the media-type sidecar, link the tag pointer (a tag reference only), clear
     * any stale {@code withheld/<hex>} marker and fire the after-commit observers; on {@code QUARANTINE}/{@code REJECT}
     * write the {@code withheld/<hex>} marker the serving path reads and lay out nothing.
     */
    static Ingested ingest(String name, String reference, byte[] content, String mediaTypeOrNull, ArtifactStore store,
                           Origin origin) throws IOException, InvalidManifest {
        // Validated before the screen stores anything. The size check matters on the proxy edge, whose body is bounded
        // only by the fetch cap; a top-level array or scalar has no layers to enumerate, so only an object passes.
        if (content.length > OciFormat.MAX_MANIFEST) {
            throw new InvalidManifest("manifest exceeds the " + OciFormat.MAX_MANIFEST + "-byte limit");
        }
        Optional<OciReferrers.Manifest> parsed = OciReferrers.Manifest.of(content);
        if (parsed.isEmpty()) {
            throw new InvalidManifest("manifest is not a parseable JSON object");
        }
        OciReferrers.Manifest manifest = parsed.get();
        String servedType = mediaTypeOrNull == null ? OCI_MANIFEST : mediaTypeOrNull;
        String path = "/v2/" + name + "/manifests/" + reference;
        ArtifactDescriptor descriptor =
                new ArtifactDescriptor("oci", name, reference, path, mediaTypeOrNull, false, null, -1L);
        // No publish/ pointer is linked: OCI's layout is the commit's Visibility, so the sidecar exists before the
        // manifest serves and the observers fire once it does.
        Publication.Commit commit = new Publication(store).commit(descriptor, new ByteArrayInputStream(content),
                // Last-writer-wins: an OCI tag is mutable by protocol and a by-digest re-push is the same bytes.
                Publication.Republish.overwrite(),
                accepted -> {
                    // The sidecar seam refuses a publish/ key, so nothing serves ahead of it.
                    accepted.sidecar("oci/.types/" + accepted.hash(), servedType.getBytes(StandardCharsets.UTF_8));
                    return Publication.Visibility
                            .through((hex, _, target) -> {
                                if (!reference.startsWith("sha256:")) {
                                    OciFormat.linkTag(target, "oci/" + name + "/tags/" + reference, "sha256:" + hex);
                                    origin.record(target, name, reference);
                                    // The tag list and catalog are written on the push, not enumerated per read.
                                    new OciListings(target).refresh(name, reference);
                                }
                            })
                            .andThrough((hex, _, target) -> clearStaleHold(target, path, hex, descriptor))
                            // A referrer joins its subject's index once it serves, and before the observers hear of
                            // it: the signature completion re-reads the subject through that index.
                            .andThrough((hex, _, target) -> new OciReferrers(target)
                                    .record(name, hex, content, manifest, servedType, true));
                });
        String hex = commit.hash();
        if (commit.disposition() != PublishInterceptor.Disposition.ACCEPT) {
            // The bytes are already at blobs/<hex>; the marker keeps them from serving by digest.
            Withheld.mark(store, hex, descriptor);
            if (commit.disposition() == PublishInterceptor.Disposition.QUARANTINE) {
                // A held referrer is recorded but not listed: a reviewer's release lists it, a discard never does.
                new OciReferrers(store).record(name, hex, content, manifest, servedType, false);
            }
        }
        return new Ingested(commit.disposition(), hex,
                commit.disposition() == PublishInterceptor.Disposition.REJECT
                        ? Optional.empty() : manifest.subject(), commit.explanation());
    }

    /**
     * Clears a stale hold, so an identical manifest withheld before and accepted now serves again - the content-keyed
     * marker is all that retracts {@code blobs/<hex>}.
     *
     * <p>It only ever narrows the clear: not while a review pointer stands on this path, and not while a quarantine
     * pointer under another alias still holds the hash ({@link Publication#quarantineAlias}), since that sibling's
     * bytes are the same blob. A review queue that cannot be enumerated whole leaves the marker: leaving one that
     * should clear is recoverable through review, clearing one that should stay discloses held bytes.
     */
    private static void clearStaleHold(ArtifactStore store, String path, String hex, ArtifactDescriptor subject)
            throws IOException {
        if (!Withheld.is(store, hex)) {
            return;   // the common accept pays one marker read, never the review scan
        }
        if (!new ServableNames(store).disclosable(path, ServableNames.Policy.HIDE_WITHHELD)) {
            return;
        }
        Known.Determined<String> otherAlias;
        switch (new Publication(store).quarantineAlias(hex, Set.of(path))) {
            case Known.Unknown<String> unknown -> {
                LOGGER.warn("oci accept-clear: leaving withheld/{} in place for {} - {}", hex, path, unknown.detail());
                return;
            }
            case Known.Determined<String> determined -> otherAlias = determined;
        }
        if (Withheld.clear(store, hex, otherAlias, subject)) {
            // The guard was a read-then-clear, so a hold linked in between - on this path or any other, by pointer or
            // by interceptor - would lose its marker. Re-checked against fresh truth with no path excluded, and
            // re-marked on any hold found or on a queue that cannot be enumerated: once the marker is gone, not being
            // able to prove nothing holds it has the same answer as something holding it. A store error propagates.
            if (!new ServableNames(store).disclosable(path, ServableNames.Policy.HIDE_WITHHELD)
                    || !(new Publication(store).quarantineAlias(hex, Set.of()) instanceof Known.Absent<String>)) {
                Withheld.mark(store, hex, subject);
            }
        }
    }

}
