package build.jenesis.repository.format.huggingface;

import module java.base;
import module tools.jackson.databind;

import build.jenesis.repository.format.Listings;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.blobs.Keys;
import build.jenesis.repository.blobs.ProxyLeg;
import build.jenesis.repository.blobs.ProxyRelay;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.walk.TraversalException;

/**
 * The Hugging Face Hub registry format (the HF Hub resolve / repo-info HTTP protocol), so {@code huggingface_hub} -
 * {@code hf_hub_download}, {@code snapshot_download}, {@code AutoModel.from_pretrained} - resolves models, datasets and
 * spaces over the shared store. It owns {@code /huggingface/...}, where the first path segment is a registry (a
 * deployment alias) and the rest mirrors the HF Hub layout:
 *
 * <ul>
 *   <li>a repository file is served from {@code GET /huggingface/<repo>/<repo_id>/resolve/<revision>/<path>} (a dataset's
 *       under {@code .../datasets/<repo_id>/resolve/...}, a space's under {@code .../spaces/<repo_id>/resolve/...}); the
 *       {@code <path>} may be nested (a {@code onnx/model.onnx} subfolder file);</li>
 *   <li>a {@code PUT} to that same resolve path pushes the file into the registry - the HF Hub upload is a git-LFS commit
 *       API, but, as the Go format does for {@code GOPROXY}, a {@code PUT} to the download path is how a build gets a file
 *       into this hosted registry, so the wire protocol a client reads stays byte-identical;</li>
 *   <li>the repository index a client reads is served from stored documents - {@code GET /huggingface/<repo>/api/models/<repo_id>}
 *       (or {@code /api/datasets/...}, {@code /api/spaces/...}) answers the repo info with its {@code siblings} file list,
 *       and {@code .../api/models/<repo_id>/tree/<revision>} the file tree with each file's {@code oid} and {@code size} -
 *       both derived from the revision's stored file list on every upload. A repository with nothing
 *       stored answers {@code 404}, so a proxy registry can later fill it from upstream.</li>
 * </ul>
 *
 * <p><b>Revision-addressed.</b> A file is addressed by its repository revision (a git branch like
 * {@code main} or an immutable commit sha) exactly as the client requests it, so this format is a streaming,
 * revision-addressed file store: it stores whatever the client pushes at the client's revision and never opens an archive
 * (the coordinate is in the request path, not inside the bytes). {@code /api/.../<repo_id>} with no explicit revision
 * resolves the default branch {@code main} (falling back to the most recently uploaded revision), each file upload
 * stamping its revision's time as a small compare-and-set pointer through the store.
 *
 * <p><b>Streaming publish.</b> An uploaded file streams straight through {@link Blobs#write(String, InputStream)} into
 * the content-addressed store, hashed on the way and never buffered, so an arbitrarily large {@code model.safetensors}
 * (tens of gigabytes) never lands in heap; a download streams the blob back out and a {@code HEAD} answers the size and
 * the content hash ({@code ETag}) from stored metadata without a body. Files dedupe in the shared {@code blobs/}
 * namespace like every other language format.
 *
 * <p>The layout declares its ecosystem ({@code "Hugging Face"}) so the console and download tracking key on it, and
 * {@link #describe} resolves a resolve download path to its {@code <repo_id>} coordinate and {@code <revision>} version.
 * OSV carries no dedicated Hugging Face advisory feed, so vulnerability screening is a graceful no-op while a coordinate
 * still drives license and malicious-package screening (the sibling {@code compliance/huggingface} inspector, a
 * follow-on). File pointers live in the shared {@code Blobs} namespace, so coordinate-only eviction ({@link #paths}) is
 * not wired here (a file is unpublished by its request path).
 *
 * <p><b>Pull-through proxy.</b> The same layout is also a {@link ProxyFormat}: a local miss on a proxy registry is
 * served from an upstream Hugging Face Hub (the canonical {@code https://huggingface.co/}, so
 * {@link #defaultUpstream()} names it - a deployment can still point a repository at a private or mirrored hub). The
 * request path maps onto the upstream one for one after the registry alias is stripped - the {@code datasets/} /
 * {@code spaces/} / {@code api/} / {@code resolve/} layout is the real Hub layout, so {@code /huggingface/<repo>/<sub>}
 * fetches {@code <upstream>/<sub>} with no rewrite. A {@code resolve} file's bytes are immutable once addressed by a
 * commit, but a mutable branch like {@code main} is first resolved to its upstream commit sha (a real HEAD) and cached
 * under that immutable commit, so a branch read revalidates and re-resolves rather than serving a stale weight forever; a
 * {@code GET} streams from upstream straight into the content-addressed store ({@link Blobs#write(String, InputStream)},
 * never buffered) and is served through the same hosted read path, so a later read is a local hit that never touches the
 * upstream, while a {@code HEAD} on an uncached file answers the size from the upstream headers without pulling the
 * body; the mutable repo-info / tree API ({@code /api/...}) is streamed through fresh on every
 * read, never cached - the API carries no absolute download URLs (a client builds {@code resolve} URLs against this
 * endpoint from the {@code siblings}/tree names), so it needs no rewrite. The sibling {@code compliance/huggingface}
 * inspector screens the coordinate on both legs, and a {@code HuggingFaceImporter} migrates a Nexus/Artifactory
 * {@code huggingfaceml} registry by replaying each revision file through the streaming publish path.
 */
