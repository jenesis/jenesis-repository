package build.jenesis.repository.test;

import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The structural ratchet behind {@code ImportScreen} (SPI_HARDENING_PLAN.md D-152): <b>an import edge must not build a
 * source with an unscreened transport.</b>
 *
 * <p>The screen rides on the {@code ProxyFormat.Fetcher} a connector is handed, so no <em>connector</em> can forget
 * it. What is left forgettable is the other side: {@code ImportSourceProvider.create} is the seam a connector
 * implements, and calling it directly hands out a raw transport, while {@code ImportSourceProvider.open} is the same
 * construction with the fetcher screened. That is exactly the "unsafe API" shape - the convenient form is the wrong
 * one - so it is checked rather than left to convention. Screening per source was one omission from the defect;
 * screening per edge would be too.
 *
 * <p>It lives in {@code test/server} rather than beside the SPI for a reason the first attempt got wrong: a source
 * scan only re-runs when the build thinks its own module's inputs changed, so a scan in a leaf module that depends on
 * nothing but the SPI stays <em>cached</em> while a new edge is added in another module - it passes because it never
 * ran. This module requires the server and every importer connector, which is where an edge would appear.
 *
 * <p>The scan is deterministic and boots nothing: it reads {@code source/}, strips comments, and looks for an
 * <em>instance</em> call to {@code create(} - a lowercase receiver, so a type's static factory
 * ({@code URI.create(...)}) is not confused with a provider's - in any file that names
 * {@code ImportSourceProvider} at all. Every hit must be allowlisted with a reason. The allowlist is shrink-only: an
 * entry whose file no longer matches fails, so a fixed call site cannot leave a stale exemption behind.
 */
class ImportFetchScreenGuardTest {

    /** An instance call to {@code create(} - a lowercase receiver, so {@code URI.create(...)} and other static
     *  factories on capitalised types are not swept up. */
    private static final Pattern INSTANCE_CREATE = Pattern.compile("\\b[a-z][A-Za-z0-9_$]*\\.create\\(");

    /** Every file allowed to build a source through the raw seam, each with the reason it is not an edge. */
    private static final Map<String, String> ALLOWED = Map.of(
            "ImportSourceProvider.java",
            "the SPI's own open(): this IS the one call, and it is the call that screens the fetcher",
            "ImportContract.java",
            "the contract kit's construction-side clause - it asserts that create() itself answers the documented "
                    + "null sentinel for a request missing a declared requirement, which is a statement about the "
                    + "raw seam and cannot be made through open()");

    @Test
    void no_source_module_builds_an_import_source_through_the_unscreened_seam() throws IOException {
        Map<String, List<String>> offenders = new TreeMap<>();
        for (Path file : sources()) {
            String body = stripComments(Files.readString(file));
            if (!body.contains("ImportSourceProvider")) {
                continue;                       // nothing here can be building an import source
            }
            Matcher matcher = INSTANCE_CREATE.matcher(body);
            List<String> calls = new ArrayList<>();
            while (matcher.find()) {
                calls.add(matcher.group());
            }
            if (!calls.isEmpty() && !ALLOWED.containsKey(file.getFileName().toString())) {
                offenders.put(file.getFileName().toString(), calls);
            }
        }
        assertThat(offenders).as("""
                An import edge must build its source with ImportSourceProvider.open(provider, request, fetcher), not \
                provider.create(request, fetcher). open() wraps the fetcher in ImportScreen, so every URL the source \
                then hands back - a Nexus listing's per-asset downloadUrl, a format index's coordinate URL - is judged \
                before it is fetched. create() hands out the raw transport, and a migration walked over it fetches \
                artifact bytes wherever a compromised incumbent says, in cleartext, into the hosted store (D-152).""")
                .isEmpty();
    }

    @Test
    void every_allowlist_entry_is_still_live() throws IOException {
        Set<String> matching = new TreeSet<>();
        for (Path file : sources()) {
            String body = stripComments(Files.readString(file));
            if (body.contains("ImportSourceProvider") && INSTANCE_CREATE.matcher(body).find()) {
                matching.add(file.getFileName().toString());
            }
        }
        assertThat(matching).as("the allowlist is shrink-only: an entry whose file no longer builds a source through "
                + "the raw seam is a stale exemption and must be deleted, and a scan that matches nothing at all is "
                + "a scan that has stopped looking").containsExactlyInAnyOrderElementsOf(ALLOWED.keySet());
    }

    @Test
    void the_scan_names_a_planted_offender() {
        // The live negative control: a scan whose pattern rotted would pass the two checks above by matching nothing.
        String planted = """
                import build.jenesis.repository.importer.ImportSourceProvider;
                class SecondImportEdge {
                    ImportSource build(ImportSourceProvider provider, ImportRequest request, Fetcher fetcher) {
                        return provider.create(request, fetcher);
                    }
                }
                """;
        assertThat(planted.contains("ImportSourceProvider")).isTrue();
        assertThat(INSTANCE_CREATE.matcher(stripComments(planted)).find())
                .as("the pattern must still recognise a raw provider.create(request, fetcher) call").isTrue();
        assertThat(INSTANCE_CREATE.matcher("URI target = URI.create(url);").find())
                .as("and must not mistake a type's static factory for one").isFalse();
    }

    /** Every {@code .java} under {@code source/}, {@code module-info.java} included - a module descriptor cannot call
     *  anything, but excluding files by name is how a scan quietly stops covering a tree. */
    private static List<Path> sources() throws IOException {
        try (Stream<Path> tree = Files.walk(sourceRoot())) {
            return tree.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }

    /** Block and line comments removed, so a javadoc sentence naming the seam is never read as a call to it. */
    private static String stripComments(String body) {
        return body.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    /** The module sources directory ({@code <repo>/source}). The build runs the test JVM from the repository root, so
     *  this walks up from the working directory to the first ancestor holding {@code source/} beside
     *  {@code build/jenesis}. Fails loudly if the tree is not reachable, so the check never passes vacuously. */
    private static Path sourceRoot() {
        Path start = Path.of("").toAbsolutePath();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("build/jenesis"))) {
                // Standalone the tree is source/ beside build/jenesis; inside an enclosing project it is
                // core/source/ beside the enclosing one. Nested first: only the outer build has both.
                Path nested = dir.resolve("core").resolve("source");
                if (Files.isDirectory(nested)) {
                    return nested;
                }
                if (Files.isDirectory(dir.resolve("source"))) {
                    return dir.resolve("source");
                }
            }
        }
        throw new AssertionError("could not locate the core repo root (an ancestor holding source/ or core/source beside "
                + "build/jenesis) from working directory " + start + " - this structural check must run from the "
                + "repository tree so it can read the module sources");
    }
}
