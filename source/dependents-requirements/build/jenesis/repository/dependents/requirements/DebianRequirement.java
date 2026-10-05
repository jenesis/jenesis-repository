package build.jenesis.repository.dependents.requirements;

import module java.base;

/**
 * Debian's version relation, as a control file's {@code Depends} writes it inside its parentheses: {@code >=},
 * {@code <=}, {@code =}, {@code >>} strictly later and {@code <<} strictly earlier, the deprecated {@code >} and
 * {@code <} reading as {@code >=} and {@code <=} as dpkg reads them. Ordered as dpkg orders, epoch first and a tilde
 * before anything.
 */
final class DebianRequirement extends SchemeRequirement {

    private static final Pattern RELATION = Pattern.compile("(>=|<=|>>|<<|=|>|<)\\s*(\\S+)");

    DebianRequirement() {
        super("deb");
    }

    @Override
    Optional<List<List<Bound>>> read(String requirement) {
        Matcher matcher = RELATION.matcher(requirement.strip());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(List.of(List.of(new Bound(switch (matcher.group(1)) {
            case ">>" -> Op.GT;
            case "<<" -> Op.LT;
            case "=" -> Op.EQ;
            case ">=", ">" -> Op.GE;
            default -> Op.LE;
        }, matcher.group(2)))));
    }
}
