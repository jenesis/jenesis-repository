package build.jenesis.repository.format.raw;

import module java.base;
import module org.slf4j;
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
import build.jenesis.repository.store.UpstreamMemory;
import build.jenesis.repository.format.RepositoryExporter;
import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.format.PublishedExport;

/**
 * The generic (raw) format: a plain HTTP file store under {@code /raw/...} for artifacts that fit no package ecosystem
 * - installers, archives, datasets, signed binaries. A {@code PUT} stores the bytes content-addressed through
 * {@link Publication} (so a raw file identical to a jar, tarball or OCI layer dedupes to one {@code blobs/<sha256>}), a
 * {@code GET} serves them, a {@code GET} on a trailing-slash path lists the directory, and a {@code DELETE} removes the
 * pointer.
 *
 * <p>Proxying, a file is fetched once and served from its copy ever after - except a file the repository names as one
 * that moves ({@value #MOVING}): a document an upstream rewrites in place, such as the {@code latest.json} naming a
 * scanner's current database. That one is a mutable index under {@link ProxyFormat}'s first clause - relayed as the
 * upstream serves it and never linked, remembered in the node's memory of upstream documents for its ttl, so a burst
 * of readers costs the upstream one fetch.
 */
public final class RawFormat implements RepositoryFormat, ProxyFormat, RepositoryImporter.Delegating,
        RepositoryExporter {

    /** The repository setting naming the files that move, as globs under {@code /raw/}. */
    public static final String MOVING = "raw-moving";

    private static final Logger LOGGER = LoggerFactory.getLogger(RawFormat.class);

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

    /** A raw file is the upstream's or this repository's, never a merge of the two - but a file that moves is asked of
     *  the upstream whatever is held, so a copy kept before the repository named it as moving does not answer for
     *  it. */
    @Override
    public boolean mergesUpstream(FormatExchange exchange) {
        String path = exchange.path();
        return path.startsWith("/raw/") && moving(path.substring("/raw/".length()), exchange.setting(MOVING));
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
        URI target = URI.create(root.endsWith("/") ? root + rest : root + "/" + rest);
        if (moving(rest, exchange.setting(MOVING))) {
            return relay(exchange, store, target, fetcher);
        }
        Optional<ProxyFormat.Download> fetched = fetcher.download(target, Map.of());
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

    /**
     * Relay the file that moves at {@code target} as the upstream serves it now, linking nothing. An upstream that
     * answered {@code 404} or {@code 410} lets the local {@code 404} stand; one that could not be asked, or answered
     * anything else, is a {@code 502}, since a client reads a moving file's absence as an answer - no database
     * published - rather than as a reason to retry (clause 2).
     */
    private static boolean relay(FormatExchange exchange, ArtifactStore store, URI target, ProxyFormat.Fetcher fetcher)
            throws IOException {
        UpstreamMemory memory = UpstreamMemory.node();
        Optional<UpstreamMemory.Remembered> remembered = memory.get(store, target);
        byte[] body;
        String type;
        if (remembered.isPresent()) {
            body = remembered.get().body();
            type = remembered.get().headers().get("Content-Type");
        } else {
            Optional<ProxyFormat.Fetched> fetched = fetcher.fetch(target, Map.of());
            if (fetched.isPresent() && (fetched.get().status() == 404 || fetched.get().status() == 410)) {
                return false;
            }
            if (fetched.isEmpty() || fetched.get().status() != 200) {
                LOGGER.warn("Refusing to answer {} as absent: {}. Nothing was served; a client reads a moving file's "
                        + "absence as the upstream's own answer.", target, fetched.isEmpty()
                        ? ProxyFormat.Fetcher.NO_ANSWER : "the upstream answered " + fetched.get().status());
                exchange.respond(502);
                return true;
            }
            body = fetched.get().body();
            type = fetched.get().header("Content-Type");
            memory.put(store, target, body, fetched.get()::header);
        }
        exchange.setResponseHeader("Content-Type", type == null ? "application/octet-stream" : type);
        exchange.respond(200, body);
        return true;
    }

    /**
     * Whether {@code rest}, a path under {@code /raw/}, is among the files {@code globs} names as moving: globs
     * separated by commas or whitespace, where {@code *} matches within one path segment, {@code **} across segments,
     * {@code **}{@code /} also matches no segment at all, and {@code ?} matches one character of a segment. No glob,
     * nothing moves.
     */
    static boolean moving(String rest, String globs) {
        if (globs == null || globs.isBlank()) {
            return false;
        }
        for (String glob : globs.strip().split("[,\\s]+")) {
            if (!glob.isEmpty() && Pattern.matches(regex(glob), rest)) {
                return true;
            }
        }
        return false;
    }

    /** {@code glob} as the regular expression {@link #moving} matches a path with. */
    private static String regex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int at = 0; at < glob.length(); at++) {
            char c = glob.charAt(at);
            if (glob.startsWith("**/", at)) {
                regex.append("(?:.*/)?");
                at += 2;
            } else if (glob.startsWith("**", at)) {
                regex.append(".*");
                at++;
            } else if (c == '*') {
                regex.append("[^/]*");
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return regex.toString();
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
