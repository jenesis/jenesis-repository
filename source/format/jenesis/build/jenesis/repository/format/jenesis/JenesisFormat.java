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
 * content-addressed through {@link Publication} and a {@code GET} serves it. A modular jar published under the Maven
 * layout gets a module view here from the Maven format, so it resolves by module name; nothing mirrors the other way -
 * a publisher wanting a Maven coordinate deploys under {@code /maven/}.
 *
 * <p>As an {@link ArtifactLayout}, it owns the module coordinate convention, so a coordinate-only consumer (download
 * tracking, eviction, {@code match=} routing) maps a {@code /module/} path to its {@link ArtifactDescriptor} and back.
 * The coordinate is the module name; the versioned pointer {@code /module/<name>/<version>/<file>} carries the version,
 * the latest pointer {@code /module/<name>/<name>.jar} none - the two shapes {@link ModuleViewPublisher} links.
 */
public final class JenesisFormat implements RepositoryFormat, ArtifactLayout, RepositoryExporter {

    /** The ecosystem name the descriptor carries, distinct from {@link #name()} "jenesis", the routing id; every
     *  consumer of a Jenesis module reports it. */
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
        // ArtifactLayout clause 3: name and version are composed into paths an eviction deletes under, so a part that
        // is not addressable maps nowhere rather than composing "/module/../1.0".
        if (!ArtifactLayout.addressable(coordinate, version)) {
            return List.of();
        }
        // The version directory only. The latest pointer belongs to whichever version it names, and this overload has
        // no store to ask, so claiming it here would let evicting one version unpublish a pointer aimed at another. The
        // store overload below reports it when it names this version, as the Maven layout does for its mirror.
        return List.of(JavaLayout.MODULE_ROUTE + coordinate + "/" + version);
    }

    @Override
    public List<String> paths(String coordinate, String version, ArtifactStore store) {
        List<String> primary = paths(coordinate, version);
        if (primary.isEmpty()) {
            return primary;
        }
        // The latest pointer, while it names this version: resolved rather than composed, so an eviction reaches only a
        // pointer it invalidates, and a release's cross-alias exclusions cover the version's own alias.
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
            // best-effort, as for the Maven mirror: the version directory still evicts, and the blob is reclaimed once
            // unreferenced
        }
        return paths;
    }

    /** The descriptor of a {@code /module/...} path, or empty when the path carries no coordinate (a directory, an
     *  {@code /artifact/} blob, another layout): {@code /module/<name>/<version>/<file>} maps to name and version, the
     *  latest pointer {@code /module/<name>/<name>.jar} to the name alone. The path grammar is {@link JavaLayout}'s,
     *  shared with consumers that may not depend on this format; the descriptor mapping is this format's. */
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
            // HEAD is answered from the pointer's recorded size, without touching the blob.
            if (size >= 0) {
                exchange.setResponseHeader("Content-Length", Long.toString(size));
            }
            exchange.respond(200);
            return;
        }
        // Opened before the response is committed, so a pointer whose blob is gone answers a clean 404.
        InputStream in;
        try {
            in = store.open(key, exchange.from(size));
        } catch (NoSuchFileException gone) {
            exchange.respond(404);
            return;
        }
        try (in; OutputStream out = exchange.respond(200, size)) {
            in.transferTo(out);
        }
    }

    /**
     * Publish one module jar: a single {@code PUT} to {@code /module/<name>/<version>/<name>[-<classifier>].jar}.
     *
     * <p>The latest pointer is the repository's to keep: a publish of a module's own jar moves it to the version just
     * published, as a Maven publish's module view does, and a {@code PUT} to it is refused. So is one under
     * {@code /artifact/}, a view derived from a Maven publish in a {@code java} repository. Any other shape is a
     * {@code 400}.
     *
     * <p>Layout only: the ingress edge has screened the body and restreams the stored blob, so this stores it
     * content-addressed, links its path and answers 201.
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
            if (LatestView.takes(store, latest, segments[1])) {
                publication.link(latest, blob.hash(), blob.size());
                ServedAliases.reassign(store, path, latest);
            }
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

    /** A module version's jars, each put at its path under the URL a Jenesis build is pointed at. The latest pointer is
     *  not sent: the target keeps its own by the same rule. */
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
