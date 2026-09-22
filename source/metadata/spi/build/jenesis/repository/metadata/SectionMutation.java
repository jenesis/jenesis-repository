package build.jenesis.repository.metadata;

import module java.base;

/**
 * A section-scoped transform, applied inside the store's read-transform-compare-and-set loop. It sees only its own
 * section's current envelope ({@link Optional#empty()} when the section was never derived) and returns the new one;
 * every other section in the document is carried through the commit untouched (§5.2), so an unknown or newer
 * writer's section is never dropped by a mutator that does not own it.
 *
 * <p>Because a concurrent writer can lose the CAS token and force a re-read, the transform may be invoked more than
 * once for a single logical mutation - it must be a pure function of {@code current}, re-derivable each attempt
 * (the same discipline {@code StoreFindings}' row mutation follows). Returning {@code null} leaves the section
 * absent, or removes it if it was present.
 */
@FunctionalInterface
public interface SectionMutation {

    /** Transform the section's current envelope into its new one; {@code null} leaves the section absent. */
    Section apply(Optional<Section> current);
}
