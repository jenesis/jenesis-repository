/**
 * The store's key-space contract: the names of the product's own spaces and the shape rule a scope name must satisfy,
 * so "where does this go in the store" is answered in one place. No name is reserved: the product's data sits in a
 * space no user-chosen name can spell.
 *
 * <p>{@code java.base} only - names and a shape rule, no store - so every module that writes into the store, and the
 * store SPI itself, can use it without weight or a cycle. The store keeps bytes under keys and does not know what the
 * keys mean; this says what they mean.
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.scope {
    exports build.jenesis.repository.scope;
}
