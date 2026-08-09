package build.jenesis.repository.format.raw;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.walk.ScreenedNames;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * The generic (raw) format: a plain HTTP file store under {@code /raw/...}, for the artifacts that fit no package
 * ecosystem - installers, archives, datasets, signed binaries. A {@code PUT} stores the bytes content-addressed
 * through {@link Publication} (so a raw file that matches a jar, a tarball or an OCI layer dedupes to the one
 * {@code blobs/<sha256>}), a {@code GET} serves them, a {@code GET} on a trailing-slash path lists the directory,
 * and a {@code DELETE} removes the pointer. No metadata, no protocol - just the content-addressed store behind a
 * file API, so it is a thin plugin over the same primitives every other layout uses.
 */
public final class RawFormat implements RepositoryFormat, ProxyFormat, RepositoryImporter {

    // Reused across listings rather than rebuilt per request: newInstance() runs the full JAXP provider lookup, and the
    // factory is safe to share for creating writers once configured.
    private static final XMLOutputFactory XML_OUTPUT = XMLOutputFactory.newInstance();

    /** How many immediate child names a listing pages from the store at a time - the directory is enumerated through
     *  repeated bounded pages rather than one whole-directory {@code list()} snapshot, so a raw directory with an
     *  enormous fan-out is never materialised twice (the raw child set and the screened subset) in heap at once. */
    private static final int LISTING_PAGE = 1_000;

    /** How many entries one rendered listing document may carry. A directory browser is navigated into, not scrolled,
     *  so an enormous directory renders its first page rather than building a millions-anchor document in heap. */
    private static final int LISTING_ENTRIES = 10_000;

    /** How many stored child names one listing may examine to fill {@link #LISTING_ENTRIES} - the bound that also
     *  covers a directory whose children are mostly screened away, where the entry cap alone would never fire. */
    private static final int LISTING_SCAN = 50_000;

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
    public void handle(FormatExchange exchange, ArtifactStore store) throws IOException {
        String path = exchange.path();
        // RepositoryFormat clause 6: a request path carrying a . or .. segment addresses nothing under /raw/, so it is
        // refused here rather than reaching the store's key screen, which throws (an unmapped 500 where the truth is
        // "no such file"). One screen, stated in ArtifactStore, applied at the format seam exactly as OciFormat has
        // always applied its own (§13 parity).
        if (!ArtifactStore.traversalFree(path)) {
            exchange.respond(404);
            return;
        }
        Publication publication = new Publication(store);
        switch (exchange.method()) {
            case "PUT" -> {
                // Layout-only (EPIC 26): screening rides the ingress edge, which screens the body to ACCEPT and
                // restreams the stored blob into this format. Store content-addressed (streamed, never buffered) and
                // link the path, then respond 201 - verdicts are the edge's business, not the format's.
                String hash = publication.storeBlob(exchange.requestStream());
                publication.link(path, hash);
                exchange.respond(201);
            }
            case "DELETE" -> {
                publication.unpublish(path);
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
                Optional<String> located = publication.located(path);
                if (located.isEmpty()) {
                    exchange.respond(404);
                    return;
                }
                exchange.setResponseHeader("Content-Type", "application/octet-stream");
                exchange.setResponseHeader("Content-Length", Long.toString(store.size(located.get())));
                exchange.respond(200);
            }
            default -> {
                if (path.endsWith("/")) {
                    listing(path, store, exchange);
                    return;
                }
                Optional<String> key = publication.located(path);
                if (key.isEmpty()) {
                    exchange.respond(404);
                    return;
                }
                exchange.setResponseHeader("Content-Type", "application/octet-stream");
                try (OutputStream out = exchange.respond(200, store.size(key.get()))) {
                    store.read(key.get(), out);
                }
            }
        }
    }

    @Override
    public boolean proxy(FormatExchange exchange, ArtifactStore store, URI upstream, ProxyFormat.Fetcher fetcher)
            throws IOException {
        String path = exchange.path();
        // The proxy leg carries the same clause-6 screen as handle(): a traversal-shaped path is no proxy target
        // either, so it never reaches the upstream and never lays a fetched body out under a path the store refuses.
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
            String hash = publication.storeBlob(download.body());
            publication.link(path, hash);
        }
        handle(exchange, store);
        return true;
    }

    private void listing(String path, ArtifactStore store, FormatExchange exchange) throws IOException {
        String prefix = ServableNames.PUBLISHED + path.substring(0, path.length() - 1);
        // The directory listing must not disclose a leaf a GET/HEAD would not serve: a withheld artifact 404s on GET
        // but its pointer name still lives under publish/, so writing every child verbatim leaked the existence - and
        // the name - of a withheld artifact. The listing is therefore produced by the shared screened enumeration:
        // ScreenedNames pages the children AND applies the servable-name seam's serve-parity screen
        // (HIDE_WITHHELD_AND_GONE, exactly what the item routes' located() decides) in one call, so this surface never
        // holds an unscreened child name and cannot page-then-forget. A child that is itself a directory (it has its
        // own children under publish/) is a sub-listing rather than a servable leaf, so it is declared a container and
        // forwards unconditionally - its own leaves carry the screen. Folder-ness is probed with a bounded one-element
        // page rather than listing (and discarding) each child's entire subtree, which was quadratic across a large
        // directory. Only the screened-visible names are retained (they are rendered anyway); the raw child set is
        // never held whole, and the render is bounded - a directory wider than the cap renders its first page rather
        // than materialising an unbounded document.
        ScreenedNames screened = ScreenedNames
                .paths(new ServableNames(store, new Publication(store)), ServableNames.Policy.HIDE_WITHHELD_AND_GONE)
                .containers(childKey -> hasChild(store, childKey))
                .scanning(BoundedChildren.bounded().entries(LISTING_SCAN).page(LISTING_PAGE))
                .take(LISTING_ENTRIES);
        List<String> visible = new ArrayList<>();
        screened.scan(store, prefix, (child, _) -> visible.add(child));
        if (visible.isEmpty()) {
            exchange.respond(404);   // no children at all, or every child screened away - both 404, as before
            return;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            XMLStreamWriter writer = XML_OUTPUT.createXMLStreamWriter(out, "UTF-8");
            writer.writeDTD("<!DOCTYPE html>");
            writer.writeStartElement("html");
            writer.writeStartElement("body");
            for (String child : visible) {
                writer.writeStartElement("a");
                writer.writeAttribute("href", child);
                writer.writeCharacters(child);
                writer.writeEndElement();
                writer.writeEmptyElement("br");
            }
            writer.writeEndElement();
            writer.writeEndElement();
            writer.writeEndDocument();
            writer.close();
        } catch (XMLStreamException e) {
            throw new IOException(e);
        }
        exchange.setResponseHeader("Content-Type", "text/html");
        exchange.respond(200, out.toByteArray());
    }

    /** Whether a prefix has at least one immediate child, tested with a bounded one-element page rather than listing
     *  (and discarding) the child's entire subtree just to check emptiness - so classifying a child as a directory is a
     *  single seek, not O(its own child count) round-trips. */
    private static boolean hasChild(ArtifactStore store, String prefix) {
        boolean[] any = {false};
        store.page(prefix, "", 1, _ -> any[0] = true);
        return any[0];
    }

    // --- RepositoryImporter capability (WSPI.2 (c)): delegated to RawImporter. ---

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
