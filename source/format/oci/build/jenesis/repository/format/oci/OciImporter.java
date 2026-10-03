package build.jenesis.repository.format.oci;

import module java.base;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import tools.jackson.databind.json.JsonMapper;

/**
 * Imports a Docker / OCI registry (Nexus {@code docker}, Artifactory {@code docker}) from an incumbent manager.
 * The source presents the Distribution layout, so an asset path carrying {@code /blobs/} is a layer or config and
 * an asset path carrying {@code /manifests/} is a manifest: both are stored by their {@code sha256} digest exactly
 * as {@link OciFormat} stores a push - a layer is just {@code blobs/<hex>}, a manifest additionally records its
 * media type in the {@code oci/.types/<hex>} sidecar and, when referenced by a tag rather than a digest, the
 * {@code oci/<name>/tags/<tag>} pointer. The manifest media type is read from the manifest's own {@code mediaType}
 * field, since an import carries no response headers.
 */
public final class OciImporter implements RepositoryImporter {

    private static final System.Logger LOGGER = System.getLogger(OciImporter.class.getName());

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";

    @Override
    public Optional<ArtifactDescriptor> importTarget(String sourcePath) {
        // Before the empty answer, which means "lay this out unscreened": a traversal-shaped path is refused.
        RepositoryImporter.importablePath(sourcePath, "oci");
        // OCI screens at its manifest (OciManifests), not at the import edge.
        return Optional.empty();
    }

    @Override
    public boolean imports(String sourceFormat) {
        return sourceFormat.equals("docker") || sourceFormat.equals("oci");
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        String rest = RepositoryImporter.importablePath(path, "oci");
        if (rest.startsWith("v2/")) {
            rest = rest.substring("v2/".length());
        }
        int manifests = rest.indexOf("/manifests/");
        if (manifests >= 0) {
            // Capped as on the push path, so a source handing back a huge "manifest" cannot exhaust memory.
            byte[] body = content.readNBytes(OciFormat.MAX_MANIFEST + 1);
            if (body.length > OciFormat.MAX_MANIFEST) {
                throw new IOException("imported manifest at " + rest + " exceeds the " + OciFormat.MAX_MANIFEST
                        + "-byte limit - refused.");
            }
            manifest(rest.substring(0, manifests), rest.substring(manifests + "/manifests/".length()), body, store);
            return;
        }
        if (rest.contains("/blobs/")) {
            store.writeBlob(content);
        }
    }

    private void manifest(String name, String reference, byte[] content, ArtifactStore store) throws IOException {
        // Screened as a push is; a withheld verdict lays out nothing, so the manifest never serves.
        try {
            OciManifests.ingest(name, reference, content, mediaType(content), store, OciManifests.Origin.PUSHED);
        } catch (OciManifests.InvalidManifest invalid) {
            // Skipped, storing nothing; the migration continues.
            LOGGER.log(System.Logger.Level.WARNING, "skipping unparseable imported OCI manifest "
                    + name + "/manifests/" + reference + ": " + invalid.getMessage());
        }
    }

    private static String mediaType(byte[] manifest) {
        try {
            return JSON.readTree(new String(manifest, StandardCharsets.UTF_8)).path("mediaType").asString(OCI_MANIFEST);
        } catch (RuntimeException _) {
            return OCI_MANIFEST;
        }
    }
}
