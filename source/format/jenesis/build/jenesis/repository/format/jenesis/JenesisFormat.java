package build.jenesis.repository.format.jenesis;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.format.java.JavaLayout;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.ServedAliases;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.format.RepositoryExporter;
import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.format.PublishedExport;

/**
 * The Jenesis module layout ({@code /module/...} and {@code /artifact/...}): a {@code PUT} stores the blob
 * content-addressed through the shared {@link Publication} store, and a {@code GET} serves it. A modular jar published
 * under the Maven layout is cross-published into this layout (its module view) by the Maven format, so it resolves by
 * module name; this format does not mirror the other way - a module published here stays in the module layout, and a
 * publisher that wants a Maven coordinate deploys under {@code /maven/} directly. The core knows nothing of it.
 *
 * <p>It also carries the {@link ArtifactLayout} capability (detected with {@code instanceof}, exactly like the Maven
 * format, so it is additive - nothing on the {@link RepositoryFormat} contract changes): the module layout is the single
 * owner of its coordinate convention, so a coordinate-only consumer (download tracking, cleanup eviction, DNS/{@code
 * match=} routing) maps a {@code /module/} path to its neutral {@link ArtifactDescriptor} and back without hand-parsing
 * the layout. The coordinate is the module name; the versioned pointer {@code /module/<name>/<version>/<file>} carries
 * the version, the version-less latest pointer {@code /module/<name>/<name>.jar} carries none - the two link shapes
 * {@link ModuleViewPublisher} publishes.
 */
public final class JenesisFormat implements RepositoryFormat, ArtifactLayout, RepositoryExporter {

    /** The package-ecosystem name the neutral descriptor carries - distinct from {@link #name()} "jenesis", the format
     *  id that routes the {@code /module/} and {@code /artifact/} paths. Any consumer of a Jenesis module reports the
     *  same ecosystem, whichever edition it runs in. */
    public static final String ECOSYSTEM = JavaLayout.MODULE_ECOSYSTEM;

    @Override
    public String name() {
        return "jenesis";
    }

    @Override
    public boolean handles(String path) {
        return path.startsWith(JavaLayout.MODULE_ROUTE) || path.startsWith("/artifact/");
    }

    /** Its routes - {@code /module/...} and {@code /artifact/...} - sit at the root of the repository. */
    @Override
    public String mount() {
        return "";
    }

    @Override
    public String ecosystem() {
        return ECOSYSTEM;
    }

    @Override
    public Optional<ArtifactDescriptor> describe(String path) {
        return descriptor(path);
    }

    @Override
    public List<String> paths(String coordinate, String version) {
        // ArtifactLayout clause 3: the module name and the version are single path segments composed straight into the
        // request paths an eviction unpublishes and deletes under, so a part that is not addressable (empty, a
        // separator, "." or "..") maps NOWHERE rather than composing "/module/../1.0". The empty-coordinate guard this
        // replaces covered only one of those shapes.
        if (!ArtifactLayout.addressable(coordinate, version)) {
            return List.of();
        }
        // The version directory holding the versioned jar. The version-less latest pointer
        // (/module/<name>/<name>.jar) is deliberately NOT here: it is not version-addressed, so it belongs to
        // whichever version it currently names and to no other, and this overload is handed no store to ask with.
        // Claiming it for every version made a first-version eviction unpublish a live pointer aimed at a later
        // one - a pointer destroyed rather than re-aimed. The store overload below reports it exactly when it
        // resolves to this version, which is the same rule the Maven layout applies to its own mirror.
        return List.of(JavaLayout.MODULE_ROUTE + coordinate + "/" + version);
    }

    @Override
    public List<String> paths(String coordinate, String version, ArtifactStore store) {
        List<String> primary = paths(coordinate, version);
        if (primary.isEmpty()) {
            return primary;
        }
        // The latest pointer, while it names this version. Resolved rather than composed, so an eviction reaches
        // the pointer it is about to invalidate and leaves alone one that names a surviving version, and so a
        // release's cross-alias exclusion set covers the version's own alias instead of reading it as a foreign
        // one still holding those bytes.
        List<String> paths = new ArrayList<>(primary);
        String versioned = JavaLayout.versionedModule(coordinate, version);
        String latest = JavaLayout.latestModule(coordinate);
        try {
            Publication publication = new Publication(store);
            Optional<String> hash = publication.blob(versioned);
            if (hash.isPresent() && publication.blob(latest).filter(hash.get()::equals).isPresent()) {
                paths.add(latest);
            }
        } catch (IOException _) {
            // best-effort, exactly as the Maven mirror is: the version directory still evicts and the blob is
            // reclaimed when it becomes unreferenced.
        }
        return paths;
    }

