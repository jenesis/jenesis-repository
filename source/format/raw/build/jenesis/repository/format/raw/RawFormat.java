package build.jenesis.repository.format.raw;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;

/**
 * The generic (raw) format: a plain HTTP file store under {@code /raw/...}, for the artifacts that fit no package
 * ecosystem - installers, archives, datasets, signed binaries. A {@code PUT} stores the bytes content-addressed
 * through {@link Publication} (so a raw file that matches a jar, a tarball or an OCI layer dedupes to the one
 * {@code blobs/<sha256>}), a {@code GET} serves them, a {@code GET} on a trailing-slash path lists the directory,
 * and a {@code DELETE} removes the pointer. No metadata, no protocol - just the content-addressed store behind a
 * file API, so it is a thin plugin over the same primitives every other layout uses.
 */
public final class RawFormat implements RepositoryFormat, ProxyFormat, RepositoryImporter, ArtifactSignatures {

    /** The ecosystem name a raw file's subject reports: a layout with no packaging is its own vocabulary. */
    public static final String ECOSYSTEM = "raw";

    /** The sidecar family a raw file's signature never covers, and whose members are never asked for one. */
    private static final List<String> SIDECARS =
            List.of(".md5", ".sha1", ".sha256", ".sha512", ".asc", ".sig", ".sigstore.json", ".attestations.json");

    /**
     * The raw layout's inbound signature story: a Sigstore bundle at {@code <file>.sigstore.json}, the file
     * {@code cosign sign-blob --bundle} writes and the one convention a layout with no packaging can offer. Optional,
     * since nothing in the ecosystem requires it, and read on both legs - a bundle published beside a file, or fetched
     * beside a proxied one as its {@linkplain #companions companion}. The bundle is keyless, so one that verifies is
     * VALID only under an operator's pin for its identity, and UNTRUSTED otherwise.
     */
    private static final ArtifactSignatures SIGNATURES = ArtifactSignatures.detachedSidecar(ECOSYSTEM,
            ".sigstore.json", ArtifactSignatures.Scheme.SIGSTORE_BUNDLE, RawFormat::signable,
            ArtifactSignatures.Coverage.OPTIONAL);

    /** Whether a request path names a file a signature would cover: any raw file that is not itself a sidecar. */
    private static boolean signable(String path) {
        return path.startsWith("/raw/") && !path.endsWith("/") && SIDECARS.stream().noneMatch(path::endsWith);
    }

    @Override
    public String ecosystem() {
        return ECOSYSTEM;
    }

    @Override
    public List<ArtifactSignatures.Expectation> expects(String path) {
        return SIGNATURES.expects(path);
    }

    @Override
    public Optional<String> covers(String path) {
        return SIGNATURES.covers(path);
    }

    @Override
    public List<ArtifactSignatures.Evidence> evidence(String path, ArtifactSignatures.Material material)
            throws IOException {
        return SIGNATURES.evidence(path, material);
    }

    /** A proxied file's bundle, fetched beside it from the same upstream folder: a mirror that publishes none
     *  answers 404, which is absence. */
    @Override
    public List<ProxyFormat.Companion> companions(FormatExchange exchange, URI upstream) {
        String path = exchange.path();
        if (!signable(path) || !ArtifactStore.traversalFree(path)) {
            return List.of();
        }
        String root = upstream.toString();
        String target = (root.endsWith("/") ? root : root + "/") + path.substring("/raw/".length());
        return List.of(new ProxyFormat.Companion(path + ".sigstore.json", URI.create(target + ".sigstore.json")));
    }

    // Reused across listings rather than rebuilt per request: newInstance() runs the full JAXP provider lookup, and the
    // factory is safe to share for creating writers once configured.


    /** How many entries one rendered listing document may carry. A directory browser is navigated into, not scrolled,
     *  so an enormous directory renders its first page rather than building a millions-anchor document in heap. */
    private static final int LISTING_ENTRIES = 10_000;


    /** The migration-import capability (WSPI.2 (c)), delegated to the layout-only {@link RawImporter} - the format IS
     *  the discovered importer now (an {@code instanceof} capability), and the importer class stays as its delegate. */
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
                // Layout-only (EPIC 26): screening rides the ingress edge, which screens the body to ACCEPT and
                // restreams the stored blob into this format. Store content-addressed (streamed, never buffered) and
                // link the path, then respond 201 - verdicts are the edge's business, not the format's.
                Publication.Blob blob = publication.stored(exchange.requestStream());
                publication.link(path, blob.hash(), blob.size());
                // The directory pages are written here, on the publish: the file joins its folder's stored page and
                // the folder its ancestors', rather than the folder being enumerated and screened on every listing.
                new RawListings(store).refresh(path);
                exchange.respond(201);
            }
            case "DELETE" -> {
                publication.unpublish(path);
                new RawListings(store).refresh(path);
                exchange.respond(204);
            }
            // HEAD must answer exactly what a GET would: located() applies the withheld (quarantine/retraction)
            // screens and confirms the content-addressed blob still exists, where blob() only reads the pointer -
            // so a withheld or GC-reclaimed path would otherwise HEAD 200 while GET 404s. And "exactly what a GET
            // would" includes its headers: the Content-Type and the Content-Length are read from the store's metadata
            // (never by opening the blob), so a client sizing an artifact before pulling it gets the same answer here
            // as from the GET below - the HEAD-from-metadata shape MavenFormat, JenesisFormat and OciFormat already
            // carry, which this leg alone was missing (§13).
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
                // Opened before the response is committed, so the open is the existence check and a pointer whose
                // blob is gone answers a clean 404 rather than a truncated 200 (the same shape MavenFormat serves).
                InputStream in;
                try {
                    in = store.open(located.get().key());
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
        // The proxy leg carries the same clause-6 screen as the request seam: a traversal-shaped path
        // is no proxy target either, so it never reaches the upstream and never lays a fetched body out
        // under a path the store refuses.
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
            // Layout-only (EPIC 26): screening rides the ingress edge (under downstream the proxy ingress is already
            // screened by ProxyScreen/harden), so this lays the fetched body out - store it content-addressed
            // (streamed, never buffered) and link the path - and the handle() re-dispatch serves it.
            Publication.Blob blob = publication.stored(download.body());
            publication.link(path, blob.hash(), blob.size());
        }
        handle(exchange, store);
        return true;
    }

    /** A directory page ({@code GET} on a trailing slash): the folder's stored listing, streamed as it is. A folder
     *  with no servable child at all - none published, or every child screened away - is a {@code 404}, as before;
     *  the structural probe is paid only until the page exists. */
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
            // Streamed rather than handed over as bytes. The document is the size of the folder, so materialising
            // it here put the whole page in heap on the request path - a folder of a hundred thousand children
            // died exactly here, in Served.bytes, once the write side stopped being the first thing to run out.
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
}
