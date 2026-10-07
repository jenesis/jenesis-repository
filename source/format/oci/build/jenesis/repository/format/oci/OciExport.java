package build.jenesis.repository.format.oci;

import module java.base;
import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.format.OciTags;
import build.jenesis.repository.format.PublishedExport;
import build.jenesis.repository.format.RepositoryExporter;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Withheld;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import build.jenesis.repository.store.ServableNames;

/**
 * One image version pushed through the Distribution API as {@code docker push} sends it: every blob the manifest
 * references - the config and each layer, and for an index each platform's manifest and its own blobs - then the
 * manifest itself under the version's tag.
 *
 * <p><b>Blobs first, each asked for before it is sent.</b> A blob already on the target - the base layer every image
 * of a family shares - is found by its digest and not sent again, which is what makes exporting the second image of
 * a family cheap. A blob is offered in one {@code POST} naming its digest, the monolithic upload the specification
 * defines; a registry that does not take it that way - the specification lets it decline, and {@code registry:2} does
 * - answers {@code 202} with an upload session in {@code Location} instead, and the blob is then put there, naming
 * its digest, as a client finishes any session. Taking that {@code 202} for done would leave the blob unsent and the
 * manifest refused for naming it. Every blob goes up before any manifest; a child manifest of an index goes up by
 * digest before its index, and the tagged manifest last, so a target never holds a manifest naming a blob it lacks.
 *
 * <p><b>A held part withholds the version.</b> A manifest or blob the store withholds is never sent, and an image
 * with one is not exported at all: sending the rest would publish a manifest the target cannot serve whole.
 */
