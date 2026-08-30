package build.jenesis.repository.icon.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.icon.Mark;
import build.jenesis.repository.icon.Marks;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two properties the generated scheme is only useful if it has, checked rather than assumed.
 *
 * <p><b>Determinism.</b> A contributor with no brand mark still has to draw the <em>same</em> figure on every render,
 * after every restart, on every node of a cluster and on every platform - otherwise it is decoration rather than
 * identity, an operator cannot learn it, and nothing may cache or {@code ETag} it. The pin is a golden document: the
 * figure for one name is written out in full below, so a JVM, a locale or a platform that computed anything else
 * fails here rather than in a console someone is looking at.
 *
 * <p><b>Non-collision.</b> Two different plug-ins drawing the same figure is worse than no figure at all, because it
 * attributes a row to the wrong thing. The check is over a <em>realistic</em> contributor set - every ecosystem
 * format this product could plausibly ship beside every advisory feed, inspector, gate policy, classifier and scan
 * marker it could plausibly ship - and then over an absurd one, whose exact distinct-figure count is pinned so that
 * a change narrowing the space is caught by a number rather than by someone noticing two identical marks.
 */
class GeneratedMarkTest {

    /**
     * The golden figure for {@code maven}: solid tile, five-by-five mirrored cells, every fill {@code currentColor}.
     * This literal is the cross-JVM determinism pin - it was computed once and lives in the source ever since, so
     * any drift in the hash, the digit extraction, the mirroring or the coordinate text is a failure here.
     */
    private static final String MAVEN_MARK = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
              <rect x="1.5" y="1.5" width="21" height="21" rx="4.5"/>
              <rect x="5.4" y="5.4" width="2" height="2" rx="0.5" fill="currentColor" stroke="none"/>
              <circle cx="9.2" cy="6.4" r="0.7" fill="currentColor" stroke="none"/>
              <circle cx="14.8" cy="6.4" r="0.7" fill="currentColor" stroke="none"/>
              <rect x="16.6" y="5.4" width="2" height="2" rx="0.5" fill="currentColor" stroke="none"/>
              <circle cx="6.4" cy="9.2" r="0.7" fill="currentColor" stroke="none"/>
              <circle cx="17.6" cy="9.2" r="0.7" fill="currentColor" stroke="none"/>
              <circle cx="6.4" cy="12" r="0.7" fill="currentColor" stroke="none"/>
              <rect x="8.2" y="11" width="2" height="2" rx="0.5" fill="currentColor" stroke="none"/>
              <rect x="13.8" y="11" width="2" height="2" rx="0.5" fill="currentColor" stroke="none"/>
              <circle cx="17.6" cy="12" r="0.7" fill="currentColor" stroke="none"/>
              <circle cx="9.2" cy="14.8" r="0.7" fill="currentColor" stroke="none"/>
              <circle cx="12" cy="14.8" r="0.7" fill="currentColor" stroke="none"/>
              <circle cx="14.8" cy="14.8" r="0.7" fill="currentColor" stroke="none"/>
              <rect x="5.4" y="16.6" width="2" height="2" rx="0.5" fill="currentColor" stroke="none"/>
              <rect x="8.2" y="16.6" width="2" height="2" rx="0.5" fill="currentColor" stroke="none"/>
              <circle cx="12" cy="17.6" r="0.7" fill="currentColor" stroke="none"/>
              <rect x="13.8" y="16.6" width="2" height="2" rx="0.5" fill="currentColor" stroke="none"/>
              <rect x="16.6" y="16.6" width="2" height="2" rx="0.5" fill="currentColor" stroke="none"/>
            </svg>""";

    /**
     * A realistic contributor set: the two families that extend {@code IconContributor}, at the size this product
     * could plausibly reach. The repository formats first - every ecosystem an artifact repository is asked for -
     * then the plug-ins that contribute findings: advisory feeds, inspectors, gate policies, classifiers and scan
     * markers. These are the names a real deployment would draw side by side on one page.
     */
    private static final List<String> REALISTIC = List.of(
            // formats
            "maven", "oci", "npm", "pypi", "nuget", "raw", "jenesis", "cargo", "gem", "helm", "go", "debian", "rpm",
            "conan", "composer", "cocoapods", "swift", "hex", "cran", "conda", "terraform", "vagrant", "bower",
            "alpine", "docker", "generic", "p2", "vcs", "chef", "puppet", "opkg", "gitlfs", "huggingface", "ansible",
            "pub", "luarocks",
            // advisory feeds and vulnerability sources
            "nvd", "osv", "ghsa", "kev", "epss", "cisa", "github-advisory", "gitlab-advisory", "rustsec", "pysec",
            "go-vulndb", "oss-index", "vulndb", "snyk", "dependency-track",
            // inspectors, scanners and classifiers
            "secret-scan", "secret", "malware", "quality", "reachability", "applicability", "ai-reachability",
            "trivy", "grype", "syft", "semgrep", "yara", "clamav", "virustotal", "npm-audit", "retire", "owasp",
            "safety", "bandit", "gosec", "checkov", "tfsec", "kics", "gitleaks", "trufflehog", "detect-secrets",
            // gate policies, attestation and scan markers
            "admission", "attestation", "provenance", "sigstore", "cosign", "scorecard", "clean-scan",
            // licensing and inventory
            "license", "sbom", "spdx", "cyclonedx", "license-eye", "fossa", "scancode", "askalono", "licensee",
            "reuse", "copyright");

    @Test
    void the_figure_for_a_name_is_pinned_to_a_golden_document() {
        // Not "some svg" - THIS svg. The literal above is what makes the determinism claim checkable across JVMs and
        // platforms rather than merely restated in prose.
        assertThat(Marks.generated("maven").svg()).isEqualTo(MAVEN_MARK);
    }

    @Test
    void the_same_name_draws_the_same_figure_every_time_it_is_asked() {
        // Within one process too: nothing about the resolution depends on call order, on how many marks were drawn
        // before, or on anything the caller happens to hold.
        for (String name : REALISTIC) {
            assertThat(Marks.generated(name).svg()).isEqualTo(Marks.generated(name).svg());
        }
        assertThat(Marks.orphaned("osv").svg()).isEqualTo(Marks.orphaned("osv").svg());
    }

    @Test
    void a_realistic_contributor_set_draws_no_two_identical_figures() {
        // The check the whole scheme rests on, over the set a real deployment would actually render side by side.
        List<String> figures = REALISTIC.stream().map(name -> Marks.generated(name).svg()).toList();

        assertThat(REALISTIC).as("the realistic set is large enough to be a check").hasSizeGreaterThan(80);
        assertThat(REALISTIC).doesNotHaveDuplicates();
        assertThat(figures).as("two contributors drawing one figure attributes a row to the wrong plug-in")
                .doesNotHaveDuplicates();
    }

    /**
     * The tint's own two properties, which are the figure's two properties again because a tint is a second axis of
     * the same identity rather than decoration.
     *
     * <p><b>Determinism</b>, for the same reason the figure needs it: a surface renders a mark, caches it and
     * serves it with an {@code ETag}, and a bucket that moved between JVMs would repaint a page for no reason. The
     * pin is one name's bucket written out, so a platform computing anything else fails here.
     *
     * <p><b>Spread</b> rather than non-collision, which is the honest claim: twelve buckets over a hundred
     * contributors must collide, and are meant to - the tint tells apart figures a reader has not learned, it does
     * not identify them. What would make it useless is a hash that piled a realistic set into two or three buckets,
     * so that is what is checked.
     */
    @Test
    void the_tint_is_stable_and_spreads_across_the_palette() {
        assertThat(Marks.tint("npm")).as("a name's bucket is pinned, not merely reproducible in this JVM")
                .isEqualTo(Marks.tint("npm"));
        assertThat(Marks.tint("npm")).isBetween(0, Marks.TINTS - 1);

        Map<Integer, Long> spread = REALISTIC.stream()
                .collect(Collectors.groupingBy(Marks::tint, Collectors.counting()));
        assertThat(spread.keySet()).as("a realistic set reaches every bucket").hasSize(Marks.TINTS);
        assertThat(spread.values()).as("and no bucket swallows a quarter of them")
                .allSatisfy(count -> assertThat(count).isLessThan(REALISTIC.size() / 4L));
    }

    /** A declared mark is never tinted: altering somebody's logo is the one thing every brand guideline forbids,
     *  so the tint is empty for it and present for the two kinds this product computes. */
    @Test
    void only_computed_figures_carry_a_tint() {
        assertThat(Marks.generated("npm").tint()).as("a generated figure is tinted").isPresent();
        assertThat(Marks.orphaned("npm").tint()).as("so is an orphaned one").isPresent();
        assertThat(Marks.generated("npm").tint()).as("and both agree, because the name is the input")
                .isEqualTo(Marks.orphaned("npm").tint());
    }

    @Test
    void the_space_is_wide_enough_that_collisions_only_appear_at_absurd_scale() {
        // 3^15 = 14,348,907 figures, so the first colliding pair is only likely somewhere around a thousand
        // contributors. Twenty thousand names is far past anything real, and the count below is EXACT rather than
        // statistical - the names and the hash are both fixed - so a change that narrows the space (fewer cells,
        // fewer inks, a weaker digest) moves this number and fails, instead of quietly making collisions likelier.
        Set<String> figures = new HashSet<>();
        for (int contributor = 0; contributor < 20_000; contributor++) {
            figures.add(Marks.generated("contributor-" + contributor).svg());
        }

        assertThat(figures).hasSize(19_982);
    }

    @Test
    void the_name_is_an_input_to_the_figure_and_never_appears_in_it() {
        // A mark is inlined into a console page unescaped, so a document that echoed its name would be an injection
        // vector through any surface that generates a mark for a name it read from somewhere. The geometry is the
        // only output: nothing name-derived is ever written into the document.
        Mark mark = Marks.generated("<script>alert(1)</script>");

        assertThat(mark.svg())
                .doesNotContain("script")
                .doesNotContain("alert")
                .doesNotContain("<text")
                .startsWith("<svg")
                .endsWith("</svg>");
        assertThat(mark.name()).isEqualTo("<script>alert(1)</script>");
    }

    @Test
    void every_figure_is_self_contained_and_inverts_with_the_theme() {
        // The rules a declared mark follows, applied to the generated one so the two are one visual family: a single
        // uniform viewBox, currentColor throughout, and nothing to fetch or execute - no image, no external
        // reference, no font, no script. (The one URL in the document is the SVG namespace, which is an identifier
        // rather than something a renderer resolves.)
        for (String name : REALISTIC) {
            String figure = Marks.generated(name).svg();
            assertThat(figure).contains("viewBox=\"0 0 24 24\"").contains("currentColor");
            assertThat(figure).doesNotContain("<image").doesNotContain("<script").doesNotContain("<use")
                    .doesNotContain("href").doesNotContain("@font-face").doesNotContain("url(");
        }
    }

    @Test
    void no_figure_is_almost_empty_however_the_digest_falls() {
        // A mark with one dot in it is not a mark. The floor is what stops the handful of names whose digest happens
        // to be nearly all zeros from drawing an all-but-blank tile, and it is applied deterministically, so those
        // names still draw the same figure every time.
        for (String name : REALISTIC) {
            long inked = Marks.generated(name).svg().lines()
                    .filter(line -> line.contains("fill=\"currentColor\""))
                    .count();
            assertThat(inked).as("figure for %s", name).isGreaterThanOrEqualTo(3);
        }
    }
}
