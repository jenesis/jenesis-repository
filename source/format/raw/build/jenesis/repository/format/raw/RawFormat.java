package build.jenesis.repository.format.raw;

import module java.base;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.format.Listings;
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
public final class RawFormat implements RepositoryFormat, ProxyFormat, RepositoryImporter.Delegating,
        RepositoryExporter {

    /** The migration-import capability, delegated to {@link RawImporter}. */
    private final RawImporter importer = new RawImporter();

    @Override
    public RepositoryImporter importer() {
        return importer;
    }

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

    /** A raw file is the upstream's or this repository's, never a merge of the two. */
    @Override
    public boolean mergesUpstream(FormatExchange exchange) {
        return false;
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

    /** A directory page ({@code GET} or {@code HEAD} on a trailing slash): the folder's stored listing, streamed as it
     *  is. A folder
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
            Listings.serve(exchange, page, "text/html");
        }
    }

    /** The length of a page listing nothing - the page frame alone. */
    private static final long EMPTY_PAGE_LENGTH = RawListings.PAGE.join(new TreeMap<>()).length;

    private static boolean hasChild(ArtifactStore store, String prefix) {
        boolean[] any = {false};
        store.page(prefix, "", 1, _ -> any[0] = true);
        return any[0];
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
