package build.jenesis.repository.test;

import module java.base;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guard on every source-scanning guard's own re-run trigger (D-154's free half).
 *
 * <h2>The failure this exists to prevent</h2>
 * Several suites in this repository answer their question by reading {@code source/**} as text - the structural
 * ratchets here, the contract censuses that parse every {@code provides ... with ...} clause, the graph tests that
 * check no runtime module requires a test kit. <b>Nothing in the build knows that.</b> A test module's inputs are its
 * own sources plus the compiled output of the modules it <b>directly</b> {@code requires}: {@code BuildStep.shouldRun}
 * is "any argument changed", and {@code ModularProject} makes one build-step argument per {@code requires} clause, so
 * a module reached only <em>transitively</em> is not an argument at all. There is no always-run or
 * needs-all-sources declaration to fall back on - the only inputs a step has are its arguments.
 *
 * <p>So breadth of direct {@code requires} is the entire mechanism, and it decays silently: somebody adds a source
 * module, or tidies an "unused" requires, and a census stops re-running over the tree it polices. <b>A ratchet that
 * stopped firing is indistinguishable, in a green build, from one that passes.</b>
 *
 * <h2>What was found when this first ran</h2>
 * Nine test modules here scan the repository root, and every one of them was outside its own subject matter:
 * {@code contract/testkit} covered <b>1</b> of the repository's 38 source modules, {@code feed} 3,
 * {@code publication} 4, {@code store/contract} 7, {@code ui} 7, {@code walkconsumer} 8, {@code format/contract} 9,
 * {@code importer/contract} 12 and {@code server} 23. The downstream edition's guard found the same shape across
 * seventeen modules there; this is the parity half, and neither repository was checking it.
 *
 * <h2>What is asserted</h2>
 * <ol>
 *   <li><b>The population.</b> The <em>discovered</em> set of source-scanning test modules is exactly
 *       {@link #SCANNERS}. Discovery rather than a list is the point: a table somebody has to remember to update
 *       carries the same defect one level up.</li>
 *   <li><b>Shrink-only coverage.</b> Each row records how many source modules that scanner's descriptor names today.
 *       The measured figure may not fall below it - that is the defect - and may not sit above it either, because an
 *       unrecorded win is where the next narrowing hides. Counting what is <em>covered</em> rather than what is
 *       missing keeps a row saying one thing: adding a source module leaves every row alone.</li>
 *   <li><b>Liveness.</b> The walks found what they claim to read, the requires parser meets a negative control, and
 *       the scanner detector fires on a synthetic locator while staying silent on code that merely mentions the
 *       word - a detector that matched everything would make legs 1 and 2 vacuous.</li>
 * </ol>
 */
class ScanTriggerPrincipleTest {

    /**
     * How many of this repository's source modules each source-scanning test module's descriptor actually names, with
     * what that module scans. <b>Shrink-only</b>: the figure is a floor, and a win must be recorded rather than
     * banked silently.
     */
    private static final Map<String, Trigger> SCANNERS = Map.ofEntries(
            Map.entry("test/server", new Trigger(23,
                    "the structural ratchets - unsafe API, unbounded listing, streaming, config, immutability, the "
                            + "SPI contract inventory and the legibility scan - all of which read source/** as text "
                            + "and none of which is about the modules it happens to require")),
            Map.entry("test/importer/contract", new Trigger(12,
                    "the importer census parses every source `provides RepositoryImporter`; its finding is 'an "
                            + "importer exists with no fixture', so an importer in an untriggered module is exactly "
                            + "what it cannot see")),
            Map.entry("test/format/contract", new Trigger(9,
                    "the format census parses every source `provides RepositoryFormat`")),
            Map.entry("test/walkconsumer", new Trigger(8,
                    "the walk-consumer census parses every source `provides WalkConsumer` and compares it with the "
                            + "runtime ServiceLoader graph")),
            Map.entry("test/store/contract", new Trigger(7,
                    "the store census parses every source `provides ArtifactStoreProvider`")),
            Map.entry("test/ui", new Trigger(7,
                    "the console panel census reads the module tree for its screens")),
            Map.entry("test/publication", new Trigger(4,
                    "PublicationHookCensusTest's static leg asserts the SHIPPED hook inventory is still empty - a "
                            + "claim about every source module, made from a descriptor that names four")),
            Map.entry("test/feed", new Trigger(3,
                    "the feed kit reads the source tree for its transport declarations")),
            Map.entry("test/contract/testkit", new Trigger(1,
                    "the census helper's own tests drive it over the real source tree, and re-run on one module of "
                            + "the 38 it is pointed at")));

    /** One scanner's recorded trigger breadth: how many source modules its descriptor names, and why. {@code covered}
     *  is a floor that may only be raised, and only in the same change that earns it. */
    private record Trigger(int covered, String reason) {
    }

    /** A descriptor that names one module and misses another - the negative control for the requires parser. */
    private static final String CONTROL_DESCRIPTOR = """
            /** @jenesis.pin something 1.0 */
            open module build.jenesis.repository.example.test {
                requires build.jenesis.repository.present;
                requires transitive build.jenesis.repository.also.present;
                // requires build.jenesis.repository.commented.out;
                uses build.jenesis.repository.store.PublicationObserver;
            }
            """;

    @Test
    void every_test_module_that_scans_the_source_tree_declares_its_trigger() throws IOException {
        assertThat(scanners())
                .as("""
                        the set of test modules whose own sources locate the repository root and read source/ must be \
                        exactly the set that records its trigger breadth. A module that started scanning has a census \
                        nothing re-runs; a module that stopped scanning is an entry that has become a lie. The set is \
                        DISCOVERED, not declared, because a table somebody has to remember to update would carry the \
                        same defect one level up.""")
                .containsExactlyInAnyOrderElementsOf(SCANNERS.keySet());
        SCANNERS.values().forEach(trigger -> assertThat(trigger.reason())
                .as("every row says what it scans").isNotBlank());
    }

    @Test
    void no_source_scanning_guard_narrows_its_trigger() throws IOException {
        Set<String> declared = sourceModules();
        List<String> narrowed = new ArrayList<>();
        List<String> widened = new ArrayList<>();
        for (String module : scanners()) {
            Trigger trigger = SCANNERS.get(module);
            if (trigger == null) {
                continue;                   // a scanner with no row at all; the population leg above names it
            }
            Set<String> required = requires(Files.readString(root().resolve(module).resolve("module-info.java")));
            int covered = (int) declared.stream().filter(required::contains).count();
            String line = "%s: %d of %d, recorded %d".formatted(module, covered, declared.size(), trigger.covered());
            if (covered < trigger.covered()) {
                narrowed.add(line);
            } else if (covered > trigger.covered()) {
                widened.add(line);
            }
        }

        assertThat(narrowed)
                .as("""
                        these source-scanning test modules now re-run on FEWER source modules than they used to. The \
                        module still scans the whole tree, still passes, and no longer re-runs when most of the tree \
                        changes - and a guard that stopped firing is indistinguishable in a green build from one \
                        that passes. A requires clause in one of these descriptors is a TRIGGER, not a compile \
                        dependency; almost none of them are imported. Put it back.%n%s""",
                        String.join(System.lineSeparator(), narrowed))
                .isEmpty();
        assertThat(widened)
                .as("""
                        these scanners now cover more than the table records, which is the good direction - but the \
                        win has to be written down in the same change that earns it, or the floor stays low and the \
                        trigger can shrink back to it with nothing failing.%n%s""",
                        String.join(System.lineSeparator(), widened))
                .isEmpty();
    }

    @Test
    void the_scans_are_alive_and_the_detector_is_not_matching_everything() throws IOException {
        Set<String> declared = sourceModules();
        Set<String> scanners = scanners();
        Set<String> testModules = testModules();

        assertThat(declared).as("the source/ walk found the repository's module descriptors")
                .hasSizeGreaterThan(20).contains("build.jenesis.repository.store", "build.jenesis.repository.server");
        assertThat(testModules).as("the test/ walk found the repository's test modules")
                .hasSizeGreaterThan(10).contains("test/server", "test/store");
        assertThat(scanners)
                .as("a detector matching nothing, or everything, would make the two legs above vacuous")
                .isNotEmpty().hasSizeLessThan(testModules.size()).contains("test/server");

        assertThat(requires(CONTROL_DESCRIPTOR))
                .as("the parser reads plain and transitive requires and ignores a commented-out one, so a module "
                        + "left out of a descriptor is genuinely detected rather than matched by accident")
                .containsExactlyInAnyOrder("build.jenesis.repository.present",
                        "build.jenesis.repository.also.present");

        assertThat(scansTheSourceTree("Path modules = directory.resolve(\"source\");")).isTrue();
        assertThat(scansTheSourceTree("Path modules = Path.of(\"source\");")).isTrue();
        assertThat(scansTheSourceTree("String upstream = \"http://source.local/simple\"; // mentions source"))
                .as("mentioning the word is not scanning the tree").isFalse();
    }

    // --- the scans ------------------------------------------------------------------------------------------------

    /** Every test module whose own sources locate the repository's {@code source/} tree, discovered by reading them. */
    private static Set<String> scanners() throws IOException {
        Set<String> scanners = new TreeSet<>();
        for (String module : testModules()) {
            try (Stream<Path> files = Files.walk(root().resolve(module))) {
                for (Path file : (Iterable<Path>) files
                        .filter(path -> path.getFileName().toString().endsWith(".java"))::iterator) {
                    if (scansTheSourceTree(Files.readString(file))) {
                        scanners.add(module);
                        break;
                    }
                }
            }
        }
        return scanners;
    }

    /** Whether a source file locates the repository's own {@code source/} tree. Keyed on the locator rather than on
     *  the word, so a suite that merely talks about upstream "source" URLs is not swept in. */
    private static boolean scansTheSourceTree(String code) {
        return SOURCE_ROOT.matcher(code.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "")).find();
    }

    private static final Pattern SOURCE_ROOT = Pattern.compile("(?:resolve|Path\\.of)\\(\"source\"\\)");

    /** Every test module, keyed by its path from the root ({@code test/store/contract}). */
    private static Set<String> testModules() throws IOException {
        Set<String> modules = new TreeSet<>();
        try (Stream<Path> files = Files.walk(root().resolve("test"))) {
            for (Path file : (Iterable<Path>) files
                    .filter(path -> path.getFileName().toString().equals("module-info.java"))::iterator) {
                modules.add(root().relativize(file.getParent()).toString());
            }
        }
        if (modules.isEmpty()) {
            throw new AssertionError("no test module descriptors found under test/ - the walk that decides which "
                    + "modules scan the tree found nothing, so every leg built on it would pass vacuously");
        }
        return modules;
    }

    /** Every module declared by a {@code module-info.java} under {@code source/}. */
    private static Set<String> sourceModules() throws IOException {
        Set<String> names = new TreeSet<>();
        try (Stream<Path> files = Files.walk(root().resolve("source"))) {
            for (Path file : (Iterable<Path>) files
                    .filter(path -> path.getFileName().toString().equals("module-info.java"))::iterator) {
                Matcher matcher = Pattern.compile("^\\s*(?:open\\s+)?module\\s+([\\w.]+)", Pattern.MULTILINE)
                        .matcher(Files.readString(file).replaceAll("(?s)/\\*.*?\\*/", ""));
                if (!matcher.find()) {
                    throw new AssertionError("no module declaration parsed from " + file);
                }
                names.add(matcher.group(1));
            }
        }
        return names;
    }

    /** The {@code requires} module names a descriptor declares, with comments removed first so a clause someone
     *  commented out cannot pass for a live one. */
    private static Set<String> requires(String descriptor) {
        String code = descriptor.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
        Set<String> names = new TreeSet<>();
        Matcher matcher = Pattern.compile("requires\\s+(?:transitive\\s+|static\\s+)*([\\w.]+)\\s*;").matcher(code);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static Path root() {
        Path start = Path.of("").toAbsolutePath();
        for (Path directory = start; directory != null; directory = directory.getParent()) {
            if (Files.isDirectory(directory.resolve("source")) && Files.isDirectory(directory.resolve("test"))
                    && Files.isDirectory(directory.resolve("build/jenesis"))) {
                return directory;
            }
        }
        throw new AssertionError("could not locate the core repo root (an ancestor holding source/ and test/ "
                + "beside build/jenesis) from working directory " + start);
    }
}
