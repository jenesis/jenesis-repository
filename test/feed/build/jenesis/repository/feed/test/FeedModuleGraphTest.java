package build.jenesis.repository.feed.test;

import module java.base;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The structural ratchet behind the feed client's one architectural promise: it is a <strong>support</strong> module
 * carrying weight ({@code java.net.http}, the store) that a {@code java.base}-light SPI contract module must never
 * be coupled to. A contract interface declares a seam; the implementation behind the seam is what fetches. So:
 *
 * <ul>
 * <li>no module anywhere may {@code requires transitive} the feed module - it can therefore never be leaked onward
 *     by whoever adopts it;</li>
 * <li>no SPI contract module (a {@code source/**\/spi} module, plus {@code server-spi}) may reach it, directly or
 *     through its own requires closure;</li>
 * <li>the feed module's own requires stay exactly the three it declares, so it cannot quietly grow an edge onto a
 *     format, an implementation or the server.</li>
 * </ul>
 *
 * <p>A deterministic source scan of every {@code module-info.java} in the core, in the mould of the other
 * structural principle tests - it reads declarations rather than a compiled graph, so a module a test forgot to
 * require is still seen.
 */
class FeedModuleGraphTest {

    private static final String FEED = "build.jenesis.repository.feed";

    /** Exactly what the support module may weigh - a support module, never an SPI, and never a bundle. */
    private static final Set<String> ALLOWED = Set.of("build.jenesis.repository.store", "java.net.http", "org.slf4j");

    @Test
    void the_feed_module_is_never_leaked_transitively() throws IOException {
        Map<String, Module> modules = modules();

        List<String> leaking = new ArrayList<>();
        modules.values().forEach(module -> {
            if (module.transitive().contains(FEED)) {
                leaking.add(module.name());
            }
        });

        assertThat(leaking)
                .describedAs("a module that re-exports the feed client couples every one of its consumers to a"
                        + " transport; the feed client is required by the implementation that fetches, never"
                        + " re-exported by a seam")
                .isEmpty();
    }

    @Test
    void no_spi_contract_module_reaches_the_feed_client() throws IOException {
        Map<String, Module> modules = modules();

        Map<String, Set<String>> offending = new TreeMap<>();
        modules.values().stream().filter(Module::contract).forEach(contract -> {
            Set<String> closure = closure(contract.name(), modules);
            if (closure.contains(FEED)) {
                offending.put(contract.name(), closure);
            }
        });

        assertThat(offending)
                .describedAs("an SPI contract module stays java.base-light (AGENTS &sect;2): it must not reach the"
                        + " feed client's transport weight, directly or through its requires closure")
                .isEmpty();
        assertThat(modules.values().stream().filter(Module::contract).map(Module::name))
                .describedAs("the scan must actually find the contract modules, or it passes vacuously")
                .contains("build.jenesis.repository.store", "build.jenesis.repository.walk");
    }

    @Test
    void the_feed_module_carries_only_its_declared_weight() throws IOException {
        Module feed = modules().get(FEED);

        assertThat(feed).describedAs("the feed module must be on the scanned source tree").isNotNull();
        assertThat(feed.requires())
                .describedAs("a new requires on the support module is a deliberate decision, not a drive-by")
                .containsExactlyInAnyOrderElementsOf(ALLOWED);
        assertThat(feed.transitive()).describedAs("the support module re-exports nothing").isEmpty();
        assertThat(feed.contract())
                .describedAs("the feed client declares no service, so it is not an SPI contract module")
                .isFalse();
    }

    /** One module's declared graph position: what it requires, what it re-exports, and whether it is a contract. */
    private record Module(String name, Set<String> requires, Set<String> transitive, boolean contract) {
    }

    /** Every module the core declares, keyed by module name. */
    private static Map<String, Module> modules() throws IOException {
        Path sourceRoot = sourceRoot();
        Map<String, Module> modules = new TreeMap<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(path -> path.getFileName().toString().equals("module-info.java")).toList()) {
                String declaration = stripped(Files.readString(file, StandardCharsets.UTF_8));
                Matcher name = Pattern.compile("\\bmodule\\s+([\\w.]+)\\s*\\{").matcher(declaration);
                if (!name.find()) {
                    continue;
                }
                Set<String> requires = new TreeSet<>();
                Set<String> transitive = new TreeSet<>();
                Matcher clause = Pattern.compile("requires\\s+(static\\s+)?(transitive\\s+)?([\\w.]+)\\s*;")
                        .matcher(declaration);
                while (clause.find()) {
                    requires.add(clause.group(3));
                    if (clause.group(2) != null) {
                        transitive.add(clause.group(3));
                    }
                }
                Path directory = file.getParent();
                boolean contract = directory != null && directory.getFileName().toString().endsWith("spi");
                modules.put(name.group(1), new Module(name.group(1), requires, transitive, contract));
            }
        }
        assertThat(modules).describedAs("the source scan found no module at all under " + sourceRoot).isNotEmpty();
        return modules;
    }

    /** Everything a module reaches through its own requires, following the declared graph. */
    private static Set<String> closure(String start, Map<String, Module> modules) {
        Set<String> reached = new TreeSet<>();
        Deque<String> pending = new ArrayDeque<>(List.of(start));
        while (!pending.isEmpty()) {
            Module module = modules.get(pending.pop());
            if (module == null) {
                continue;
            }
            for (String required : module.requires()) {
                if (reached.add(required)) {
                    pending.push(required);
                }
            }
        }
        return reached;
    }

    /** The declaration with its javadoc and line comments removed, so a name in prose is never read as a clause. */
    private static String stripped(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ");
    }

    /** The module sources directory ({@code <repo>/source}), located by walking up from the working directory. */
    private static Path sourceRoot() {
        Path start = Path.of("").toAbsolutePath();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("build/jenesis"))) {
                // Standalone the tree is source/ beside build/jenesis; inside an enclosing project it is
                // free/source/ beside the enclosing one. Nested first: only the outer build has both.
                Path nested = dir.resolve("free").resolve("source");
                if (Files.isDirectory(nested)) {
                    return nested;
                }
                if (Files.isDirectory(dir.resolve("source"))) {
                    return dir.resolve("source");
                }
            }
        }
        throw new AssertionError("could not locate the core repo root (an ancestor holding source/ or free/source beside "
                + "build/jenesis) from working directory " + start + " - this structural check must run from the "
                + "repository tree so it can read the module sources");
    }
}
