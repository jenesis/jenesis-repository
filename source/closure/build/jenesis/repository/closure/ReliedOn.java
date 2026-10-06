package build.jenesis.repository.closure;

import module java.base;
import build.jenesis.repository.inventory.PublishedSection;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.metadata.MetadataDocument;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.store.ArtifactStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The reverse of the closures: which published versions rely on a version a repository holds. One row per published
 * version whose closure reaches it, kept in the repository holding what is relied on - where the cached copy is, so a
 * copy's page asks its own store - at
 * {@code closure/relied-on/<ecosystem>/<coordinate>/<version>/<sha-256 of the dependent>}, each segment URL-encoded, the
 * row naming the dependent's repository, coordinate and version as JSON. A row is written for each component of a
 * closure, in the repository of the walk that holds it, and for each version the closure stopped at because a
 * repository of the walk holds it for review - the held copy a build relying on it would fail on.
 *
 * <p>The index is an accelerator, never the authority: a row is believed only when the dependent's document still
 * records a closure reaching the version through this repository ({@link #page}), so a row whose dependent is gone, or
 * whose closure no longer reaches it, is passed over by the reader and removed by the {@link #reconcile} the closure
 * pass runs over the repository holding it.
 *
 * <p><b>Writers.</b> A row has one writer, the closure pass of the dependent's repository, which writes the rows of a
 * closure before it records the closure - so a crash leaves rows the reader passes over and the next pass writes again,
 * never a closure whose rows are missing - and makes sure of them on every full pass, which is how a closure resolved
 * before the index is indexed. The pass writes into the repositories its walk reaches although it leases only its own:
 * nothing else writes a row of its dependent, and the reconcile in the holding repository deletes a row only once the
 * dependent's document says it is no longer relied on, never while the dependent waits for its closure.
 *
 * <p><b>Following a change.</b> Where what a row names changes - a finding, a hold - the closure pass of the holding
 * repository marks the row's dependent {@linkplain #stale stale} in the dependent's own repository, under
 * {@value #STALE}, and that repository's pass re-derives it. A marker is a request, removed before it is acted on.
 *
 * <p><b>By coordinate, across the tenant.</b> A package a carried bill names in another ecosystem than its version's
 * ({@link ClosureSection.Foreign}) is held by no repository of the walk, so its rows are kept once for the tenant, in
 * the same layout under the tenant's {@value #SPACE} space, each naming its dependent's ecosystem. A copy of it in any
 * repository of the tenant is then relied on by those dependents: every repository's pass follows a change to one of
 * its versions through them as through its own rows, and a copy's page lists them after its own. The tenant's
 * {@linkplain #reconcile reconcile} runs in the pass's tenant hook.
 */
public final class ReliedOn {

    /** The root of the rows. */
    public static final String ROOT = "closure/relied-on";

    /** The tenant's space holding the rows of the packages closures name by coordinate, in another ecosystem: under
     *  {@code <tenant>/.closure/}, beside the repositories. Also the name such a row's holder goes by, which no
     *  repository can take. */
    public static final String SPACE = ".closure";

    /** The cursor prefix of a page continuing from a repository's own rows into the tenant's. */
    private static final String ACROSS = "tenant-";

    /** The most rows one page answers. */
    public static final int MAX_PAGE = 200;

    /** The rows one reconcile step lists. */
    private static final int SWEEP_PAGE = 1_000;

    /** The dependents' documents one reconcile remembers, the most recently asked kept. */
    private static final int REMEMBERED = 1_000;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ReliedOn() {
    }

    /** A published version relying on what a repository holds, as a row names it: its repository, coordinate and
     *  version, and its ecosystem where that is not the one the row is kept under - empty otherwise. */
    public record Row(String repository, String coordinate, String version, String ecosystem) {

        public Row {
            ecosystem = ecosystem == null ? "" : ecosystem;
        }

        /** A dependent in the ecosystem of what it relies on. */
        public Row(String repository, String coordinate, String version) {
            this(repository, coordinate, version, "");
        }

        /** The dependent's ecosystem, {@code subject} being that of what the row says it relies on. */
        public String ecosystemOr(String subject) {
            return ecosystem.isEmpty() ? subject : ecosystem;
        }
    }

    /**
     * A published version relying on the version asked about: its repository, ecosystem, coordinate and version, how
     * its closure reaches it - from the dependency it names itself down to the version, both included - whether the
     * closure stopped there, a version held for review being a cut rather than a component, and whether it names the
     * version by coordinate alone, in another ecosystem than its own, and so relies on whichever copy its build
     * installed.
     */
    public record Dependent(String repository, String ecosystem, String coordinate, String version,
                            List<ClosureSection.Hop> path, boolean cut, boolean byCoordinate) {

        public Dependent {
            path = List.copyOf(path);
        }
    }

    /** One page of dependents: those the rows of the page named and their closures confirm, how many rows were read,
     *  and the cursor resuming after the last of them, empty once the rows are exhausted. */
    public record Page(List<Dependent> dependents, int examined, Optional<String> next) {

        public Page {
            dependents = List.copyOf(dependents);
        }
    }

    /** The root of the markers asking a repository's closure pass to re-derive a release: one per release at
     *  {@code closure/stale/<sha-256 of the release>}, naming its ecosystem, coordinate and version as JSON. */
    public static final String STALE = "closure/stale";

    /** What a pass does with one row, or with one stale release. */
    @FunctionalInterface
    interface Visitor<T> {

        void accept(T value) throws IOException;
    }

    /** Hand every row naming a dependent of {@code coordinate} at {@code version} of {@code ecosystem}, held by
     *  {@code holder}, to {@code visitor}, a page at a time; a row that does not parse is passed over. Unconfirmed:
     *  what the visitor does with a row it has to tolerate one whose dependent no longer relies on the version. */
    static void dependents(ArtifactStore holder, String ecosystem, String coordinate, String version,
                           Visitor<Row> visitor) throws IOException {
        String level = level(ecosystem, coordinate, version);
        String after = "";
        while (after != null) {
            List<String> names = new ArrayList<>();
            holder.page(level, after, SWEEP_PAGE, names::add);
            for (String name : names) {
                Optional<Row> row = row(holder, level + "/" + name);
                if (row.isPresent()) {
                    visitor.accept(row.get());
                }
            }
            after = names.size() < SWEEP_PAGE ? null : names.getLast();
        }
    }

    /** Ask the closure pass of {@code store}, the repository of {@code dependent}, to re-derive it: what its closure
     *  reaches changed. A release asked twice before the pass is one marker. */
    static void stale(ArtifactStore store, String ecosystem, Row dependent) throws IOException {
        ecosystem = dependent.ecosystemOr(ecosystem);
        store.write(STALE + "/" + HexFormat.of().formatHex(sha256((ecosystem + "\n" + dependent.coordinate() + "\n"
                        + dependent.version()).getBytes(StandardCharsets.UTF_8))),
                new ByteArrayInputStream(JSON.writeValueAsBytes(JSON.createObjectNode().put("ecosystem", ecosystem)
                        .put("coordinate", dependent.coordinate()).put("version", dependent.version()))));
    }

    /** Remove up to {@code limit} of {@code store}'s stale markers, handing each release to {@code visitor} once its
     *  marker is gone - so a change while it runs leaves a marker of its own for the next drain, and one lost to a
     *  failure is the full pass's to re-derive. A marker that does not parse is removed and passed over. */
    static void drainStale(ArtifactStore store, int limit,
                           Visitor<StoreRepositoryInventory.Coordinate> visitor) throws IOException {
        List<String> names = new ArrayList<>();
        store.page(STALE, "", limit, names::add);
        for (String name : names) {
            String key = STALE + "/" + name;
            Optional<ArtifactStore.Versioned> marker = store.readVersioned(key);
            store.delete(key);
            if (marker.isEmpty()) {
                continue;
            }
            Optional<StoreRepositoryInventory.Coordinate> release = release(marker.get().content());
            if (release.isPresent()) {
                visitor.accept(release.get());
            }
        }
    }

    /** The release a stale marker names, or empty for one that does not parse. */
    private static Optional<StoreRepositoryInventory.Coordinate> release(byte[] marker) {
        try {
            JsonNode node = JSON.readTree(marker);
            String ecosystem = node.path("ecosystem").asString("");
            String coordinate = node.path("coordinate").asString("");
            String version = node.path("version").asString("");
            return ecosystem.isEmpty() || coordinate.isEmpty() || version.isEmpty() ? Optional.empty()
                    : Optional.of(new StoreRepositoryInventory.Coordinate(ecosystem, coordinate, version));
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /** The level holding the rows of {@code coordinate} at {@code version} of {@code ecosystem}. */
    static String level(String ecosystem, String coordinate, String version) {
        return ROOT + "/" + encode(ecosystem) + "/" + encode(coordinate) + "/" + encode(version);
    }

    /** The row recording that {@code dependent} relies on {@code coordinate} at {@code version}. */
    static String key(String ecosystem, String coordinate, String version, Row dependent) {
        return level(ecosystem, coordinate, version) + "/" + HexFormat.of().formatHex(sha256((dependent.repository()
                + "\n" + dependent.coordinate() + "\n" + dependent.version()).getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Write the rows of {@code closure}, the closure of {@code dependent} in the walk's first repository, into the
     * repositories of {@code walk} holding what it reaches - every component, and every version {@code exposure} names
     * as held where the closure stopped - and the rows of its {@linkplain ClosureSection.Foreign packages of other
     * ecosystems} into {@code tenant}'s {@value #SPACE} space, none where there is no tenant store. With {@code blind}
     * every row is written; otherwise only a row not yet present, which is what a pass making sure of an indexed
     * closure pays: one probe per row. Answers how many were written.
     */
    public static int index(ClosureWalk walk, String ecosystem, Row dependent, ClosureSection.Closure closure,
                            ExposureSection.Exposure exposure, boolean blind, Optional<ArtifactStore> tenant)
            throws IOException {
        Map<String, ArtifactStore> holders = new HashMap<>();
        List<ClosureWalk.Member> members = walk.members();
        for (int i = 1; i < members.size(); i++) {
            holders.putIfAbsent(members.get(i).repository(), members.get(i).store());
        }
        byte[] row = JSON.writeValueAsBytes(JSON.createObjectNode().put("repository", dependent.repository())
                .put("coordinate", dependent.coordinate()).put("version", dependent.version()));
        Set<String> components = new HashSet<>();
        int written = 0;
        for (ClosureSection.Component component : closure.components()) {
            components.add(component.coordinate() + "@" + component.version() + "@" + component.repository());
            ArtifactStore holder = component.elsewhere() ? holders.get(component.repository())
                    : members.getFirst().store();
            written += put(holder, key(ecosystem, component.coordinate(), component.version(), dependent), row, blind);
        }
        for (ExposureSection.Reached reached : exposure.reached()) {
            if (!reached.held() || reached.version().isBlank() || !reached.ecosystem().isEmpty() || components.contains(
                    reached.coordinate() + "@" + reached.version() + "@" + reached.repository())) {
                continue;
            }
            ArtifactStore holder = reached.repository().isEmpty() ? members.getFirst().store()
                    : holders.get(reached.repository());
            written += put(holder, key(ecosystem, reached.coordinate(), reached.version(), dependent), row, blind);
        }
        if (!closure.foreign().isEmpty() && tenant.isPresent()) {
            ArtifactStore space = tenant.get().scope(SPACE);
            byte[] across = JSON.writeValueAsBytes(JSON.createObjectNode().put("repository", dependent.repository())
                    .put("coordinate", dependent.coordinate()).put("version", dependent.version())
                    .put("ecosystem", ecosystem));
            for (ClosureSection.Foreign foreign : closure.foreign()) {
                written += put(space, key(foreign.ecosystem(), foreign.coordinate(), foreign.version(), dependent),
                        across, blind);
            }
        }
        return written;
    }

    private static int put(ArtifactStore holder, String key, byte[] row, boolean blind) throws IOException {
        if (holder == null || !blind && holder.exists(key)) {
            return 0;
        }
        holder.write(key, new ByteArrayInputStream(row));
        return 1;
    }

    /**
     * One page of the published versions relying on {@code coordinate} at {@code version} of {@code ecosystem}, held by
     * {@code holder}, the repository named {@code holderName}: up to {@code limit} rows (at most {@link #MAX_PAGE})
     * after {@code after}, each believed only where the dependent's document, read from {@code repositories} by its
     * repository's name, records a closure reaching the version through this repository. A dependent in a repository
     * {@code readable} refuses is left out unread. A row and a document read per row, so a page costs the same whatever
     * the repositories hold.
     */
    public static Page page(ArtifactStore holder, String holderName,
                            Function<String, Optional<ArtifactStore>> repositories, Predicate<String> readable,
                            String ecosystem, String coordinate, String version, String after, int limit)
            throws IOException {
        int bound = Math.max(1, Math.min(limit, MAX_PAGE));
        List<String> names = new ArrayList<>();
        String level = level(ecosystem, coordinate, version);
        holder.page(level, after == null ? "" : after, bound, names::add);
        List<Dependent> dependents = new ArrayList<>();
        for (String name : names) {
            Optional<Row> row = row(holder, level + "/" + name);
            if (row.isEmpty() || !readable.test(row.get().repository())) {
                continue;
            }
            Optional<ArtifactStore> store = repositories.apply(row.get().repository());
            if (store.isEmpty()) {
                continue;
            }
            Optional<MetadataDocument> document = MetadataProvider.installed().over(store.get())
                    .read(row.get().ecosystemOr(ecosystem), row.get().coordinate(), row.get().version());
            document.flatMap(read -> ClosureSection.closure(read.section(ClosureSection.TAG)))
                    .flatMap(closure -> reached(closure, document.get(), row.get(), holderName, ecosystem, coordinate,
                            version))
                    .ifPresent(dependents::add);
        }
        return new Page(dependents, names.size(),
                names.size() < bound ? Optional.empty() : Optional.of(names.getLast()));
    }

    /**
     * One page of the published versions relying on a version {@code holder} holds, its own rows first and then the
     * tenant's - the versions naming it by coordinate in another ecosystem - as {@link #page} answers each. A page
     * ending the repository's own rows fills the rest of its bound from the tenant's, so the two together read no more
     * rows than one page does; the cursor of a page within the tenant's rows is marked so.
     */
    public static Page pageAcross(ArtifactStore holder, String holderName, Optional<ArtifactStore> tenant,
                                  Function<String, Optional<ArtifactStore>> repositories, Predicate<String> readable,
                                  String ecosystem, String coordinate, String version, String after, int limit)
            throws IOException {
        Optional<ArtifactStore> space = tenant.map(store -> store.scope(SPACE));
        String resume = after == null ? "" : after;
        int bound = Math.max(1, Math.min(limit, MAX_PAGE));
        if (space.isEmpty()) {
            return page(holder, holderName, repositories, readable, ecosystem, coordinate, version,
                    resume.startsWith(ACROSS) ? "" : resume, bound);
        }
        List<Dependent> dependents = new ArrayList<>();
        int examined = 0;
        if (!resume.startsWith(ACROSS)) {
            Page own = page(holder, holderName, repositories, readable, ecosystem, coordinate, version, resume,
                    bound);
            if (own.next().isPresent()) {
                return own;
            }
            dependents.addAll(own.dependents());
            examined = own.examined();
            resume = ACROSS;
        }
        Page across = page(space.get(), SPACE, repositories, readable, ecosystem, coordinate, version,
                resume.substring(ACROSS.length()), Math.max(1, bound - examined));
        dependents.addAll(across.dependents());
        return new Page(dependents, examined + across.examined(), across.next().map(next -> ACROSS + next));
    }

    /** How {@code closure}, of the dependent {@code row} names, reaches {@code coordinate} at {@code version} of
     *  {@code ecosystem} held by {@code holderName}: as a component held there, or as a cut its exposure names as held
     *  there - or, held by the tenant's {@value #SPACE} space, as a package its bill names in that ecosystem. */
    private static Optional<Dependent> reached(ClosureSection.Closure closure, MetadataDocument document, Row row,
                                               String holderName, String ecosystem, String coordinate,
                                               String version) {
        String own = row.ecosystemOr(ecosystem);
        if (SPACE.equals(holderName)) {
            List<ClosureSection.Hop> path = ClosureSection.foreignPath(closure, ecosystem, coordinate, version);
            return path.isEmpty() ? Optional.empty() : Optional.of(new Dependent(row.repository(), own,
                    row.coordinate(), row.version(), path, false, true));
        }
        String through = holderName.equals(row.repository()) ? "" : holderName;
        for (ClosureSection.Component component : closure.components()) {
            if (component.coordinate().equals(coordinate) && component.version().equals(version)
                    && component.repository().equals(through)) {
                return Optional.of(new Dependent(row.repository(), own, row.coordinate(), row.version(),
                        ClosureSection.path(closure, coordinate, version), false, false));
            }
        }
        Optional<ExposureSection.Exposure> exposure = ExposureSection.exposure(document.section(ExposureSection.TAG));
        if (exposure.isPresent()) {
            for (ExposureSection.Reached reached : exposure.get().reached()) {
                if (reached.held() && reached.ecosystem().isEmpty() && reached.coordinate().equals(coordinate)
                        && reached.version().equals(version) && reached.repository().equals(through)) {
                    return Optional.of(new Dependent(row.repository(), own, row.coordinate(), row.version(),
                            List.of(new ClosureSection.Hop(coordinate, version)), true, false));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Remove every row of {@code holder}, the repository named {@code holderName}, whose dependent is no longer
     * relied on: a dependent that is not published, or whose recorded closure no longer reaches the version through
     * this repository. A dependent still waiting for its closure keeps its rows, since its pass writes them before it
     * records the closure. The documents read last are remembered ({@value #REMEMBERED} of them), so a dependent
     * relying on many of the repository's versions is read about once. Answers how many rows were removed.
     */
    static long reconcile(ArtifactStore holder, String holderName,
                          Function<String, Optional<ArtifactStore>> repositories) throws IOException {
        Map<Row, Optional<MetadataDocument>> documents = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Row, Optional<MetadataDocument>> eldest) {
                return size() > REMEMBERED;
            }
        };
        long removed = 0;
        String resume = "";
        while (resume != null) {
            List<ArtifactStore.Listed> rows = new ArrayList<>();
            ArtifactStore.Scan scan = holder.scan(ROOT, resume, SWEEP_PAGE, rows::add);
            resume = scan.cursor().orElse(null);
            for (ArtifactStore.Listed listed : rows) {
                Optional<Subject> subject = subject(listed.key());
                Optional<Row> row = row(holder, listed.key());
                if (subject.isEmpty() || row.isEmpty()) {
                    continue;
                }
                Optional<ArtifactStore> store = repositories.apply(row.get().repository());
                if (store.isEmpty()) {
                    continue;   // a repository this pass cannot reach is "cannot tell", never "gone"
                }
                Optional<MetadataDocument> document = documents.get(row.get());
                if (document == null) {
                    document = MetadataProvider.installed().over(store.get()).read(
                            row.get().ecosystemOr(subject.get().ecosystem()), row.get().coordinate(),
                            row.get().version());
                    documents.put(row.get(), document);
                }
                if (relied(document, row.get(), holderName, subject.get())) {
                    continue;
                }
                holder.delete(listed.key());
                removed++;
            }
        }
        return removed;
    }

    /** Whether the dependent's {@code document} still relies on {@code subject} through {@code holderName}: published
     *  and waiting for its closure, or with a closure reaching it. */
    private static boolean relied(Optional<MetadataDocument> document, Row row, String holderName, Subject subject) {
        if (document.isEmpty() || document.get().section(PublishedSection.TAG).isEmpty()) {
            return false;
        }
        Optional<ClosureSection.Closure> closure = ClosureSection.closure(document.get().section(ClosureSection.TAG));
        return closure.isEmpty() || reached(closure.get(), document.get(), row, holderName, subject.ecosystem(),
                subject.coordinate(), subject.version()).isPresent();
    }

    /** The version a row's key is a row of. */
    private record Subject(String ecosystem, String coordinate, String version) {
    }

    private static Optional<Subject> subject(String key) {
        String[] segments = key.substring(Math.min(key.length(), ROOT.length() + 1)).split("/", -1);
        if (!key.startsWith(ROOT + "/") || segments.length != 4) {
            return Optional.empty();
        }
        return Optional.of(new Subject(decode(segments[0]), decode(segments[1]), decode(segments[2])));
    }

    /** The dependent a stored row names, or empty for one that is gone or does not parse. */
    private static Optional<Row> row(ArtifactStore holder, String key) throws IOException {
        Optional<ArtifactStore.Versioned> stored = holder.readVersioned(key);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonNode node = JSON.readTree(stored.get().content());
            String repository = node.path("repository").asString("");
            String coordinate = node.path("coordinate").asString("");
            String version = node.path("version").asString("");
            return repository.isEmpty() || coordinate.isEmpty() || version.isEmpty() ? Optional.empty()
                    : Optional.of(new Row(repository, coordinate, version, node.path("ecosystem").asString("")));
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8);
    }

    private static String decode(String segment) {
        return URLDecoder.decode(segment, StandardCharsets.UTF_8);
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", impossible);
        }
    }
}
