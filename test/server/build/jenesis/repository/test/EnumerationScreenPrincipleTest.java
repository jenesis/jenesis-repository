package build.jenesis.repository.test;

import module java.base;

import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Core structural guard (the servable-name enumeration seam EPIC, phase&nbsp;P-F4): every surface that
 * materialises published <em>names</em> - browse children, version folders, OCI catalog/tags, the {@code publish/}
 * asset walk - must route its disclosure decision through the one {@link build.jenesis.repository.store.ServableNames}
 * seam, so a withheld/held artifact's existence cannot leak through a name-enumeration listing. This is a
 * source-scanning guard in the exact mould of its sibling {@code *PrincipleTest}s
 * ({@link FormatScreeningMonopolyPrincipleTest}, {@link ImmutabilityPrincipleTest}, {@link ConfigPrincipleTest}): it
 * reads the sources rather than booting anything, strips comments, classifies each file by token match, and fails the
 * build naming any offender, with a justified allowlist for the genuine non-name-disclosure enumerations.
 *
 * <h2>The scanned set (files that can materialise served names)</h2>
 * <ul>
 *   <li>everything under {@code source/format/} (the ecosystem layout writers whose index/metadata surfaces list
 *       versions/tags: maven-metadata, OCI catalog/tags, the raw directory listing);</li>
 *   <li>everything under {@code source/ui/} (the console browse tree and its namespace quick-links);</li>
 *   <li>{@code source/server/}{@code **}{@code /*Controller.java} (the REST controllers - the server ingress that can
 *       enumerate);</li>
 *   <li>everything under {@code source/walk/} (the {@code publish/}-tree walkers that feed index rebuild);</li>
 *   <li>the {@code PublishedAssets} walker under {@code source/store/} (the one shared {@code publish/} walk behind the
 *       console {@code /assets} export and the server {@code /api/assets} catalogue).</li>
 * </ul>
 *
 * <h2>The classification (per file, over the comment-stripped source)</h2>
 * <ol>
 *   <li><b>enumerating</b> - the file contains any {@link #ENUMERATION_TOKENS raw-enumeration token} that walks stored
 *       names ({@code store.list(} / {@code store.page(}, and the coordinate/version/children/releases enumeration
 *       idioms). On the current free tree only {@code store.list(} and {@code store.page(} fire; the remaining tokens
 *       ({@code .children(}, {@code .coordinates(}, {@code .versions(}, {@code releases(}, {@code blobs.list(},
 *       {@code blobs.page(}) are the downstream-shaped coordinate/version-enumeration idioms (search, inventory) that
 *       do not yet exist in the free tree - they are live forward guards so a <em>new</em> free surface built in that
 *       shape is caught the moment it lands, not invented matches.</li>
 *   <li><b>screened</b> - the file contains any {@link #SEAM_TOKENS seam token}, i.e. it routes a disclosure decision
 *       through {@code ServableNames} (or the promoted {@code Withheld} marker convention).</li>
 *   <li><b>helped</b> - the file drives the shared screened <em>enumeration</em>
 *       ({@code build.jenesis.repository.walk.ScreenedNames}), which lists and screens in one call.</li>
 *   <li><b>offender</b> = enumerating &and; &not;screened &and; &not;allowlisted. An offender fails the build. Since
 *       T-103a a second, tighter leg applies to serving surfaces: enumerating &and; &not;helped &and; not a walk
 *       internal &and; &not;{@link #HAND_SCREENED} also fails, because screening per name is separable from listing and
 *       the separable half is the one that gets lost.</li>
 * </ol>
 *
 * <h2>The allowlist</h2>
 * A {@code Map<source-relative-path, justification>} of genuine <em>non</em>-name-disclosure enumerations - a walk
 * internal that delivers every key to a consumer which itself screens, retention/GC scans that must see withheld
 * artifacts, etc. On the current free scanned set the entries are the store-traversal machinery and nothing else:
 * {@code walk/store/.../StoreArtifactWalk.java}, the reference layout-neutral store DFS - it pages every key of every
 * namespace ({@code blobs/}, {@code walks/}, {@code publish/}, ...) and hands them to walk consumers; the consumer
 * ({@code RebuildPass}) is where the screen lives ({@code names.state(path) == WITHHELD} skips), so the walker itself
 * discloses no served name and must not screen - plus the two shared primitives it is built from
 * ({@code walk/spi/.../Trees.java}, {@code walk/spi/.../BoundedChildren.java}), which page to a
 * <em>caller-supplied</em> consumer for exactly the same reason. Each entry carries a one-line justification, and
 * {@link #the_allowlist_stays_live_and_would_be_an_offender()} fails if an entry's file is gone or has since started
 * screening (so a grant cannot rot into a dead mask).
 *
 * <h2>Non-vacuity &amp; the negative control</h2>
 * The scan asserts it saw {@literal >} 0 enumerating files, that every known free name-disclosure surface
 * ({@link #SCREENED_SURFACES}) is in the scanned set <em>and</em> classified screened, that {@literal >=} 1 scanned file
 * references the seam and that {@literal >=} 1 drives the shared enumeration (proving neither token list is a dead
 * matcher that would pass every offender). The surfaces are pinned on "screened" rather than "enumerating" precisely
 * because adopting the shared enumeration REMOVES a surface's raw {@code store.list}/{@code store.page} call - the fix
 * must not read as the regression. The <b>negative control was verified during implementation</b>: temporarily deleting the
 * {@code ServableNames} import + call from {@code BrowseController} (unscreening a real free surface) made this test
 * FAIL and name {@code ui/.../BrowseController.java} as an offender; adding a dummy enumerating-without-seam file under
 * {@code source/format/} likewise failed and named it; both were reverted, confirming the guard bites.
 *
 * <p>Like every token-scanning ratchet this is heuristic (helper indirection could hide a raw call); the allowlist,
 * the non-vacuity asserts and the negative-control discipline are what keep it honest, and the seam being the
 * convenient path (a screened listing helper) means indirection buys nothing.
 */
class EnumerationScreenPrincipleTest {

    /**
     * Raw enumeration idioms that walk stored names; any hit marks a file "enumerating". {@code store.list(} /
     * {@code store.page(} are the free store-walk primitives (both fire on the current tree). {@code .children(},
     * {@code .coordinates(}, {@code .versions(}, {@code releases(}, {@code blobs.list(}, {@code blobs.page(} are the
     * coordinate/version-enumeration idioms of the (downstream-side) inventory and search facades and the blobs
     * namespace; none exists in the free tree today, so they are forward guards that catch a new free surface built in
     * that shape - not invented matches (the non-vacuity check proves at least the store-walk primitives fire).
     */
    private static final List<String> ENUMERATION_TOKENS = List.of(
            "store.list(", "store.page(", "blobs.list(", "blobs.page(",
            ".children(", ".coordinates(", ".versions(", "releases(");

    /**
     * The shared screened-enumeration primitive ({@code build.jenesis.repository.walk.ScreenedNames}): one call that
     * pages a container AND applies the {@code ServableNames} verdict to every name, so a serving surface never holds
     * an unscreened name and cannot page-then-forget. Referencing the seam per name is no longer enough for a serving
     * surface that enumerates - {@link #a_serving_surface_that_enumerates_uses_the_shared_screened_enumeration()}
     * requires this - because hand-assembled "page, then screen" is precisely the shape that loses its second half in
     * the next refactor.
     */
    private static final String HELPER = "ScreenedNames";

    /** The free surfaces that materialise served names today - the browse tree and its panel, the raw directory
     *  listing, the maven-metadata version index, the OCI catalog/tags. Each must stay in the scanned set and stay
     *  screened; the list is the guard's non-vacuity pin, so deleting a surface from it is a deliberate act. */
    private static final List<String> SCREENED_SURFACES = List.of(
            "ui/build/jenesis/repository/ui/BrowseController.java",
            "ui/build/jenesis/repository/ui/BrowsePanel.java",
            "format/raw/build/jenesis/repository/format/raw/RawFormat.java",
            "format/maven/build/jenesis/repository/format/maven/MavenMetadata.java",
            "format/oci/build/jenesis/repository/format/oci/OciFormat.java");

    /**
     * Seam faces; any hit marks a file "screened". {@code ServableNames} is the seam type; {@link #HELPER} is the
     * shared screened <em>enumeration</em> built on it; {@code .disclosable} covers {@code disclosable(path, policy)} /
     * {@code disclosableKey(...)}; {@code disclosableVersionFolder(} is the version-index face; {@code withheldHash(}
     * is the bare {@code withheld/<hash>} marker face; {@code reviewSubtree} is the reserved-quarantine-name face;
     * {@code Withheld.is(} is the promoted marker convention. On the current free tree every token but
     * {@code Withheld.is(} fires.
     */
    private static final List<String> SEAM_TOKENS = List.of(
            "ServableNames", HELPER, ".disclosable", "withheldHash(", "Withheld.is(",
            "disclosableVersionFolder(", "reviewSubtree");

    /**
     * Genuine non-name-disclosure enumerations, keyed by {@code source}-relative path with a one-line justification.
     * An enumerating, unscreened file NOT in this map is an offender that fails the build.
     */
    private static final Map<String, String> ALLOWLIST = allowlist();

    private static Map<String, String> allowlist() {
        Map<String, String> allow = new LinkedHashMap<>();

        // --- The reference store walk: a layout-neutral depth-first descent that pages EVERY key of EVERY namespace
        //     (blobs/, walks/, publish/, ...) through ArtifactStore#page and delivers them to a WalkConsumer. It
        //     discloses no served name itself - the consumer decides. Screening lives in that consumer: RebuildPass
        //     routes each delivered pointer through ServableNames.state and skips WITHHELD. The walker paging all keys
        //     (incl. withheld ones) to a screening consumer is a walk internal, not a name-disclosure surface. ---
        allow.put("walk/store/build/jenesis/repository/walk/store/StoreArtifactWalk.java",
                "layout-neutral store DFS: pages every key of every namespace to walk consumers; the consumer "
                        + "(RebuildPass) screens via ServableNames.state - the walker discloses no served name itself");
        allow.put("walk/spi/build/jenesis/repository/walk/Trees.java",
                "the iterative descent primitive extracted from StoreArtifactWalk (which now delegates to it): "
                        + "pages every key of every namespace to a visitor; screening lives in the visitor/consumer, "
                        + "not the walk internal - it discloses no served name itself");

        // --- The shared bounded-traversal primitives beside it. Same argument, one level down: they page a subtree /
        //     a container and hand every name to the CALLER's consumer, which is where the disclosure decision lives
        //     (a rebuild consumer must see withheld keys; a serving listing must screen them through ServableNames).
        //     A primitive that screened internally would be wrong for the first and redundant for the second. ---
        allow.put("walk/spi/build/jenesis/repository/walk/BoundedChildren.java",
                "the shared bounded flat-child enumeration: pages one container's names to a caller's consumer, "
                        + "which owns the disclosure decision - it discloses no served name itself");

        return Map.copyOf(allow);
    }

    /**
     * Serving surfaces that enumerate but may not use the shared screened enumeration, keyed by {@code source}
     * -relative path with a one-line justification. This is a strictly smaller grant than {@link #ALLOWLIST}: an entry
     * here still MUST screen through the seam (it is checked by
     * {@link #every_enumeration_surface_routes_through_the_servable_name_seam()} like everything else); it is only
     * excused from routing through the shared primitive, and only for a structural reason.
     */
    private static final Map<String, String> HAND_SCREENED = Map.of(
            "store/spi/build/jenesis/repository/store/PublishedAssets.java",
            "the one shared publish/-tree walk (the /assets export and /api/assets catalogue) - it IS the seam's tree "
                    + "face and screens every emitted leaf through ServableNames.state, but it lives in the store SPI "
                    + "module that the walk module (where the shared screened enumeration and the bounded traversal "
                    + "primitives live) depends on, so adopting the helper here would be a module cycle");

    @Test
    void every_enumeration_surface_routes_through_the_servable_name_seam() throws IOException {
        Scan scan = scan(sourceRoot());

        // Non-vacuity: a broken scanned-set or matcher that saw nothing would otherwise pass every offender silently.
        assertThat(scan.enumerating())
                .as("the scan found no enumerating files - the scanned-set globs or the enumeration-token list is "
                        + "broken; this structural check would then pass vacuously")
                .isNotEmpty();

        // The known free name-disclosure surfaces must be present in the scanned set AND classified screened. They are
        // pinned on "screened" rather than "enumerating" because that is the property this guard defends: a surface
        // that adopts the shared screened enumeration stops calling store.list/page itself, which is the fix, not a
        // regression. If one drops out of scope or stops screening, the guard has lost a surface it exists to protect.
        for (String surface : SCREENED_SURFACES) {
            assertThat(scan.screened())
                    .as("the free name-disclosure surface %s must be scanned and classified screened", surface)
                    .anyMatch(f -> f.endsWith(surface));
        }

        // The seam-token list is alive: at least one scanned file actually references the seam. A dead seam-token list
        // (a rename that matches nothing) would classify every enumerating file "unscreened" - this catches that.
        assertThat(scan.screened())
                .as("no scanned file references the servable-name seam - the SEAM_TOKENS list is dead (a rename?), so "
                        + "the guard would flag every screened surface as an offender")
                .isNotEmpty();

        List<String> offenders = scan.enumerating().stream()
                .filter(f -> !scan.screened().contains(f))
                .filter(f -> !ALLOWLIST.containsKey(f))
                .sorted()
                .map(f -> "  - " + f)
                .toList();

        assertThat(offenders)
                .as("these files enumerate stored names but route no disclosure decision through the servable-name "
                        + "seam (build.jenesis.repository.store.ServableNames), so a withheld/held artifact's existence "
                        + "can leak through the listing. Screen the enumeration - filter names through "
                        + "the names through the shared ScreenedNames enumeration, or (for a single name) through "
                        + "ServableNames.disclosable / disclosableVersionFolder / withheldHash - or, if this "
                        + "enumeration genuinely discloses no served "
                        + "name (a walk internal, a retention/GC scan that must see withheld artifacts), add it to "
                        + "ALLOWLIST (keyed by source-relative path) with a one-line justification.%n%s",
                        String.join(System.lineSeparator(), offenders))
                .isEmpty();
    }

    @Test
    void a_serving_surface_that_enumerates_uses_the_shared_screened_enumeration() throws IOException {
        Scan scan = scan(sourceRoot());

        // The tightened clause (T-103a): screening per name is no longer enough for a serving surface that enumerates.
        // "Page the container, then screen each name" is two separable steps, and the second one is what gets lost -
        // to a refactor, to a new surface copied from an older one, to a format author who only knows the first step.
        // The shared primitive fuses them, so the failure mode stops being expressible. Walk internals are exempt by
        // the same argument as ALLOWLIST (they hand every name to a caller's consumer, which owns the decision), and
        // the shared primitive itself obviously cannot require itself.
        List<String> offenders = scan.enumerating().stream()
                .filter(file -> !file.startsWith("walk/"))
                .filter(file -> !ALLOWLIST.containsKey(file))
                .filter(file -> !HAND_SCREENED.containsKey(file))
                .filter(file -> !scan.helped().contains(file))
                .sorted()
                .map(file -> "  - " + file)
                .toList();

        assertThat(offenders)
                .as("these serving surfaces enumerate stored names and screen them by hand instead of listing AND "
                        + "screening in one call through the shared primitive (%s). Drive the enumeration through it - "
                        + "it applies the very ServableNames faces this file calls, and a caller never sees an "
                        + "unscreened name - or, if the surface genuinely cannot (a module cycle, a walk internal), "
                        + "add it to HAND_SCREENED with a structural justification.%n%s",
                        HELPER, String.join(System.lineSeparator(), offenders))
                .isEmpty();

        // Non-vacuity: the primitive is actually adopted somewhere, so a rename that matches nothing cannot make this
        // clause pass by classifying every surface "not enumerating" or "not helped".
        assertThat(scan.helped())
                .as("no scanned file uses the shared screened enumeration - the HELPER token is dead (a rename?), and "
                        + "this clause would then be vacuous")
                .isNotEmpty();
    }

    @Test
    void the_hand_screened_grants_stay_live_and_would_be_offenders() throws IOException {
        Scan scan = scan(sourceRoot());
        List<String> dead = HAND_SCREENED.keySet().stream()
                .filter(file -> !scan.enumerating().contains(file) || scan.helped().contains(file))
                .sorted()
                .map(file -> "  - " + file)
                .toList();
        assertThat(dead)
                .as("these HAND_SCREENED entries no longer enumerate by hand - the file moved, stopped enumerating, or "
                        + "now routes through the shared primitive, so the grant masks nothing; remove or update it so "
                        + "the exemption tracks the source and cannot rot into a dead grant.%n%s",
                        String.join(System.lineSeparator(), dead))
                .isEmpty();
    }

    @Test
    void the_allowlist_stays_live_and_would_be_an_offender() throws IOException {
        Scan scan = scan(sourceRoot());
        List<String> dead = ALLOWLIST.keySet().stream()
                .filter(path -> !scan.enumerating().contains(path) || scan.screened().contains(path))
                .sorted()
                .map(path -> "  - " + path)
                .toList();
        assertThat(dead)
                .as("these ALLOWLIST entries are no longer enumerating-and-unscreened - the file was moved/deleted or "
                        + "it now routes through the seam, so the grant masks nothing; remove or update the entry so the "
                        + "allowlist tracks the source and cannot rot into a dead grant.%n%s",
                        String.join(System.lineSeparator(), dead))
                .isEmpty();
    }

    // --- the scan -----------------------------------------------------------------------------------------------------

    /** The classification result: the source-relative path of every scanned file that is enumerating, of every scanned
     *  file that references the seam, and of every scanned file that drives the shared screened enumeration. */
    private record Scan(Set<String> enumerating, Set<String> screened, Set<String> helped) {}

    private static Scan scan(Path sourceRoot) throws IOException {
        Set<String> enumerating = new TreeSet<>();
        Set<String> screened = new TreeSet<>();
        Set<String> helped = new TreeSet<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : (Iterable<Path>) files.filter(EnumerationScreenPrincipleTest::isJava)::iterator) {
                String relative = sourceRoot.relativize(file).toString().replace(File.separatorChar, '/');
                if (!inScannedSet(relative)) {
                    continue;
                }
                String body = stripComments(Files.readString(file));
                if (ENUMERATION_TOKENS.stream().anyMatch(body::contains)) {
                    enumerating.add(relative);
                }
                if (SEAM_TOKENS.stream().anyMatch(body::contains)) {
                    screened.add(relative);
                }
                if (body.contains(HELPER)) {
                    helped.add(relative);
                }
            }
        }
        return new Scan(enumerating, screened, helped);
    }

    /** The scanned set: files that can materialise served names. {@code source/format/}, {@code source/ui/} and
     *  {@code source/walk/} wholesale; the REST controllers under {@code source/server/}; and the one shared
     *  {@code PublishedAssets} walk under {@code source/store/}. {@code module-info.java} is never a surface. */
    private static boolean inScannedSet(String relative) {
        if (relative.endsWith("/module-info.java") || relative.equals("module-info.java")) {
            return false;
        }
        if (relative.startsWith("format/") || relative.startsWith("ui/") || relative.startsWith("walk/")) {
            return true;
        }
        if (relative.startsWith("server/") && relative.endsWith("Controller.java")) {
            return true;
        }
        return relative.startsWith("store/") && relative.endsWith("/PublishedAssets.java");
    }

    private static boolean isJava(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".java");
    }

    /** Blanks out {@code //} and {@code /* *}{@code /} comments (preserving newlines and string/char literals) so the
     *  scan never trips on a token that appears inside a javadoc {@code {@code ...}} example rather than in real code.
     *  Ported verbatim from the sibling {@link ImmutabilityPrincipleTest} guard. */
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

    /** The module sources directory ({@code <repo>/source}) - located exactly as the sibling structural tests do, by
     *  walking up from the working directory to the first ancestor holding {@code source/} beside {@code build/jenesis}.
     *  Fails loudly if the tree is not reachable, so this structural check never passes vacuously. */
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
}
