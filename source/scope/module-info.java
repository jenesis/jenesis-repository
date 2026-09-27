/**
 * The store root's key-space contract: the names of the product's own spaces, the {@code tenants/} space every
 * user-chosen scope lives under, and the shape rule a scope name must satisfy.
 *
 * <p>It exists so that "where does this go in the store" is answered in one place rather than as a string literal in
 * each module that writes there. It does not answer the harder question - which top-level names are reserved, so
 * that a tenant cannot be called one - because giving the product's data a space of its own removes that question:
 * no user-chosen name can collide with a space it cannot spell.
 *
 * <p>{@code java.base} only - names and a shape rule, no store - so every module that writes into the store can
 * share it without pulling in any weight, and so the store SPI itself can use it without a cycle. The store keeps
 * bytes under keys and is deliberately ignorant of what the keys mean; this says what they mean, and nothing else. It sits here because the layout it describes is the free
 * product's own, and every composition writes into it.
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.scope {
    exports build.jenesis.repository.scope;
}
