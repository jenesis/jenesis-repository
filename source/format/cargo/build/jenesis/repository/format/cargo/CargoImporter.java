package build.jenesis.repository.format.cargo;

import module java.base;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Imports a Cargo registry (Nexus/Artifactory {@code cargo}) from an incumbent manager. The migrated assets are the
 * {@code .crate} archives; the sparse index a client reads is derived metadata, regenerated on read by
 * {@link CargoFormat}, so it is not imported. A crate's coordinate is its {@code name} and {@code version}, taken
 * from the {@code <name>-<version>.crate} filename: the version is the leftmost {@code -}-delimited suffix that
 * parses as a semver, exactly how Cargo splits a crate filename - a crate name cannot contain a dotted version, so
 * the split is unambiguous. Each crate is replayed through {@link CargoFormat#importCrate}, which streams the archive
 * straight into the content-addressed store (never buffered, like the RPM importer and unlike the buffered
 * {@code .gem}/{@code .nupkg}/{@code .deb} importers) and records the crate pointer and its index line with the stored
 * {@code cksum}; the migrated registry then serves and indexes it as its own. All crates migrate to a single
 * {@code /cargo/cargo/...} registry keyed by their coordinate (a yum-style flat migration, as the RPM importer files
 * to {@code /rpm/rpm/...}). One of the language importers, delegated to by {@link CargoFormat}, which
 * carries the same {@code RepositoryImporter} capability the built-in importers use.
 *
 * <p>The crate's dependency edges are not reconstructed - that would mean parsing the crate's embedded
 * {@code Cargo.toml}, and the pull-through proxy is the dependency-faithful route. The index line is otherwise
 * complete (name, version, checksum), so a migrated crate resolves and downloads.
 */
public final class CargoImporter implements RepositoryImporter {

    /** A crate version is strict semver ({@code major.minor.patch}, with an optional pre-release / build suffix). */
    private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+([-+].*)?");

    /** The single registry migrated crates land in, so the on-read index and download paths sit under one repo. */
    private static final String REPO = "cargo";

    @Override
    public boolean imports(String format) {
        return format.equals("cargo") || format.equals("crates");
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String path) {
        // RepositoryImporter clause 4: a source path is as client-supplied as a request path, so a
        // traversal-shaped one is refused by name rather than echoed into the descriptor the import edge
        // screens and the trail records (the fix retrofitted here - §13).
        String relative = RepositoryImporter.importablePath(path, "cargo");
        if (!relative.toLowerCase(Locale.ROOT).endsWith(".crate")) {
            return Optional.empty();
        }
        String file = relative.substring(relative.lastIndexOf('/') + 1);
        String stem = file.substring(0, file.length() - ".crate".length());
        for (int dash = stem.indexOf('-'); dash > 0; dash = stem.indexOf('-', dash + 1)) {
            if (VERSION.matcher(stem.substring(dash + 1)).matches()) {
                // The crate's target download coordinate under /cargo/cargo/api/v1/crates/<name>/<version>/download,
                // the path CargoFormat serves and describes, so the edge screens the real crate coordinate.
                return new CargoFormat().describe("/cargo/" + REPO + "/api/v1/crates/"
                        + stem.substring(0, dash) + "/" + stem.substring(dash + 1) + "/download");
            }
        }
        // No parseable <name>-<version> filename: nothing to key on, so it is laid out unscreened (importArtifact skips).
        return Optional.empty();
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        // RepositoryImporter clause 4: a source path is as client-supplied as a request path, so a
        // traversal-shaped one is refused by name rather than echoed into the descriptor the import edge
        // screens and the trail records (the fix retrofitted here - §13).
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
        // No parseable <name>-<version> filename: nothing to key the crate on, so it is skipped rather than mis-filed.
    }
}
