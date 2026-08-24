package build.jenesis.repository.format.jenesis;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.format.java.JavaLayout;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;

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
public final class JenesisFormat implements RepositoryFormat, ArtifactLayout {

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
    public void handle(FormatExchange exchange, ArtifactStore store) throws IOException {
        String path = exchange.path();
        // RepositoryFormat clause 6: a request path carrying a . or .. segment addresses nothing under /module/ or
        // /artifact/, so it is refused here rather than reaching the store's key screen, which throws (an unmapped 500
        // where the truth is "no such artifact"). One screen, stated in ArtifactStore, applied at the format seam
        // exactly as OciFormat has always applied its own (§13 parity).
        if (!ArtifactStore.traversalFree(path)) {
            exchange.respond(404);
            return;
        }
        Publication publication = new Publication(store);
        if (exchange.method().equals("PUT")) {
            // Layout-only (EPIC 26): screening rides the ingress edge, which screens the body to ACCEPT and restreams
            // the stored blob into this format, so this branch stores the body content-addressed (streamed, never
            // buffered) and links its path, then responds 201 - verdicts are the edge's business, not the format's.
            String hash = publication.storeBlob(exchange.requestStream());
            publication.link(path, hash);
            exchange.respond(201);
            return;
        }
        Optional<String> key = publication.located(path);
        if (key.isEmpty()) {
            exchange.respond(404);
            return;
        }
        long size = store.size(key.get());
        if (exchange.method().equals("HEAD")) {
            // A HEAD is answered from the stored size (Content-Length), 200 with no body, without opening the blob -
            // the same HEAD-from-metadata contract OciFormat/RawFormat follow, rather than streaming the whole blob.
            exchange.setResponseHeader("Content-Length", Long.toString(size));
            exchange.respond(200);
            return;
        }
        try (OutputStream out = exchange.respond(200, size)) {
            store.read(key.get(), out);
        }
    }
}
