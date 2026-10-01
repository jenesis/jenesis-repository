package build.jenesis.repository.metadata;

import module java.base;

/**
 * A section-scoped transform inside the store's read-transform-compare-and-set loop. It sees only its own section's
 * current envelope ({@link Optional#empty()} when never derived) and returns the new one; every other section is
 * carried untouched.
 *
 * <p>A lost CAS token forces a re-read, so the transform may run more than once for one mutation: it must be a pure
 * function of {@code current}. Returning {@code null} leaves the section absent, or removes it.
 */
@FunctionalInterface
public interface SectionMutation {

    /** Transform the section's current envelope into its new one; {@code null} leaves the section absent. */
    Section apply(Optional<Section> current);
}
