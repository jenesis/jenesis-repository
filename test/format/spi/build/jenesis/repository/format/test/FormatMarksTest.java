package build.jenesis.repository.format.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.EcosystemLayout;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.FormatMarks;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.icon.IconResource;
import build.jenesis.repository.icon.Mark;
import build.jenesis.repository.icon.Marks;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The format family's half of the shared mark resolution: which installed format owns a repository's top-level
 * storage namespace (a format writes its layout under the request prefix it claims) and which one declares a browse
 * hit's ecosystem. Everything <em>generic</em> - the neutral fallback, the rendering rule, the generated figure, the
 * three states - is asserted once over {@code Marks}; what is asserted here is only the mapping, driven over stub
 * formats rather than through {@code ServiceLoader}, so it is the mapping and not discovery that is under test.
 */
class FormatMarksTest {

    private static final String NPM_MARK = "<svg data-mark=\"npm\">stroke=currentColor</svg>";
    private static final String CARGO_MARK = "<svg data-mark=\"cargo\">stroke=currentColor</svg>";

    // A hosted-only format that owns /npm/, ships a mark and declares its ecosystem without laying artifacts out
    // under the published tree - the shape of every blobs-namespace format.
    private final RepositoryFormat npm = new StubEcosystemFormat("npm", "/npm/", "npm", NPM_MARK);
    // A hosted-only format that declares no ecosystem at all.
    private final RepositoryFormat raw = new StubFormat("raw", "/raw/", null);
    // A coordinate-bearing format with an ecosystem and a mark - the shape a browse hit resolves against.
    private final RepositoryFormat cargo = new StubLayoutFormat("cargo", "/cargo/", "crates.io", CARGO_MARK);
    // A coordinate-bearing format with an ecosystem and no mark - the shape every bundled format has today.
    private final RepositoryFormat maven = new StubLayoutFormat("maven", "/maven/", "Maven", null);

    private final FormatMarks marks = new FormatMarks(List.of(npm, raw, cargo, maven));

    @Test
    void a_namespace_resolves_to_the_mark_of_the_format_that_claims_its_request_prefix() {
        assertThat(marks.forNamespace("npm")).hasValueSatisfying(mark -> {
            assertThat(mark.kind()).isEqualTo(Mark.Kind.DECLARED);
            assertThat(mark.svg()).isEqualTo(NPM_MARK);
            assertThat(mark.name()).isEqualTo("npm");
        });
        assertThat(marks.forNamespace("cargo")).hasValueSatisfying(mark ->
                assertThat(mark.svg()).isEqualTo(CARGO_MARK));
    }

    @Test
    void a_format_that_claims_a_namespace_but_ships_no_mark_resolves_to_its_generated_figure() {
        // Not empty and not the neutral glyph: the format IS installed, so the answer names it. Emptiness is
        // reserved for "nothing installed claims this", which is a different fact with a different rendering.
        assertThat(marks.forNamespace("maven")).hasValueSatisfying(mark -> {
            assertThat(mark.kind()).isEqualTo(Mark.Kind.GENERATED);
            assertThat(mark.svg()).isEqualTo(Marks.generated("maven").svg());
            assertThat(mark.installed()).isTrue();
        });
    }

    @Test
    void a_namespace_no_installed_format_claims_is_empty_so_the_caller_decides_what_it_means() {
        // The plumbing buckets and a namespace whose format module is gone are indistinguishable HERE - both are
        // "no installed format claims this" - and they render completely differently. Resolving that ambiguity in
        // this class would force one of the two callers to be wrong, so it is deliberately left to them.
        assertThat(marks.forNamespace("blobs")).isEmpty();
        assertThat(marks.forNamespace("publish")).isEmpty();
        assertThat(marks.forNamespace("gem")).isEmpty();
    }