final class OciExport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** How deep an index may nest before the walk gives up - an index of indexes is the deepest a client builds. */
    private static final int DEPTH = 4;

    private OciExport() {
    }

    static RepositoryExporter.Exported export(ArtifactStore store, String name, String reference, ExportTarget target)
            throws IOException {
        if (!OciFormat.isImageName(name)) {
            return RepositoryExporter.Exported.WITHHELD;
        }
        Optional<String> hex = manifest(store, name, reference);
        if (hex.isEmpty() || !exportable(store, hex.get())) {
            return RepositoryExporter.Exported.WITHHELD;
        }
        List<PublishedExport.File> files = new ArrayList<>();
        if (!references(store, name, hex.get(), files, new HashSet<>(), 0)) {
            return RepositoryExporter.Exported.WITHHELD;
        }
        files.add(manifestFile(store, name, reference, hex.get()));
        boolean sent = false;
        List<PublishedExport.File> manifests = new ArrayList<>();
        for (PublishedExport.File file : files) {
            if ("POST".equals(file.request().method())) {
                sent |= blob(file, target);
            } else {
                manifests.add(file);
            }
        }
        RepositoryExporter.Exported exported = PublishedExport.send(manifests, target);
        return sent && exported == RepositoryExporter.Exported.ALREADY_PRESENT
                ? RepositoryExporter.Exported.PUBLISHED
                : exported;
    }

    /** Push one blob unless the target already holds it, answering whether it was sent. */
    private static boolean blob(PublishedExport.File file, ExportTarget target) throws IOException {
        String served = file.served().orElseThrow();
        if (target.sha256(served).equals(Optional.of(file.sha256()))) {
            return false;
        }
        ExportTarget.Response offered = target.send(file.request());
        ExportTarget.Response response = offered;
        if (offered.status() == 202) {
            // The registry opened a session rather than taking the blob whole: finish it where it said.
            String session = offered.location().orElseThrow(() -> new IOException("the target opened an upload "
                    + "session for " + served + " and named no Location under its URL to put the blob at"));
            response = target.send(new ExportTarget.Request("PUT",
                    session + (session.contains("?") ? "&" : "?") + "digest=sha256:" + file.sha256(),
                    file.request().headers(), file.request().body()));
        }
        if (!response.ok() && !target.sha256(served).equals(Optional.of(file.sha256()))) {
            throw new IOException("the target refused " + served + " with " + response.status()
                    + (response.body().isBlank() ? "" : ": " + response.body().strip()));
        }
        return true;
    }

    /** The manifest a tag or a digest names, or empty when it names none. */
    private static Optional<String> manifest(ArtifactStore store, String name, String reference) throws IOException {
        if (reference.startsWith("sha256:")) {
            return Optional.of(OciFormat.hex(reference)).filter(ServableNames::isSha256Hex);
        }
        if (!OciTags.isTag(reference)) {
            return Optional.empty();
        }
        return store.readVersioned("oci/" + name + "/tags/" + reference)
                .map(pointer -> OciFormat.hex(new String(pointer.content(), StandardCharsets.UTF_8).trim()))
                .filter(ServableNames::isSha256Hex);
    }

    private static boolean exportable(ArtifactStore store, String hex) throws IOException {
        return store.exists("blobs/" + hex) && !Withheld.is(store, hex);
    }

    /** Add every blob and child manifest {@code hex} references, before it; false when one of them is withheld. */
    private static boolean references(ArtifactStore store, String name, String hex, List<PublishedExport.File> files,
                                      Set<String> added, int depth) throws IOException {
        if (depth > DEPTH) {
            throw new IOException("the manifest " + hex + " nests indexes deeper than " + DEPTH);
        }
        JsonNode manifest;
        try (InputStream in = store.open("blobs/" + hex)) {
            manifest = JSON.readTree(in);
        }
        for (JsonNode child : manifest.path("manifests")) {
            String childHex = OciFormat.hex(child.path("digest").asString(""));
            if (!ServableNames.isSha256Hex(childHex) || !added.add(childHex)) {
                continue;
            }
            if (!exportable(store, childHex) || !references(store, name, childHex, files, added, depth + 1)) {
                return false;
            }
            files.add(manifestFile(store, name, "sha256:" + childHex, childHex));
        }
        List<String> blobs = new ArrayList<>();
        blobs.add(manifest.path("config").path("digest").asString(""));
        manifest.path("layers").forEach(layer -> blobs.add(layer.path("digest").asString("")));
        manifest.path("fsLayers").forEach(layer -> blobs.add(layer.path("blobSum").asString("")));
        for (String digest : blobs) {
            String blob = OciFormat.hex(digest);
            if (!ServableNames.isSha256Hex(blob) || !added.add(blob)) {
                continue;
            }
            if (Withheld.is(store, blob)) {
                return false;
            }
            if (!store.exists("blobs/" + blob)) {
                continue;   // a foreign layer the image names but no registry holds - the client fetches it elsewhere
            }
            long size = store.size("blobs/" + blob);
            files.add(new PublishedExport.File(new ExportTarget.Request("POST",
                    name + "/blobs/uploads/?digest=sha256:" + blob, Map.of("Content-Type", "application/octet-stream"),
                    ExportTarget.Body.of(size, () -> store.open("blobs/" + blob))),
                    Optional.of(name + "/blobs/sha256:" + blob), blob));
        }
        return true;
    }

    /** The manifest {@code hex} put under {@code reference}, with the media type it was pushed with. */
    private static PublishedExport.File manifestFile(ArtifactStore store, String name, String reference, String hex)
            throws IOException {
        String type = store.readVersioned("oci/.types/" + hex)
                .map(versioned -> new String(versioned.content(), StandardCharsets.UTF_8).trim())
                .orElse(OciFormat.OCI_MANIFEST);
        byte[] content;
        try (InputStream in = store.open("blobs/" + hex)) {
            content = in.readAllBytes();
        }
        return new PublishedExport.File(new ExportTarget.Request("PUT", name + "/manifests/" + reference,
                Map.of("Content-Type", type), ExportTarget.Body.of(content)),
                Optional.of(name + "/manifests/" + reference), hex);
    }
}