    /** The neutral descriptor of a {@code /module/...} path, or empty when the path carries no coordinate to describe (a
     *  directory, an {@code /artifact/} blob, a non-jenesis path): a full {@code /module/<name>/<version>/<file>} maps
     *  to the module name + version, and the version-less latest pointer {@code /module/<name>/<name>.jar} to the module
     *  name with no version.
     *
     *  <p>The path grammar itself lives in {@link JavaLayout} - the shared Java-layout module - so that a consumer
     *  which must describe a module artifact without taking an edge to this format implementation reads the same
     *  rules. What stays here is the descriptor mapping, including the version-less latest pointer, which is a
     *  format concern rather than a grammar one. */
    private static Optional<ArtifactDescriptor> descriptor(String path) {
        if (!path.startsWith(JavaLayout.MODULE_ROUTE)) {
            return Optional.empty();
        }
        String[] segments = path.substring(JavaLayout.MODULE_ROUTE.length()).split("/");
        if (segments.length == 3 && !segments[0].isEmpty() && !segments[1].isEmpty() && !segments[2].isEmpty()) {
            // /module/<name>/<version>/<file> - the versioned pointer.
            return Optional.of(new ArtifactDescriptor(ECOSYSTEM, segments[0], segments[1], path, null, false, null, -1L));
        }
        if (segments.length == 2 && !segments[0].isEmpty() && segments[1].equals(segments[0] + ".jar")) {
            // /module/<name>/<name>.jar - the version-less latest pointer, described version-less.
            return Optional.of(new ArtifactDescriptor(ECOSYSTEM, segments[0], null, path, null, false, null, -1L));
        }
        return Optional.empty();
    }

    @Override
    public void serve(FormatExchange exchange, ArtifactStore store) throws IOException {
        String path = exchange.path();
        Publication publication = new Publication(store);
        if (exchange.method().equals("PUT")) {
            put(exchange, publication, store);
            return;
        }
        Optional<Publication.Located> located = publication.locate(path);
        if (located.isEmpty()) {
            exchange.respond(404);
            return;
        }
        String key = located.get().key();
        long size = located.get().size();
        if (exchange.method().equals("HEAD")) {
            // A HEAD is answered from the pointer's recorded size (Content-Length), 200 with no body, without
            // touching the blob - the same HEAD-from-metadata contract OciFormat/RawFormat follow.
            if (size >= 0) {
                exchange.setResponseHeader("Content-Length", Long.toString(size));
            }
            exchange.respond(200);
            return;
        }
        // Opened before the response is committed, so the open is the existence check and a pointer whose blob is
        // gone answers a clean 404 rather than a truncated 200 (the shape MavenFormat and RawFormat serve).
        InputStream in;
        try {
            in = store.open(key);
        } catch (NoSuchFileException gone) {
            exchange.respond(404);
            return;
        }
        try (in; OutputStream out = exchange.respond(200, size)) {
            in.transferTo(out);
        }
    }

    /**
     * Publish one module jar: a single {@code PUT} to {@code /module/<name>/<version>/<name>[-<classifier>].jar}, and
     * nothing else.
     *
     * <p>The version-less latest pointer is this repository's to keep, so a publish of a module's own jar moves it
     * to the version just published - the pointer a Maven publish's module view moves the same way - and a
     * {@code PUT} to it is refused. So is one under {@code /artifact/}: that view is derived from a Maven publish in a
     * {@code java} repository and has nothing to be derived from here. A path of any other shape names no module
     * file and is a {@code 400}.
     *
     * <p>Layout only: screening rides the ingress edge, which screens the body to ACCEPT and restreams the stored
     * blob into this format, so this stores the body content-addressed (streamed, never buffered), links its path
     * and answers 201 - verdicts are the edge's business, not the format's.
     */
    private static void put(FormatExchange exchange, Publication publication, ArtifactStore store)
            throws IOException {
        String path = exchange.path();
        if (!path.startsWith(JavaLayout.MODULE_ROUTE)) {
            exchange.respond(405);
            return;
        }
        String[] segments = path.substring(JavaLayout.MODULE_ROUTE.length()).split("/", -1);
        if (segments.length == 2 && segments[1].equals(segments[0] + ".jar")) {
            exchange.respond(405);
            return;
        }
        if (segments.length != 3 || !ArtifactLayout.addressable(segments) || !moduleFile(segments[0], segments[2])) {
            exchange.respond(400);
            return;
        }
        Publication.Blob blob = publication.stored(exchange.requestStream());
        publication.link(path, blob.hash(), blob.size());
        if (segments[2].equals(segments[0] + ".jar")) {
            String latest = JavaLayout.latestModule(segments[0]);
            publication.link(latest, blob.hash(), blob.size());
            ServedAliases.reassign(store, path, latest);
        }
        exchange.respond(201);
    }

    /** Whether {@code file} is a jar of the module {@code name}: its own, or one classified {@code -<classifier>}. */
    private static boolean moduleFile(String name, String file) {
        if (!file.endsWith(".jar")) {
            return false;
        }
        String stem = file.substring(0, file.length() - ".jar".length());
        return stem.equals(name) || stem.startsWith(name + "-") && stem.length() > name.length() + 1;
    }

    /** A module version's jars, each put at its path under the repository URL a Jenesis build is pointed at. The
     *  version-less latest pointer is not sent: the target keeps its own, moving it with each module jar it takes, and
     *  versions arrive in the order they were published, so it ends where it does here. */
    @Override
    public Exported export(ArtifactStore repository, String coordinate, String version, ExportTarget target)
            throws IOException {
        List<String> paths = new ArrayList<>();
        for (String folder : paths(coordinate, version)) {
            for (String published : PublishedExport.published(repository, folder)) {
                if (published.endsWith(".jar")) {
                    paths.add(published);
                }
            }
        }
        return PublishedExport.put(repository, paths, "/", target);
    }
}
