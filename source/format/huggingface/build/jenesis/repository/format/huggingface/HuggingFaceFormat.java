package build.jenesis.repository.format.huggingface;

import module java.base;
import module tools.jackson.databind;

import build.jenesis.repository.format.Listings;
import build.jenesis.repository.blobs.BlobExport;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.format.RepositoryExporter;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.blobs.Keys;
import build.jenesis.repository.blobs.ProxyLeg;
import build.jenesis.repository.blobs.ProxyRelay;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.walk.TraversalException;

/**
 * The Hugging Face Hub registry format (the Hub's resolve and repo-info HTTP protocol), so {@code huggingface_hub} -
 * {@code hf_hub_download}, {@code snapshot_download}, {@code from_pretrained} - resolves models, datasets and spaces
 * over the shared store. It owns {@code /huggingface/...}, the first segment a registry alias and the rest the Hub
 * layout:
 * <ul>
 *   <li>a file is served from {@code GET /huggingface/<repo>/<repo_id>/resolve/<revision>/<path>} (a dataset's under
 *       {@code datasets/<repo_id>/...}, a space's under {@code spaces/<repo_id>/...}), {@code <path>} possibly
 *       nested;</li>
 *   <li>a {@code PUT} to that path pushes the file - the Hub's own upload is a git-LFS commit API, but as for
 *       {@code GOPROXY}, a {@code PUT} to the download path gets a file into a hosted registry and leaves the read
 *       protocol unchanged;</li>
 *   <li>the index is served from stored documents: {@code GET /huggingface/<repo>/api/models/<repo_id>} (or
 *       {@code datasets}, {@code spaces}) with its {@code siblings}, and {@code .../tree/<revision>} with each file's
 *       {@code oid} and {@code size}, both derived from the revision's stored file list on every upload. A repository
 *       with nothing stored answers {@code 404}, so a proxy registry can fill it.</li>
 * </ul>
 *
 * <p><b>Revision-addressed.</b> A file is addressed by the revision the client names - a branch like {@code main} or a
 * commit sha - and stored as pushed, no archive opened. A file at a commit keeps its first bytes, other bytes refused;
 * a file on a branch is replaced by the next push. {@code /api/.../<repo_id>} without a revision resolves {@code main},
 * else the most recently uploaded revision, each upload stamping its revision's time by compare-and-set.
 *
 * <p><b>Streaming publish.</b> An upload streams through {@link Blobs#write(String, InputStream)} into the
 * content-addressed store, so a weights file of tens of gigabytes never lands in heap; a {@code HEAD} answers size and
 * {@code ETag} from stored metadata.
 *
 * <p>The ecosystem is {@code "Hugging Face"}, and {@link #describe} maps a resolve path to its {@code <repo_id>}
 * coordinate and {@code <revision>} version. OSV carries no Hugging Face feed, so vulnerability screening finds nothing
 * while the coordinate still drives licence and malicious-package screening. File pointers live in the shared
 * {@code Blobs} namespace, so {@link #paths} is empty.
 *
 * <p><b>Pull-through proxy.</b> A local miss is served from an upstream Hub (the canonical
 * {@code https://huggingface.co/} by default), the path mapping one for one after the alias. A branch is resolved to
 * its upstream commit by a HEAD and cached under that commit, so a moved branch re-resolves instead of serving a stale
 * weight; a {@code GET} streams into the store and serves through the hosted read path, and a {@code HEAD} on an
 * uncached file answers from the upstream's headers without pulling the body. The {@code /api/...} index is streamed
 * fresh and needs no rewrite.
 */
