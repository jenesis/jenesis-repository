package build.jenesis.repository.icon.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.icon.IconContributor;
import build.jenesis.repository.icon.IconResource;
import build.jenesis.repository.icon.Mark;
import build.jenesis.repository.icon.Marks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shared resolution every contributing plug-in family renders through: a contributor's own mark where it
 * declares one, its generated figure where it does not, the orphan figure for a name nothing answers to any more,
 * and the neutral glyph for the case where there is no contributor to ask at all. Four answers, and the middle two
 * are the pair that must never collapse into one.
 */
class MarksTest {

    private static final String NPM_MARK =
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" stroke=\"currentColor\"/>";

    /** An installed contributor that ships a mark - the shape a format or a findings plug-in with a brand has. */
    private record Branded(String name, String document) implements IconContributor {
        @Override
        public Optional<IconResource> icon() {
            return Optional.of(IconResource.svg(document));
        }
    }

    /** An installed contributor that ships none, which is every core format today: it inherits the default. */
    private record Plain(String name) implements IconContributor {
    }

    @Test
    void a_contributor_that_declares_a_mark_resolves_to_its_own_document() {
        // The document is the contributor's, byte for byte: the core renders what the module declared and never
        // substitutes, re-encodes or wraps it.
        Mark mark = Marks.of(new Branded("npm", NPM_MARK));

        assertThat(mark.kind()).isEqualTo(Mark.Kind.DECLARED);
        assertThat(mark.svg()).isEqualTo(NPM_MARK);
        assertThat(mark.name()).isEqualTo("npm");
        assertThat(mark.installed()).isTrue();
    }

    @Test
    void a_contributor_that_declares_none_resolves_to_its_generated_figure_not_to_the_neutral_glyph() {
        // The distinction the whole scheme exists for: an installed plug-in with no brand still gets a mark OF ITS
        // OWN, so a page of them attributes something. Collapsing this onto the neutral glyph would make every
        // unbranded plug-in look like every other one - and like a row with no contributor at all.
        Mark mark = Marks.of(new Plain("maven"));

        assertThat(mark.kind()).isEqualTo(Mark.Kind.GENERATED);
        assertThat(mark.svg()).isEqualTo(Marks.generated("maven").svg()).isNotEqualTo(Marks.neutral());
        assertThat(mark.installed()).isTrue();
    }

    @Test
    void an_orphaned_name_keeps_the_figure_and_changes_the_tile() {
        // A finding outlives the plug-in that produced it, so all that is left is the recorded name. It draws the
        // same figure - the row stays recognisable as that plug-in's - inside a dashed tile, which is the whole
        // difference between "declares no mark" and "is not installed".
        Mark generated = Marks.generated("secret-scan");
        Mark orphaned = Marks.orphaned("secret-scan");

        assertThat(orphaned.kind()).isEqualTo(Mark.Kind.ORPHANED);
        assertThat(orphaned.installed()).isFalse();
        assertThat(orphaned.svg())
                .isNotEqualTo(generated.svg())
                .contains("stroke-dasharray=\"3 2.5\"");
        assertThat(generated.svg()).doesNotContain("stroke-dasharray");
        // Everything but the tile is identical: strip the tile line from each and the two figures coincide.
        assertThat(withoutTile(orphaned)).isEqualTo(withoutTile(generated));
    }

    @Test
    void the_three_states_are_distinguishable_without_a_single_colour() {
        // The constraint the downstream console's colour treatment sits on top of: with the palette taken away, the
        // three answers are still three. The drawings differ (a declared document, a solid tile, a dashed tile), the
        // kinds differ, and the text a title/aria-label carries differs - so a reader that never sees a colour, and
        // a reader that never sees an image, both still learn which state a row is in.
        List<Mark> marks = List.of(
                Marks.of(new Branded("npm", NPM_MARK)),
                Marks.of(new Plain("npm")),
                Marks.orphaned("npm"));

        assertThat(marks).extracting(Mark::svg).doesNotHaveDuplicates();
        assertThat(marks).extracting(Mark::kind)
                .containsExactly(Mark.Kind.DECLARED, Mark.Kind.GENERATED, Mark.Kind.ORPHANED);
        assertThat(marks).extracting(Mark::title).containsExactly("npm", "npm", "npm (not installed)");
        // No mark names a colour at all: every stroke and fill is currentColor, so the console owns the palette and
        // a mark can never be the thing that fixes it.
        assertThat(marks).allSatisfy(mark -> assertThat(mark.svg()).doesNotContain("#").doesNotContain("rgb("));
    }

    @Test
    void the_neutral_glyph_stands_for_no_contributor_and_inverts_with_the_theme() {
        // Rendered where nothing markable was identified at all. It is not a Mark, because a Mark always names a
        // contributor - and it is currentColor, so it sizes and inverts exactly like every other mark on the page.
        assertThat(Marks.neutral()).startsWith("<svg").contains("currentColor").contains("viewBox=\"0 0 24 24\"");
    }

    @Test
    void a_declared_marks_bytes_are_decoded_as_the_utf8_document_the_contributor_wrote() {
        // The rendering rule: a mark is inlined, not fetched as an image, which is what lets its currentColor inherit
        // the surrounding text colour. Non-ASCII survives the round trip, so a contributor's document is served as
        // written rather than mangled by the platform's default charset.
        String document = "<svg xmlns=\"http://www.w3.org/2000/svg\"><title>café — naïve</title></svg>";

        assertThat(Marks.render(IconResource.svg(document))).isEqualTo(document);
        assertThat(IconResource.svg(document).mediaType()).isEqualTo(IconResource.SVG_MEDIA_TYPE);
    }

    @Test
    void a_contributor_with_no_name_is_refused_rather_than_sharing_an_unknown_mark() {
        // Two differently-named things sharing one figure is the mis-attribution this type exists to prevent, so an
        // absent name fails loudly instead of resolving to a shared placeholder.
        assertThatThrownBy(() -> Marks.generated("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Marks.orphaned("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Marks.of(new Plain("\t"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_contributor_declares_no_mark_by_default_so_a_family_forces_none_on_its_implementations() {
        // The default is what lets RepositoryFormat - and the findings seam after it - extend IconContributor
        // without a single existing implementation changing.
        assertThat(new Plain("raw").icon()).isEmpty();
    }

    private static String withoutTile(Mark mark) {
        return Arrays.stream(mark.svg().split("\n"))
                .filter(line -> !line.contains("x=\"1.5\""))
                .collect(Collectors.joining("\n"));
    }
}
