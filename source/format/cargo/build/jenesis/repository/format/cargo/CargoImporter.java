package build.jenesis.repository.format.cargo;

import module java.base;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Imports a Cargo registry (Nexus/Artifactory {@code cargo}). The {@code .crate} archives are migrated; the sparse
 * index is derived and rebuilt by {@link CargoFormat}. The coordinate comes from the {@code <name>-<version>.crate}
 * filename: the version is the leftmost {@code -}-delimited suffix that parses as semver, as Cargo splits it - a crate
 * name cannot hold a dotted version, so the split is unambiguous. Each crate goes through
 * {@link CargoFormat#importCrate} into the single {@code /cargo/cargo/...} registry.
 */
public final class CargoImporter implements RepositoryImporter {

    /** A crate version is semver ({@code major.minor.patch}, with an optional pre-release or build suffix). */
    private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+([-+].*)?");

    /** The single registry migrated crates land in. */
    private static final String REPO = "cargo";

    @Override
    public boolean imports(String format) {
        return format.equals("cargo") || format.equals("crates");
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String path) {
        // RepositoryImporter clause 4: a traversal-shaped source path is refused by name rather than echoed into the
        // descriptor the import edge screens.
        String relative = RepositoryImporter.importablePath(path, "cargo");
        if (!relative.toLowerCase(Locale.ROOT).endsWith(".crate")) {
            return Optional.empty();
        }
        String file = relative.substring(relative.lastIndexOf('/') + 1);
        String stem = file.substring(0, file.length() - ".crate".length());
        for (int dash = stem.indexOf('-'); dash > 0; dash = stem.indexOf('-', dash + 1)) {
            if (VERSION.matcher(stem.substring(dash + 1)).matches()) {
                // The download path CargoFormat serves and describes, so the edge screens the real coordinate.
                return new CargoFormat().describe("/cargo/" + REPO + "/api/v1/crates/"
                        + stem.substring(0, dash) + "/" + stem.substring(dash + 1) + "/download");
            }
        }
        // No parseable <name>-<version> filename: nothing to key on (importArtifact skips it).
        return Optional.empty();
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        // RepositoryImporter clause 4: a traversal-shaped source path is refused by name.
        String relative = RepositoryImporter.importablePath(path, "cargo");
        if (!relative.toLowerCase(Locale.ROOT).endsWith(".crate")) {
            return;
        }
        String file = relative.substring(relative.lastIndexOf('/') + 1);
        String stem = file.substring(0, file.length() - ".crate".length());
        for (int dash = stem.indexOf('-'); dash > 0; dash = stem.indexOf('-', dash + 1)) {
            if (VERSION.matcher(stem.substring(dash + 1)).matches()) {
                new CargoFormat().importCrate(REPO, stem.substring(0, dash), stem.substring(dash + 1), content, store);
                return;
            }
        }
        // No parseable <name>-<version> filename: skipped rather than mis-filed.
    }
}
