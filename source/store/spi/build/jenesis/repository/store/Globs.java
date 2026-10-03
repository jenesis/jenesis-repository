package build.jenesis.repository.store;

import module java.base;

/**
 * The one glob an operator writes into a setting - a fallback's {@code match=}, a redirect rule, a trusted token's
 * subject: every character is literal except {@code *}, which matches any run including none, so {@code com.foo.*}
 * and {@code @corp/*} each select a namespace. Two settings that name the same glob select the same coordinates.
 */
public final class Globs {

    private Globs() {
    }

    /** {@code glob} compiled to a pattern anchored at both ends. Not cached: a rule set is small. */
    public static Pattern compile(String glob) {
        StringBuilder regex = new StringBuilder("^");
        String[] literals = glob.split("\\*", -1);
        for (int index = 0; index < literals.length; index++) {
            if (index > 0) {
                regex.append(".*");
            }
            if (!literals[index].isEmpty()) {
                regex.append(Pattern.quote(literals[index]));
            }
        }
        return Pattern.compile(regex.append('$').toString());
    }
}
