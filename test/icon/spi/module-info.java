/**
 * Focused unit tests for the icon-contributor SPI - the seam every plug-in family lends the console a mark through -
 * exercised without a store, a console or any network: the {@link build.jenesis.repository.icon.Marks} resolution of
 * a contributor into one of its three answers (a declared mark, an installed contributor's generated figure, and an
 * <em>orphaned</em> name nothing answers to any more), the neutral fallback that stands for no contributor at all,
 * and the generated scheme's two load-bearing properties: that it is deterministic across renders, restarts and JVMs
 * (pinned against a golden document, so a platform that computed a different one fails here), and that it does not
 * collide across a realistic contributor set (measured over one, not assumed).
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.icon
 * @jenesis.bom pin-repository.properties
 */
open module build.jenesis.repository.icon.test {
    requires build.jenesis.repository.icon;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
