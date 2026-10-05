package build.jenesis.repository.dependents.requirements;

import module java.base;

/**
 * Alpine's version constraint, as a {@code .PKGINFO} {@code depend} line writes it after the name: {@code =},
 * {@code <}, {@code >}, {@code <=}, {@code >=}, and {@code ~} the fuzzy match, every version continuing the one it
 * names. Ordered as apk orders, the {@code -r} package release last.
 */
final class AlpineRequirement extends SchemeRequirement {

    private static final Pattern CONSTRAINT = Pattern.compile("(>=|<=|=|>|<|~)\\s*(\\S+)");

    AlpineRequirement() {
        super("alpine");
    }

    @Override
    Optional<List<List<Bound>>> read(String requirement) {
        Matcher matcher = CONSTRAINT.matcher(requirement.strip());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(List.of(List.of(new Bound(switch (matcher.group(1)) {
            case ">=" -> Op.GE;
            case "<=" -> Op.LE;
            case ">" -> Op.GT;
            case "<" -> Op.LT;
            case "~" -> Op.PREFIX;
            default -> Op.EQ;
        }, matcher.group(2)))));
    }
}
