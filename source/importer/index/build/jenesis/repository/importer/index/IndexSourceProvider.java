package build.jenesis.repository.importer.index;

import module java.base;

import build.jenesis.repository.net.Origins;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.importer.ImportRequest;
import build.jenesis.repository.importer.ImportSource;
import build.jenesis.repository.importer.ImportSourceProvider;

/**
 * Builds an {@link IndexSource} for an {@code "index"} migration from any server that publishes the requested format's
 * mirror-style index. The format is required; it is resolved through {@link RepositoryFormat#installed(String)}, and
 * no source is built when it is absent or does not proxy, or when the host does not answer - which the caller reports
 * as a bad request. Credentials ride as HTTP basic auth, injected around the shared fetcher so the format's
 * enumeration stays credential-blind.
 */
public final class IndexSourceProvider implements ImportSourceProvider {

    @Override
    public String name() {
        return "index";
    }

    @Override
    public String label() {
        return "Format index";
    }

    @Override
    public boolean requiresFormat() {
        return true;
    }

    @Override
    public ImportSource create(ImportRequest request, ProxyFormat.Fetcher fetcher) {
        if (request.format() == null) {
            return null;
        }
        RepositoryFormat format = RepositoryFormat.installed(request.format())
                .filter(candidate -> candidate instanceof ProxyFormat)
                .orElse(null);
        if (format == null) {
            return null;
        }
        URI root = root(request);
        ProxyFormat.Fetcher walker = request.username() != null && request.password() != null
                ? authorized(fetcher, request.username(), request.password(), root)
                : fetcher;
        IndexSource source = new IndexSource(format, root, walker, request.cursor());
        return source.reachable() ? source : null;
    }

    /** The walk's root: the base URL with the repository appended as a path ({@code .} or blank when the URL
     *  already points at the index root), always with a trailing slash so index links resolve against it. */
    private static URI root(ImportRequest request) {
        StringBuilder url = new StringBuilder(request.url().toString());
        while (!url.isEmpty() && url.charAt(url.length() - 1) == '/') {
            url.setLength(url.length() - 1);
        }
        String path = request.repository() == null ? "" : request.repository();
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (!path.isEmpty() && !path.equals(".")) {
            url.append('/').append(path);
        }
        return URI.create(url.append('/').toString());
    }

    /** The shared fetcher with HTTP basic credentials on every same-origin request that carries no
     *  {@code Authorization} of its own. A cross-origin URL - a download the foreign index aimed at a third host - is
     *  never given the operator's credential, which must not leave the origin the operator named. */
    private static ProxyFormat.Fetcher authorized(ProxyFormat.Fetcher fetcher, String username, String password,
                                                  URI root) {
        String token = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        String authorization = "Basic " + token;
        return new ProxyFormat.Fetcher() {
            @Override
            public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) throws IOException {
                return fetcher.fetch(url, merged(url, requestHeaders));
            }

            @Override
            public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders) throws IOException {
                return fetcher.download(url, merged(url, requestHeaders));
            }

            @Override
            public Optional<ProxyFormat.Head> head(URI url, Map<String, String> requestHeaders) throws IOException {
                // Delegated rather than derived from download, which would open the body when only the size is
                // wanted.
                return fetcher.head(url, merged(url, requestHeaders));
            }

            private Map<String, String> merged(URI url, Map<String, String> requestHeaders) {
                if (requestHeaders.containsKey("Authorization") || !Origins.same(root, url)) {
                    return requestHeaders;
                }
                Map<String, String> merged = new HashMap<>(requestHeaders);
                merged.put("Authorization", authorization);
                return merged;
            }
        };
    }
}
