package build.jenesis.repository.ui;

import module java.base;

/**
 * One document about a single version a console module serves - a bill of materials in one format, say - contributed
 * through {@link ConsoleModuleProvider#versionDocuments()}. A version's page links every installed module's documents
 * under their labels, so a module offers a document for every version by being installed.
 *
 * @param label what the link reads, naming the document and its format ({@code CycloneDX JSON})
 * @param path  the document's path below the repository, starting with a slash; the page resolves it against
 *              {@code /ui/repositories/<name>} and adds the version's {@code ecosystem}, {@code coordinate} and
 *              {@code version} as parameters
 */
public record VersionDocument(String label, String path) {

    public VersionDocument {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(path, "path");
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("A version document's path starts with a slash: " + path);
        }
    }
}
