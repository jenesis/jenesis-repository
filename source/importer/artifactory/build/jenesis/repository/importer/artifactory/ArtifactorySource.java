package build.jenesis.repository.importer.artifactory;

import module java.base;
import build.jenesis.repository.importer.ImportDownloads;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.importer.ImportFailure;
import build.jenesis.repository.importer.ImportSource;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * An {@link ImportSource} over a JFrog Artifactory instance, the read half of an Artifactory migration. It lists a
 * repository's files with the deep File List storage API ({@code GET /api/storage/<repo>?list&deep=1&listFolders=0}),
 * then downloads each from {@code <base>/<repo><uri>}. That API is an Artifactory Pro feature; a free instance refuses
 * it with {@code 400}, and the walk falls back to the per-folder Folder Info API
 * ({@code GET /api/storage/<repo>/<path>}), recursed for the same files and checkpointing after each top-level subtree
 * so an interrupted migration resumes without re-walking it. The listing carries no per-file format - a repository has
 * a single package type - so the format is supplied for the repository and reported for every asset. The network sits
 * behind the proxy's {@link ProxyFormat.Fetcher}, so the walk is tested without an Artifactory.
 */
public final class ArtifactorySource implements ImportSource {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The OSS folder crawl's recursion cap - past any real repository's nesting, so a hostile or cyclic folder
     *  listing fails with a clear message instead of a {@code StackOverflowError}. */
    private static final int MAX_DEPTH = 64;

    private final URI base;
    private final String repository;
    private final String format;
    private final ProxyFormat.Fetcher fetcher;
    private final String authorization;
    private final String cursor;

    public ArtifactorySource(URI base, String repository, String format, ProxyFormat.Fetcher fetcher) {
        this(base, repository, format, fetcher, null, null);
    }

    private ArtifactorySource(URI base, String repository, String format,
                              ProxyFormat.Fetcher fetcher, String authorization, String cursor) {
        this.base = base;
        this.repository = repository;
        this.format = format;
        this.fetcher = fetcher;
        this.authorization = authorization;
        this.cursor = cursor;
    }

    /** Authenticate the listing and downloads with HTTP basic credentials (an Artifactory user and password or token). */
    public ArtifactorySource withCredentials(String username, String password) {
        String token = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        return new ArtifactorySource(base, repository, format, fetcher, "Basic " + token, cursor);
    }

    /** Resume the OSS folder crawl after the top-level entry a prior walk last reported. The deep File List path is a
     *  single response, so it ignores the cursor. */
    public ArtifactorySource from(String cursor) {
        return new ArtifactorySource(base, repository, format, fetcher, authorization, cursor);
    }

    @Override
    public void forEach(Asset consumer, Checkpoint checkpoint) throws IOException {
        String root = base.toString();
        String prefix = root.endsWith("/") ? root : root + "/";
        URI listing = URI.create(prefix + "api/storage/" + encode(repository) + "?list&deep=1&listFolders=0");
        // The deep File List is one JSON document over every file in the repository, so it is pull-parsed and each
        // file emitted as the parser reaches it, the download staying open across the assets' own lazy downloads.
        try (ProxyFormat.Download page = fetcher.download(listing, headers())
                .orElseThrow(() -> ImportFailure.unreachable(listing))) {
            if (page.status() == 200) {
                streamDeepList(consumer, prefix, page.body());
                // A single response, so there is no mid-walk resume point.
                checkpoint.reached(null);
            } else if (proGated(page)) {
                // A free Artifactory gates the deep File List behind Pro, so walk the Folder Info API instead.
                crawlRepository(consumer, checkpoint, prefix);
            } else {
                throw ImportFailure.status(page.status(), listing, "Artifactory listing");
            }
        }
    }

