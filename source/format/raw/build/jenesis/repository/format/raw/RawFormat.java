package build.jenesis.repository.format.raw;

import module java.base;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.format.RepositoryExporter;
import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.format.PublishedExport;

/**
 * The generic (raw) format: a plain HTTP file store under {@code /raw/...} for artifacts that fit no package ecosystem
 * - installers, archives, datasets, signed binaries. A {@code PUT} stores the bytes content-addressed through
 * {@link Publication} (so a raw file identical to a jar, tarball or OCI layer dedupes to one {@code blobs/<sha256>}), a
 * {@code GET} serves them, a {@code GET} on a trailing-slash path lists the directory, and a {@code DELETE} removes the
 * pointer.
 */
public final class RawFormat implements RepositoryFormat, ProxyFormat, RepositoryImporter, RepositoryExporter {

    /** The migration-import capability, delegated to {@link RawImporter}. */
    private final RawImporter importer = new RawImporter();

    @Override
    public String name() {
        return "raw";
    }

    @Override
    public boolean handles(String path) {
        return path.startsWith("/raw/");
    }

    @Override
    public void serve(FormatExchange exchange, ArtifactStore store) throws IOException {
        String path = exchange.path();
        Publication publication = new Publication(store);
        switch (exchange.method()) {
            case "PUT" -> {
                // Layout only: the ingress edge has screened the body and restreams the stored blob, so this stores it
                // content-addressed, links the path and answers 201.
                Publication.Blob blob = publication.stored(exchange.requestStream());
                publication.link(path, blob.hash(), blob.size());
                // The file joins its folder's stored page, and the folder its ancestors', on the publish rather than
                // per listing.
                new RawListings(store).refresh(path);
                exchange.respond(201);
            }
            // A raw file is a path, not a coordinate's version, so no pin applies; the key's delete right governs this.
            case "DELETE" -> {
                publication.unpublish(path);
                new RawListings(store).refresh(path);
                exchange.audit(AuditActions.ARTIFACT_DELETE, path);
                exchange.respond(204);
            }
            // HEAD answers exactly what a GET would: locate() applies the withheld screens and confirms the blob
            // exists, where blob() only reads the pointer, and Content-Type and Content-Length come from the store's
            // metadata, never by opening the blob.
            case "HEAD" -> {
                Optional<Publication.Located> located = publication.locate(path);
                if (located.isEmpty()) {
                    exchange.respond(404);
                    return;
                }
                exchange.setResponseHeader("Content-Type", "application/octet-stream");
                if (located.get().size() >= 0) {
                    exchange.setResponseHeader("Content-Length", Long.toString(located.get().size()));
                }
                exchange.respond(200);
            }
            default -> {
                if (path.endsWith("/")) {
                    listing(path, store, exchange);
                    return;
                }
                Optional<Publication.Located> located = publication.locate(path);
                if (located.isEmpty()) {
                    exchange.respond(404);
                    return;
                }
                exchange.setResponseHeader("Content-Type", "application/octet-stream");
                // Opened before the response is committed, so a pointer whose blob is gone answers a clean 404.
                InputStream in;
                try {
                    in = store.open(located.get().key(), exchange.from(located.get().size()));
                } catch (NoSuchFileException gone) {
                    exchange.respond(404);
                    return;
                }
                try (in; OutputStream out = exchange.respond(200, located.get().size())) {
                    in.transferTo(out);
                }
            }
        }
    }

    @Override
    public boolean proxy(FormatExchange exchange, ArtifactStore store, URI upstream, ProxyFormat.Fetcher fetcher)
            throws IOException {
        String path = exchange.path();
        // The request seam's clause-6 screen applies here too: a traversal-shaped path is no proxy target.
        if (!path.startsWith("/raw/") || path.endsWith("/") || !ArtifactStore.traversalFree(path)) {
            return false;
        }
        String rest = path.substring("/raw/".length());
        String root = upstream.toString();
        Optional<ProxyFormat.Download> fetched = fetcher.download(
                URI.create(root.endsWith("/") ? root + rest : root + "/" + rest), Map.of());
        if (fetched.isEmpty()) {
            return false;
        }
        Publication publication = new Publication(store);
        try (ProxyFormat.Download download = fetched.get()) {
            if (download.status() != 200) {
                return false;
            }
            // Layout only: the proxy ingress has screened the fetch, so this stores the body content-addressed and
            // links the path, and handle() serves it.
            Publication.Blob blob = publication.stored(download.body());
            publication.link(path, blob.hash(), blob.size());
        }
        handle(exchange, store);
        return true;
    }

    /** A directory page ({@code GET} on a trailing slash): the folder's stored listing, streamed as it is. A folder
     *  with no servable child - none published, or all screened away - is a {@code 404}; the structural probe is paid
     *  only until the page exists. */
    private void listing(String path, ArtifactStore store, FormatExchange exchange) throws IOException {
        RawListings listings = new RawListings(store);
        if (!StoredListing.present(store, RawListings.page(path))
                && !hasChild(store, ServableNames.PUBLISHED + path.substring(0, path.length() - 1))) {
            exchange.respond(404);
            return;
        }
        Optional<StoredListing.Served> served = StoredListing.open(store, listings.spec(path));
        if (served.isEmpty()) {
            exchange.respond(404);
            return;
        }
        try (StoredListing.Served page = served.get()) {
            if (page.header().size() <= EMPTY_PAGE_LENGTH) {
                exchange.respond(404);   // every child screened away - 404, as before
                return;
            }
            String etag = '"' + page.header().sha256() + '"';
            exchange.setResponseHeader("ETag", etag);
            if (etag.equals(exchange.requestHeader("If-None-Match"))) {
                exchange.respond(304);
                return;
            }
            exchange.setResponseHeader("Content-Type", "text/html");
            // Streamed: the document is the size of the folder.
            try (OutputStream out = exchange.respond(200, page.header().size())) {
                page.body().transferTo(out);
            }
        }
    }

    /** The length of a page listing nothing - the page frame alone. */
    private static final long EMPTY_PAGE_LENGTH = RawListings.PAGE.join(new TreeMap<>()).length;

    private static boolean hasChild(ArtifactStore store, String prefix) {
        boolean[] any = {false};
        store.page(prefix, "", 1, _ -> any[0] = true);
        return any[0];
    }

    @Override
    public boolean imports(String sourceFormat) {
        return importer.imports(sourceFormat);
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String sourcePath) {
        return importer.importTarget(sourcePath);
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        importer.importArtifact(path, content, store);
    }

    /** Raw files record no coordinates, so each is a unit of its own, put at its path under the client's
     *  {@code .../raw/} URL. */
    @Override
    public Exported export(ArtifactStore repository, String path, String version, ExportTarget target)
            throws IOException {
        return PublishedExport.put(repository, List.of(path), "/raw/", target);
    }

    @Override
    public Units units() {
        return Units.PUBLISHED_PATHS;
    }
}
