package build.jenesis.repository.format;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;

/**
 * The export of a format whose client publishes by putting each file at its path - Maven, Ivy, the Jenesis module
 * layout, raw files: every servable file published under a version's folders, each {@code PUT} at its path relative to
 * the URL the client is pointed at.
 *
 * <p><b>In the order a client sends them.</b> The artifacts first, then their signatures, then the checksums, and the
 * metadata a client writes about the version last - so a target that checks a checksum against its file, or rebuilds a
 * listing when the metadata lands, sees them in the order it expects.
 *
 * <p><b>Already there is not a failure.</b> Each file is looked up on the target first, and one it already serves
 * with the same bytes is not sent again, so a resumed or repeated export moves nothing twice. A target that refuses a
 * file - a release repository that allows no redeploy, a {@code 409} for an immutable release - is asked for it back
 * as well: the same bytes mean it is present, different bytes are a failure naming the file. Nothing on the target is
 * ever overwritten.
 */
public final class PublishedExport {

    /** The most files one version is walked for; past it, the version fails rather than exporting part of itself. */
    public static final int MAX_FILES = 10_000;

    private PublishedExport() {
    }

    /**
     * Put every servable file published under {@code folders} (request paths such as {@code /maven/org/acme/lib/1.0})
     * to {@code target}, each at its request path less {@code strip} (the part of the path the client's URL already
     * carries, such as {@code /maven/}).
     */
    public static RepositoryExporter.Exported putAll(ArtifactStore repository, List<String> folders, String strip,
                                                     ExportTarget target) throws IOException {
        List<String> paths = new ArrayList<>();
        for (String folder : folders) {
            paths.addAll(published(repository, folder));
        }
        return put(repository, paths, strip, target);
    }

    /** Put each of {@code paths} (served request paths), in the order a client sends them. */
    public static RepositoryExporter.Exported put(ArtifactStore repository, List<String> paths, String strip,
                                                  ExportTarget target) throws IOException {
        Publication publication = new Publication(repository);
        List<String> ordered = new ArrayList<>(paths);
        ordered.sort(Comparator.comparingInt(PublishedExport::rank).thenComparing(Comparator.naturalOrder()));
        List<File> files = new ArrayList<>();
        for (String path : ordered) {
            Optional<Publication.Located> located = publication.locate(path);
            if (located.isEmpty()) {
                continue;
            }
            String key = located.get().key();
            String relative = relative(path, strip);
            files.add(File.put(relative, Optional.of(relative), contentType(path),
                    key.substring(key.lastIndexOf('/') + 1), located.get().size(), () -> repository.open(key)));
        }
        return send(files, target);
    }

    /**
     * One file an export sends: the request that publishes it - a {@code PUT} at its path for most formats, a form
     * {@code POST} for a client that uploads one - where the target serves it once sent - the same path, a path of its
     * own, or nowhere a single file can be asked for back (a winget manifest, served only inside its package's
     * document) - and the SHA-256 of its stored bytes, which is what the target is asked for there.
     */
    public record File(ExportTarget.Request request, Optional<String> served, String sha256) {

        public File {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(served, "served");
            Objects.requireNonNull(sha256, "sha256");
        }

        /** A file put at {@code path} under the target's URL, from stored bytes of {@code length} ({@code -1} when the
         *  store never recorded it). */
        public static File put(String path, Optional<String> served, String contentType, String sha256, long length,
                               ExportTarget.Opener content) {
            return new File(ExportTarget.Request.put(path, contentType, ExportTarget.Body.of(length, content)), served,
                    sha256);
        }
    }

    /**
     * Put each of {@code files}, in the order given, skipping one the target already serves with the same bytes at its
     * {@link File#served} path: the mechanism every put-at-its-path export shares, whichever namespace its files were
     * found in. A file the target cannot be asked about is sent unless every file it can be asked about is already
     * there, in which case the version is. No files - every one withheld or gone - is {@code WITHHELD}; nothing sent
     * because everything is there is {@code ALREADY_PRESENT}.
     */
    public static RepositoryExporter.Exported send(List<File> files, ExportTarget target) throws IOException {
        if (files.isEmpty()) {
            return RepositoryExporter.Exported.WITHHELD;
        }
        List<Boolean> there = new ArrayList<>(files.size());
        boolean asked = false;
        boolean complete = true;
        for (File file : files) {
            boolean held = file.served().isPresent() && holds(target, file);
            there.add(held);
            asked |= file.served().isPresent();
            complete &= held || file.served().isEmpty();
        }
        if (asked && complete) {
            // Already there, byte for byte: a resumed or repeated export sends nothing twice.
            return RepositoryExporter.Exported.ALREADY_PRESENT;
        }
        boolean present = true;
        for (int index = 0; index < files.size(); index++) {
            File file = files.get(index);
            if (there.get(index)) {
                continue;
            }
            ExportTarget.Response response = target.send(file.request());
            if (response.ok()) {
                present = false;
            } else if (file.served().isEmpty() || !holds(target, file)) {
                throw new IOException("the target refused " + file.served().orElse(file.request().path()) + " with "
                        + response.status()
                        + (response.body().isBlank() ? "" : ": " + response.body().strip())
                        + ", and does not hold the same bytes there");
            }
        }
        return present ? RepositoryExporter.Exported.ALREADY_PRESENT : RepositoryExporter.Exported.PUBLISHED;
    }

    /** Whether the target serves {@code file}'s bytes where it is served once put. */
    private static boolean holds(ExportTarget target, File file) throws IOException {
        return target.sha256(file.served().orElseThrow()).equals(Optional.of(file.sha256()));
    }

    /** The request paths published under {@code folder}, every depth, bounded by {@link #MAX_FILES}. */
    public static List<String> published(ArtifactStore repository, String folder) throws IOException {
        String prefix = "publish" + (folder.endsWith("/") ? folder : folder + "/");
        List<String> paths = new ArrayList<>();
        String after = "";
        while (true) {
            ArtifactStore.Scan scan = repository.scan(prefix, after, 1_000,
                    listed -> paths.add(listed.key().substring("publish".length())));
            if (paths.size() > MAX_FILES) {
                throw new IOException(folder + " holds more than " + MAX_FILES + " files; it is not one version");
            }
            if (!scan.truncated()) {
                return paths;
            }
            after = scan.cursor().orElseThrow();
        }
    }

    private static String relative(String path, String strip) {
        String relative = path.startsWith(strip) ? path.substring(strip.length()) : path;
        return relative.startsWith("/") ? relative.substring(1) : relative;
    }

    /** Artifacts, then signatures, then checksums, then metadata. */
    private static int rank(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.startsWith("maven-metadata") || name.startsWith("ivy-") && name.endsWith(".xml")) {
            return 3;
        }
        if (name.endsWith(".sha1") || name.endsWith(".md5") || name.endsWith(".sha256") || name.endsWith(".sha512")) {
            return 2;
        }
        if (name.endsWith(".asc") || name.endsWith(".sig")) {
            return 1;
        }
        return 0;
    }

    /** The content type a file is put with, judged from its name. */
    public static String contentType(String path) {
        return path.endsWith(".pom") || path.endsWith(".xml") ? "application/xml"
                : path.endsWith(".json") ? "application/json"
                : "application/octet-stream";
    }
}
