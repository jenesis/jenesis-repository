package build.jenesis.repository.dependents.requirements;

import module java.base;

/**
 * RubyGems' requirement grammar: comma-separated clauses all of which hold, each an operator and a version - the
 * orderings, {@code =} and {@code !=} (a bare version is {@code =}), and {@code ~>} the pessimistic constraint, at
 * least the version and below its bump ({@code ~> 1.2.3} is {@code >= 1.2.3, < 1.3}, {@code ~> 1.2} is
 * {@code >= 1.2, < 2}). Ordered as {@code Gem::Version} orders, a letter segment preceding the release.
 */
final class RubyGemsRequirement extends SchemeRequirement {

    private static final Pattern CLAUSE = Pattern.compile("(~>|>=|<=|!=|=|>|<)?\\s*(\\S+)");

    RubyGemsRequirement() {
        super("gem");
    }

    @Override
    Optional<List<List<Bound>>> read(String requirement) {
        List<Bound> bounds = new ArrayList<>();
        for (String clause : requirement.split(",")) {
            Matcher matcher = CLAUSE.matcher(clause.strip());
            if (!matcher.matches()) {
                return Optional.empty();
            }
            String operator = matcher.group(1) == null ? "=" : matcher.group(1);
            String version = matcher.group(2);
            if (operator.equals("~>")) {
                List<String> release = new ArrayList<>(numericSegments(version));
                if (release.isEmpty()) {
                    return Optional.empty();
                }
                if (release.size() > 1) {
                    release.removeLast();
                }
                release.set(release.size() - 1, new BigInteger(release.getLast()).add(BigInteger.ONE).toString());
                bounds.add(new Bound(Op.GE, version));
                bounds.add(new Bound(Op.LT, String.join(".", release)));
                continue;
            }
            bounds.add(new Bound(switch (operator) {
                case ">=" -> Op.GE;
                case "<=" -> Op.LE;
                case "!=" -> Op.NE;
                case ">" -> Op.GT;
                case "<" -> Op.LT;
                default -> Op.EQ;
            }, version));
        }
        return Optional.of(List.of(bounds));
    }
}