public final class HuggingFaceFormat implements RepositoryFormat, ArtifactLayout, ProxyLeg, BlobLayout, RepositoryImporter {

    /** The package-ecosystem name this format's artifacts report (distinct from {@link #name()}, the routing id). OSV
     *  has no dedicated Hugging Face feed, so vulnerability lookups on it simply find nothing; the coordinate still
     *  drives license and malicious-package screening. */
    public static final String ECOSYSTEM = "Hugging Face";

    /** Package-private like its peers: the listings beside this build their derived documents with it. */
    static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PREFIX = "/huggingface/";
    private static final String RESOLVE = "/resolve/";

    /** The default git branch a Hugging Face repository serves when a client names no revision. */
    private static final String DEFAULT_REVISION = "main";

    /** The three Hugging Face repository types, each with its own {@code api/<type>/} and (for datasets/spaces) resolve
     *  URL prefix. A model has no URL prefix; a dataset and a space carry {@code datasets/} / {@code spaces/}. */
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
        // The store root is hf/ (the /huggingface/ prefix is only the request path).
        return List.of("hf");
    }

    @Override
    public List<String> blobKeys(String coordinate, String version, ArtifactStore store) throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            // A traversal-shaped coordinate or version maps nowhere: these keys are what an eviction DELETES, and
            // ArtifactStore.delete is not screened. The shared per-part screen, so a legitimately
            // multi-segment coordinate still resolves.
            return List.of();
        }
        // A repository's files are addressed under a git revision and a percent-encoded filepath, discovered by listing
        // rather than derivable from the <repoId,revision> coordinate alone. Walk the version's own file tree per
        // registry and per repository type (models/datasets/spaces - the descriptor carries no type): base
        // hf/<repo>/<type>/<repoId>, then PAGE base/revs/<R>/files for R = the requested revision AND, when a hosted
        // branch synthesised a commit for it, the immutable commit that revision maps to - a hosted upload stores files
        // under the branch name while a pull-through proxy caches the identical content under the immutable commit, so
        // the UNION covers a hosted-branch hold that also has a proxy-cached commit alias (content-addressing shares one
        // withheld/<hash> marker for identical bytes, so the common case converges anyway). Each file pointer's body is a
        // bare-hex blob hash, so the BlobLayout.blobHashes default resolves the withhold set from them (the time/commit
        // markers live OUTSIDE files/ and are never collected). A retroactive hold marks those hashes - the resolve
        // serve, the repo-info siblings list and the file tree all gate on the marker - and an eviction deletes these
        // exact keys; before this the empty return made a hold a silent no-op and a KEV-listed model/dataset kept
        // serving. The files listing is attacker-publishable, so it is PAGED, never list()ed whole (the Audit-26 DoS
        // lesson).
        List<HuggingFaceFile> files = huggingFaceFiles(coordinate, version, store);
        List<String> keys = new ArrayList<>(files.size());
        for (HuggingFaceFile file : files) {
            keys.add(file.key());
        }
        return keys;
    }

    /** The resolve request paths this coordinate version's files serve at
     *  ({@code /huggingface/<repo>/[datasets/|spaces/]<repo_id>/resolve/<revision>/<path>}, the inverse of
     *  {@link #describe}), so a retroactive hold links a {@code /quarantine} review handle per served path exactly as
     *  {@code ArtifactLayout.paths} does for a publish/ layout. A commit-alias file (a proxy-cached commit of a hosted
     *  branch) still maps to the branch resolve path the coordinate version names, so its review handle round-trips
     *  through {@link #describe} back to this same version; identical paths across the union are de-duplicated. Reads
     *  only the tiny pointers, never a blob body. */
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

    /** One stored Hugging Face file located by the retroactive-hold discovery walk: its store pointer key and the
     *  resolve request path it serves at. */
    private record HuggingFaceFile(String key, String path) {
    }

    /** Every file stored for {@code (repoId, revision)}, across every registry and repository type, over the union of
     *  the requested revision and the immutable commit a hosted branch synthesised for it. The registry set is
     *  operator-configured (bounded), so a bare {@code store.list("hf")} is right for it; the per-revision files listing
     *  is attacker-publishable, so it is enumerated through the shared bounded {@link #FILES} scan, never
     *  {@code list()}ed whole. The revision/alias union above is a two-element set of POINT lookups (the requested
     *  revision plus the commit a hosted branch cached for it), not a traversal - only the listing beneath it is. */
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
                // The union of storage revisions: the requested revision (a hosted branch stores files under its own
                // name), plus the immutable commit that revision maps to when a hosted branch cached one (a proxy caches
                // identical content under that commit). A commit-pinned revision has no commit pointer, so the union is
                // just the revision itself.
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

    /**
     * The immutable commit a hosted branch synthesised for {@code revision} (its stored commit document), or
     * {@code null} when there is none (a commit-pinned revision synthesises no commit), the stored commit is the
     * revision itself, or the stored value is unsafe as a key segment - so the union only ever adds a distinct, safe
     * commit alias.
     *
     * <p><b>This alias is why this format does not answer {@code BlobLayout.describePointer}, and the reason is
     * worth keeping.</b> That clause rebuilds a lost {@code published/} row by naming, from a stored pointer key
     * alone, the coordinate version the pointer serves. Every other format here can: the pair is in the key, in
     * path segments or in a filename the ecosystem guarantees is splittable. Here it is not - a key under
     * {@code revs/<commit>/files} looks identical to one under {@code revs/<branch>/files}, and which of the two
     * the publish recorded is decided by a stored commit document this method reads. {@code describePointer} is
     * handed a key and nothing else, so it cannot make that read.
     *
     * <p>Answering the storage revision anyway would not be a parse error, it would be a decision: a commit is a
     * revision a client really can resolve at, so the row would not be fictional - it would be a <em>second</em>
     * version of the same content that the publish never recorded, which retention would then age on its own and
     * eviction would count separately. That is a product decision about the inventory, not a key grammar, so this
     * format answers nothing until it is made. Empty is the documented answer for "no repair for this format",
     * and a format with no repair is exactly as repairable as it was before the seam existed.
     */
    private static String commitAlias(String base, String revision, ArtifactStore store) throws IOException {
        String commit = storedCommit(base, revision, store);
        if (commit == null || commit.isEmpty() || commit.equals(revision) || Keys.unsafe(commit)) {
            return null;
        }
        return commit;
    }

    /** The resolve request path a file serves at - the mirror of the {@code PUT}/{@code GET}
     *  {@code /huggingface/<repo>/[datasets/|spaces/]<repo_id>/resolve/<revision>/<path>} route, keyed on the coordinate
     *  revision (the branch name a client requests) so it round-trips through {@link #describe} back to this version. */
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

    /** The bounded page size the retroactive-hold discovery walk streams the per-revision files listing through. */
    private static final int WALK_PAGE = 1000;

    /** The per-revision files listing: a flat container enumerated through the shared bounded primitive - a
     *  revision's file set, not a subtree, so the flat scan is the right shape and a tree walk would buy an
     *  {@code exists} probe per name it does not need. This feeds {@code blobKeys}/{@code servedPaths}, so a listing
     *  that answered short would be a KEV-listed file that keeps serving after its hold: the entry cap is therefore
     *  OFF, and the binding bound is the primitive's step budget (1000 page round-trips, ~10^6 names), which raises a
     *  named {@link TraversalException} rather than dropping keys. */
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
        // The repo-info / tree API, served from stored documents: api/{models,datasets,spaces}/<repo_id>[/tree|revision/<rev>...].
        for (String type : new String[] {MODELS, DATASETS, SPACES}) {
            String apiPrefix = "api/" + type + "/";
            if (sub.startsWith(apiPrefix)) {
                api(repo, type, sub.substring(apiPrefix.length()), exchange, store);
                return;
            }
        }
        // A file resolve/download (or a PUT publish): [datasets/|spaces/]<repo_id>/resolve/<revision>/<path>.
        Resolve resolve = parseResolve(sub);
        if (resolve != null) {
            file(repo, resolve, exchange, store);
            return;
        }
        exchange.respond(404);
    }

    /**
     * Parse a resolve/download path {@code [datasets/|spaces/]<repo_id>/resolve/<revision>/<path>} into its repository
     * type, {@code repo_id}, {@code revision} and (possibly nested) file path, or {@code null} when {@code sub} is not a
     * resolve path. The type prefix is stripped first so a model (no prefix), a dataset and a space are all recognised.
     */
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
     * Serve a stored file ({@code GET}/{@code HEAD}) or stream an upload into the CAS ({@code PUT}). The upload is
     * content-addressed while it streams, so a large model weight never lands in heap; the upload also stamps its
     * revision's time so the generated {@code siblings} / {@code tree} index and the default-revision resolution order
     * correctly. A {@code HEAD} answers the size and the content hash ({@code ETag}) from stored metadata without a body.
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
            blobs.write(revBase + "/files/" + enc(resolve.filepath()), exchange.requestStream());
            stampTime(store, revBase + "/time");
            // The revision's commit id, file tree and info document are derived from its stored file list, which
            // the upload updates with this one file - never a walk of the revision's other files.
            new HuggingFaceListings(blobs).refresh(base, resolve.revision(), resolve.type(), resolve.repoId(),
                    resolve.filepath());
            // Stamp the per-registry hosted-publish marker, so a later repo-info / tree API read serves the local
            // files. A pull-through proxy registry (whose files are cached by proxy() under their immutable commit,
            // which stamps only the revision time, never this marker) never writes it, so its API reads miss locally
            // and reproxy the authoritative upstream index fresh (the RPM hosted gate).
            markHosted(store, hostedKey(repo));
            exchange.respond(201);
            return;
        }
        if (!exchange.method().equals("GET") && !exchange.method().equals("HEAD")) {
            exchange.respond(405);
            return;
        }
        // Serve only from a revision whose bytes are stored directly: a hosted branch under its own name, or an
        // immutable commit sha (a proxy-cached branch lives under its commit, never under the branch, so a branch
        // resolve on a proxy repo 404s here and is revalidated by the proxy). The reported X-Repo-Commit is always the
        // real commit, never the branch name.
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

    /**
     * Route a repo-info / tree API request {@code <repo_id>[/revision/<rev>][/tree/<rev>[/<subpath>]]} for a repository
     * type, served from stored documents. Everything before a {@code revision} or {@code tree} keyword segment is the {@code repo_id}
     * (which may be a two-segment {@code namespace/name}); a {@code tree} request answers the file tree, anything else the
     * repository info with its {@code siblings} file list. The API is read-only ({@code GET}/{@code HEAD}); an upload goes
     * through the resolve {@code PUT} path.
     */
    private void api(String repo, String type, String remainder, FormatExchange exchange, ArtifactStore store)
            throws IOException {
        if (!exchange.method().equals("GET") && !exchange.method().equals("HEAD")) {
            exchange.respond(405);
            return;
        }
        if (!hosted(repo, store)) {
            // A proxy registry needs this local miss so the pull-through streams the authoritative upstream repo-info /
            // tree index fresh on every read. In particular a default-revision repo-info (no explicit revision) is
            // served fresh from upstream and never resolved to a proxy-cached commit - restoring the round-1
            // "streamed fresh, never cached" contract, which the local index otherwise regresses once any
            // file has been cached under its commit. Mirrors the RPM hosted-revision gate.
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
        // A structural emptiness probe over the revision's raw file container: a revision with nothing stored is the
        // local miss a proxy registry needs. The rendered listings below re-read it through the screened enumeration,
        // which is what decides which names may be disclosed.
        if (!StoredListing.present(store, HuggingFaceListings.files(base, resolved))
                && store.isEmpty(base + "/revs/" + resolved + "/files")) {
            exchange.respond(404);
            return;
        }
        // Both documents are derived from the revision's stored file list on every upload; a revision read before
        // its list was materialised derives them now, once.
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
                // A subtree: the stored tree filtered to the file at the subpath or the files beneath it.
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
            // The validator the dispatcher used to derive from the bytes it was handed. Streaming means it never
            // holds them, so the format attaches its own - the document's stored sha256, which costs no
            // materialisation to read. Every other format's listing already does this.
            Listings.serve(exchange, document, null);
        }
    }

    /**
     * The storage revision a read resolves to: the one requested when a client named it and its bytes are stored
     * directly (a hosted branch under its own name, or an immutable commit sha), otherwise the default branch
     * {@code main} when it holds files, falling back to the most recently uploaded revision. A proxy-cached branch lives
     * under its commit sha (never under the branch), so a branch resolve on a proxy repo returns {@code null} here and
     * is revalidated by the proxy. {@code null} when nothing is published, so the caller answers {@code 404} (and a
     * proxy registry can fill it from upstream).
     */
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

    /** Whether any file is stored under a revision - a bounded ONE-child probe of the revision's {@code files} prefix,
     *  never a whole-directory {@code list()}. {@link #resolveRevision} is called from the hot single-file download
     *  ({@link #file}) on EVERY read, and its only question of a revision is "does it hold any file?"; the former
     *  {@code store.isEmpty(...)} materialised the revision's ENTIRE (attacker-publishable) file listing just to
     *  test emptiness, so a revision with many thousands of files heap-blew on each download. This answers the same
     *  question in O(1) by paging at most one child, the paged existence idiom the Audit-26 DoS sweep applied across the
     *  store walks; the branch/commit resolution semantics are unchanged (empty revision -&gt; null -&gt; 404). */
    private static boolean hasStoredFiles(ArtifactStore store, String filesPrefix) {
        if (!Blobs.nameable(filesPrefix)) {
            // A prefix composed from a client-shaped model/revision name can exceed the store's key cap, and nothing
            // can be stored beneath one - see Blobs.nameable. Answered here rather than by paging a container the
            // backend cannot even name (the filesystem raises ENAMETOOLONG rather than paging empty).
            return false;
        }
        List<String> probe = new ArrayList<>(1);
        store.page(filesPrefix, "", 1, probe::add);
        return !probe.isEmpty();
    }

    /** The commit id a read reports for a storage revision: the revision itself when it is a commit sha, else the
     *  id derived from the revision's stored file list (materialising the list, once, when it never was). */
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

    /** The stored commit id of a revision, or {@code null} when none was derived yet; derives nothing. The stored
     *  listing is the one place a commit lives - a revision whose listing is not materialised yet answers null and
     *  {@link #reportedCommit} derives it. */
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
        // Hugging Face has one canonical public hub; a deployment can still override it per repository (a private or
        // mirrored hub), unlike RPM/Conda where no single upstream exists and the default stays empty.
        return Optional.of(URI.create("https://huggingface.co/"));
    }

    /**
     * Proxy a Hugging Face miss to an upstream Hub. The request {@code /huggingface/<repo>/<sub>} maps to
     * {@code <upstream>/<sub>} one for one - the registry name is a deployment alias, and everything after it
     * ({@code datasets/} / {@code spaces/} / {@code api/} / {@code resolve/} ...) is the real Hub layout, so no path is
     * rewritten. A {@code resolve} file's bytes are immutable once addressed by a commit, but the requested revision may
     * be a mutable branch ({@code main}): a branch is resolved to its current upstream commit via a real HEAD (the
     * fetcher's {@link ProxyFormat.Fetcher#head}) on every read, so the cache is keyed under the immutable commit sha
     * and a moved branch re-resolves and re-fetches instead of serving a stale weight; a 40-hex sha is already
     * immutable and cached directly. A {@code GET} streams the body from upstream straight into the content-addressed
     * store ({@link Blobs#write(String, InputStream)}, never buffered) under that commit key and serves it through the
     * hosted read path ({@link #file}), so a later read is a local hit; a {@code HEAD} on an uncached file answers
     * {@code Content-Length} from the upstream HEAD's headers WITHOUT pulling the tens-of-GB body, so a client's initial
     * size probe never downloads the artifact. The mutable repo-info / tree API is streamed through fresh on every read
     * and never cached; it carries no absolute download URLs, so it needs no rewrite (the client builds {@code resolve}
     * URLs against this endpoint from the {@code siblings}/tree names, which the local {@code config.json}-less protocol
     * points back through us). Returns {@code false} to let the local {@code 404} stand on an unproxyable path or an
     * upstream miss.
     */
    @Override
    public boolean pullThrough(FormatExchange exchange, ArtifactStore store, URI upstream,
                               ProxyFormat.Fetcher fetcher) throws IOException {
        String path = exchange.path();
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
        // The repo-info / tree API is a mutable index: stream it through fresh, never cached (no rewrite needed).
        for (String type : new String[] {MODELS, DATASETS, SPACES}) {
            if (sub.startsWith("api/" + type + "/")) {
                return proxyIndex(target(upstream, sub), exchange, fetcher);
            }
        }
        // A resolve/download file's bytes are immutable once addressed by a commit, but the requested revision may be a
        // mutable branch: resolve it to the upstream commit, cache under that immutable commit, and serve as a local hit.
        Resolve resolve = parseResolve(sub);
        if (resolve == null) {
            return false;
        }
        String base = base(repo, resolve.type(), resolve.repoId());
        if (base == null || Keys.unsafe(resolve.revision()) || unsafeFilepath(resolve.filepath())) {
            return false;
        }
        URI fileUrl = target(upstream, sub);
        // Resolve the requested revision to the immutable commit sha the cache is keyed under. A 40-hex sha is already
        // immutable and cached directly; a branch/tag (main) must never be a cache key - it is resolved to its current
        // upstream commit via a HEAD on every read. The bytes are keyed under the resolved commit and never under the
        // branch, so a moved branch re-resolves to the new commit and re-fetches (a cache miss) rather than serving a
        // stale weight forever - the "re-point" is implicit in keying by the freshly-resolved commit. A branch read is
        // therefore always routed here (the hosted read path never serves a branch resolve from a cached commit, so it
        // 404s and hands control to this revalidating proxy), while the immutable commit path is a local hit.
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
                // A HEAD on an uncached file answers Content-Length from the upstream response headers WITHOUT pulling
                // the whole (tens-of-GB) body into the cache; only a GET streams and caches the body.
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
                // Point-integrity: an LFS-backed file's resolve response carries its content sha256 as the git-lfs oid
                // in X-Linked-Etag (or ETag), so verify the streamed body against it and refuse a mismatch - the
                // checksum parity the Maven proxy leg has. A non-LFS file's ETag is a git-blob sha1 (not a raw-content
                // digest), so it is not a verifiable point checksum and the file falls back to plain caching.
                //
                // This is the one leg with no declaring DOCUMENT at all, so the earlier split cannot arise here: the digest
                // rides on the artifact's own response headers, and a response this repository could not read is
                // already the `download == null || status != 200` decline two lines above. There is no second fetch to
                // drop.
                byte[] expected = lfsSha256(download);
                if (!ProxyRelay.fill(blobs, fileKey, fileUrl, download.body(),
                        expected == null ? ProxyRelay.Declared.NONE : ProxyRelay.Declared.of("SHA-256", expected))) {
                    return false;
                }
                stampTime(store, base + "/revs/" + commit + "/time");
            }
        }
        // Serve the cached file (GET body or HEAD metadata) through the hosted read path, keyed by the immutable commit
        // so X-Repo-Commit reports the real commit rather than the requested branch name.
        file(repo, new Resolve(resolve.type(), resolve.repoId(), commit, resolve.filepath()), exchange, store);
        return true;
    }

    /** The commit sha an upstream resolve HEAD reports: the Hub's {@code X-Repo-Commit} header, falling back to the
     *  {@code ETag} (dequoted, weak-validator prefix stripped) when that is absent. {@code null} when neither is
     *  present, so the caller declines to cache under a non-commit key. */
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

    /** The content sha256 an LFS-backed resolve download declares as its git-lfs oid: the Hub sends it as
     *  {@code X-Linked-Etag} (preferred - it is the LFS object's own validator) or, absent that, {@code ETag}, in each
     *  case a quoted 64-hex string. {@code null} when neither is a 64-hex digest - a non-LFS file's {@code ETag} is a
     *  git-blob sha1 (a digest of {@code "blob <len>\0" + content}, not of the raw bytes), so it is not a point checksum
     *  this can verify the streamed body against, and the caller falls back to plain caching. */
    private static byte[] lfsSha256(ProxyFormat.Download download) {
        byte[] linked = hex64(download.header("X-Linked-Etag"));
        if (linked != null) {
            return linked;
        }
        return hex64(download.header("ETag"));
    }

    /** Decode a (possibly quoted / weak) validator to its raw bytes when it is exactly a 64-hex SHA-256, else
     *  {@code null}. */
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

    /** Answer a proxied {@code HEAD} on an uncached file from the upstream response headers alone - the content type
     *  from the filepath, the resolved commit, the upstream {@code ETag}, and the {@code Content-Length} the Hub
     *  reports ({@code X-Linked-Size} for an LFS object) - so a client's initial size probe never pulls the body. */
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

    /** The first value of a response header, case-insensitively, or {@code null} - the accessor {@link ProxyFormat.Head}
     *  (status + headers only) does not carry, unlike {@link ProxyFormat.Download}. */
    private static String firstHeader(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** Stream a mutable index (the repo-info / tree API) through fresh from upstream, never cached; returns
     *  {@code false} only on an upstream {@code 404}/{@code 410}, so the local {@code 404} stands when - and only
     *  when - an origin said there is no such thing. */
    private static boolean proxyIndex(URI target, FormatExchange exchange, ProxyFormat.Fetcher fetcher)
            throws IOException {
        // ENUMERATION: the repo-info / tree API is what a client reads to learn which files a revision carries and
        // which revisions exist, so an absent answer is the fact "no such model / an empty tree" that a download plan
        // is then built from - never "not cached here, re-pull". Only an upstream that ANSWERED 404/410 may reach the
        // client as a 404; anything else refuses visibly. The loop stays this leg's own for the X-Repo-Commit
        // relay and the HEAD short-circuit, but the verdict is the shared one.
        try (ProxyFormat.Download download = fetcher.download(target, ProxyRelay.conditionalHeaders(exchange)).orElse(null)) {
            if (download == null) {
                return ProxyRelay.unanswered(target, exchange, ProxyRelay.Document.ENUMERATION,
                        "the upstream could not be reached");
            }
            if (download.status() == 304) {
                // The client's validators still match upstream: relay the 304 (and the validators) so a 304-capable
                // client is not forced to re-download the whole repo-info / tree index.
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

    /** The upstream URL a request path maps to: {@code <upstream>/<sub>}, the registry alias already stripped so the
     *  Hub layout in {@code sub} lands on the upstream one for one. */
    private static URI target(URI upstream, String sub) {
        String root = upstream.toString();
        if (!root.endsWith("/")) {
            root += "/";
        }
        return URI.create(root + sub);
    }

    /**
     * The store key that serves a resolve path - the same {@code revs/<commit>/files/<file>} key {@link #handle}
     * resolves, so a reader outside this format sees exactly what a client would be served.
     *
     * <p>What needs it is the compliance screen's sibling read: an inspector holding a config.json or a weights
     * shard asks for the model card beside it, and until this existed that read resolved only through the free
     * core's generic {@code publish/<path>} pointer - which Hugging Face does not use, so the card was invisible
     * from beside the file it describes and a repository could not be gated on its own declared licence.
     */
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
        // Through the same revision resolution the serve does: a branch name only answers where its bytes are stored
        // under that name, and a proxy-cached branch lives under its commit. A reader must not see what a client
        // would not be served.
        String storageRev = resolveRevision(base, resolve.revision(), store);
        if (storageRev == null) {
            return Optional.empty();
        }
        String fileKey = base + "/revs/" + storageRev + "/files/" + enc(resolve.filepath());
        return new Blobs(store).size(fileKey) < 0 ? Optional.empty() : Optional.of(fileKey);
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
        // The repo_id is legitimately multi-segment (a namespaced repository, {@code namespace/name}), stored as nested
        // key directories, so it is validated segment by segment exactly as base() does - NOT as one Keys.unsafe(...)
        // that would reject every '/' and strip the coordinate from a namespaced repo's descriptor. Without this a
        // namespaced Hugging Face upload (the common case) resolved to a coordinate-less descriptor, so the retroactive
        // enforcement sweeps never enumerated it and a release could not re-derive its coordinate to lift the hold.
        if (unsafeRepoId(resolve.repoId()) || Keys.unsafe(resolve.revision())) {
            return Optional.of(ArtifactDescriptor.at(ECOSYSTEM, path));
        }
        return Optional.of(new ArtifactDescriptor(ECOSYSTEM, resolve.repoId(), resolve.revision(), path,
                contentType(resolve.filepath()), prerelease(resolve.revision()), null, -1L));
    }

    @Override
    public List<String> paths(String coordinate, String version, ArtifactStore store) {
        // Hugging Face file pointers live in the shared Blobs namespace (like npm/pypi/go/conan), not the Publication
        // namespace coordinate-based eviction walks, so nothing is enumerable from the coordinate alone.
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

    /** Whether a {@code repo_id} is unsafe as a coordinate: it may legitimately be multi-segment ({@code namespace/name},
     *  stored as nested key directories), so each segment is validated with {@link Keys#unsafe} rather than the whole
     *  string - the same segment-wise guard {@link #base} applies - so a namespaced repository is not wrongly rejected
     *  by the single '/' it contains. */
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

    /** Stamp a revision's upload time to now, a small compare-and-set pointer through the store (never a raw file). The
     *  stamp is load-bearing - it drives default-revision resolution and the {@code lastModified} ordering - so a lost
     *  compare-and-set re-reads the token and retries (the shared {@link Retries} policy) rather than
     *  discarding the returned boolean, which would leave a concurrently-uploaded revision unstamped and mis-ordered. */
    private static void stampTime(ArtifactStore store, String key) throws IOException {
        Retries.update(store, key, _ -> Long.toString(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
    }

    private static final byte[] HOSTED = "1".getBytes(StandardCharsets.UTF_8);

    /** The reserved store key of a registry's hosted-publish marker - a child of {@code hf/<repo>}, a sibling of the
     *  {@code models}/{@code datasets}/{@code spaces} type trees, so it is never surfaced by any revision/file listing. */
    private static String hostedKey(String repo) {
        return "hf/" + repo + "/hosted";
    }

    /** Whether this registry has ever taken a hosted upload - it then carries {@link #hostedKey}, which a pull-through
     *  proxy never writes (its file cache stamps only the revision time). The repo-info / tree API gate keys on it so a
     *  proxy registry's API reads always miss locally and reproxy the upstream index fresh, so a default-revision
     *  repo-info is never served from a proxy-cached commit. */
    private static boolean hosted(String repo, ArtifactStore store) throws IOException {
        return store.readVersioned(hostedKey(repo)).isPresent();
    }

    /** Stamp the hosted-publish marker once, idempotently - a compare-and-set against an absent pointer, so a
     *  concurrent upload's lost race simply means a peer already set it. */
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

    /** The content hash (the SHA-256 the blob is stored under) of the file pointer at {@code key}, for the {@code ETag}
     *  and the {@code tree} {@code oid}, or {@code null} when nothing is published there. */
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

    /** Whether a revision denotes a prerelease - a Hugging Face revision is a branch or commit, never a release, so a
     *  branch other than {@code main} is treated as a non-final revision. */
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

    /** Percent-encode a (possibly nested) file path into a single store-key segment, so a revision's files enumerate
     *  flat under one prefix; {@link #dec} reverses it. Only {@code %} and {@code /} are escaped, the two characters that
     *  would otherwise split the segment. */
    static String enc(String path) {
        return path.replace("%", "%25").replace("/", "%2F");
    }

    /** Reverse {@link #enc}: {@code %2F} back to a path separator and {@code %25} back to a literal {@code %} (in that
     *  order, since a {@code %25} never contains a {@code %2F}). */
    static String dec(String encoded) {
        return encoded.replace("%2F", "/").replace("%25", "%");
    }

    /**
     * A single path segment ({@code repo}, a {@code repo_id} segment, a {@code revision}) becomes store-key segments, so a
     * value that is empty, carries a path separator or control character, or is a {@code .}/{@code ..} traversal segment
     * could steer a write or read outside the repository's key space and is refused. Real repository ids and revisions
     * are identifiers or hex shas, so no legitimate value is rejected.
     */

    /** A file path may be nested ({@code onnx/model.onnx}), so it is validated segment by segment: no empty, {@code .},
     *  {@code ..}, backslash or control-character segment reaches {@link #enc} and the store key. */
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

    /** The migration-import capability (WSPI.2 (c)), delegated to the layout-only {@link HuggingFaceImporter} - the format IS the
     *  discovered importer now (an {@code instanceof} capability), and the importer class stays as its delegate. */
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
}