public final class HuggingFaceFormat implements RepositoryFormat, ArtifactLayout, ProxyLeg, BlobLayout, RepositoryImporter,
        RepositoryExporter {

    /** The ecosystem name this format's artifacts report, distinct from {@link #name()}, the routing id. */
    public static final String ECOSYSTEM = "Hugging Face";

    /** Shared with the listings beside this, which build the derived documents with it. */
    static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PREFIX = "/huggingface/";
    private static final String RESOLVE = "/resolve/";

    /** The default git branch a Hugging Face repository serves when a client names no revision. */
    private static final String DEFAULT_REVISION = "main";

    /** The three repository types, each with its {@code api/<type>/} prefix; a dataset's and a space's resolve URLs
     *  also carry {@code datasets/} / {@code spaces/}, a model's none. */
    private static final String MODELS = "models", DATASETS = "datasets", SPACES = "spaces";

    @Override
    public String name() {
        return "huggingface";
    }

    @Override
    public String ecosystem() {
        return ECOSYSTEM;
    }

    @Override
    public List<String> blobRoots() {
        // The store root is hf/; /huggingface/ is only the request path.
        return List.of("hf");
    }

    @Override
    public List<String> blobKeys(String coordinate, String version, ArtifactStore store) throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            // A traversal-shaped coordinate or version maps nowhere, since an eviction deletes these keys; judged part
            // by part, so a multi-segment coordinate resolves.
            return List.of();
        }
        // Files live under a revision and a percent-encoded path, found by listing: per registry and type, the
        // version's revs/<R>/files for R the requested revision and, when a hosted branch has one, the commit it maps
        // to - a hosted upload stores under the branch while a proxy caches identical content under the commit. Each
        // pointer's body is the blob hash, from which the withhold set derives; a hold marks those hashes and eviction
        // deletes these keys. The files listing is publisher-grown, so it is paged.
        List<HuggingFaceFile> files = huggingFaceFiles(coordinate, version, store);
        List<String> keys = new ArrayList<>(files.size());
        for (HuggingFaceFile file : files) {
            keys.add(file.key());
        }
        return keys;
    }

    /**
     * The resolve paths this version's files serve at
     * ({@code /huggingface/<repo>/[datasets/|spaces/]<repo_id>/resolve/<revision>/<path>}), where a retroactive hold
     * links its {@code /quarantine} handles. A commit-alias file maps to the branch path the version names, so its
     * handle describes back to this version; duplicates are dropped. Only pointers are read.
     */
    @Override
    public List<String> servedPaths(String coordinate, String version, ArtifactStore store) throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            return List.of();
        }
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (HuggingFaceFile file : huggingFaceFiles(coordinate, version, store)) {
            paths.add(file.path());
        }
        return new ArrayList<>(paths);
    }

    /** One stored file found by the hold discovery walk: its pointer key and the resolve path it serves at. */
    private record HuggingFaceFile(String key, String path) {
    }

    /** Every file stored for {@code (repoId, revision)} across every registry and type, over the requested revision and
     *  the commit a hosted branch synthesised for it - two point lookups. The registry set is operator-configured, so a
     *  plain list suits it; each revision's files listing is publisher-grown and scanned through the bounded
     *  {@link #FILES}. */
    private static List<HuggingFaceFile> huggingFaceFiles(String repoId, String revision, ArtifactStore store)
            throws IOException {
        if (Keys.unsafe(revision)) {
            return List.of();
        }
        List<HuggingFaceFile> files = new ArrayList<>();
        for (String repo : store.list("hf")) {
            for (String type : new String[] {MODELS, DATASETS, SPACES}) {
                String base = base(repo, type, repoId);   // validates every repo_id segment; null on an unsafe one
                if (base == null) {
                    continue;
                }
                // The requested revision, plus the commit a hosted branch maps to when one was cached; a commit-pinned
                // revision has none.
                LinkedHashSet<String> revisions = new LinkedHashSet<>();
                revisions.add(revision);
                String commit = commitAlias(base, revision, store);
                if (commit != null) {
                    revisions.add(commit);
                }
                for (String storageRevision : revisions) {
                    String filesPrefix = base + "/revs/" + storageRevision + "/files";
                    FILES.scan(store, filesPrefix, encoded -> files.add(new HuggingFaceFile(
                            filesPrefix + "/" + encoded,
                            resolvePath(repo, type, repoId, revision, dec(encoded)))));
                }
            }
        }
        return files;
    }

    /** The immutable commit a hosted branch synthesised for {@code revision}, or {@code null} when there is none, it is
     *  the revision itself, or it is unsafe as a key segment. A read-time union only: files are recorded under the
     *  revision they are stored under - a branch's own name, or the commit a proxied fetch resolved to
     *  ({@link #keptAs}) - which is what lets {@link #describePointer} name a pointer's version from its key. */
    private static String commitAlias(String base, String revision, ArtifactStore store) throws IOException {
        String commit = storedCommit(base, revision, store);
        if (commit == null || commit.isEmpty() || commit.equals(revision) || Keys.unsafe(commit)) {
            return null;
        }
        return commit;
    }

    /** The resolve path a file serves at, keyed on the coordinate's revision, so it describes back to this version. */
    private static String resolvePath(String repo, String type, String repoId, String revision, String filepath) {
        return PREFIX + repo + "/" + typePrefix(type) + repoId + RESOLVE + revision + "/" + filepath;
    }

    /** A stored file located for the listing observer: its revision's base, revision, type, repo id and path. */
    record Located(String base, String revision, String type, String repoId, String filepath) {
    }

    /** The stored file a served path names ({@code /huggingface/<repo>/[datasets/|spaces/]<repo_id>/resolve/<rev>/<path>}). */
    Located locate(String path) {
        if (!path.startsWith(PREFIX)) {
            return null;
        }
        String rest = path.substring(PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return null;
        }
        String repo = rest.substring(0, slash);
        Resolve resolve = parseResolve(rest.substring(slash + 1));
        if (resolve == null || Keys.unsafe(repo) || Keys.unsafe(resolve.revision())
                || unsafeFilepath(resolve.filepath())) {
            return null;
        }
        String base = base(repo, resolve.type(), resolve.repoId());
        return base == null ? null
                : new Located(base, resolve.revision(), resolve.type(), resolve.repoId(), resolve.filepath());
    }

    /** The stored file a pointer key names ({@code hf/<repo>/<type>/<repo_id>/revs/<rev>/files/<encoded>}). */
    Located locateKey(String key) {
        int revs = key.indexOf("/revs/");
        int files = key.indexOf("/files/", revs);
        if (!key.startsWith("hf/") || revs < 0 || files < 0) {
            return null;
        }
        String base = key.substring(0, revs);
        String[] segments = base.split("/");
        if (segments.length < 4) {
            return null;
        }
        String type = segments[2];
        String repoId = String.join("/", Arrays.copyOfRange(segments, 3, segments.length));
        return new Located(base, key.substring(revs + "/revs/".length(), files), type, repoId,
                dec(key.substring(files + "/files/".length())));
    }

    /** The resolve URL prefix for a repository type: none for a model, {@code datasets/} / {@code spaces/} for the
     *  other two (mirroring {@link #parseResolve}'s type stripping). */
    private static String typePrefix(String type) {
        return switch (type) {
            case DATASETS -> DATASETS + "/";
            case SPACES -> SPACES + "/";
            default -> "";
        };
    }

    /** The page size the hold discovery walk lists a revision's files in. */
    private static final int WALK_PAGE = 1000;

    /** A revision's files listing, a flat container. It feeds {@code blobKeys} and {@code servedPaths}, where a short
     *  listing would leave a held file serving, so the entry cap is off and the step budget (1000 pages) raises a
     *  {@link TraversalException} rather than dropping keys. */
    private static final BoundedChildren FILES =
            BoundedChildren.bounded().entries(Integer.MAX_VALUE).page(WALK_PAGE);

    @Override
    public boolean handles(String path) {
        return path.startsWith(PREFIX);
    }

    @Override
    public void serve(FormatExchange exchange, ArtifactStore store) throws IOException {
        String rest = exchange.path().substring(PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            exchange.respond(404);
            return;
        }
        String repo = rest.substring(0, slash);
        String sub = rest.substring(slash + 1);
        if (repo.isEmpty() || Keys.unsafe(repo)) {
            exchange.respond(404);
            return;
        }
        // The repo-info and tree API, from stored documents:
        // api/{models,datasets,spaces}/<repo_id>[/tree|revision/<rev>...].
        for (String type : new String[] {MODELS, DATASETS, SPACES}) {
            String apiPrefix = "api/" + type + "/";
            if (sub.startsWith(apiPrefix)) {
                api(repo, type, sub.substring(apiPrefix.length()), exchange, store);
                return;
            }
        }
        // A file download, or a PUT: [datasets/|spaces/]<repo_id>/resolve/<revision>/<path>.
        Resolve resolve = parseResolve(sub);
        if (resolve != null) {
            file(repo, resolve, exchange, store);
            return;
        }
        exchange.respond(404);
    }

    /** Parse a resolve path {@code [datasets/|spaces/]<repo_id>/resolve/<revision>/<path>} into type, {@code repo_id},
     *  revision and file path, or {@code null} when it is none; the type prefix is stripped first. */
    private static Resolve parseResolve(String sub) {
        String type = MODELS;
        String s = sub;
        if (s.startsWith(DATASETS + "/")) {
            type = DATASETS;
            s = s.substring(DATASETS.length() + 1);
        } else if (s.startsWith(SPACES + "/")) {
            type = SPACES;
            s = s.substring(SPACES.length() + 1);
        }
        int idx = s.indexOf(RESOLVE);
        if (idx < 0) {
            return null;
        }
        String repoId = s.substring(0, idx);
        String tail = s.substring(idx + RESOLVE.length());
        int rs = tail.indexOf('/');
        if (rs < 0) {
            return null;
        }
        String revision = tail.substring(0, rs);
        String filepath = tail.substring(rs + 1);
        if (repoId.isEmpty() || revision.isEmpty() || filepath.isEmpty()) {
            return null;
        }
        return new Resolve(type, repoId, revision, filepath);
    }

    /** A parsed resolve/download request: the repository type, {@code repo_id}, {@code revision} and file path. */
    private record Resolve(String type, String repoId, String revision, String filepath) {
    }

    /**
     * Serve a stored file ({@code GET}/{@code HEAD}) or stream an upload into the store ({@code PUT}), the upload
     * stamping its revision's time for the index and the default-revision order. A {@code HEAD} answers size and
     * {@code ETag} from stored metadata.
     *
     * <p>An upload to a commit links through {@link Blobs#linkRelease}, so other bytes there are refused with
     * {@code 409} inside the pointer's compare-and-set and the same bytes converge; an upload to a branch replaces the
     * file.
     */
    private void file(String repo, Resolve resolve, FormatExchange exchange, ArtifactStore store) throws IOException {
        String base = base(repo, resolve.type(), resolve.repoId());
        if (base == null || Keys.unsafe(resolve.revision()) || unsafeFilepath(resolve.filepath())) {
            exchange.respond(exchange.method().equals("PUT") ? 400 : 404);
            return;
        }
        Blobs blobs = new Blobs(store);
        if (exchange.method().equals("PUT")) {
            String revBase = base + "/revs/" + resolve.revision();
            String fileKey = revBase + "/files/" + enc(resolve.filepath());
            if (isCommit(resolve.revision())) {
                Publication.Blob stored = blobs.stored(exchange.requestStream());
                try {
                    blobs.linkRelease(fileKey, stored.hash(), stored.size());
                } catch (Publication.RepublishConflict taken) {
                    exchange.respond(409, Blobs.alreadyPublished(resolve.repoId() + "@" + resolve.revision() + "/"
                            + resolve.filepath()));
                    return;
                }
            } else {
                blobs.write(fileKey, exchange.requestStream());
            }
            stampTime(store, revBase + "/time");
            // The revision's commit id, tree and info document derive from its stored file list, updated with this one
            // file.
            new HuggingFaceListings(blobs).refresh(base, resolve.revision(), resolve.type(), resolve.repoId(),
                    resolve.filepath());
            // The per-registry hosted marker switches on the local API; a proxy registry never writes it, so its API
            // reads fall through to the upstream's.
            markHosted(store, hostedKey(repo));
            exchange.respond(201);
            return;
        }
        if (!exchange.method().equals("GET") && !exchange.method().equals("HEAD")) {
            exchange.respond(405);
            return;
        }
        // Served only from a revision stored directly: a hosted branch under its name, or a commit sha. A proxy-cached
        // branch lives under its commit, so a branch read on a proxy registry 404s here and the proxy revalidates it.
        // X-Repo-Commit is always the real commit.
        String storageRev = resolveRevision(base, resolve.revision(), store);
        if (storageRev == null) {
            exchange.respond(404);
            return;
        }
        String fileKey = base + "/revs/" + storageRev + "/files/" + enc(resolve.filepath());
        Optional<Blobs.Located> located = blobs.locate(fileKey);
        if (located.isEmpty()) {
            exchange.respond(404);
            return;
        }
        long size = located.get().size();
        exchange.setResponseHeader("Content-Type", contentType(resolve.filepath()));
        exchange.setResponseHeader("X-Repo-Commit",
                reportedCommit(base, storageRev, resolve.type(), resolve.repoId(), store));
        exchange.setResponseHeader("ETag", '"' + located.get().hash() + '"');
        if (exchange.method().equals("HEAD")) {
            if (size >= 0) {
                exchange.setResponseHeader("Content-Length", Long.toString(size));
            }
            exchange.setResponseHeader("X-Linked-Size", Long.toString(size));
            exchange.respond(200, -1L).close();
            return;
        }
        blobs.serve(located.get(), exchange);
    }

    /** Route an API request {@code <repo_id>[/revision/<rev>][/tree/<rev>[/<subpath>]]} for a type, served from stored
     *  documents: everything before a {@code revision} or {@code tree} segment is the {@code repo_id} (possibly
     *  {@code namespace/name}); {@code tree} answers the file tree, anything else the info with its {@code siblings}.
     *  Read-only; an upload takes the resolve {@code PUT}. */
    private void api(String repo, String type, String remainder, FormatExchange exchange, ArtifactStore store)
            throws IOException {
        if (!exchange.method().equals("GET") && !exchange.method().equals("HEAD")) {
            exchange.respond(405);
            return;
        }
        if (!hosted(repo, store)) {
            // A proxy registry misses locally, so the upstream's index streams fresh on every read and a
            // default-revision info is never answered from a proxy-cached commit.
            exchange.respond(404);
            return;
        }
        String[] seg = remainder.split("/", -1);
        int keyword = -1;
        for (int i = 1; i < seg.length; i++) {
            if (seg[i].equals("tree") || seg[i].equals("revision")) {
                keyword = i;
                break;
            }
        }
        boolean tree = keyword >= 0 && seg[keyword].equals("tree");
        String repoId = String.join("/", Arrays.copyOfRange(seg, 0, keyword < 0 ? seg.length : keyword));
        String revision = keyword >= 0 && keyword + 1 < seg.length ? seg[keyword + 1] : null;
        String subpath = tree && keyword + 2 < seg.length
                ? String.join("/", Arrays.copyOfRange(seg, keyword + 2, seg.length))
                : null;
        String base = base(repo, type, repoId);
        if (base == null || (revision != null && Keys.unsafe(revision))) {
            exchange.respond(404);
            return;
        }
        String resolved = resolveRevision(base, revision, store);
        if (resolved == null) {
            exchange.respond(404);
            return;
        }
        // A structural emptiness probe of the raw files container: an empty revision is the miss a proxy registry
        // needs. The rendered listings decide which names may be disclosed.
        if (!StoredListing.present(store, HuggingFaceListings.files(base, resolved))
                && store.isEmpty(base + "/revs/" + resolved + "/files")) {
            exchange.respond(404);
            return;
        }
        // Both documents derive from the revision's stored file list; a revision read before it exists derives it once.
        HuggingFaceListings listings = new HuggingFaceListings(new Blobs(store));
        Optional<StoredListing.Served> served = listings.derived(base, resolved, type, repoId,
                tree ? HuggingFaceListings.tree(base, resolved) : HuggingFaceListings.info(base, resolved));
        if (served.isEmpty()) {
            exchange.respond(404);
            return;
        }
        try (StoredListing.Served document = served.get()) {
            exchange.setResponseHeader("Content-Type", "application/json");
            if (tree && subpath != null && !subpath.isEmpty()) {
                // A subtree: the stored tree filtered to the subpath's file or the files beneath it.
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                document.copyTo(buffer);
                ArrayNode root = MAPPER.createArrayNode();
                for (JsonNode node : MAPPER.readTree(buffer.toByteArray())) {
                    String file = node.path("path").asString("");
                    if (file.equals(subpath) || file.startsWith(subpath + "/")) {
                        root.add(node);
                    }
                }
                respondJson(exchange, root);
                return;
            }
            // The document's stored sha256 is the validator, read without materialising the body.
            Listings.serve(exchange, document, null);
        }
    }

    /** The storage revision a read resolves to: the requested one when stored directly (a hosted branch, or a commit
     *  sha), else {@code main} when it holds files, else the most recently uploaded revision. A proxy-cached branch
     *  lives under its commit, so a branch read on a proxy registry answers {@code null} and the proxy revalidates it;
     *  {@code null} too when nothing is published, so the caller answers {@code 404}. */
    private static String resolveRevision(String base, String requested, ArtifactStore store) throws IOException {
        if (requested != null) {
            return hasStoredFiles(store, base + "/revs/" + requested + "/files") ? requested : null;
        }
        if (hasStoredFiles(store, base + "/revs/" + DEFAULT_REVISION + "/files")) {
            return DEFAULT_REVISION;
        }
        String newest = null;
        long best = Long.MIN_VALUE;
        if (!Blobs.nameable(base + "/revs")) {
            return null;    // a client-shaped model name past the store's key cap holds no revision - see nameable
        }
        for (String rev : store.list(base + "/revs")) {
            if (!hasStoredFiles(store, base + "/revs/" + rev + "/files")) {
                continue;
            }
            long time = readTime(store, base + "/revs/" + rev + "/time");
            if (time >= best) {
                best = time;
                newest = rev;
            }
        }
        return newest;
    }

    /** Whether any file is stored under a revision, by a one-child probe of its {@code files} prefix:
     *  {@link #resolveRevision} runs on every download, and the listing is publisher-grown. */
    private static boolean hasStoredFiles(ArtifactStore store, String filesPrefix) {
        if (!Blobs.nameable(filesPrefix)) {
            // A prefix composed from a client-shaped name can exceed the store's key cap, and nothing can be stored
            // beneath it (Blobs.nameable); the filesystem would raise rather than page empty.
            return false;
        }
        List<String> probe = new ArrayList<>(1);
        store.page(filesPrefix, "", 1, probe::add);
        return !probe.isEmpty();
    }

    /** The commit id a read reports for a storage revision: the revision when it is a commit sha, else the id derived
     *  from its stored file list, materialised once when it never was. */
    private static String reportedCommit(String base, String storageRev, String type, String repoId,
                                         ArtifactStore store) throws IOException {
        if (isCommit(storageRev)) {
            return storageRev;
        }
        Optional<StoredListing.Served> served = new HuggingFaceListings(new Blobs(store))
                .derived(base, storageRev, type, repoId, HuggingFaceListings.commit(base, storageRev));
        if (served.isEmpty()) {
            return "";
        }
        try (StoredListing.Served commit = served.get()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            commit.copyTo(buffer);
            return buffer.toString(StandardCharsets.UTF_8).trim();
        }
    }

    /** The stored commit id of a revision, or {@code null} when none was derived yet; derives nothing. */
    private static String storedCommit(String base, String revision, ArtifactStore store) throws IOException {
        Optional<StoredListing.Served> served = StoredListing.openDerived(store,
                HuggingFaceListings.commit(base, revision));
        if (served.isEmpty()) {
            return null;
        }
        try (StoredListing.Served commit = served.get()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            commit.copyTo(buffer);
            return buffer.toString(StandardCharsets.UTF_8).trim();
        }
    }


    @Override
    public Optional<URI> defaultUpstream() {
        // One canonical public hub; a repository can still name a private or mirrored one.
        return Optional.of(URI.create("https://huggingface.co/"));
    }

    /** A {@code GET} of a file at a branch is kept under the commit the upstream resolves the branch to, so one content
     *  is one version - a commit, which never changes under its name - rather than a {@code main} whose bytes move. It
     *  is how other Hugging Face proxies keep a model, and what lets {@link #describePointer} name a proxied pointer's
     *  version from its key. The resolution is one HEAD; a {@code HEAD} request records nothing and keeps its path, as
     *  does a branch the upstream cannot resolve, which the leg then declines. */
    @Override
    public Optional<String> keptAs(FormatExchange exchange, URI upstream, ProxyFormat.Fetcher fetcher)
            throws IOException {
        String path = exchange.path();
        if (!exchange.method().equals("GET") || !path.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String rest = path.substring(PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return Optional.empty();
        }
        String repo = rest.substring(0, slash);
        String sub = rest.substring(slash + 1);
        Resolve resolve = parseResolve(sub);
        if (repo.isEmpty() || Keys.unsafe(repo) || resolve == null || isCommit(resolve.revision())
                || base(repo, resolve.type(), resolve.repoId()) == null || Keys.unsafe(resolve.revision())
                || unsafeFilepath(resolve.filepath())) {
            return Optional.empty();
        }
        ProxyFormat.Head head = fetcher.head(target(upstream, sub), Map.of()).orElse(null);
        String commit = head == null || head.status() != 200 ? null : commitOf(head);
        if (commit == null || !isCommit(commit)) {
            return Optional.empty();
        }
        return Optional.of(resolvePath(repo, resolve.type(), resolve.repoId(), commit, resolve.filepath()));
    }

    /**
     * Proxy a Hugging Face miss to an upstream Hub: {@code /huggingface/<repo>/<sub>} maps to {@code <upstream>/<sub>},
     * no path rewritten. A {@code resolve} file at a branch is resolved to its upstream commit by a HEAD on every read
     * and cached under that commit, so a moved branch re-fetches; a commit sha is cached directly. A {@code GET}
     * streams into the store and serves through {@link #file}; a {@code HEAD} on an uncached file answers
     * {@code Content-Length} from the upstream's headers without pulling the body. The {@code /api/...} index is
     * streamed fresh and carries no absolute URLs. {@code false} lets the local {@code 404} stand.
     */
    @Override
    public boolean pullThrough(FormatExchange exchange, ArtifactStore store, URI upstream,
                               ProxyFormat.Fetcher fetcher) throws IOException {
        String path = exchange.path();
        // The client's request is fetched upstream; path() is the name it is kept under, a commit where keptAs resolved
        // one.
        String requested = exchange.requestedPath();
        String rest = path.substring(PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return false;
        }
        String repo = rest.substring(0, slash);
        String sub = rest.substring(slash + 1);
        if (repo.isEmpty() || Keys.unsafe(repo)) {
            return false;
        }
        // The API is a mutable index, streamed fresh and never cached.
        for (String type : new String[] {MODELS, DATASETS, SPACES}) {
            if (sub.startsWith("api/" + type + "/")) {
                return proxyIndex(target(upstream, sub), exchange, fetcher);
            }
        }
        // A file: a branch is resolved to its upstream commit and cached under it.
        Resolve resolve = parseResolve(sub);
        if (resolve == null) {
            return false;
        }
        String base = base(repo, resolve.type(), resolve.repoId());
        if (base == null || Keys.unsafe(resolve.revision()) || unsafeFilepath(resolve.filepath())) {
            return false;
        }
        URI fileUrl = target(upstream, requested.startsWith(PREFIX + repo + "/")
                ? requested.substring((PREFIX + repo + "/").length()) : sub);
        // A 40-hex sha is cached directly; a branch is never a cache key, but resolved to its current commit by a HEAD
        // on every read, so a moved branch re-fetches. The hosted read path never serves a branch from a cached commit,
        // so a branch read always lands here, while a commit read is a local hit.
        ProxyFormat.Head upstreamHead = null;
        String commit;
        if (isCommit(resolve.revision())) {
            commit = resolve.revision();
        } else {
            upstreamHead = fetcher.head(fileUrl, Map.of()).orElse(null);
            if (upstreamHead == null || upstreamHead.status() != 200) {
                return false;   // the branch cannot be resolved (transport failure or upstream miss): local 404 stands
            }
            commit = commitOf(upstreamHead);
            if (commit == null || Keys.unsafe(commit)) {
                return false;   // upstream reported no usable commit sha: cannot cache immutably
            }
        }
        Blobs blobs = new Blobs(store);
        String fileKey = base + "/revs/" + commit + "/files/" + enc(resolve.filepath());
        if (blobs.size(fileKey) < 0) {
            if (exchange.method().equals("HEAD")) {
                // A HEAD on an uncached file answers from the upstream's headers without pulling the body.
                if (upstreamHead == null) {
                    upstreamHead = fetcher.head(fileUrl, Map.of()).orElse(null);
                    if (upstreamHead == null || upstreamHead.status() != 200) {
                        return false;
                    }
                }
                headFromUpstream(upstreamHead, commit, resolve.filepath(), exchange);
                return true;
            }
            try (ProxyFormat.Download download = fetcher.download(fileUrl, Map.of()).orElse(null)) {
                if (download == null || download.status() != 200) {
                    return false;
                }
                // An LFS-backed file's response carries its content sha256 as the git-lfs oid in X-Linked-Etag (or
                // ETag), and the streamed body is held to it. A non-LFS ETag is a git-blob sha1, not a content digest,
                // so such a file caches plainly. The digest rides on the artifact's own response, so there is no
                // separate declaring document to fail to read.
                byte[] expected = lfsSha256(download);
                if (!ProxyRelay.fill(blobs, fileKey, fileUrl, download.body(),
                        expected == null ? ProxyRelay.Declared.NONE : ProxyRelay.Declared.of("SHA-256", expected))) {
                    return false;
                }
                stampTime(store, base + "/revs/" + commit + "/time");
            }
        }
        // Served through the hosted read path under the commit, so X-Repo-Commit reports the real commit.
        file(repo, new Resolve(resolve.type(), resolve.repoId(), commit, resolve.filepath()), exchange, store);
        return true;
    }

    /** The commit an upstream resolve HEAD reports: {@code X-Repo-Commit}, else the dequoted {@code ETag}; {@code null}
     *  when neither is present, and the caller declines rather than cache under a non-commit key. */
    private static String commitOf(ProxyFormat.Head head) {
        String commit = firstHeader(head.headers(), "X-Repo-Commit");
        if (commit != null && !commit.isBlank()) {
            return commit.trim();
        }
        String etag = firstHeader(head.headers(), "ETag");
        if (etag == null) {
            return null;
        }
        etag = etag.trim();
        if (etag.startsWith("W/")) {
            etag = etag.substring(2).trim();
        }
        if (etag.length() >= 2 && etag.startsWith("\"") && etag.endsWith("\"")) {
            etag = etag.substring(1, etag.length() - 1);
        }
        return etag.isBlank() ? null : etag;
    }

    /** The content sha256 an LFS-backed download declares as its git-lfs oid: {@code X-Linked-Etag}, else {@code ETag},
     *  a quoted 64-hex string. {@code null} otherwise: a non-LFS {@code ETag} is a git-blob sha1, a digest of
     *  {@code "blob <len>\0" + content}, which cannot verify the raw bytes. */
    private static byte[] lfsSha256(ProxyFormat.Download download) {
        byte[] linked = hex64(download.header("X-Linked-Etag"));
        if (linked != null) {
            return linked;
        }
        return hex64(download.header("ETag"));
    }

    /** Decode a possibly quoted or weak validator to bytes when it is exactly a 64-hex SHA-256, else {@code null}. */
    private static byte[] hex64(String value) {
        if (value == null) {
            return null;
        }
        String hex = value.trim();
        if (hex.startsWith("W/")) {
            hex = hex.substring(2).trim();
        }
        if (hex.length() >= 2 && hex.startsWith("\"") && hex.endsWith("\"")) {
            hex = hex.substring(1, hex.length() - 1);
        }
        if (hex.length() != 64) {
            return null;
        }
        try {
            return HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Answer a proxied {@code HEAD} on an uncached file from the upstream's headers alone: the content type from the
     *  path, the commit, the {@code ETag}, and the length ({@code X-Linked-Size} for an LFS object). */
    private static void headFromUpstream(ProxyFormat.Head head, String commit, String filepath,
                                         FormatExchange exchange) throws IOException {
        exchange.setResponseHeader("Content-Type", contentType(filepath));
        exchange.setResponseHeader("X-Repo-Commit", commit);
        String etag = firstHeader(head.headers(), "ETag");
        if (etag != null) {
            exchange.setResponseHeader("ETag", etag);
        }
        String length = firstHeader(head.headers(), "Content-Length");
        if (length == null) {
            length = firstHeader(head.headers(), "X-Linked-Size");
        }
        if (length != null) {
            exchange.setResponseHeader("Content-Length", length);
            exchange.setResponseHeader("X-Linked-Size", length);
        }
        exchange.respond(200, -1L).close();
    }

    /** The first value of a response header, case-insensitively, or {@code null}. */
    private static String firstHeader(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** Stream the API index fresh from upstream, never cached; {@code false} only on an upstream
     *  {@code 404}/{@code 410}. */
    private static boolean proxyIndex(URI target, FormatExchange exchange, ProxyFormat.Fetcher fetcher)
            throws IOException {
        // ENUMERATION: the API tells a client which files and revisions exist, so only an upstream that answered
        // 404/410 reaches the client as a 404. The loop is this leg's own for the X-Repo-Commit relay and the HEAD
        // short-circuit.
        try (ProxyFormat.Download download = fetcher.download(target, ProxyRelay.conditionalHeaders(exchange)).orElse(null)) {
            if (download == null) {
                return ProxyRelay.unanswered(target, exchange, ProxyRelay.Document.ENUMERATION,
                        "the upstream could not be reached");
            }
            if (download.status() == 304) {
                // The client's validators still match: the 304 and validators are relayed.
                ProxyRelay.relayValidators(download, exchange);
                String commit = download.header("X-Repo-Commit");
                if (commit != null) {
                    exchange.setResponseHeader("X-Repo-Commit", commit);
                }
                exchange.respond(304);
                return true;
            }
            if (ProxyRelay.upstreamMiss(download.status())) {
                return false;
            }
            if (download.status() != 200) {
                return ProxyRelay.unanswered(target, exchange, ProxyRelay.Document.ENUMERATION,
                        "the upstream answered " + download.status());
            }
            String contentType = download.header("Content-Type");
            exchange.setResponseHeader("Content-Type", contentType != null ? contentType : "application/json");
            ProxyRelay.relayValidators(download, exchange);
            String commit = download.header("X-Repo-Commit");
            if (commit != null) {
                exchange.setResponseHeader("X-Repo-Commit", commit);
            }
            if (exchange.method().equals("HEAD")) {
                String len = download.header("Content-Length");
                if (len != null) {
                    exchange.setResponseHeader("Content-Length", len);
                }
                exchange.respond(200, -1L).close();
                return true;
            }
            try (OutputStream out = exchange.respond(200, ProxyRelay.length(download.header("Content-Length")))) {
                download.body().transferTo(out);
            }
        }
        return true;
    }

    /** The upstream URL a request path maps to: {@code <upstream>/<sub>}, the alias already stripped. */
    private static URI target(URI upstream, String sub) {
        String root = upstream.toString();
        if (!root.endsWith("/")) {
            root += "/";
        }
        return URI.create(root + sub);
    }

    /** The store key that serves a resolve path, resolved as {@link #handle} resolves it. The compliance screen's
     *  sibling read needs it: an inspector holding a config or a weights shard asks for the model card beside it, which
     *  no generic {@code publish/} pointer would find. */
    @Override
    public Optional<String> servingKey(String requestPath, ArtifactStore store) throws IOException {
        if (!requestPath.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String rest = requestPath.substring(PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return Optional.empty();
        }
        Resolve resolve = parseResolve(rest.substring(slash + 1));
        if (resolve == null) {
            return Optional.empty();
        }
        String base = base(rest.substring(0, slash), resolve.type(), resolve.repoId());
        if (base == null || Keys.unsafe(resolve.revision())) {
            return Optional.empty();
        }
        // Through the serve's own revision resolution, so a reader sees only what a client would be served.
        String storageRev = resolveRevision(base, resolve.revision(), store);
        if (storageRev == null) {
            return Optional.empty();
        }
        String fileKey = base + "/revs/" + storageRev + "/files/" + enc(resolve.filepath());
        return new Blobs(store).size(fileKey) < 0 ? Optional.empty() : Optional.of(fileKey);
    }

    /** The version a pointer serves, from its key: the revision its files are stored and recorded under, a hosted
     *  branch's name or the commit a proxied fetch resolved to ({@link #keptAs}). A key that is no file pointer names
     *  nothing. */
    @Override
    public Optional<ArtifactDescriptor> describePointer(String key) {
        Located located = locateKey(key);
        if (located == null || located.filepath().isEmpty()) {
            return Optional.empty();
        }
        String repo = located.base().split("/")[1];
        return describe(resolvePath(repo, located.type(), located.repoId(), located.revision(), located.filepath()))
                .filter(described -> described.coordinate() != null && described.version() != null)
                .map(described -> described.withPath(key));
    }

    @Override
    public Optional<ArtifactDescriptor> describe(String path) {
        if (!path.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String rest = path.substring(PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return Optional.empty();
        }
        Resolve resolve = parseResolve(rest.substring(slash + 1));
        if (resolve == null) {
            return Optional.empty();
        }
        // The repo_id may be namespace/name, stored as nested directories, so it is validated segment by segment as
        // base() does; a whole-string check would strip the coordinate from every namespaced repository.
        if (unsafeRepoId(resolve.repoId()) || Keys.unsafe(resolve.revision())) {
            return Optional.of(ArtifactDescriptor.at(ECOSYSTEM, path));
        }
        return Optional.of(new ArtifactDescriptor(ECOSYSTEM, resolve.repoId(), resolve.revision(), path,
                contentType(resolve.filepath()), prerelease(resolve.revision()), null, -1L));
    }

    @Override
    public List<String> paths(String coordinate, String version, ArtifactStore store) {
        // File pointers live in the shared Blobs namespace, so the coordinate enumerates nothing in publish/.
        return List.of();
    }

    /** The store-key base of a repository, {@code hf/<repo>/<type>/<repo_id>} with the {@code repo_id}'s segments kept as
     *  nested key directories, or {@code null} when any segment is unsafe. */
    private static String base(String repo, String type, String repoId) {
        for (String segment : repoId.split("/", -1)) {
            if (Keys.unsafe(segment)) {
                return null;
            }
        }
        return "hf/" + repo + "/" + type + "/" + repoId;
    }

    /** Whether a {@code repo_id} is unsafe as a coordinate, judged segment by segment with {@link Keys#unsafe} as
     *  {@link #base} does, so a {@code namespace/name} is not rejected for its slash. */
    private static boolean unsafeRepoId(String repoId) {
        if (repoId.isEmpty()) {
            return true;
        }
        for (String segment : repoId.split("/", -1)) {
            if (Keys.unsafe(segment)) {
                return true;
            }
        }
        return false;
    }

    /** Stamp a revision's upload time by compare-and-set through {@link Retries}: the stamp drives default-revision
     *  resolution and {@code lastModified}, so a lost race is retried rather than leaving a revision unstamped. */
    private static void stampTime(ArtifactStore store, String key) throws IOException {
        Retries.update(store, key, _ -> Long.toString(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
    }

    private static final byte[] HOSTED = "1".getBytes(StandardCharsets.UTF_8);

    /** A registry's hosted marker, beside the type trees, so no revision or file listing surfaces it. */
    private static String hostedKey(String repo) {
        return "hf/" + repo + "/hosted";
    }

    /** Whether this registry has taken a hosted upload. The API gate keys on it, so a proxy registry's API reads relay
     *  the upstream index and never answer from a proxy-cached commit. */
    private static boolean hosted(String repo, ArtifactStore store) throws IOException {
        return store.readVersioned(hostedKey(repo)).isPresent();
    }

    /** Stamp the hosted marker once, by compare-and-set against absence; a lost race means a peer set it. */
    private static void markHosted(ArtifactStore store, String key) throws IOException {
        if (store.readVersioned(key).isEmpty()) {
            store.writeVersioned(key, HOSTED, null);
        }
    }

    /** Read a revision's stored upload time, or {@code 0} when absent or unparseable. */
    private static long readTime(ArtifactStore store, String key) throws IOException {
        Optional<ArtifactStore.Versioned> stored = store.readVersioned(key);
        if (stored.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(new String(stored.get().content(), StandardCharsets.UTF_8).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** The content hash of the file pointer at {@code key}, for the {@code ETag} and the tree {@code oid}, or
     *  {@code null}. */
    private static String hashOf(ArtifactStore store, String key) throws IOException {
        return store.readVersioned(key)
                .map(versioned -> ServableNames.hash(versioned.content()))
                .orElse(null);
    }

    /** An ISO-8601 UTC timestamp for a repository's {@code lastModified} field. */
    private static String iso(long millis) {
        return Instant.ofEpochMilli(millis).toString();
    }

    /** The content type for a Hugging Face file by extension - weights and archives are binary, configs and cards text. */
    private static String contentType(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json")) {
            return "application/json";
        }
        if (lower.endsWith(".md") || lower.endsWith(".txt")) {
            return "text/plain";
        }
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) {
            return "application/yaml";
        }
        if (lower.endsWith(".gz") || lower.endsWith(".tgz")) {
            return "application/gzip";
        }
        return "application/octet-stream";
    }

    /** Whether a revision is a prerelease: a revision is a branch or a commit, never a release, so a branch other than
     *  {@code main} is non-final. */
    private static boolean prerelease(String revision) {
        return !revision.equals(DEFAULT_REVISION) && !isCommit(revision);
    }

    /** Whether a revision looks like a 40-hex-character git commit sha (an immutable pin) rather than a branch. */
    static boolean isCommit(String revision) {
        if (revision.length() != 40) {
            return false;
        }
        for (int i = 0; i < revision.length(); i++) {
            char c = revision.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return false;
            }
        }
        return true;
    }

    /** Percent-encode a nested file path into one key segment, so a revision's files enumerate flat; {@link #dec}
     *  reverses it. Only {@code %} and {@code /} are escaped. */
    static String enc(String path) {
        return path.replace("%", "%25").replace("/", "%2F");
    }

    /** Reverse {@link #enc}: {@code %2F} to a separator, then {@code %25} to {@code %}. */
    static String dec(String encoded) {
        return encoded.replace("%2F", "/").replace("%25", "%");
    }

    /** A file path may be nested ({@code onnx/model.onnx}), so it is validated segment by segment: no empty, {@code .},
     *  {@code ..}, backslash or control-character segment reaches the store key. */
    private static boolean unsafeFilepath(String path) {
        if (path.isEmpty()) {
            return true;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return true;
            }
            for (int i = 0; i < segment.length(); i++) {
                char c = segment.charAt(i);
                if (c == '\\' || c < 0x20) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void respondJson(FormatExchange exchange, JsonNode node) throws IOException {
        exchange.setResponseHeader("Content-Type", "application/json");
        byte[] body = MAPPER.writeValueAsBytes(node);
        if (exchange.method().equals("HEAD")) {
            exchange.setResponseHeader("Content-Length", Integer.toString(body.length));
            exchange.respond(200, -1L).close();
        } else {
            exchange.respond(200, body);
        }
    }

    /** The migration-import capability, delegated to {@link HuggingFaceImporter}. */
    private final HuggingFaceImporter importer = new HuggingFaceImporter();

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

    /** Each file of the revision is put at its {@code resolve/<revision>/<path>}, one request a file; the target
     *  synthesises a commit of its own. A file stored under both the revision and a hosted branch's cached commit is
     *  put once. */
    @Override
    public Exported export(ArtifactStore repository, String coordinate, String version, ExportTarget target)
            throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            return Exported.WITHHELD;
        }
        Map<String, BlobExport.Pair> pairs = new LinkedHashMap<>();
        for (HuggingFaceFile file : huggingFaceFiles(coordinate, version, repository)) {
            pairs.putIfAbsent(file.path(), new BlobExport.Pair(file.key(), file.path().substring(PREFIX.length())));
        }
        return BlobExport.put(repository, List.copyOf(pairs.values()), target);
    }
}
