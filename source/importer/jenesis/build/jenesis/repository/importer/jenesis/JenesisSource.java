package build.jenesis.repository.importer.jenesis;

import module java.base;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.importer.ImportFailure;
import build.jenesis.repository.importer.ImportSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Walks another jenesis instance through its {@code GET /api/assets} enumeration - the outbound mirror of the
 * importers, so jenesis-to-jenesis migration joins the framework symmetrically with the Nexus and Artifactory
 * connectors. Each page lists the source repository's published assets with their serving path, format,
 * SHA-256 and size (metadata only, straight from the publication pointer - the source opens no blob to answer);
 * the walk reports each asset with its format and the layout-relative path the matching {@code RepositoryImporter}
 * expects (the source's {@code /<format>/} serving prefix stripped, which that importer re-applies), streams the
 * bytes lazily from the source's {@code /repository} serving path, and resumes from the opaque {@code cursor} the
 * response carries - checkpointing it after each page and a terminal {@code null}, exactly as the Nexus walk
 * checkpoints its continuation token. The optional jenesis API key travels in the {@code Jenesis-Repository-Key}
 * header on both the listing and the downloads, since a source that enforces auth gates its reads.
 */
public final class JenesisSource implements ImportSource {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final URI base;
    private final String repository;
    private final ProxyFormat.Fetcher fetcher;
    private final String key;
    private final String cursor;

    /** Whether the source addresses artifacts with the repository segment ({@code /repository/<repo><path>}, the
     *  multi-tenant edition) or without ({@code /repository<path>}, the fixed-tenant server, whose one artifact
     *  space needs no name in the path). The walk cannot know which edition it faces, so the first download decides:
     *  the repository-qualified shape is tried first - the walk names the repository explicitly - and a {@code 404}
     *  falls back once to the bare shape; whichever answered is remembered for the rest of the walk. */
    private volatile Boolean repositorySegment;

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
                    // Reported, not merely skipped: a laced path is the one signal that a source is hostile, and a
                    // walk that drops every row silently finishes indistinguishable from an empty source (D-155).
                    consumer.dropped(layout, ImportSource.Reason.UNSAFE_PATH);
                    continue;   // a traversal-laced listing path no store write should see
                }
                consumer.accept(format, layout, () -> open(prefix, path));
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

    private InputStream open(String prefix, String path) throws IOException {
        Boolean qualified = repositorySegment;
        URI first = downloadUrl(prefix, path, qualified == null || qualified);
        ProxyFormat.Download download = fetcher.download(first, headers())
                .orElseThrow(() -> ImportFailure.unreachable(first));
        if (qualified == null && download.status() == 404) {
            // The first download decides the edition: a 404 on the repository-qualified shape is retried once on the
            // fixed-tenant shape, and the shape that answers is kept for the rest of the walk.
            download.close();
            URI second = downloadUrl(prefix, path, false);
            download = fetcher.download(second, headers()).orElseThrow(() -> ImportFailure.unreachable(second));
            if (download.status() != 200) {
                download.close();
                throw ImportFailure.status(download.status(), second, "Download");
            }
            repositorySegment = false;
            return download.body();
        }
        if (download.status() != 200) {
            download.close();
            throw ImportFailure.status(download.status(), first, "Download");
        }
        if (qualified == null) {
            repositorySegment = true;
        }
        return download.body();
    }

    private String encodedRepository() {
        return URLEncoder.encode(repository, StandardCharsets.UTF_8);
    }

    private URI downloadUrl(String prefix, String path, boolean withRepository) {
        return URI.create(prefix + "/repository" + (withRepository ? "/" + encodedRepository() : "") + path);
    }

    private ProxyFormat.Fetched get(URI url) throws IOException {
        return fetcher.fetch(url, headers()).orElseThrow(() -> ImportFailure.unreachable(url));
    }

    private Map<String, String> headers() {
        return key == null ? Map.of() : Map.of("Jenesis-Repository-Key", key);
    }
}
