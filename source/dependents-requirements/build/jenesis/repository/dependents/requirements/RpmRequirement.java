package build.jenesis.repository.dependents.requirements;

import module java.base;

/**
 * RPM's version relation as a header's requires carry it: an operator - {@code <}, {@code >}, {@code =}, {@code <=},
 * {@code >=} - and an {@code [epoch:]version[-release]}, a bare one meaning {@code =}. Ordered as {@code rpmvercmp}
 * orders, and as rpm compares, a requirement naming no release compares a version's without its release, so
 * {@code = 1.0} admits {@code 1.0-3}.
 */
final class RpmRequirement extends SchemeRequirement {

    private static final Pattern RELATION = Pattern.compile("(<=|>=|<|>|=)?\\s*(\\S+)");

    RpmRequirement() {
        super("rpm");
    }

    @Override
    Optional<List<List<Bound>>> read(String requirement) {
        Matcher matcher = RELATION.matcher(requirement.strip());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String operator = matcher.group(1) == null ? "=" : matcher.group(1);
        return Optional.of(List.of(List.of(new Bound(switch (operator) {
            case "<=" -> Op.LE;
            case ">=" -> Op.GE;
            case "<" -> Op.LT;
            case ">" -> Op.GT;
            default -> Op.EQ;
        }, matcher.group(2)))));
    }

    @Override
    String compared(String version, Bound bound) {
        int release = version.lastIndexOf('-');
        return bound.version().indexOf('-') < 0 && release > 0 ? version.substring(0, release) : version;
    }
}
