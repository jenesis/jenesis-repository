package build.jenesis.repository.dependents.requirements;

import module java.base;

/**
 * Go's requirement, a {@code go.mod} {@code require} line's version: the minimum the module asks for, which minimal
 * version selection raises only to a version another requirement in the build names. A closure has no such other
 * requirement to consult, so the minimum is taken as written - it admits the version it names, ordered as Go orders
 * module versions, and excludes every other.
 */
final class GoRequirement extends SchemeRequirement {

    GoRequirement() {
        super("golang");
    }

    @Override
    Optional<List<List<Bound>>> read(String requirement) {
        String version = requirement.strip();
        return version.isEmpty() || version.contains(" ") ? Optional.empty()
                : Optional.of(List.of(List.of(new Bound(Op.EQ, version))));
    }
}
