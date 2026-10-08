package build.jenesis.repository.inventory;

import module java.base;
import build.jenesis.repository.format.PackageNaming;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The reverse index from the name a package goes by to the versions a repository holds of it, where a format's
 * coordinate names more than that ({@link PackageNaming}): an RPM published into {@code updates} is held as
 * {@code updates/openssl}, while an image's bill names it {@code openssl}. One row per held version, at
 * {@code packages/<ecosystem>/<name>/<version>/<sha-256 of the version>}, the row the version's ecosystem, coordinate
 * and version as JSON.
 *
 * <p>Written as a release is recorded or a copy cached, before the document that records it, and only where it is
 * missing, so a re-record costs one existence probe; removed where the version stops being held, as its advised row
 * is. A row whose version is no longer held is passed over by the reader, so a removal that did not land costs a read
 * and never an answer. The reconcile re-records what the pointers hold, which writes a row a version recorded before
 * the index existed lacks.
 */
public final class PackageNameIndex {

    /** The root of the index. */
    public static final String INDEX = "packages";

    private PackageNameIndex() {
    }

    /** The level of the versions held of {@code name} at {@code version} of {@code ecosystem}. */
    static String level(String ecosystem, String name, String version) {
        return INDEX + "/" + ArtifactStore.segment(ecosystem) + "/" + ArtifactStore.segment(name) + "/"
                + ArtifactStore.segment(version);
    }

    /** The row of {@code coordinate} at {@code version}, or empty where its format names the package by its
     *  coordinate and no row is kept. */
    static Optional<String> row(String ecosystem, String coordinate, String version) {
        String name = PackageNaming.of(ecosystem, coordinate);
        return name.equals(coordinate) ? Optional.empty()
                : Optional.of(level(ecosystem, name, version) + "/" + VersionRows.digest(ecosystem, coordinate, version));
    }

    /** Write the row of {@code coordinate} at {@code version} where its format renames the package and none is kept. */
    static void record(ArtifactStore store, String ecosystem, String coordinate, String version) throws IOException {
        Optional<String> row = row(ecosystem, coordinate, version);
        if (row.isPresent() && !store.exists(row.get())) {
            store.write(row.get(), new ByteArrayInputStream(VersionRows.encode(ecosystem, coordinate, version)));
        }
    }

    /** Remove the row of {@code coordinate} at {@code version}, if any: the version stops being held. */
    static void forget(ArtifactStore store, String ecosystem, String coordinate, String version) throws IOException {
        Optional<String> row = row(ecosystem, coordinate, version);
        if (row.isPresent() && store.exists(row.get())) {
            store.delete(row.get());
        }
    }
}