    /** Pull-parses the deep File List's {@code files} array, emitting each non-folder file as it is read and skipping
     *  every other field, so the listing is consumed in the parser's bounded buffer. */
    private void streamDeepList(Asset consumer, String prefix, InputStream body) throws IOException {
        try (JsonParser parser = JSON.createParser(body)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return;                                          // not the expected storage-listing object
            }
            while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                boolean files = "files".equals(parser.currentName());
                parser.nextToken();                              // advance onto the field's value
                if (files && parser.currentToken() == JsonToken.START_ARRAY) {
                    streamFiles(consumer, prefix, parser);
                } else {
                    parser.skipChildren();                       // scalar (no-op) or an unrelated subtree
                }
            }
        }
    }

    private void streamFiles(Asset consumer, String prefix, JsonParser parser) throws IOException {
        JsonToken token;
        while ((token = parser.nextToken()) != null && token != JsonToken.END_ARRAY) {
            if (token != JsonToken.START_OBJECT) {
                parser.skipChildren();                           // a non-object array element is not a file entry
                continue;
            }
            String uri = null;
            boolean folder = false;
            while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                String field = parser.currentName();
                parser.nextToken();                              // advance onto the field's value
                if ("uri".equals(field) && parser.currentToken() == JsonToken.VALUE_STRING) {
                    uri = parser.getString();
                } else if ("folder".equals(field)
                        && (parser.currentToken() == JsonToken.VALUE_TRUE || parser.currentToken() == JsonToken.VALUE_FALSE)) {
                    folder = parser.currentToken() == JsonToken.VALUE_TRUE;
                } else {
                    parser.skipChildren();
                }
            }
            if (folder || uri == null) {
                continue;
            }
            String path = uri.startsWith("/") ? uri.substring(1) : uri;
            if (!ImportSource.safePath(path)) {
                consumer.dropped(path, ImportSource.Reason.UNSAFE_PATH);
                continue;                                        // a traversal-laced listing path no store write should see
            }
            URI download = URI.create(prefix + encode(repository) + "/" + encode(path));
            consumer.accept(format, path, () -> open(download));
        }
    }

    /** True when the deep File List API is refused specifically because it is an Artifactory Pro feature (a free
     *  instance answers {@code 400} with that message), as opposed to a genuine error that should surface. The error
     *  body is a tiny JSON object, so it is read bounded off the download stream. */
    private static boolean proGated(ProxyFormat.Download page) throws IOException {
        if (page.status() != 400) {
            return false;
        }
        byte[] body = page.body().readNBytes(64 * 1024);
        return new String(body, StandardCharsets.UTF_8).contains("available only in Artifactory Pro");
    }

    /** Walks the repository over the Folder Info API, importing each top-level entry's subtree in turn and reporting a
     *  checkpoint once it is consumed, so {@link #from(String)} resumes after the last completed entry. The entries are
     *  sorted, so the resume skip does not depend on the order Artifactory returns them. */
    private void crawlRepository(Asset consumer, Checkpoint checkpoint, String prefix) throws IOException {
        URI rootFolder = URI.create(prefix + "api/storage/" + encode(repository));
        ProxyFormat.Fetched page = get(rootFolder);
        if (page.status() != 200) {
            throw ImportFailure.status(page.status(), rootFolder, "Artifactory folder listing");
        }
        List<JsonNode> children = new ArrayList<>();
        for (JsonNode child : JSON.readTree(new String(page.body(), StandardCharsets.UTF_8)).path("children")) {
            if (name(child) != null) {
                children.add(child);
            } else {
                // name() answers null for an entry with no uri (a broken listing) and for one that is not a single
                // traversal-free segment (a hostile one); only the second is an attack indicator.
                String uri = child.path("uri").asString(null);
                consumer.dropped(uri == null ? "<no uri>" : uri,
                        uri == null ? ImportSource.Reason.INCOMPLETE_ENTRY : ImportSource.Reason.UNSAFE_PATH);
            }
        }
        children.sort(Comparator.comparing(ArtifactorySource::name));
        for (JsonNode child : children) {
            String name = name(child);
            if (cursor != null && name.compareTo(cursor) <= 0) {
                continue;   // this top-level entry (and everything before it) was completed in a prior run
            }
            if (child.path("folder").asBoolean(false)) {
                crawl(consumer, prefix, name, 1);
            } else {
                URI download = URI.create(prefix + encode(repository) + "/" + encode(name));
                consumer.accept(format, name, () -> open(download));
            }
            checkpoint.reached(name);   // the whole subtree is imported, so a resume can safely skip past it
        }
        checkpoint.reached(null);       // the walk is complete
    }

    /** Recurse a folder's subtree over the Folder Info API, emitting every file. No checkpoint - the resume cursor is
     *  reported at top-level-subtree granularity by {@link #crawlRepository}. */
    private void crawl(Asset consumer, String prefix, String path, int depth) throws IOException {
        URI folder = URI.create(prefix + "api/storage/" + encode(repository) + "/" + encode(path));
        ProxyFormat.Fetched page = get(folder);
        if (page.status() != 200) {
            throw ImportFailure.status(page.status(), folder, "Artifactory folder listing");
        }
        for (JsonNode child : JSON.readTree(new String(page.body(), StandardCharsets.UTF_8)).path("children")) {
            String name = name(child);
            if (name == null) {
                String uri = child.path("uri").asString(null);
                consumer.dropped(uri == null ? path + "/<no uri>" : path + uri,
                        uri == null ? ImportSource.Reason.INCOMPLETE_ENTRY : ImportSource.Reason.UNSAFE_PATH);
                continue;
            }
            String childPath = path + "/" + name;
            if (child.path("folder").asBoolean(false)) {
                if (depth >= MAX_DEPTH) {
                    throw ImportFailure.protocol("Folder tree exceeds depth " + MAX_DEPTH + " at " + childPath);
                }
                crawl(consumer, prefix, childPath, depth + 1);
            } else {
                URI download = URI.create(prefix + encode(repository) + "/" + encode(childPath));
                consumer.accept(format, childPath, () -> open(download));
            }
        }
    }

    /** A child entry's name - its {@code uri} without the leading slash - or {@code null} for an entry that carries
     *  none or whose name is not a single traversal-free segment (never legitimate repository content). */
    private static String name(JsonNode child) {
        String uri = child.path("uri").asString(null);
        if (uri == null) {
            return null;
        }
        String name = uri.startsWith("/") ? uri.substring(1) : uri;
        return name.indexOf('/') >= 0 || !ImportSource.safePath(name) ? null : name;
    }

    /** Percent-encode a repository-relative path (or single name) for splicing into a request URI, segment by
     *  segment - an Artifactory name may legally carry a space or other character {@code URI} refuses raw. */
    private static String encode(String path) {
        StringBuilder encoded = new StringBuilder(path.length());
        for (String segment : path.split("/", -1)) {
            if (!encoded.isEmpty()) {
                encoded.append('/');
            }
            encoded.append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return encoded.toString();
    }

    private InputStream open(URI url) throws IOException {
        return ImportDownloads.open(fetcher, base, url, headers());
    }

    private ProxyFormat.Fetched get(URI url) throws IOException {
        return fetcher.fetch(url, headers()).orElseThrow(() -> ImportFailure.unreachable(url));
    }

    private Map<String, String> headers() {
        return authorization == null ? Map.of() : Map.of("Authorization", authorization);
    }
}
