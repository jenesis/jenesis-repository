package build.jenesis.repository.importer.jenesis;

import module java.base;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.importer.ImportFailure;
import build.jenesis.repository.importer.ImportSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Walks another instance of this product through its {@code GET /api/assets} enumeration. Each page lists the source
 * repository's published assets with their serving path, format, SHA-256 and size, read from the publication pointers
 * without opening a blob. The walk reports each asset with its format and the layout-relative path the matching
 * {@code RepositoryImporter} expects (the {@code /<format>/} serving prefix stripped, which that importer re-applies),
 * streams the bytes lazily from the path the listing says the asset is served at, and resumes from the opaque
 * {@code cursor} the response carries, checkpointing it after each page and a terminal {@code null}. The optional API
 * key travels in the {@code Jenesis-Repository-Key} header on the listing and the downloads.
 */
public final class JenesisSource implements ImportSource {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final URI base;
    private final String repository;
    private final ProxyFormat.Fetcher fetcher;
    private final String key;
    private final String cursor;

    public JenesisSource(URI base, String repository, ProxyFormat.Fetcher fetcher) {
        this(base, repository, fetcher, null, null);
    }

    private JenesisSource(URI base, String repository, ProxyFormat.Fetcher fetcher, String key, String cursor) {
        this.base = base;
        this.repository = repository;
        this.fetcher = fetcher;
        this.key = key;
        this.cursor = cursor;
    }

    /** The jenesis API key to present, carried verbatim in the {@code Jenesis-Repository-Key} header (jenesis auth is
     *  a single opaque key rather than a username/password pair). */
    public JenesisSource withKey(String key) {
        return new JenesisSource(base, repository, fetcher, key, cursor);
    }

    /** Resume the walk from a cursor a prior run checkpointed. */
    public JenesisSource from(String cursor) {
        return new JenesisSource(base, repository, fetcher, key, cursor);
    }

    @Override
    public void forEach(Asset consumer, Checkpoint checkpoint) throws IOException {
        String root = base.toString();
        String prefix = root.endsWith("/") ? root.substring(0, root.length() - 1) : root;
        String token = cursor;
        do {
            URI url = URI.create(prefix + "/api/assets?repo="
                    + URLEncoder.encode(repository, StandardCharsets.UTF_8)
                    + (token == null ? "" : "&cursor=" + URLEncoder.encode(token, StandardCharsets.UTF_8)));
            ProxyFormat.Fetched page = get(url);
            if (page.status() != 200) {
                throw ImportFailure.status(page.status(), url, "jenesis listing");
            }
            JsonNode body = JSON.readTree(page.body());   // parse straight off the bytes, no intermediate String copy
            for (JsonNode asset : body.path("assets")) {
                String path = asset.path("path").asString(null);
                if (path == null) {
                    continue;
                }
                String format = asset.path("format").asString(null);
                String layout = layoutPath(format, path);
                if (!ImportSource.safePath(layout)) {
                    // Reported rather than skipped silently: a laced path is the signal that a source is hostile.
                    consumer.dropped(layout, ImportSource.Reason.UNSAFE_PATH);
                    continue;   // a traversal-laced listing path no store write should see
                }
                // Where the source serves the asset, as a URL path on the source's own host - checked as one, since a
                // value that did not start at the host's root could move the download to another host.
                String served = asset.path("served").asString("");
                if (!served.startsWith("/repository/") || !ImportSource.safePath(served.substring(1))) {
                    consumer.dropped(layout, ImportSource.Reason.UNSAFE_PATH);
                    continue;
                }
                consumer.accept(format, layout, () -> open(prefix, served));
            }
            token = body.path("cursor").asString(null);
            checkpoint.reached(token);
        } while (token != null);
    }

    /** The path the owning format's {@code RepositoryImporter} expects: a jenesis serving path is
     *  {@code /<format>/<layout...>} and the importer re-applies the {@code /<format>/} prefix, so it is stripped
     *  here (a coordinate-less or unknown-format path just loses its leading slash). */
    private static String layoutPath(String format, String path) {
        if (format != null) {
            String formatPrefix = "/" + format + "/";
            if (path.startsWith(formatPrefix)) {
                return path.substring(formatPrefix.length());
            }
        }
        return path.startsWith("/") ? path.substring(1) : path;
    }

    /** The asset's bytes, from the URL path the listing reported the source serves it at. */
    private InputStream open(String prefix, String served) throws IOException {
        URI url = URI.create(prefix + served);
        ProxyFormat.Download download = fetcher.download(url, headers())
                .orElseThrow(() -> ImportFailure.unreachable(url));
        if (download.status() != 200) {
            download.close();
            throw ImportFailure.status(download.status(), url, "Download");
        }
        return download.body();
    }

    private ProxyFormat.Fetched get(URI url) throws IOException {
        return fetcher.fetch(url, headers()).orElseThrow(() -> ImportFailure.unreachable(url));
    }

    private Map<String, String> headers() {
        return key == null ? Map.of() : Map.of("Jenesis-Repository-Key", key);
    }
}
