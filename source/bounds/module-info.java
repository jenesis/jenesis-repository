/**
 * The ceiling an inherited whole-collection default refuses past, and the refusal. An SPI whose paged or streaming leg
 * ships a default over a whole-list sibling hands every silent implementation a body that materialises the deployment's
 * data; as {@code ArtifactStore.page} does, the default stays and fails visibly past
 * {@code ArtifactStore.MAX_INHERITED_CHILDREN} rows, naming the inheriting class and the override. One reusable call,
 * so every SPI shares one ceiling, message and failure mode. It declares no bound of its own, and depends only on
 * {@code java.base} and the light store contract, since the SPIs needing it are minimal contract modules.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.bounds {
    // The ceiling lives on ArtifactStore; nothing exported mentions a store type, so the dependency is non-transitive.
    requires build.jenesis.repository.store;
    exports build.jenesis.repository.bounds;
}
