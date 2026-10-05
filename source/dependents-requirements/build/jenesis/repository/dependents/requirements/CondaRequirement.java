package build.jenesis.repository.dependents.requirements;

import module java.base;

/**
 * conda's version spec, as a match spec carries it after the package name: alternatives separated by {@code |}, each
 * comma-separated clauses all of which hold - the orderings, {@code ==} and {@code !=}, {@code ~=} the compatible
 * release, a trailing {@code .*} or {@code *} a prefix, and a bare version the fuzzy match conda reads it as
 * ({@code 1.11} admits {@code 1.11.2}). A build string after the version is not a version constraint and is ignored.
 *
 * <p>No library carries conda's own version order on this module's path, so versions are ordered by versatile's
 * generic scheme: numeric segments compare as numbers, which orders the release versions conda packages carry, while
 * conda's special treatment of a letter segment ({@code 1.0a} before {@code 1.0}) is not reproduced.
 */
final class CondaRequirement extends SchemeRequirement {

    private static final Pattern CLAUSE = Pattern.compile("(==|!=|>=|<=|~=|>|<)?\\s*([^\\s,|]+)");

    CondaRequirement() {
        super("generic");
    }

    @Override
    Optional<List<List<Bound>>> read(String requirement) {
        String spec = requirement.strip().split("\\s+")[0];
        List<List<Bound>> alternatives = new ArrayList<>();
        for (String alternative : spec.split("\\|")) {
            List<Bound> bounds = new ArrayList<>();
            for (String clause : alternative.split(",")) {
                Matcher matcher = CLAUSE.matcher(clause.strip());
                if (!matcher.matches()) {
                    return Optional.empty();
                }
                String operator = matcher.group(1);
                String version = matcher.group(2);
                boolean wildcard = version.endsWith("*");
                String prefix = wildcard ? version.substring(0, version.length() - 1) : version;
                prefix = prefix.endsWith(".") ? prefix.substring(0, prefix.length() - 1) : prefix;
                if (operator == null || operator.equals("==") && wildcard) {
                    bounds.add(new Bound(Op.PREFIX, prefix));
                } else if (operator.equals("!=")) {
                    bounds.add(new Bound(wildcard ? Op.NOT_PREFIX : Op.NE, prefix));
                } else if (operator.equals("~=")) {
                    List<String> release = numericSegments(version);
                    if (release.size() < 2) {
                        return Optional.empty();
                    }
                    bounds.add(new Bound(Op.GE, version));
                    bounds.add(new Bound(Op.PREFIX, String.join(".", release.subList(0, release.size() - 1))));
                } else if (wildcard) {
                    return Optional.empty();
                } else {
                    bounds.add(new Bound(switch (operator) {
                        case "==" -> Op.EQ;
                        case ">=" -> Op.GE;
                        case "<=" -> Op.LE;
                        case ">" -> Op.GT;
                        default -> Op.LT;
                    }, version));
                }
            }
            alternatives.add(bounds);
        }
        return Optional.of(alternatives);
    }
}
