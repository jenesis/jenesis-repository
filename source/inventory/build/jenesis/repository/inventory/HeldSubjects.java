package build.jenesis.repository.inventory;

import module java.base;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.walk.Traversal;

/**
 * The single owner of the durable {@code subjects/} key space: <b>what a held request path is a path to</b>, written
 * where the hold is placed and reclaimed with it. This is the format-independent path &rarr; (ecosystem, coordinate,
 * version) record the hold paths kept failing against.
 *
 * <p><b>The gap it closes.</b> Every path-keyed hold question - "which kinds hold this path", "may this name be
 * disclosed", "what does discarding this path destroy" - has to turn a request path into a coordinate first, and the
 * only route from a path to a coordinate is the owning format's {@code ArtifactLayout}/{@code BlobLayout} reverse
 * mapping. So uninstalling a <em>format</em> module made every one of them answer as though nothing was held, while
 * the coordinate-keyed {@code holds/<kind>/} records ({@link HoldMarkers}) sat there intact and unreachable. Nothing
 * else in the store could stand in: {@code publish/<path>} is a bare content hash by design, {@code holds/dispatch}
 * carries a format name and a body hash but no coordinate and is written only by the screen-time legs, the quarantine
 * index fuses coordinate and version into one string with no ecosystem and is best-effort and age-pruned, and every
 * other per-version space is keyed <em>by</em> the triple with no reverse index. So the record had to be invented, and
 * this is it.
 *
 * <p><b>Why it is affordable: bounded by holds, not by artifacts.</b> The objection that kept this open was that a
 * path &rarr; coordinate index means a new durable key space written for every artifact. It does not, because every
 * consumer of it is a <em>hold</em> path. A row is written when a hold is placed - a screen-time QUARANTINE, a
 * retroactive enforcement sweep, a proxied body withheld for review - and never on an ordinary publish, so the space
 * is the size of the review queue rather than the size of the repository. A repository with no holds carries no rows
 * at all.
 *
 * <p><b>Both directions, because the question is asked both ways.</b> One row would not do, and neither direction may
 * be a scan of the other:
 * <ul>
 *   <li>{@code subjects/path/<sha-256 of the path>} - the path face, read by
 *       {@code HoldRecords.heldKinds(store, path)} and the release/discard primitive. A point read.</li>
 *   <li>{@code subjects/version/<eco>/<coord>/<ver>/<sha-256 of the path>} - the version face, read by
 *       {@link InventoryBrowse}'s name-enumeration screen, which has a coordinate in hand and must find out whether a
 *       live {@code /quarantine} review pointer belongs to it. One bounded level listing.</li>
 * </ul>
 * Deriving the second from the first would mean descending the whole review queue per enumerated name; deriving the
 * first from the second would mean scanning the whole space per path. Both are written and reclaimed together, by this
 * class and nowhere else - the one-owner rule {@link OverrideRecords} and {@link HoldMarkers} are the
 * precedent for, and the reason the four {@code overrides/} spellings could not happen again here.
 *
 * <p><b>A row is a statement made when the format was installed.</b> {@link #record} is called at hold time, which is
 * exactly when the owning format is by construction present: something had to place the path to screen or sweep it. So
 * the record captures the answer while it can still be got, and every later reader gets that answer whether or not the
 * module survives. A path a format placed but that names no versioned artifact (a checksum, generated metadata, a raw
 * upload) is recorded too, with a null coordinate: <em>"asked, and the answer is: no coordinate"</em> is a fact a
 * reader needs, and it is not the same fact as an absent row. The three-valued shape is the theme's own idiom -
 * spelled as the one {@link build.jenesis.repository.store.Known} type the reconcile
 * sweep's liveness, {@code knownPaths} and the reclaiming pass's pointer roots all answer in - applied to the one
 * question this space answers.
 *
 * <p><b>Reclaimed with what it describes, by the existing lifecycle.</b> A row lives exactly as long as its hold: the
 * release and discard legs of {@code HoldLifecycle} drop it beside the {@code /quarantine} pointer they clear, the
 * gate's accepted-re-publish clear drops it with the pointer it supersedes, the re-analysis auto-release drops it with
 * the hold it lifts, and a version's {@link InventoryEviction eviction} takes the whole version face with the version.
 * There is no sweep for it and there must not be one - a periodic pass that reaped rows it could not explain would be
 * exactly the absence-triggered deletion this whole theme exists to remove. Nothing here is ever deleted because a
 * module is missing.
 *
 * <p><b>Fail-closed, and self-correcting where it can be.</b> Every read propagates its {@link IOException} rather
 * than answering "no subject", and a truncated version-face listing is refused rather than returned short, for the
 * reason {@link HoldMarkers#kinds} states: a short answer here reads as "nothing holds this". A stale row - one whose
 * hold ended in a window nothing reclaimed it in - can only make a reader ask a further question it would not
 * otherwise have asked, never assert a hold: the version face's consumer probes the live {@code /quarantine} pointer
 * before it withholds anything, and the path face's consumer reads the durable {@code holds/<kind>} records, which are
 * authoritative on their own. So the failure mode of a lost reclaim is an extra point read, not a permanent withhold.
 */