    @Test
    void an_ecosystem_resolves_through_every_format_that_declares_one_whichever_way_it_stores() {
        // A browse hit carries the ecosystem of its coordinate. Both layout families declare one: a format under the
        // published tree and a format in its own blobs namespace are equally installed, and an operator must never
        // read "not installed" beside a format that is. A format that declares no ecosystem is not a candidate
        // however its namespace is spelled.
        assertThat(marks.forEcosystem("crates.io")).hasValueSatisfying(mark ->
                assertThat(mark.svg()).isEqualTo(CARGO_MARK));
        assertThat(marks.forEcosystem("Maven")).hasValueSatisfying(mark ->
                assertThat(mark.kind()).isEqualTo(Mark.Kind.GENERATED));
        assertThat(marks.forEcosystem("npm")).hasValueSatisfying(mark -> {
            assertThat(mark.svg()).isEqualTo(NPM_MARK);
            assertThat(mark.installed()).isTrue();
        });
        assertThat(marks.forEcosystem("raw")).isEmpty();
        assertThat(marks.forEcosystem("nothing-installed")).isEmpty();
    }

    @Test
    void a_deployment_with_no_formats_claims_nothing_at_all() {
        // The console without any format module on its graph: every namespace and every ecosystem is unclaimed, and
        // nothing here invents a mark for them.
        FormatMarks empty = new FormatMarks(List.of());

        assertThat(empty.forNamespace("npm")).isEmpty();
        assertThat(empty.forEcosystem("crates.io")).isEmpty();
    }

    @Test
    void a_marks_bytes_are_resolved_once_per_key_and_then_memoized_not_per_row() {
        // A repository list renders a mark per repository per namespace and a browse search one per hit, so the same
        // key is resolved over and over. Each key must scan the format list and decode the constant SVG bytes once,
        // and serve the memoized answer thereafter.
        AtomicInteger resolutions = new AtomicInteger();
        RepositoryFormat counting = new StubLayoutFormat("gem", "/gem/", "rubygems", CARGO_MARK) {
            @Override
            public Optional<IconResource> icon() {
                resolutions.incrementAndGet();
                return super.icon();
            }
        };
        FormatMarks marks = new FormatMarks(List.of(counting));

        Optional<Mark> namespace = marks.forNamespace("gem");
        for (int row = 0; row < 50; row++) {
            assertThat(marks.forNamespace("gem")).isSameAs(namespace);
        }
        assertThat(resolutions.get()).as("51 list rows sharing one namespace resolved its mark once").isEqualTo(1);

        Optional<Mark> ecosystem = marks.forEcosystem("rubygems");
        for (int hit = 0; hit < 50; hit++) {
            assertThat(marks.forEcosystem("rubygems")).isSameAs(ecosystem);
        }
        assertThat(resolutions.get()).as("the ecosystem is memoized independently, adding exactly one").isEqualTo(2);
    }

    private static class StubFormat implements RepositoryFormat {

        private final String name;
        private final String prefix;
        private final String mark;

        StubFormat(String name, String prefix, String mark) {
            this.name = name;
            this.prefix = prefix;
            this.mark = mark;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean handles(String path) {
            return path.startsWith(prefix);
        }

        @Override
        public void serve(FormatExchange exchange, ArtifactStore store) {
            throw new UnsupportedOperationException("the mark lookup dispatches no request");
        }

        @Override
        public Optional<IconResource> icon() {
            return Optional.ofNullable(mark).map(IconResource::svg);
        }
    }

    private static class StubEcosystemFormat extends StubFormat implements EcosystemLayout {
        private final String ecosystem;

        StubEcosystemFormat(String name, String prefix, String ecosystem, String mark) {
            super(name, prefix, mark);
            this.ecosystem = ecosystem;
        }

        @Override
        public String ecosystem() {
            return ecosystem;
        }
    }

    private static class StubLayoutFormat extends StubFormat implements ArtifactLayout {

        private final String ecosystem;

        StubLayoutFormat(String name, String prefix, String ecosystem, String mark) {
            super(name, prefix, mark);
            this.ecosystem = ecosystem;
        }

        @Override
        public String ecosystem() {
            return ecosystem;
        }

        @Override
        public Optional<ArtifactDescriptor> describe(String path) {
            return Optional.empty();
        }

        @Override
        public List<String> paths(String coordinate, String version, ArtifactStore store) {
            return List.of();
        }
    }
}
