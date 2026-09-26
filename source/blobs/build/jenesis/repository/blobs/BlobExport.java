package build.jenesis.repository.blobs;

import module java.base;
import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.format.PublishedExport;
import build.jenesis.repository.format.RepositoryExporter;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The export of a format that keeps its files in its own blobs namespace and whose client publishes each by putting it
 * at a path: every {@link Pair pair} names the pointer a file is stored at and the path, under the client's URL, it is
 * put at. A pointer that resolves to nothing - gone, or {@linkplain Blobs#locate withheld} - is not sent, so a held
 * file never leaves the repository. What is sent, skipped and refused is {@link PublishedExport#send}'s, which every
 * put-at-its-path export shares.
 */
public final class BlobExport {

    private BlobExport() {
    }

    /** A stored pointer, the path relative to the target's URL its file is put at, and the path the target serves it
     *  at once put - which is where an export asks whether the target already holds it, and empty where no one file
     *  can be asked for back. */
    public record Pair(String key, String path, Optional<String> served) {

        public Pair {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(served, "served");
        }

        /** A file served at the path it is put at. */
        public Pair(String key, String path) {
            this(key, path, Optional.of(path));
        }

        /** A file served elsewhere than it is put. */
        public Pair(String key, String path, String served) {
            this(key, path, Optional.of(served));
        }
    }

    /**
     * Put the file behind each of {@code keys} at the path it is served at less {@code mount} - for a format whose
     * pointer keys are its served paths without their leading slash, which is most of them.
     */
    public static RepositoryExporter.Exported put(ArtifactStore repository, String mount, List<String> keys,
                                                  ExportTarget target) throws IOException {
        String prefix = mount.isEmpty() ? "" : mount.substring(1) + "/";
        List<Pair> pairs = new ArrayList<>();
        for (String key : keys) {
            if (!key.startsWith(prefix)) {
                throw new IllegalArgumentException(key + " is not under the format's mount " + mount);
            }
            pairs.add(new Pair(key, key.substring(prefix.length())));
        }
        return put(repository, pairs, target);
    }

    /** Put the file behind each pair's pointer at its path, in the order given. */
    public static RepositoryExporter.Exported put(ArtifactStore repository, List<Pair> pairs, ExportTarget target)
            throws IOException {
        Blobs blobs = new Blobs(repository);
        List<PublishedExport.File> files = new ArrayList<>();
        for (Pair pair : pairs) {
            Optional<Blobs.Located> located = blobs.locate(pair.key());
            if (located.isEmpty()) {
                continue;
            }
            String hash = located.get().hash();
            files.add(PublishedExport.File.put(pair.path(), pair.served(), PublishedExport.contentType(pair.path()),
                    hash, located.get().size(), () -> blobs.open(hash)));
        }
        return PublishedExport.send(files, target);
    }
}
