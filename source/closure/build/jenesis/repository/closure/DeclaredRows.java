package build.jenesis.repository.closure;

import module java.base;
import build.jenesis.repository.dependents.spi.DependentsQuery;
import build.jenesis.repository.inventory.DependencySection;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Checksums;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A version's declared dependents, kept by the closure pass beside its resolved ones ({@link ReliedOn}): one row per
 * declaration a published version's manifest makes, in the repository holding that version, at
 * {@code closure/declared/<ecosystem>/<package>/<sha-256 of the declaring version>}, each segment URL-encoded, the row
 * naming the declaring version as JSON. A declaration is a requirement, and which version
 * satisfies it is a client's later decision, so a row is keyed by the package alone and never counts as reaching a
 * version.
 *
 * <p>The rows are an accelerator, never the authority: a row is believed only while its version is still published and
 * its recorded dependencies still name the package ({@link #page}), and the requirement a page answers is the one
 * they record now. A row nothing confirms is passed over by the reader and removed by the {@link #reconcile} the
 * closure pass's full pass runs. The pass writes a version's rows blind when it resolves its closure and where missing
 * on a full pass, so a version published before the rows existed is indexed by the next full pass; an eviction takes a
 * version's rows with it ({@link ClosureEvictionObserver}).
 */
final class DeclaredRows {

    /** The root of the rows. */
    static final String ROOT = "closure/declared";

    /** The rows one page or one reconcile step lists. */
    private static final int PAGE = 500;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private DeclaredRows() {
    }

    /** Write a row for each of {@code declared}, what {@code release} declares in {@code store} - every one where
     *  {@code blind}, else only those absent. */
    static void index(ArtifactStore store, StoreRepositoryInventory.Coordinate release,
                      List<DependencySection.Declared> declared, boolean blind) throws IOException {
        Set<String> seen = new HashSet<>();
        for (DependencySection.Declared declaration : declared) {
            if (declaration.coordinate() == null || declaration.coordinate().isBlank()
                    || !seen.add(declaration.coordinate())) {
                continue;
            }
            String key = key(release.ecosystem(), declaration.coordinate(), release.coordinate(), release.version());
            if (blind || !store.exists(key)) {
                ObjectNode row = JSON.createObjectNode();
                row.put("coordinate", release.coordinate());
                row.put("version", release.version());
                store.write(key, new ByteArrayInputStream(JSON.writeValueAsBytes(row)));
            }
        }
    }

    /** Delete the rows of {@code declared}, what {@code version} of {@code coordinate} declares in {@code store}. */
    static void forget(ArtifactStore store, String ecosystem, String coordinate, String version,
                       List<DependencySection.Declared> declared) throws IOException {
        for (DependencySection.Declared declaration : declared) {
            if (declaration.coordinate() == null || declaration.coordinate().isBlank()) {
                continue;
            }
            String key = key(ecosystem, declaration.coordinate(), coordinate, version);
            if (store.exists(key)) {
                store.delete(key);
            }
        }
    }

    /**
     * One page of the versions of {@code store} declaring a dependency on {@code dependency} of {@code ecosystem},
     * after {@code cursor}: at most {@code limit} rows ({@value #PAGE} at most), each confirmed against the declaring
     * version's recorded dependencies, which also give the requirement it states.
     */
    static DependentsQuery.DeclarationPage page(ArtifactStore store, String ecosystem, String dependency,
                                                String cursor, int limit) throws IOException {
        int bound = Math.max(0, Math.min(limit, PAGE));
        if (bound == 0) {
            return new DependentsQuery.DeclarationPage(List.of(), null);
        }
        String level = level(ecosystem, dependency);
        List<String> names = new ArrayList<>();
        store.page(level, cursor == null ? "" : cursor, bound, names::add);
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        List<DependentsQuery.Declaration> declarations = new ArrayList<>();
        for (String name : names) {
            Optional<Row> row = row(store, level + "/" + name);
            if (row.isPresent()) {
                requirement(inventory, ecosystem, row.get(), dependency).ifPresent(requirement -> declarations.add(
                        new DependentsQuery.Declaration(ecosystem, row.get().coordinate(), row.get().version(),
                                requirement)));
            }
        }
        return new DependentsQuery.DeclarationPage(declarations, names.size() < bound ? null : names.getLast());
    }

    /** Remove every row of {@code store} nothing confirms - its version gone, or its dependencies no longer naming
     *  the package; how many went. A page of rows and a document a row a step. */
    static long reconcile(ArtifactStore store) throws IOException {
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        long removed = 0;
        String resume = "";
        while (resume != null) {
            List<ArtifactStore.Listed> rows = new ArrayList<>();
            ArtifactStore.Scan scan = store.scan(ROOT, resume, PAGE, rows::add);
            resume = scan.cursor().orElse(null);
            for (ArtifactStore.Listed listed : rows) {
                String[] segments = listed.key().substring(ROOT.length() + 1).split("/");
                Optional<Row> row = segments.length == 3 ? row(store, listed.key()) : Optional.empty();
                if (row.isPresent() && requirement(inventory, decode(segments[0]), row.get(), decode(segments[1]))
                        .isPresent()) {
                    continue;
                }
                store.delete(listed.key());
                removed++;
            }
        }
        return removed;
    }

    /** The declaring version a row names. */
    private record Row(String coordinate, String version) {
    }

    /** The requirement {@code row}'s version states for {@code dependency}, while it is published and its recorded
     *  dependencies name it; empty otherwise. */
    private static Optional<String> requirement(StoreRepositoryInventory inventory, String ecosystem, Row row,
                                                String dependency) throws IOException {
        if (inventory.publishedAt(ecosystem, row.coordinate(), row.version()).isEmpty()) {
            return Optional.empty();
        }
        for (DependencySection.Declared declared : inventory.dependencies(ecosystem, row.coordinate(), row.version())
                .orElse(List.of())) {
            if (dependency.equals(declared.coordinate())) {
                return Optional.of(declared.requirement() == null ? "" : declared.requirement());
            }
        }
        return Optional.empty();
    }

    private static Optional<Row> row(ArtifactStore store, String key) throws IOException {
        if (!store.exists(key)) {
            return Optional.empty();
        }
        try (InputStream in = store.open(key)) {
            JsonNode node = JSON.readTree(in);
            String coordinate = node.path("coordinate").asString("");
            String version = node.path("version").asString("");
            return coordinate.isEmpty() || version.isEmpty() ? Optional.empty()
                    : Optional.of(new Row(coordinate, version));
        } catch (RuntimeException torn) {
            return Optional.empty();       // a torn row is passed over, and the reconcile removes it
        }
    }

    private static String key(String ecosystem, String dependency, String coordinate, String version) {
        return level(ecosystem, dependency) + "/" + Checksums.sha256(coordinate + "\n" + version);
    }

    private static String level(String ecosystem, String dependency) {
        return ROOT + "/" + encode(ecosystem) + "/" + encode(dependency);
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8);
    }

    private static String decode(String segment) {
        return URLDecoder.decode(segment, StandardCharsets.UTF_8);
    }
}
