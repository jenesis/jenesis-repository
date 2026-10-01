package build.jenesis.repository.compliance.policy;

import module java.base;
import build.jenesis.repository.compliance.Verdict;

/**
 * One policy-as-code rule: a compiled {@link PolicyExpression} over a subject's facts and the {@link Verdict} raised
 * when it holds - {@code reject #severityRank >= 4 and #reachable} - composing with every other dimension as the
 * built-in ones do. Parsed from one {@code policy-rules} line, {@code <verdict> <expression>}.
 */
record PolicyRule(Verdict verdict, PolicyExpression expression) {

    /** Parse one rule line: the first token is the verdict ({@code allow}, {@code quarantine}, {@code reject}), the
     *  rest the expression. {@link IllegalArgumentException} on a missing or unknown verdict, a missing expression or
     *  one that does not compile, so a malformed policy rolls back. */
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
