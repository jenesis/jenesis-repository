package build.jenesis.repository.test;

import build.jenesis.repository.format.BlobReferences;
import build.jenesis.repository.format.FormatMarks;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.gc.GarbageCollectorProvider;
import build.jenesis.repository.server.spi.ImportEdgeProvider;
import build.jenesis.repository.store.TenantsProvider;
import build.jenesis.repository.walk.WalkProvider;
import module java.base;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The core's half of the <b>capability-signal census</b>: every {@code installed()} discovery static this
 * repository declares is inventoried with the production surface that reads it, and a javadoc that <em>claims</em> a
 * reader must have one (D-164). The downstream edition carries the mirror of this test over its own tree; between them
 * the two cover the readers neither can see alone.
 *
 * <p><b>The defect this closes.</b> Three core signals - {@code WalkProvider.installed()},
 * {@code GarbageCollectorProvider.installed()} and {@code TenantsProvider.installed()} - each carried the sentence
 * "the capability signal a console ... gates on", and <b>no production code in either repository called any of them</b>.
 * The surface the sentence named exists and works; it just reads something else. The reclamation module's
 * {@code CapabilityContributor} reports {@code walk} and {@code gc} from {@code resolve(config).isPresent()}, and the
 * tenant kernel follows the resolved directory.
 *
 * <p><b>And the two are not the same question</b>, which is what makes this more than a tidy-up. {@code installed()}
 * filters on {@link build.jenesis.repository.store.Features#enabled}; {@code resolve} filters on
 * {@link build.jenesis.repository.store.Features#active} <em>and</em> applies the optional-unique selection policy. So
 * a provider that self-disabled on a missing required key counts as installed while {@code resolve} reports it absent,
 * and two enabled providers count as installed while {@code resolve} refuses them as ambiguous. A console that had
 * followed the javadoc would have opened a surface for a capability that is not there, and opened it immediately
 * before {@code resolve} threw. The dead leg and the wrong answer were the same line.
 *
 * <h2>Three legs, deliberately separate (design gate 1)</h2>
 * <ol>
 *   <li>The <b>static</b> leg parses {@code source/**} as text: every {@code static ... installed(} declaration and
 *       every {@code <Type>.installed(} call site. It is the only leg that can see a <em>missing</em> caller, which is
 *       what the defect is.</li>
 *   <li>The <b>runtime</b> leg reads the compiled types this module links against, so a rename the text scan would
 *       silently stop matching fails here instead of passing there (D-149).</li>
 *   <li>The <b>claim</b> leg reads the javadoc for the house phrase, "capability signal", and holds it to a reader
 *       that exists. It is deliberately not the primary leg: a guard whose only trigger is prose can be silenced by
 *       editing prose.</li>
 * </ol>
 *
 * <h2>What this scan cannot see (design gate 4)</h2>
 * <ul>
 *   <li><b>The downstream edition's readers.</b> This scan walks this repository's {@code source/} and nothing else,
 *       so a core signal read only from {@code ../jenesis-downstream} reads here as unread. That is why the three
 *       signals above are censused {@link Reader#NONE} on the strength of <em>both</em> censuses having been run, and
 *       why the downstream mirror carries them as {@code FREE_CORE_ONLY} rather than asserting they are dead.</li>
 *   <li><b>Reflective and container-mediated readers.</b> A caller that reaches a signal through {@code ServiceLoader},
 *       a Spring bean or a template expression rather than by naming the type is invisible to a text scan.</li>
 * </ul>
 */
class CapabilitySignalPrincipleTest {

    /** Who reads a capability signal in production - the distinction is not "is it called" but "is it called by
     *  something a deployment runs". */
    private enum Reader {
        /** A production caller in {@code source/**} that is not a test-support module. */
        PRODUCTION,
        /** Only a contract suite or completeness census reads it. Legitimate; but it is not a console. */
        TESTKIT,
        /** Nothing in production reads it, here or in the downstream edition whose own census was run against the
         *  same signal. The claim, if the javadoc makes one, is false. */
        NONE
    }

    /** One inventoried signal: its classification, the simple names of the production readers, and why. */
    private record Signal(Reader reader, Set<String> readers, String why) {

        private static Signal read(String why, String... readers) {
            return new Signal(Reader.PRODUCTION, Set.of(readers), why);
        }

        private static Signal unread(Reader reader, String why) {
            return new Signal(reader, Set.of(), why);
        }
    }

    /** The hand-maintained census. Adding an {@code installed()} static means classifying it here in the same change;
     *  removing one that is gone fails just as loudly. A {@link Reader#NONE} entry is a burn-down item, not an
     *  exemption: it fails the moment a production caller appears without being named. */
    private static final Map<String, Signal> READERS = new TreeMap<>(Map.of(
            "BlobReferences", Signal.read(
                    "the mark-sweep collector asks every installed layout which blobs a descriptor references, so a "
                            + "referenced blob is never swept",
                    "MarkSweepGarbageCollectorProvider"),
            "FormatMarks", Signal.read(
                    "the console's browse panel renders each installed format's marks",
                    "BrowsePanel"),
            "RepositoryFormat", Signal.read(
                    "the format marks, the index importer and the format fixture enumerate the installed formats",
                    "FormatMarks", "IndexSourceProvider", "FormatFixture"),
            "ImportEdgeProvider", Signal.read(
                    "the server's auto-configuration gates the import edge on it",
                    "RepositoryAutoConfiguration"),
            "GarbageCollectorProvider", Signal.unread(Reader.NONE,
                    "D-164. The reclamation module's CapabilityContributor reports the gc flag from "
                            + "resolve(config).isPresent(), which is the config-aware question; this static answers "
                            + "Features.enabled and would report a collector that self-disabled. Read by test/gc"),
            "TenantsProvider", Signal.unread(Reader.NONE,
                    "D-164. The tenant kernel resolves a directory and the console's tenancy chrome follows the "
                            + "resolved directory; this static answers Features.enabled and would offer tenant "
                            + "management over a directory that never grows. Read by test/store/spi"),
            "WalkProvider", Signal.unread(Reader.NONE,
                    "D-164. The reclamation module's CapabilityContributor reports the walk flag from "
                            + "resolve(config).isPresent() and every walk-riding pass resolves the walk itself; this "
                            + "static answers Features.enabled. Read by test/walk and the walk-consumer census")));

    /** Test-support modules that live under {@code source/} because a JUnit test module is a leaf and cannot be
     *  shared, so their readers are censused {@link Reader#TESTKIT} rather than {@link Reader#PRODUCTION}. */
    private static final Set<String> TEST_SUPPORT_ROOTS = Set.of(
            "contract" + File.separator + "testkit",
            "format" + File.separator + "testkit",
            "store" + File.separator + "testkit",
            "walk" + File.separator + "testkit");

    /** Every censused signal as the compiled type this module links against - the runtime leg's subject. */
    private static final Map<String, Class<?>> LINKED = Map.of(
            "BlobReferences", BlobReferences.class,
            "FormatMarks", FormatMarks.class,
            "RepositoryFormat", RepositoryFormat.class,
            "ImportEdgeProvider", ImportEdgeProvider.class,
            "GarbageCollectorProvider", GarbageCollectorProvider.class,
            "TenantsProvider", TenantsProvider.class,
            "WalkProvider", WalkProvider.class);

    /** The house phrase for the promise this test polices. */
    private static final String CLAIM = "capability signal";

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\n]*");
    private static final Pattern DECLARED = Pattern.compile(
            "(?m)^[ \\t]*(?:public\\s+)?static\\s+[\\w<>,.\\[\\]?\\s]+?\\s+installed\\s*\\(");

    private static final Map<String, String> SOURCES = read();

    // --- the static leg -------------------------------------------------------------------------------------------

    @Test
    void every_installed_static_the_tree_declares_is_censused() {
        assertThat(declared())
                .as("the census is the source tree's own list of capability signals, type for type - a signal added "
                        + "with no classification is the D-164 dead leg arriving unnoticed, and an entry for a signal "
                        + "that is gone is a census outliving what it censuses")
                .containsExactlyInAnyOrderElementsOf(READERS.keySet());
    }

    @Test
    void every_production_reader_the_census_names_really_reads_it() {
        Map<String, Set<String>> callers = callers();
        List<String> wrong = new ArrayList<>();
        READERS.forEach((signal, entry) -> {
            if (entry.reader() != Reader.PRODUCTION) {
                return;
            }
            Set<String> found = callers.getOrDefault(signal, Set.of());
            entry.readers().stream().filter(reader -> !found.contains(reader)).forEach(reader ->
                    wrong.add(signal + ".installed() is censused as read by " + reader + ", which does not call it"));
            found.stream().filter(reader -> !entry.readers().contains(reader)).forEach(reader ->
                    wrong.add(signal + ".installed() is really read by " + reader + ", which the census omits"));
        });
        assertThat(wrong)
                .as("a censused reader is a claim about the call graph, confronted with it in both directions")
                .isEmpty();
    }

    @Test
    void no_signal_censused_as_unread_has_a_production_reader() {
        Map<String, Set<String>> callers = callers();
        List<String> wired = new ArrayList<>();
        READERS.forEach((signal, entry) -> {
            if (entry.reader() == Reader.PRODUCTION) {
                return;
            }
            Set<String> found = callers.getOrDefault(signal, Set.of());
            if (!found.isEmpty()) {
                wired.add(signal + ".installed() is censused as " + entry.reader() + " but is read by "
                        + new TreeSet<>(found) + " - reclassify it as PRODUCTION and name the reader");
            }
        });
        assertThat(wired)
                .as("the burn-down direction: wiring a dead signal is a fix, and it must move the entry rather than "
                        + "leave a stale 'nothing reads this' beside a live reader")
                .isEmpty();
    }

    // --- the claim leg --------------------------------------------------------------------------------------------

    @Test
    void no_signal_claims_a_capability_reader_it_does_not_have() {
        List<String> unbacked = new ArrayList<>();
        READERS.forEach((signal, entry) -> {
            if (entry.reader() == Reader.PRODUCTION) {
                return;
            }
            String javadoc = installedJavadoc(signal);
            if (javadoc != null && javadoc.contains(CLAIM)) {
                unbacked.add(signal + ".installed() calls itself a \"" + CLAIM + "\" a console or API gates on, and "
                        + "the census says " + entry.reader() + ": " + entry.why());
            }
        });
        assertThat(unbacked)
                .as("D-164 exactly: the sentence and the reader stand or fall together. A signal nothing reads may "
                        + "still exist, but its javadoc must say what actually reads it")
                .isEmpty();
    }

    @Test
    void the_claim_scanner_would_still_catch_the_defect_it_was_built_for() {
        // After the census is fixed no live entry carries an unbacked claim, so the leg above would pass on a scanner
        // that had quietly stopped reading javadoc at all. Confront it with the pre-fix and post-fix text.
        assertThat("""
                /** Whether an enabled walk implementation is installed - the capability signal a console or a
                 *  walk-riding maintenance surface gates on; without one nothing ever enumerates. */
                """.contains(CLAIM))
                .as("the phrase this leg triggers on is the one D-164's own defect was written in")
                .isTrue();
        assertThat("""
                /** Whether a walk implementation is installed and not switched off. No production surface reads this;
                 *  resolve(config).isPresent() is what the reclamation contributor reports. */
                """.contains(CLAIM))
                .as("and the corrected text is what clears it, so the leg distinguishes the two")
                .isFalse();
    }

    // --- the runtime leg ------------------------------------------------------------------------------------------

    @Test
    void every_censused_signal_really_declares_installed_on_the_linked_type() {
        List<String> missing = new ArrayList<>();
        LINKED.forEach((signal, type) -> {
            if (Stream.of(type.getDeclaredMethods()).noneMatch(method -> method.getName().equals("installed"))) {
                missing.add(signal + " is censused but " + type.getName() + " declares no installed() - a rename the "
                        + "text scan above would simply stop matching, which reads exactly like a pass");
            }
        });
        assertThat(missing).isEmpty();
        assertThat(LINKED.keySet())
                .as("the runtime leg covers the whole census, so it cannot go partly blind unnoticed")
                .containsExactlyInAnyOrderElementsOf(READERS.keySet());
    }

    @Test
    void the_scan_is_alive() {
        assertThat(SOURCES).as("the source tree really was read").hasSizeGreaterThan(300);
        assertThat(declared()).as("and installed() declarations really were found in it").hasSizeGreaterThan(5);
        assertThat(callers()).as("and call sites really were found for the signals that have them").isNotEmpty();
        assertThat(installedJavadoc("WalkProvider"))
                .as("and the javadoc the claim leg reads really is being extracted")
                .isNotNull();
        assertThat(READERS.values().stream().map(Signal::reader).collect(Collectors.toSet()))
                .as("both live classifications are exercised; TESTKIT is declared for the downstream mirror's shape "
                        + "and for the first free signal that earns it")
                .containsExactlyInAnyOrder(Reader.PRODUCTION, Reader.NONE);
    }

    // --- the scan -------------------------------------------------------------------------------------------------

    private static Set<String> declared() {
        Set<String> signals = new TreeSet<>();
        SOURCES.forEach((path, body) -> {
            if (DECLARED.matcher(stripped(body)).find()) {
                signals.add(owner(path));
            }
        });
        return signals;
    }

    private static Map<String, Set<String>> callers() {
        Map<String, Set<String>> callers = new TreeMap<>();
        for (String signal : READERS.keySet()) {
            Pattern call = Pattern.compile("\\b" + Pattern.quote(signal) + "\\s*\\.\\s*installed\\s*\\(");
            SOURCES.forEach((path, body) -> {
                String caller = owner(path);
                if (caller.equals(signal) || testSupport(path)) {
                    return;
                }
                if (call.matcher(stripped(body)).find()) {
                    callers.computeIfAbsent(signal, _ -> new TreeSet<>()).add(caller);
                }
            });
        }
        return callers;
    }

    private static boolean testSupport(String path) {
        return TEST_SUPPORT_ROOTS.stream().anyMatch(root -> path.startsWith(root + File.separator));
    }

    /** The javadoc block immediately preceding a type's {@code installed(} declaration. */
    private static String installedJavadoc(String signal) {
        for (Map.Entry<String, String> source : SOURCES.entrySet()) {
            if (!owner(source.getKey()).equals(signal)) {
                continue;
            }
            Matcher declaration = DECLARED.matcher(source.getValue());
            if (!declaration.find()) {
                continue;
            }
            int close = source.getValue().lastIndexOf("*/", declaration.start());
            int open = close < 0 ? -1 : source.getValue().lastIndexOf("/**", close);
            return open < 0 ? "" : source.getValue().substring(open, close);
        }
        return null;
    }

    private static Map<String, String> read() {
        Map<String, String> sources = new TreeMap<>();
        Path root = sourceRoot();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(path -> path.toString().endsWith(".java"))::iterator) {
                sources.put(root.relativize(file).toString(), Files.readString(file));
            }
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the source tree must be readable for this structural check", unreadable);
        }
        return Map.copyOf(sources);
    }

    /** The module sources directory ({@code <repo>/source}), located by walking up from the working directory. Fails
     *  loudly if the tree is not reachable, so the check never passes vacuously. */
    private static Path sourceRoot() {
        Path start = Path.of("").toAbsolutePath();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("source")) && Files.isDirectory(dir.resolve("build/jenesis"))) {
                return dir.resolve("source");
            }
        }
        throw new AssertionError("could not locate the core repo root (an ancestor holding source/ beside "
                + "build/jenesis) from working directory " + start + " - this structural check must run from the "
                + "repository tree so it can read the module sources");
    }

    private static String owner(String relative) {
        String name = Path.of(relative).getFileName().toString();
        return name.substring(0, name.length() - ".java".length());
    }

    private static String stripped(String source) {
        return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(source).replaceAll("")).replaceAll("");
    }
}
