/**
 * The store root's key-space contract: the names of the product's own spaces, the {@code tenants/} space every
 * user-chosen scope lives under, and the shape rule a scope name must satisfy.
 *
 * <p>It exists so that "where does this go in the store" is answered in one place rather than as a string literal in
 * each module that writes there. It used to answer a harder question - which top-level names are reserved, so that a
 * tenant cannot be called one - in four copies of one {@code Set.of(...)} across three modules, each carrying a "must
 * stay in sync" comment. The copies drifted exactly where a comment cannot enforce anything, and the console listed
 * {@code audit} as a tenant with working Open and Delete controls. Giving user-chosen names a space of their own
 * retired the question: nothing under {@code tenants/} can collide with a space that is not under it.
 *
 * <p>{@code java.base} only - names and a shape rule, no store - so every module that writes into the store can
 * share it without pulling in any weight, and so the store SPI itself can use it without a cycle. The store keeps
 * bytes under keys and is deliberately ignorant of what the keys mean; this says what they mean, and nothing else. It sits in the free core because the layout it describes is the free
 * product's own, and both editions write into it.
 *
 * @jenesis.release 25
 */
module build.jenesis.repository.scope {
    exports build.jenesis.repository.scope;
}
