package build.jenesis.repository.closure;

import module java.base;
import build.jenesis.repository.store.Checksums;
import build.jenesis.repository.closure.spi.ClosureSection;
import build.jenesis.repository.closure.spi.Reliance;
import build.jenesis.repository.closure.spi.ClosureWalk;
import build.jenesis.repository.closure.spi.ExposureSection;
import build.jenesis.repository.inventory.Mailbox;
import build.jenesis.repository.inventory.PublishedSection;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.metadata.MetadataDocument;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Epoch;
import build.jenesis.repository.store.Names;
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
 * {@link #STALE}, and that repository's pass re-derives it. A marker is a request, removed before it is acted on.
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

    /** The {@link Epoch} a holder's rows move: bumped once by every pass that wrote or removed rows the holder keeps -
     *  a repository, or the tenant's {@value #SPACE} space - so a view ordering versions by what relies on them, which
     *  folds it into its stamp, rebuilds on its next pass. */
    public static final String EPOCH = "closure/reliance";

    /** The cursor prefix of a page continuing from a repository's own rows into the tenant's. */
    private static final String ACROSS = "tenant-";


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

    /** The mailbox asking a repository's closure pass to re-derive a release: what its closure reaches changed. */
    public static final Mailbox STALE = new Mailbox("closure/stale");

    /** What a pass does with one row. */
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
        Names names = Names.over(holder, level, SWEEP_PAGE);
        for (String name = names.next(); name != null; name = names.next()) {
            Optional<Row> row = row(holder, level + "/" + name);
            if (row.isPresent()) {
                visitor.accept(row.get());
            }
        }
    }

    /** Ask the closure pass of {@code store}, the repository of {@code dependent}, to re-derive it: what its closure
     *  reaches changed. A release asked twice before the pass is one marker. */
    static void stale(ArtifactStore store, String ecosystem, Row dependent) throws IOException {
        STALE.post(store, dependent.ecosystemOr(ecosystem), dependent.coordinate(), dependent.version());
    }

    /** The level holding the rows of {@code coordinate} at {@code version} of {@code ecosystem}. */
    static String level(String ecosystem, String coordinate, String version) {
        return ROOT + "/" + encode(ecosystem) + "/" + encode(coordinate) + "/" + encode(version);
    }

    /** The row recording that {@code dependent} relies on {@code coordinate} at {@code version}. */
    static String key(String ecosystem, String coordinate, String version, Row dependent) {
        return level(ecosystem, coordinate, version) + "/" + Checksums.sha256(dependent.repository() + "\n"
                + dependent.coordinate() + "\n" + dependent.version());
    }

    /**
     * Write the rows of {@code closure}, the closure of {@code dependent} in the walk's first repository, into the
     * repositories of {@code walk} holding what it reaches - every component, and every version {@code exposure} names
     * as held where the closure stopped - and the rows of its {@linkplain ClosureSection.Foreign packages of other
     * ecosystems} into {@code tenant}'s {@value #SPACE} space, none where there is no tenant store. With {@code blind}
     * every row is written; otherwise only a row not yet present, which is what a pass making sure of an indexed
     * closure pays: one probe per row. Answers how many were written and the stores they were written into.
     */
    public static Changed index(ClosureWalk walk, String ecosystem, Row dependent, ClosureSection.Closure closure,
                                ExposureSection.Exposure exposure, boolean blind, Optional<ArtifactStore> tenant)
            throws IOException {
        int written = 0;
        Map<Object, ArtifactStore> holders = new LinkedHashMap<>();
        for (Placed placed : placed(walk, ecosystem, dependent, closure, exposure.reached(), tenant)) {
            if (blind || !placed.holder().exists(placed.key())) {
                placed.holder().write(placed.key(), new ByteArrayInputStream(placed.row()));
                holders.putIfAbsent(placed.holder().identity(), placed.holder());
                written++;
            }
        }
        return new Changed(written, List.copyOf(holders.values()));
    }

    /** What one closure's rows changed: how many rows, and the stores holding them, each once - whose
     *  {@link #epoch} a pass moves once at its end, however many closures changed rows in a store. */
    public record Changed(int rows, List<ArtifactStore> holders) {

        public Changed {
            holders = List.copyOf(holders);
        }
    }

    /**
     * Take back the rows {@code retired}, a closure {@code dependent} gave up, wrote - those it and the exposure
     * {@code retiredReached} placed, less those {@code current}, the dependent's closure now, and its exposure
     * {@code currentReached} place - from every holder the walk still reaches; a holder it no longer reaches keeps its
     * rows for its reconcile. Answers how many were removed and the stores they were removed from.
     */
    static Changed retire(ClosureWalk walk, String ecosystem, Row dependent, ClosureSection.Closure retired,
                          List<ExposureSection.Reached> retiredReached, Optional<ClosureSection.Closure> current,
                          List<ExposureSection.Reached> currentReached, Optional<ArtifactStore> tenant)
            throws IOException {
        Set<Map.Entry<Object, String>> kept = new HashSet<>();
        if (current.isPresent()) {
            for (Placed placed : placed(walk, ecosystem, dependent, current.get(), currentReached, tenant)) {
                kept.add(Map.entry(placed.holder().identity(), placed.key()));
            }
        }
        int removed = 0;
        Map<Object, ArtifactStore> holders = new LinkedHashMap<>();
        for (Placed placed : placed(walk, ecosystem, dependent, retired, retiredReached, tenant)) {
            if (!kept.contains(Map.entry(placed.holder().identity(), placed.key()))
                    && placed.holder().exists(placed.key())) {
                placed.holder().delete(placed.key());
                holders.putIfAbsent(placed.holder().identity(), placed.holder());
                removed++;
            }
        }
        return new Changed(removed, List.copyOf(holders.values()));
    }

    /** A row a closure places: the store holding it, its key, and what it says. */
    private record Placed(ArtifactStore holder, String key, byte[] row) {
    }

    /** The rows {@code closure} of {@code dependent}, and the exposure {@code reached} derived from it, place - each
     *  component in the repository of the walk holding it, each version the exposure names as held where the closure
     *  stopped, and each package of another ecosystem in the tenant's space - leaving out a holder the walk does not
     *  reach. */
    private static List<Placed> placed(ClosureWalk walk, String ecosystem, Row dependent,
                                       ClosureSection.Closure closure, List<ExposureSection.Reached> reached,
                                       Optional<ArtifactStore> tenant) {
        byte[] row = JSON.writeValueAsBytes(JSON.createObjectNode().put("repository", dependent.repository())
                .put("coordinate", dependent.coordinate()).put("version", dependent.version()));
        List<Placed> placed = new ArrayList<>();
        Set<String> components = new HashSet<>();
        for (ClosureSection.Component component : closure.components()) {
            components.add(component.coordinate() + "@" + component.version() + "@" + component.repository());
            walk.holder(component.repository()).ifPresent(holder -> placed.add(new Placed(holder.store(),
                    key(ecosystem, component.coordinate(), component.version(), dependent), row)));
        }
        for (ExposureSection.Reached held : reached) {
            if (!held.held() || held.version().isBlank() || !held.ecosystem().isEmpty() || components.contains(
                    held.coordinate() + "@" + held.version() + "@" + held.repository())) {
                continue;
            }
            walk.holder(held.repository()).ifPresent(holder -> placed.add(new Placed(holder.store(),
                    key(ecosystem, held.coordinate(), held.version(), dependent), row)));
        }
        if (!closure.foreign().isEmpty() && tenant.isPresent()) {
            ArtifactStore space = tenant.get().scope(SPACE);
            byte[] across = JSON.writeValueAsBytes(JSON.createObjectNode().put("repository", dependent.repository())
                    .put("coordinate", dependent.coordinate()).put("version", dependent.version())
                    .put("ecosystem", ecosystem));
            for (ClosureSection.Foreign foreign : closure.foreign()) {
                placed.add(new Placed(space, key(foreign.ecosystem(), foreign.coordinate(), foreign.version(),
                        dependent), across));
            }
        }
        return placed;
    }

    /** The epoch {@code holder}'s rows move - a repository's, or the tenant's {@value #SPACE} space's. */
    public static Epoch epoch(ArtifactStore holder) {
        return new Epoch(holder, EPOCH);
    }

    /**
     * One page of the published versions relying on {@code coordinate} at {@code version} of {@code ecosystem}, held by
     * {@code holder}, the repository named {@code holderName}: up to {@code limit} rows (at most
     * {@link Reliance#MAX_PAGE}) after {@code after}, each believed only where the dependent's document, read from {@code repositories} by its
     * repository's name, records a closure reaching the version through this repository. A dependent in a repository
     * {@code readable} refuses is left out unread. A row and a document read per row, so a page costs the same whatever
     * the repositories hold.
     */
    public static Reliance.Page page(ArtifactStore holder, String holderName,
                            Function<String, Optional<ArtifactStore>> repositories, Predicate<String> readable,
                            String ecosystem, String coordinate, String version, String after, int limit)
            throws IOException {
        int bound = Math.max(1, Math.min(limit, Reliance.MAX_PAGE));
        List<String> names = new ArrayList<>();
        String level = level(ecosystem, coordinate, version);
        holder.page(level, after == null ? "" : after, bound, names::add);
        List<Reliance.Dependent> dependents = new ArrayList<>();
        MetadataProvider metadata = MetadataProvider.installed();      // once per page, never per row
        for (String name : names) {
            Optional<Row> row = row(holder, level + "/" + name);
            if (row.isEmpty() || !readable.test(row.get().repository())) {
                continue;
            }
            Optional<ArtifactStore> store = repositories.apply(row.get().repository());
            if (store.isEmpty()) {
                continue;
            }
            Optional<MetadataDocument> document = metadata.over(store.get())
                    .read(row.get().ecosystemOr(ecosystem), row.get().coordinate(), row.get().version());
            document.flatMap(read -> ClosureSection.closure(read.section(ClosureSection.TAG)))
                    .flatMap(closure -> reached(closure, document.get(), row.get(), holderName, ecosystem, coordinate,
                            version))
                    .ifPresent(dependents::add);
        }
        return new Reliance.Page(dependents, names.size(),
                names.size() < bound ? Optional.empty() : Optional.of(names.getLast()));
    }

    /**
     * How many published versions are built against {@code version} of {@code coordinate} of {@code ecosystem} held by
     * {@code holder}, up to {@code cap}: the rows of its own index, then the tenant's naming it by coordinate, listed
     * no further than the cap. Unconfirmed - a row whose dependent no longer relies on it counts until the reconcile
     * removes it - which is what a ranking ordering vulnerable versions by their dependents wants, never what a page
     * listing them may show.
     */
    public static int usedBy(ArtifactStore holder, Optional<ArtifactStore> tenant, String ecosystem,
                             String coordinate, String version, int cap) throws IOException {
        String level = level(ecosystem, coordinate, version);
        int[] counted = {0};
        holder.page(level, "", cap, _ -> counted[0]++);
        if (counted[0] < cap && tenant.isPresent()) {
            tenant.get().scope(SPACE).page(level, "", cap - counted[0], _ -> counted[0]++);
        }
        return Math.min(counted[0], cap);
    }

    /**
     * One page of the published versions relying on a version {@code holder} holds, its own rows first and then the
     * tenant's - the versions naming it by coordinate in another ecosystem - as {@link #page} answers each. A page
     * ending the repository's own rows fills the rest of its bound from the tenant's, so the two together read no more
     * rows than one page does; the cursor of a page within the tenant's rows is marked so.
     */
    public static Reliance.Page pageAcross(ArtifactStore holder, String holderName, Optional<ArtifactStore> tenant,
                                  Function<String, Optional<ArtifactStore>> repositories, Predicate<String> readable,
                                  String ecosystem, String coordinate, String version, String after, int limit)
            throws IOException {
        Optional<ArtifactStore> space = tenant.map(store -> store.scope(SPACE));
        String resume = after == null ? "" : after;
        int bound = Math.max(1, Math.min(limit, Reliance.MAX_PAGE));
        if (space.isEmpty()) {
            return page(holder, holderName, repositories, readable, ecosystem, coordinate, version,
                    resume.startsWith(ACROSS) ? "" : resume, bound);
        }
        List<Reliance.Dependent> dependents = new ArrayList<>();
        int examined = 0;
        if (!resume.startsWith(ACROSS)) {
            Reliance.Page own = page(holder, holderName, repositories, readable, ecosystem, coordinate, version,
                    resume, bound);
            if (own.next().isPresent()) {
                return own;
            }
            dependents.addAll(own.dependents());
            examined = own.examined();
            resume = ACROSS;
        }
        Reliance.Page across = page(space.get(), SPACE, repositories, readable, ecosystem, coordinate, version,
                resume.substring(ACROSS.length()), Math.max(1, bound - examined));
        dependents.addAll(across.dependents());
        return new Reliance.Page(dependents, examined + across.examined(), across.next().map(next -> ACROSS + next));
    }

    /** How {@code closure}, of the dependent {@code row} names, reaches {@code coordinate} at {@code version} of
     *  {@code ecosystem} held by {@code holderName}: as a component held there, or as a cut its exposure names as held
     *  there - or, held by the tenant's {@value #SPACE} space, as a package its bill names in that ecosystem. */
    private static Optional<Reliance.Dependent> reached(ClosureSection.Closure closure, MetadataDocument document,
                                                        Row row, String holderName, String ecosystem,
                                                        String coordinate, String version) {
        String own = row.ecosystemOr(ecosystem);
        if (SPACE.equals(holderName)) {
            List<ClosureSection.Hop> path = ClosureSection.foreignPath(closure, ecosystem, coordinate, version);
            return path.isEmpty() ? Optional.empty() : Optional.of(new Reliance.Dependent(row.repository(), own,
                    row.coordinate(), row.version(), path, false, true));
        }
        String through = holderName.equals(row.repository()) ? "" : holderName;
        for (ClosureSection.Component component : closure.components()) {
            if (component.coordinate().equals(coordinate) && component.version().equals(version)
                    && component.repository().equals(through)) {
                return Optional.of(new Reliance.Dependent(row.repository(), own, row.coordinate(), row.version(),
                        ClosureSection.path(closure, coordinate, version), false, false));
            }
        }
        Optional<ExposureSection.Exposure> exposure = ExposureSection.exposure(document.section(ExposureSection.TAG));
        if (exposure.isPresent()) {
            for (ExposureSection.Reached reached : exposure.get().reached()) {
                if (reached.held() && reached.ecosystem().isEmpty() && reached.coordinate().equals(coordinate)
                        && reached.version().equals(version) && reached.repository().equals(through)) {
                    return Optional.of(new Reliance.Dependent(row.repository(), own, row.coordinate(), row.version(),
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
        MetadataProvider metadata = MetadataProvider.installed();      // once per sweep, never per row
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
                    document = metadata.over(store.get()).read(
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
        if (removed > 0) {
            epoch(holder).bump();
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
}
