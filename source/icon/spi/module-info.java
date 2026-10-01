/**
 * The icon-contributor SPI: the seam through which any plug-in family lends the console a small mark, and the shared
 * rules the console resolves it with. {@link build.jenesis.repository.icon.IconContributor} is the interface a family's
 * SPI extends; {@link build.jenesis.repository.icon.IconResource} is a contributor's embedded SVG; and
 * {@link build.jenesis.repository.icon.Marks} / {@link build.jenesis.repository.icon.Mark} resolve a contributor, or a
 * bare recorded name, into the inline document a console renders.
 *
 * <p>It is a module of its own because two unrelated families contribute marks - repository formats and the plug-ins
 * that contribute findings - and neither may depend on the other; the seam sits below both, {@code java.base}-only, and
 * discovers nothing.
 *
 * <p>A brand mark lives in the module that contributes it, with its source and licence beside it. This module holds
 * only the two documents that belong to no contributor - the neutral fallback and the generated scheme - both original
 * CC0 line glyphs drawn for this project.
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.icon {
    exports build.jenesis.repository.icon;
}
