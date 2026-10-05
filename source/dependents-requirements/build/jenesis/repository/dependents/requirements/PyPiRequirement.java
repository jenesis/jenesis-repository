package build.jenesis.repository.dependents.requirements;

import module java.base;

/**
 * PyPI's requirement grammar, the version specifiers of PEP 440: comma-separated clauses all of which hold, each an
 * operator and a version - {@code ~=} the compatible release ({@code ~=1.4.5} is {@code >=1.4.5} and {@code ==1.4.*}),
 * {@code ==} and {@code !=} with an optional {@code .*} prefix match, the four orderings, and {@code ===} the arbitrary
 * string equality. Ordered by PEP 440, so {@code 1.0} equals {@code 1.0.0} and {@code 1.0rc1} precedes {@code 1.0}. A
 * direct reference ({@code @ <url>}) names no version and answers unknown.
 */
final class PyPiRequirement extends SchemeRequirement {

    private static final Pattern CLAUSE = Pattern.compile("(~=|===|==|!=|<=|>=|<|>)\\s*([^\\s,]+)");

    PyPiRequirement() {
        super("pypi");
    }

    @Override
    Optional<List<List<Bound>>> read(String requirement) {
        List<Bound> bounds = new ArrayList<>();
        for (String clause : requirement.split(",")) {
            Matcher matcher = CLAUSE.matcher(clause.strip());
            if (!matcher.matches()) {
                return Optional.empty();
            }
            String operator = matcher.group(1);
            String version = matcher.group(2);
            boolean wildcard = version.endsWith(".*");
            String prefix = wildcard ? version.substring(0, version.length() - 2) : version;
            switch (operator) {
                case "~=" -> {
                    List<String> release = numericSegments(version);
                    if (wildcard || release.size() < 2) {
                        return Optional.empty();
                    }
                    bounds.add(new Bound(Op.GE, version));
                    bounds.add(new Bound(Op.PREFIX, String.join(".", release.subList(0, release.size() - 1))));
                }
                case "==" -> bounds.add(new Bound(wildcard ? Op.PREFIX : Op.EQ, prefix));
                case "!=" -> bounds.add(new Bound(wildcard ? Op.NOT_PREFIX : Op.NE, prefix));
                case "===" -> bounds.add(new Bound(Op.IDENTICAL, version));
                default -> {
                    if (wildcard) {
                        return Optional.empty();
                    }
                    bounds.add(new Bound(switch (operator) {
                        case "<=" -> Op.LE;
                        case ">=" -> Op.GE;
                        case "<" -> Op.LT;
                        default -> Op.GT;
                    }, version));
                }
            }
        }
        return Optional.of(List.of(bounds));
    }
}