public final class HeldSubjects {

    /** The {@code subjects/} root. Its two children are the two faces below and nothing else, which is what lets the
     *  version face be a level listing rather than a guess. Public because the module that <em>declares</em> this space
     *  in the storage manifest is one away from the module that owns its spelling ({@code GateStorageNamespace} in the
     *  gate, for the reason {@code overrides} is declared there too), and the two are held to each other by name rather
     *  than by inspection. */
    public static final String ROOT = "subjects";

    /** The path face: {@code subjects/path/<sha-256 of the request path>}. */
    private static final String PATHS = ROOT + "/path";

    /** The version face: {@code subjects/version/<eco>/<coord>/<ver>/<sha-256 of the request path>}, the three
     *  coordinate segments URL-encoded for the reason {@link HoldMarkers#key} states - an un-encoded segment carrying a
     *  {@code /} (or empty) splices extra segments into the key and lets one version's rows collide with another's, and
     *  a reader that cannot predict the spelling cannot construct the key at all. */
    private static final String VERSIONS = ROOT + "/version";

    /** The version face's bounds. One level per held version - the paths of a single coordinate version that are
     *  currently under review - so no upload can grow it and the caps are never near. They are still declared and
     *  still refused loudly rather than left to a raw level listing: a SHORT answer here reads as "no held path
     *  belongs to this version", which is the fail-open shape this space exists to remove. */
    private static final BoundedChildren PATHS_OF_VERSION = BoundedChildren.bounded();

    private HeldSubjects() {
    }

    /**
     * What a held request path was a path to, as judged when the hold was placed. {@link #coordinate} and
     * {@link #version} are {@code null} for a path an installed format placed but that names no versioned artifact -
     * a real answer, and not the same as {@link #read} finding no row at all.
     */
    public record Subject(String path, String ecosystem, String coordinate, String version) {

        /** Whether this subject names a versioned artifact - the guard every caller that is about to compose a
         *  coordinate-keyed key or destroy a version's state must pass first. */
        public boolean versioned() {
            return coordinate != null && version != null;
        }
    }

    /**
     * <b>A request path is keyed by its digest, never spelled into the key.</b> Both faces name a held path by the
     * hex SHA-256 of it, and the path itself rides the row's body. That is not tidiness - it is what makes this space
     * writable at all, and the sibling spaces that spell a path into a key are the evidence: appending the path
     * ({@code holds/dispatch<path>}) grows the key by one segment per path segment and walks towards the store's
     * 64-segment cap, while URL-encoding it into one segment triples every separator and walks straight past a
     * filesystem's 255-byte name limit - a real, deep pool path is unwritable either way. The gate's own
     * {@code audit/quarantine-index} was the second of those and has since taken this fix; the first still
     * stands. A hold is placed on whatever path an ecosystem serves, including a pathological one, and a record that a
     * deep path cannot be written for is exactly the hold this class exists to keep answerable. A digest is
     * fixed-width, so neither bound can be reached and the two faces stay symmetric.
     *
     * <p>The trade is that a key no longer reads back as a path, which costs one thing and buys nothing back: the
     * version face's enumeration reads each row's body rather than decoding its name. That is a point read per held
     * path of one coordinate version - bounded by the review queue, like everything else here.
     */
    static String pathKey(String path) {
        return PATHS + "/" + digest(path);
    }

    /** The version face's key for one held path of one coordinate version - see {@link #pathKey} for why the path is
     *  a digest and the three coordinate segments are URL-encoded. */
    static String versionKey(String ecosystem, String coordinate, String version, String path) {
        return versionRoot(ecosystem, coordinate, version) + "/" + digest(path);
    }

