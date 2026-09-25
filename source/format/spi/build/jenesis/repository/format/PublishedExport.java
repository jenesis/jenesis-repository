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
        boolean sent = false;
        boolean present = true;
        for (String path : ordered) {
            Optional<Publication.Located> located = publication.locate(path);
            if (located.isEmpty()) {
                continue;
            }
            sent = true;
            String relative = relative(path, strip);
            String key = located.get().key();
            Optional<String> hash = Optional.of(key.substring(key.lastIndexOf('/') + 1));
            if (target.sha256(relative).equals(hash)) {
                // Already there, byte for byte: a resumed or repeated export sends nothing twice.
                continue;
            }
            long length = located.get().size();
            ExportTarget.Response response = target.send(ExportTarget.Request.put(relative, contentType(path),
                    new ExportTarget.Body() {
                        @Override
                        public long length() {
                            return length;
                        }

                        @Override
                        public InputStream open() throws IOException {
                            return repository.open(key);
                        }
                    }));
            if (response.ok()) {
                present = false;
            } else if (!target.sha256(relative).equals(hash)) {
                throw new IOException("the target refused " + relative + " with " + response.status()
                        + (response.body().isBlank() ? "" : ": " + response.body().strip())
                        + ", and does not hold the same bytes there");
            }
        }
        if (!sent) {
            return RepositoryExporter.Exported.WITHHELD;
        }
        return present ? RepositoryExporter.Exported.ALREADY_PRESENT : RepositoryExporter.Exported.PUBLISHED;
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

    private static String contentType(String path) {
        return path.endsWith(".pom") || path.endsWith(".xml") ? "application/xml"
                : path.endsWith(".json") ? "application/json"
                : "application/octet-stream";
    }
}
