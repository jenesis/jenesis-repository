package build.jenesis.repository.dependents.requirements;

import module java.base;

/**
 * NuGet's version ranges: interval notation ({@code [1.0,2.0)}, an open end left empty, {@code [1.0]} exactly that
 * version), a bare version the minimum it names ({@code 1.0} is {@code >=1.0}), and a floating version
 * ({@code 1.*}) every version continuing its prefix. Ordered as NuGet orders versions, so {@code 1.0} equals
 * {@code 1.0.0}.
 */
final class NuGetRequirement extends SchemeRequirement {

    NuGetRequirement() {
        super("nuget");
    }

    @Override
    Optional<List<List<Bound>>> read(String requirement) {
        String range = requirement.replace(" ", "");
        char open = range.charAt(0);
        if (open == '[' || open == '(') {
            char close = range.charAt(range.length() - 1);
            if (range.length() < 3 || (close != ']' && close != ')')) {
                return Optional.empty();
            }
            String inner = range.substring(1, range.length() - 1);
            int comma = inner.indexOf(',');
            if (comma < 0) {
                return open == '[' && close == ']' ? Optional.of(List.of(List.of(new Bound(Op.EQ, inner))))
                        : Optional.empty();
            }
            List<Bound> bounds = new ArrayList<>();
            String lower = inner.substring(0, comma);
            String upper = inner.substring(comma + 1);
            if (!lower.isEmpty()) {
                bounds.add(new Bound(open == '[' ? Op.GE : Op.GT, lower));
            }
            if (!upper.isEmpty()) {
                bounds.add(new Bound(close == ']' ? Op.LE : Op.LT, upper));
            }
            return Optional.of(List.of(bounds));
        }
        if (range.endsWith("*")) {
            String prefix = range.substring(0, range.length() - 1);
            return Optional.of(List.of(prefix.isEmpty() ? List.of()
                    : List.of(new Bound(Op.PREFIX, prefix.endsWith(".") ? prefix.substring(0, prefix.length() - 1)
                            : prefix))));
        }
        return Optional.of(List.of(List.of(new Bound(Op.GE, range))));
    }
}