    /** The version face's container for one coordinate version - the level {@link #paths} lists and {@link #forget}
     *  reaps. */
    private static String versionRoot(String ecosystem, String coordinate, String version) {
        return VERSIONS + "/" + encode(ecosystem) + "/" + encode(coordinate) + "/" + encode(version);
    }

    /**
     * Record what the hold now being placed at {@code path} is a hold on. Called where the hold begins - the gate's
     * screen-time QUARANTINE, the retroactive enforcement sweeps, the proxy's withheld body - and therefore while the
     * owning format is still installed to answer.
     *
     * <p>A null {@code coordinate} or {@code version} records the path with no coordinate: the honest answer for a
     * checksum, generated metadata or a raw upload, and one a reader must be able to tell from an absent row. Both
     * faces are written for a versioned subject, the path face alone for a coordinate-less one. Idempotent - a
     * re-screen or a converging sweep pass overwrites with the same content - and every write goes through the
     * bounded compare-and-set idiom the sibling markers use, so a concurrent writer is a retry rather than a lost row.
     */
    public static void record(ArtifactStore store, String path, String ecosystem, String coordinate, String version)
            throws IOException {
        StringBuilder body = new StringBuilder();
        // The path is the row's own subject and its key is only a digest of it, so it is carried in the body: the
        // version face reads it back to answer "which paths of this version are held", and an operator reading a raw
        // row sees what it is about rather than a hash.
        body.append("path=").append(path).append('\n');
        if (ecosystem != null) {
            body.append("ecosystem=").append(ecosystem).append('\n');
        }
        if (coordinate != null && version != null) {
            body.append("coordinate=").append(coordinate).append('\n');
            body.append("version=").append(version).append('\n');
        }
        writeVersioned(store, pathKey(path), body.toString().getBytes(StandardCharsets.UTF_8));
        if (ecosystem != null && coordinate != null && version != null) {
            writeVersioned(store, versionKey(ecosystem, coordinate, version, path),
                    path.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Record the subject of a hold being placed at {@code path}, resolved through the installed formats - the form a
     *  hold site that has only a request path uses (the proxy's withheld body), where {@link #record(ArtifactStore,
     *  String, String, String, String)}'s callers already hold the triple. Same contract: the resolution happens now,
     *  while the format that claims the path is still installed, and a path that resolves to no coordinate is recorded
     *  as carrying none rather than not recorded at all. */
    public static void record(ArtifactStore store, String path) throws IOException {
        Optional<build.jenesis.repository.store.ArtifactDescriptor> described =
                new StoreRepositoryInventory(store).describe(path);
        record(store, path,
                described.map(build.jenesis.repository.store.ArtifactDescriptor::ecosystem).orElse(null),
                described.map(build.jenesis.repository.store.ArtifactDescriptor::coordinate).orElse(null),
                described.map(build.jenesis.repository.store.ArtifactDescriptor::version).orElse(null));
    }

    /**
     * Record the subject and then link the {@code /quarantine} review pointer - the one primitive a retroactive
     * enforcement sweep places a hold through, so no sweep can grow a hold pointer without the record that explains
     * it. The order is the crash-safe one and it is the opposite of the intuitive one: the record goes <em>first</em>,
     * because a record with no hold costs a reader one wasted point read (its consumers all re-probe the live pointer
     * or the durable {@code holds/} records) while a hold with no record is the fail-open gap this class exists to
     * close.
     */
    public static void hold(Publication publication, ArtifactStore store, String path, String hash,
                            String ecosystem, String coordinate, String version) throws IOException {
        record(store, path, ecosystem, coordinate, version);
        publication.link("/quarantine" + path, hash);
    }

    /** What the hold at {@code path} is a hold on, or empty when no row was ever written for it - a hold placed before
     *  this space existed, or a path that was never held. Empty is <em>not</em> "no coordinate": a recorded path with
     *  no coordinate returns a present {@link Subject} whose {@link Subject#versioned()} is {@code false}. */
    public static Optional<Subject> read(ArtifactStore store, String path) throws IOException {
        return store.readVersioned(pathKey(path)).map(versioned -> parse(versioned.content()));
    }

    /**
     * The request paths a hold was ever placed at for {@code (ecosystem, coordinate, version)} - the version face,
     * read by a caller that has the coordinate and needs to find the review pointers no installed format can name.
     *
     * <p>A path here is not a promise that the path is still held: a reclaim lost to a crash leaves a row behind, so
     * a caller that acts on the answer probes the live {@code publish/quarantine<path>} pointer itself. What it IS is
     * the only route from a coordinate to its held paths that survives the owning format's removal.
     *
     * <p>A truncated enumeration is refused rather than returned short, exactly as {@link HoldMarkers#kinds} refuses:
     * a dropped path reads as "this version has no held path", which is the disclosure this space exists to prevent.
     */
    public static SortedSet<String> paths(ArtifactStore store, String ecosystem, String coordinate, String version)
            throws IOException {
        String root = versionRoot(ecosystem, coordinate, version);
        List<String> rows = new ArrayList<>();
        Traversal.Result result = PATHS_OF_VERSION.scan(store, root, rows::add);
        SortedSet<String> paths = new TreeSet<>();
        for (String row : rows) {
            // The key is a digest, so the path comes out of the body - one small point read per held path of this one
            // version. A row whose body is unreadable is skipped rather than guessed at: it can only cost a probe.
            store.readVersioned(root + "/" + row)
                    .map(versioned -> new String(versioned.content(), StandardCharsets.UTF_8).trim())
                    .filter(path -> !path.isEmpty())
                    .ifPresent(paths::add);
        }
        if (result.truncated()) {
            throw new IOException("the subjects/ paths of " + ecosystem + " " + coordinate + ":" + version
                    + " did not enumerate whole (" + result.delivered() + " delivered); refusing to answer, because a "
                    + "short path list reads as 'no held path belongs to this version'");
        }
        return paths;
    }

    /** Drop the record for one held path - called beside the {@code /quarantine} pointer clear that ends the hold,
     *  never on a schedule and never because a module is absent. Delete-if-present on both faces, so a retry after a
     *  crash converges; the version face is reached through the row's own recorded coordinate, so a format that has
     *  since been uninstalled cannot strand it. */
    public static void forget(ArtifactStore store, String path) throws IOException {
        Optional<Subject> subject = read(store, path);
        if (subject.isPresent() && subject.get().versioned()) {
            deleteIfPresent(store, versionKey(subject.get().ecosystem(), subject.get().coordinate(),
                    subject.get().version(), path));
        }
        deleteIfPresent(store, pathKey(path));
    }

    /** Drop every record of a coordinate version - the leg a version's eviction runs, so no row outlives the artifact
     *  it describes. Both faces go: the version face is the enumeration, and each path it names takes its path-face
     *  row with it. */
    public static void forget(ArtifactStore store, String ecosystem, String coordinate, String version)
            throws IOException {
        for (String path : paths(store, ecosystem, coordinate, version)) {
            deleteIfPresent(store, versionKey(ecosystem, coordinate, version, path));
            deleteIfPresent(store, pathKey(path));
        }
    }

    private static Subject parse(byte[] content) {
        String path = null;
        String ecosystem = null;
        String coordinate = null;
        String version = null;
        for (String line : new String(content, StandardCharsets.UTF_8).split("\n")) {
            int split = line.indexOf('=');
            if (split < 0) {
                continue;
            }
            String value = line.substring(split + 1);
            switch (line.substring(0, split)) {
                case "path" -> path = value;
                case "ecosystem" -> ecosystem = value;
                case "coordinate" -> coordinate = value;
                case "version" -> version = value;
                default -> {
                    // an unknown line from a newer writer: ignored, never fatal to a fail-closed read
                }
            }
        }
        return new Subject(path, ecosystem, coordinate, version);
    }

    /** The hex SHA-256 of a request path - the fixed-width name both faces key a held path by. */
    private static String digest(String path) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(path.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is a required JDK digest", impossible);
        }
    }

    private static void deleteIfPresent(ArtifactStore store, String key) throws IOException {
        if (store.readVersioned(key).isPresent()) {
            store.delete(key);
        }
    }

    /** Compare-and-set with the bounded-retry idiom every load-bearing small pointer in this module uses, so a
     *  concurrent writer's conflict is a retry rather than a silently lost row. */
    private static void writeVersioned(ArtifactStore store, String key, byte[] value) throws IOException {
        Retries.update(store, key, _ -> value);
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8);
    }

}
