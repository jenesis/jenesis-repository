package build.jenesis.repository.importer.nexus;

import module java.base;
import build.jenesis.repository.importer.ImportDownloads;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.importer.ImportFailure;
import build.jenesis.repository.importer.ImportScreen;
import build.jenesis.repository.importer.ImportSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * An {@link ImportSource} over a Sonatype Nexus 3 instance, the read half of a Nexus migration. It pages the
 * components REST API ({@code GET /service/rest/v1/components?repository=<name>}, followed by
 * {@code &continuationToken=<token>} until the token is absent), and for each component downloads every asset from
 * the {@code downloadUrl} the listing carries, handing it on with the component's {@code format} (Nexus names them
 * {@code maven2}, {@code docker}, {@code npm}, {@code pypi}, {@code nuget}, {@code rubygems}, {@code raw}). The
 * format is reported per asset, so a single Nexus instance with repositories of several formats migrates in one
 * pass and each asset reaches the importer for its ecosystem. The network sits behind the same
 * {@link ProxyFormat.Fetcher} the proxy uses, so the walk is tested without a Nexus.
 *
 * <p>Each {@code downloadUrl} is a semi-trusted absolute URL the listing chooses, screened before it is fetched by the
 * {@link ImportScreen} wrapped around this source's fetcher, the one rule for every connector. The credential decision
 * is this source's: the operator's basic credentials travel only to the base origin, so a cross-origin download goes
 * out unauthenticated.
 */
public final class NexusSource implements ImportSource {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final URI base;
    private final String repository;
    private final ProxyFormat.Fetcher fetcher;
    private final String authorization;
    private final String cursor;

    public NexusSource(URI base, String repository, ProxyFormat.Fetcher fetcher) {
        this(base, repository, fetcher, null, null);
    }

    private NexusSource(URI base, String repository, ProxyFormat.Fetcher fetcher, String authorization, String cursor) {
        this.base = base;
        this.repository = repository;
        this.fetcher = fetcher;
        this.authorization = authorization;
        this.cursor = cursor;
    }

    /** Authenticate the listing and downloads with HTTP basic credentials (a Nexus user and password or token). */
    public NexusSource withCredentials(String username, String password) {
        String token = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        return new NexusSource(base, repository, fetcher, "Basic " + token, cursor);
    }

    /** Resume the walk from a previously reported cursor (a continuation token) instead of from the first page. */
    public NexusSource from(String cursor) {
        return new NexusSource(base, repository, fetcher, authorization, cursor);
    }

    @Override
    public void forEach(Asset consumer, Checkpoint checkpoint) throws IOException {
        String root = base.toString();
        String prefix = root.endsWith("/") ? root : root + "/";
        String token = cursor;
        do {
            URI url = URI.create(prefix + "service/rest/v1/components?repository="
                    + URLEncoder.encode(repository, StandardCharsets.UTF_8)
                    + (token == null ? "" : "&continuationToken=" + URLEncoder.encode(token, StandardCharsets.UTF_8)));
            ProxyFormat.Fetched page = get(url);
            if (page.status() != 200) {
                throw ImportFailure.status(page.status(), url, "Nexus listing");
            }
            JsonNode body = JSON.readTree(page.body());   // parse straight off the bytes, no intermediate String copy
            for (JsonNode item : body.path("items")) {
                String format = item.path("format").asString(null);
                for (JsonNode asset : item.path("assets")) {
                    String path = asset.path("path").asString(null);
                    String downloadUrl = asset.path("downloadUrl").asString(null);
                    if (path != null && path.startsWith("/")) {
                        // Nexus on the H2 or PostgreSQL datastore reports asset paths with a leading slash, which the
                        // repository-relative path drops before safePath, whose empty-first-segment check would
                        // otherwise reject the asset.
                        path = path.substring(1);
                    }
                    if (path == null || downloadUrl == null || !ImportSource.safePath(path)) {
                        // A missing field is a broken listing, a laced path a hostile one; only the second is an
                        // attack indicator.
                        consumer.dropped(path == null ? "<no path>" : path,
                                path == null || downloadUrl == null
                                        ? ImportSource.Reason.INCOMPLETE_ENTRY
                                        : ImportSource.Reason.UNSAFE_PATH);
                        continue;   // an incomplete entry, or a traversal-laced path no store write should see
                    }
                    URI download;
                    try {
                        download = URI.create(downloadUrl);
                    } catch (IllegalArgumentException malformed) {
                        continue;   // a download URL that is not even a URI is a broken listing entry, not an asset
                    }
                    // Screened by the ImportScreen wrapped around this source's fetcher, since the URL comes off the
                    // listing as an initial request rather than a redirect.
                    consumer.accept(format, path, () -> open(download));
                }
            }
            token = body.path("continuationToken").asString(null);
            checkpoint.reached(token);
        } while (token != null);
    }

    private InputStream open(URI url) throws IOException {
        return ImportDownloads.open(fetcher, base, url,
                authorization == null ? Map.of() : Map.of("Authorization", authorization));
    }

    private ProxyFormat.Fetched get(URI url) throws IOException {
        Map<String, String> headers = authorization == null ? Map.of() : Map.of("Authorization", authorization);
        return fetcher.fetch(url, headers).orElseThrow(() -> ImportFailure.unreachable(url));
    }
}
