package build.jenesis.repository.compliance.policy;

import module java.base;
import build.jenesis.repository.compliance.Verdict;

/**
 * One policy-as-code rule: a compiled {@link PolicyExpression} over a subject's facts and the {@link Verdict} to raise
 * when it holds. It generalises the fixed gate dimensions - instead of a built-in "reject at or above this CVSS band",
 * an operator writes {@code reject #severityRank >= 4 and #reachable} and the rule composes with every other discovered
 * dimension exactly as the built-in ones do. Parsed from one line of the {@code policy-rules} setting:
 * {@code <verdict> <expression>}, e.g. {@code quarantine #severityRank >= 3 and #reachable}.
 */
record PolicyRule(Verdict verdict, PolicyExpression expression) {

    /** Parse one rule line: the first whitespace-delimited token is the verdict ({@code allow} / {@code quarantine} /
     *  {@code reject}), the remainder the expression. Throws {@link IllegalArgumentException} on a missing or unknown
     *  verdict, a missing expression, or an expression that does not compile - so a live settings rebuild rejects a
     *  malformed policy and rolls back rather than wedging the gate. */
    static PolicyRule parse(String line) {
        String trimmed = line.strip();
        int split = 0;
        while (split < trimmed.length() && !Character.isWhitespace(trimmed.charAt(split))) {
            split++;
        }
        String verdictToken = trimmed.substring(0, split);
        String expression = split < trimmed.length() ? trimmed.substring(split).strip() : "";
        if (verdictToken.isEmpty() || expression.isEmpty()) {
            throw new IllegalArgumentException(
                    "A policy rule must read '<verdict> <expression>', e.g. 'reject #severityRank >= 4': " + line);
        }
        Verdict verdict;
        try {
            verdict = Verdict.valueOf(verdictToken.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException _) {
            throw new IllegalArgumentException("A policy rule's verdict must be allow, quarantine or reject: "
                    + verdictToken);
        }
        return new PolicyRule(verdict, new PolicyExpression(expression));
    }
}
