/**
 * The one ceiling an <em>inherited</em> whole-collection default refuses past, and the refusal itself. An SPI whose
 * paged or streaming leg ships a {@code default} implemented over a whole-list abstract sibling hands every
 * implementation that says nothing a body that materialises the deployment's data to answer a bounded question; the
 * free core settled that shape on {@code ArtifactStore.page} by keeping the default and making it
 * <em>fail visibly</em> - past {@code ArtifactStore.MAX_INHERITED_CHILDREN} rows it throws, naming the inheriting
 * class and the override that fixes it, rather than degrading silently on a large store. This module is that ruling
 * as one reusable call, so every SPI that copies the idiom shares one ceiling, one message and one failure mode
 * instead of a hand-rolled copy of the check per contract (the shape found and removed elsewhere).
 *
 * <p><strong>It declares no bound of its own.</strong> The number is
 * {@code ArtifactStore.MAX_INHERITED_CHILDREN}, read from there, so there is exactly one such ceiling in the product
 * and it cannot drift; this module only applies it away from the store's own key space.
 *
 * <p>{@code java.base} plus the {@code java.base}-light store contract, because every SPI that needs the
 * rule is itself a minimal-dependency contract module that must stay light.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.bounds {
    // The ceiling itself lives on ArtifactStore; nothing this module exports mentions a store type,
    // so the dependency stays non-transitive.
    requires build.jenesis.repository.store;
    exports build.jenesis.repository.bounds;
}
