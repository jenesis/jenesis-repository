package build.jenesis.repository.format.npm;

import module java.base;
import module org.apache.commons.compress;
import module tools.jackson.databind;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.blobs.Keys;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.Checksums;
import build.jenesis.repository.store.ArchiveInflation;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Imports an npm repository from an incumbent manager. The assets are the tarballs; the packument is derived and not
 * imported. A tarball's {@code package/package.json} gives the name and version and becomes the version's metadata,
 * with a {@code dist} whose {@code shasum} and {@code integrity} are computed from the bytes, and the tarball is stored
 * under {@code npm/<name>/tarballs/<shortName>-<version>.tgz}, where the packument points.
 */
public final class NpmImporter implements RepositoryImporter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A ceiling on the buffered tarball, which must be read whole to hash it. It bounds a runaway source body; the
     *  {@code package.json} entry read is bounded by {@link ArchiveInflation}. */
    private static final int MAX_TARBALL = 256 * 1024 * 1024;

    @Override
    public boolean imports(String format) {
        return format.equals("npm");
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String path) {
        // RepositoryImporter clause 4: a traversal-shaped source path is refused by name.
        String relative = RepositoryImporter.importablePath(path, "npm");
        // The tarball's serving coordinate, so the edge screens what NpmFormat parses; empty for a path that is no
        // tarball.
        return new NpmFormat().describe("/npm/" + relative);
    }

    @Override
    public void importArtifact(String path, InputStream stream, ArtifactStore store) throws IOException {
        // RepositoryImporter clause 4: a traversal-shaped source path is refused by name.
        String relative = RepositoryImporter.importablePath(path, "npm");
        if (!relative.endsWith(".tgz")) {
            return;
        }
        // The edge screened the coordinate from the path, and the name and version below come from package.json; they
        // must agree, or a tarball screened as one package would serve as another.
        Optional<ArtifactDescriptor> screened = importTarget(path);
        if (screened.isEmpty()) {
            return;   // not a /-/ tarball path the edge screens - not imported here (the walk lays it out unscreened)
        }
        byte[] content = stream.readNBytes(MAX_TARBALL + 1);
        if (content.length > MAX_TARBALL) {
            return;   // an over-ceiling "tarball" is not a real package - do not buffer or import it
        }
        byte[] packageJson = packageJson(content);
        if (packageJson == null || !(MAPPER.readTree(packageJson) instanceof ObjectNode metadata)) {
            return;
        }
        JsonNode name = metadata.get("name");
        JsonNode version = metadata.get("version");
        if (name == null || version == null) {
            return;
        }
        if (!name.asString().equals(screened.get().coordinate())
                || !version.asString().equals(screened.get().version())) {
            // The embedded coordinate disagrees with the screened path: refused.
            return;
        }
        String shortName = name.asString().contains("/")
                ? name.asString().substring(name.asString().indexOf('/') + 1) : name.asString();
        if (Keys.unsafe(version.asString()) || Keys.unsafe(shortName)) {
            // A tarball-supplied version or short name must not splice key structure, as on the publish path.
            return;
        }
        ObjectNode dist = metadata.get("dist") instanceof ObjectNode existing ? existing : metadata.putObject("dist");
        dist.put("shasum", Checksums.hex("SHA-1", content));
        dist.put("integrity", "sha512-" + Base64.getEncoder().encodeToString(Checksums.digest("SHA-512", content)));
        Blobs blobs = new Blobs(store);
        blobs.write("npm/" + name.asString() + "/tarballs/" + shortName + "-" + version.asString() + ".tgz", content);
        blobs.write("npm/" + name.asString() + "/versions/" + version.asString(), MAPPER.writeValueAsBytes(metadata));
    }

    /** Read {@code package/package.json} from the gzipped tar, through {@link TarArchiveInputStream}, which decodes
     *  PAX, GNU long-name and ustar-prefix entries. */
    private static byte[] packageJson(byte[] tgz) throws IOException {
        try (TarArchiveInputStream tar = new TarArchiveInputStream(
                new GZIPInputStream(new ByteArrayInputStream(tgz)), "UTF-8")) {
            for (TarArchiveEntry entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry()) {
                if (entry.getName().equals("package/package.json")) {
                    // The manifest is a guard input, so a read the inflation ceiling stopped yields nothing and the
                    // tarball is not imported, never a prefix read as the whole manifest.
                    return ArchiveInflation.entry(tar).orNull();   // gunzip-bomb cap
                }
            }
        }
        return null;
    }
}
