/**
 * The shared artifact-walk SPI: one totally ordered, resumable, range-segmented, multi-node-aware enumeration of a
 * store's key space, kept separate from its implementation so the enumeration strategy can change without breaking a
 * consumer. Everywhere the whole artifact set must be enumerated - garbage collection, retention eviction, every
 * derived-metadata rebuild - goes through {@code ArtifactWalk}, never a private {@code list()} loop. A walk pass is
 * durable in the walked store itself ({@code walks/<consumer>/...}): each segment is one compare-and-set state object
 * embedding its claim ({@code range, state, holder, expiry, cursor}), so threads <em>and different VMs</em> take
 * disjoint segments, a checkpoint doubles as lease renewal, and a dead node's segment is reclaimed from its last
 * committed cursor - the walk never restarts from scratch. Delivery is exactly-once per pass in the absence of a
 * crash and at-least-once for the uncommitted stride tail after a crash-resume, so every consumer must be idempotent
 * per item. An implementation ships as its own module that {@code provides} a {@code WalkProvider}, discovered with
 * {@code ServiceLoader} and selected with {@code jenreg.walk=<name>}; with none installed
 * {@code WalkProvider.resolve} is empty and every walk-riding surface degrades gracefully, while a selection nothing
 * answers to fails at resolution rather than silently turning every sweep into a no-op (&sect;9). {@code WalkConsumer} is
 * the walk half of the two-route derived-metadata contract (steady state = publication events; back-fill, periodic
 * refresh and self-heal = the walk), discovered the same way and driven by the shared {@code RebuildPass} - one
 * enumeration of the pointer roots feeding every discovered consumer.
 *
 * <p>Beside the pass model the module owns the shared <em>traversal primitives</em> every scoped store enumeration
 * rides, so a format never hand-rolls a stack walk or a page loop again: {@code Trees} is the one iterative
 * (never recursive) depth-first descent over a store's key layout - the reference {@code ArtifactWalk} implementation
 * drives it too, so there is exactly one descent in the product; {@code PagedTreeWalk} adds depth / step / entry /
 * page caps and a continuation cursor to it for a request-scoped subtree walk; {@code BoundedChildren} is its flat
 * sibling for the many surfaces that enumerate one container (a search window, a version list, a marker space) and
 * are not subtree walks at all. All three answer in the {@code Traversal} vocabulary - {@code EXHAUSTED} or
 * {@code TRUNCATED} with a resume cursor, and a named {@code TraversalException} for the depth / step / hostile-segment
 * bounds that have no safe continuation - so a cap can never be mistaken for a complete listing. They are deliberately
 * here rather than in the {@code walk.store} implementation module: a serving surface adopts the bounds without
 * requiring, or being coupled to, a walk implementation, and the module stays {@code java.base} plus the store SPI.
 * {@code ScreenedNames} is the serving-side composition of the two SPIs this module already sits between: a bounded
 * child enumeration whose every name is judged by the store SPI's {@code ServableNames} seam before it is delivered,
 * so a format or console lists and screens in one call and cannot page a container and then forget the withhold
 * screen. It decides nothing itself - the disclosure verdict stays the store seam's - and it lives here because the
 * store SPI must not depend on the traversal primitives it is built from.
 *
 * @jenesis.release 25
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
 */
module build.jenesis.repository.walk {
    requires transitive build.jenesis.repository.store;
    exports build.jenesis.repository.walk;
    uses build.jenesis.repository.walk.WalkProvider;
    uses build.jenesis.repository.walk.WalkConsumer;
}
