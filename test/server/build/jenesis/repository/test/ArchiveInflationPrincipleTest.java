package build.jenesis.repository.test;

import build.jenesis.repository.store.ArchiveInflation;

import module java.base;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enforces engineering principle <b>&sect;13 - cross-format parity</b> for the one shared concern it names first,
 * the <b>archive-inflation cap</b>, structurally at build time across the free {@code source/} tree.
 *
 * <p>&sect;13: <i>a guard/cap/behaviour one format applies to a shared concern (archive-inflation caps, path-traversal
 * guards, withhold-on-enumeration, proxy integrity verification, streaming) is applied by EVERY format with that
 * concern.</i> The inflation cap is the clause a new format is most likely to arrive without, because the ratio is the
 * attacker's to choose and nothing about opening a {@code ZipInputStream} announces that a member may inflate to
 * gigabytes on the publish thread of a shared JVM.
 *
 * <p>Before {@link ArchiveInflation} the cap had no owner: the core's only bound was one module's <em>private</em>
 * constant, so the rule {@code RepositoryFormat} clause 15 states was a rule a format inherited with nothing to inherit
 * it <em>from</em>. This is the guard that closes that: it makes a module that decompresses without the shared bound
 * <b>visibly wrong at build time</b> rather than silently unbounded until a crafted artifact finds it.
 *
 * <h2>What is flagged (the matcher)</h2>
 * Over the comment-stripped source of every {@code .java} under {@code source/}, a file is a <b>hit</b> when it
 * constructs a decompressing stream - {@code new ZipInputStream(}, {@code new JarInputStream(}, {@code new
 * GZIPInputStream(}, {@code new InflaterInputStream(}, {@code new ZipFile(} - because that is the point at which a
 * stored byte count stops bounding a heap allocation. A hit is <b>cleared</b> when the same file routes a member
 * through {@link ArchiveInflation} ({@code ArchiveInflation.entry(}), and otherwise must carry an
 * {@link #ALLOWLIST} entry justifying that it materialises no member at all. Anything else fails the build, naming the
 * file, the line and the construction, and pointing at the shared bound.
 *
 * <p>The file, not the statement, is the unit deliberately: a walk opens the archive in one method and reads its
 * members in another, so a statement-level matcher would have to model dataflow to say anything true. Per file it says
 * something true and cheap - "this module decompresses; show me where it bounds a member, or say why it never
 * materialises one".
 *
 * <h2>Scope &amp; honest limitations</h2>
 * A text scan in the {@code StreamingPrincipleTest} / {@code UnboundedListingPrincipleTest} mould, with the same
 * {@link #TEST_SUPPORT} exclusion of the two JUnit-free contract kits under {@code source/**}. It cannot see a
 * decompressor reached through a library that opens one internally, and a file that both calls
 * {@code ArchiveInflation.entry} and separately drains an unbounded member passes - the value it delivers is that a
 * <em>new</em> module opening an archive without the shared bound is caught the moment it is written, which is exactly
 * the way clause 15 was previously arrived at without.
 */
class ArchiveInflationPrincipleTest {

    /** The decompressor constructions that turn a bounded stored length into an unbounded heap allocation. */
    private static final List<String> DECOMPRESSORS = List.of(
            "new ZipInputStream(", "new JarInputStream(", "new GZIPInputStream(",
            "new InflaterInputStream(", "new ZipFile(", "new JarFile(");

    /** The call that clears a hit: the shared, operator-settable bound applied to a member. */
    private static final String BOUNDED_READ = "ArchiveInflation.entry(";

    /**
     * A justified decompressing module that materialises no member, keyed on {@code SimpleClassName} with a one-line
     * reason. An entry is <em>not</em> a licence to inflate: it asserts that every member this file opens is streamed
     * through or handed on, so there is nothing for the bound to apply to.
     */
    private record Allow(String className, String justification) {}

    private static final List<Allow> ALLOWLIST = List.of(
            new Allow("BatchIngestion",
                    "walks the uploaded archive with ZipInputStream and dispatches each entry as its own request "
                            + "body, streamed straight into the format's publish path - no entry is ever read into "
                            + "heap here, so there is no inflation to bound (the per-entry bound belongs to whichever "
                            + "format materialises a declaration out of the artifact it was handed)"));

    @Test
    void every_module_that_decompresses_an_archive_bounds_the_member_it_materialises() throws IOException {
        Path sourceRoot = sourceRoot();
        List<String> hits = new ArrayList<>();
        List<String> sites = new ArrayList<>();
        Set<String> clearedByAllowlist = new TreeSet<>();

        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : (Iterable<Path>) files.filter(ArchiveInflationPrincipleTest::isJava)::iterator) {
                String code = stripComments(Files.readString(file));
                List<String> found = constructions(code);
                if (found.isEmpty()) {
                    continue;
                }
                sites.add(sourceRoot.relativize(file).toString());
                if (code.contains(BOUNDED_READ)) {
                    continue;
                }
                String className = file.getFileName().toString().replace(".java", "");
                Optional<Allow> allowed = ALLOWLIST.stream()
                        .filter(allow -> allow.className().equals(className))
                        .findFirst();
                if (allowed.isPresent()) {
                    clearedByAllowlist.add(className);
                    continue;
                }
                hits.add("  - " + sourceRoot.relativize(file) + "  " + String.join(", ", found));
            }
        }

        assertThat(sites)
                .as("the scan found modules that decompress - the check is not vacuous")
                .isNotEmpty();

        assertThat(hits)
                .as("these modules open a decompressing stream but never bound a member through the shared "
                        + "ArchiveInflation ceiling, so a crafted artifact chooses how much heap they allocate on the "
                        + "publish thread (RepositoryFormat contract clause 15, principle 13). Read the member with "
                        + "ArchiveInflation.entry(...) - taking orNull() for an optional declaration, required(...) "
                        + "for the artifact's identity - or, if this file genuinely materialises no member, add an "
                        + "ALLOWLIST entry saying so.%n%s", String.join(System.lineSeparator(), hits))
                .isEmpty();

        // The allowlist is a burn-down list, not a parking space: an entry whose file stopped decompressing, or which
        // now routes through the shared bound, must go rather than sit there masking the next real offender.
        List<String> stale = ALLOWLIST.stream()
                .map(Allow::className)
                .filter(className -> !clearedByAllowlist.contains(className))
                .sorted()
                .toList();
        assertThat(stale)
                .as("these allowlist entries no longer correspond to an unbounded decompressing module - remove them "
                        + "so the allowlist tracks the code")
                .isEmpty();
    }

    @Test
    void the_bound_has_one_home_and_no_module_keeps_a_private_copy_of_it() throws IOException {
        // D-054 was not "there is no cap" - it was that every cap was a private constant in the module that needed it,
        // parallel by convention and keyed to nothing an operator can set. A re-privatised copy would restore exactly
        // that, so the shared bound's own literal is asserted to appear once, in the module that owns it.
        Path sourceRoot = sourceRoot();
        Pattern privateCeiling = Pattern.compile(
                "private\\s+static\\s+final\\s+(?:int|long)\\s+[A-Z_]*(?:METADATA_ENTRY|INFLAT|DECOMPRESS)[A-Z_]*\\s*=");
        List<String> copies = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : (Iterable<Path>) files.filter(ArchiveInflationPrincipleTest::isJava)::iterator) {
                if (privateCeiling.matcher(stripComments(Files.readString(file))).find()) {
                    copies.add("  - " + sourceRoot.relativize(file));
                }
            }
        }
        assertThat(copies)
                .as("a private per-module inflation ceiling is exactly the shape D-054 removed - the bound is "
                        + "ArchiveInflation.largestEntry(), settable at %s. A module that legitimately needs a "
                        + "different ceiling passes it to ArchiveInflation.entry(member, limit) with its reason at "
                        + "that call site.%n%s", ArchiveInflation.LARGEST_ENTRY_KEY,
                        String.join(System.lineSeparator(), copies))
                .isEmpty();
    }

    /** Every decompressor construction the file carries, collapsed to distinct names, in declaration order. */
    private static List<String> constructions(String code) {
        List<String> found = new ArrayList<>();
        for (String decompressor : DECOMPRESSORS) {
            if (code.contains(decompressor)) {
                found.add(decompressor.substring("new ".length(), decompressor.length() - 1));
            }
        }
        return found;
    }

    private static boolean isJava(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".java")
                && !TEST_SUPPORT.matcher(path.toString().replace(File.separatorChar, '/')).find();
    }

    /** The two JUnit-free contract kits under {@code source/**}, excluded by the same one-module-deep rule the sibling
     *  scans use: neither ships in a bundle nor sits on a publish path, and an archive one of them builds or reads
     *  exists to <em>assert</em> a format's handling rather than to be a format's handling. */
    private static final Pattern TEST_SUPPORT = Pattern.compile("(^|/)(store|format)/testkit/");

    /** The module sources directory, located by walking up to the first ancestor holding {@code source/} beside
     *  {@code build/jenesis}; fails loudly rather than passing vacuously when the tree is not reachable. */
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
        throw new AssertionError("could not locate the free repo root (an ancestor holding source/ or core/source beside "
                + "build/jenesis) from working directory " + start + " - this structural check must run from the "
                + "repository tree so it can read the module sources");
    }

    /** Blanks out {@code //} and block comments (preserving newlines and string/char literals) so the matcher never
     *  trips on a decompressor named in prose. Ported from the sibling {@code StreamingPrincipleTest} guard. */
    private static String stripComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int n = text.length();
        int state = 0; // 0 code, 1 string, 2 char, 3 line-comment, 4 block-comment
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            char next = i + 1 < n ? text.charAt(i + 1) : '\0';
            switch (state) {
                case 0 -> {
                    if (c == '"') { out.append(c); state = 1; }
                    else if (c == '\'') { out.append(c); state = 2; }
                    else if (c == '/' && next == '/') { out.append("  "); i++; state = 3; }
                    else if (c == '/' && next == '*') { out.append("  "); i++; state = 4; }
                    else { out.append(c); }
                }
                case 1 -> {
                    out.append(c);
                    if (c == '\\' && i + 1 < n) { out.append(text.charAt(++i)); }
                    else if (c == '"') { state = 0; }
                }
                case 2 -> {
                    out.append(c);
                    if (c == '\\' && i + 1 < n) { out.append(text.charAt(++i)); }
                    else if (c == '\'') { state = 0; }
                }
                case 3 -> {
                    if (c == '\n') { out.append('\n'); state = 0; } else { out.append(' '); }
                }
                case 4 -> {
                    if (c == '*' && next == '/') { out.append("  "); i++; state = 0; }
                    else { out.append(c == '\n' ? '\n' : ' '); }
                }
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
