/**
 * The icon-contributor SPI: the one seam through which <em>any</em> plug-in family lends the console a small mark of
 * its own, and the shared rendering rules the console resolves it with. It carries three types and no marks of its
 * own:
 * {@link build.jenesis.repository.icon.IconContributor} - the interface a family's SPI <em>extends</em> so its
 * implementations may declare a mark, together with the identity the console attributes one to;
 * {@link build.jenesis.repository.icon.IconResource} - the mark itself, a self-contained SVG document a contributor
 * embeds in its own module; and {@link build.jenesis.repository.icon.Marks} / {@link
 * build.jenesis.repository.icon.Mark} - the resolution helper that turns a contributor (or a bare recorded name)
 * into the inline document a console renders, with the neutral fallback and the deterministic generated-from-name
 * scheme held once here.
 *
 * <p><strong>Why a module of its own.</strong> Two unrelated families contribute marks - repository formats
 * ({@code RepositoryFormat}, which extends {@link build.jenesis.repository.icon.IconContributor}) and the plug-ins
 * that contribute findings (advisory feeds, inspectors, gate policies, classifiers, scan markers). Neither may
 * depend on the other, and a console that resolved each family through its own copy of the fallback, the rendering
 * rule and the generated scheme would be the parallel mechanism the shared-infrastructure rule (&sect;2) exists to
 * refuse: the second family would arrive not with a different scheme but with a second one. The seam therefore sits
 * below both, is {@code java.base}-only, and is registry-free - it discovers nothing, so it never becomes a second
 * discovery pipeline beside the family clauses that already find these implementations.
 *
 * <p><strong>The core stays mark-agnostic.</strong> A brand mark lives in the module that contributes it, with its
 * source and licence recorded next to that module. The only documents this module holds are the two that belong to
 * no contributor: the <em>neutral</em> fallback rendered where nothing markable was identified at all, and the
 * <em>generated</em> mark derived from a contributor's own name. Both are original CC0 line glyphs drawn for this
 * project - a claim that stands on its own, where a pointer to a file would be a reference with a scheduled
 * expiry.
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.icon {
    exports build.jenesis.repository.icon;
}
