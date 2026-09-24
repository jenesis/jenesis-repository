package build.jenesis.repository.compliance.policy;

import module java.base;
import module org.slf4j;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

/**
 * One compiled policy expression, evaluated against a subject's {@link PolicyInput} variables in a locked-down sandbox.
 * The expression language is SpEL (a maintained expression evaluator, so no policy grammar is hand-rolled), but it is
 * evaluated through {@link SimpleEvaluationContext#forReadOnlyDataBinding()} - a context that permits property access,
 * relational and boolean operators, collection selection and regular-expression {@code matches}, and forbids Java type
 * references ({@code T(...)}), constructors and method invocation. So an operator-authored rule can read the subject's
 * facts and combine them, but cannot reach a class, call a method or execute code - the policy surface is data-in,
 * boolean-out, never a code-execution vector even though the rule text is operator configuration.
 *
 * <p>A rule that does not parse throws at construction, so a live settings rebuild rejects a malformed policy and rolls
 * back (the {@code GatePolicyProvider.create} contract) rather than wedging the running gate. A rule that parses but
 * fails at evaluation (a reference to an unknown variable used in an arithmetic comparison, say) does <em>not</em> match
 * and logs one line, so a single broken rule can never block every upload - it simply enforces nothing until fixed.
 */
final class PolicyExpression {

    private static final Logger LOGGER = LoggerFactory.getLogger(PolicyExpression.class);

    private static final SpelExpressionParser PARSER = new SpelExpressionParser();

    private final String text;
    private final Expression expression;

    /** Compile {@code text} as a SpEL boolean expression; throws {@link IllegalArgumentException} when it does not
     *  parse, so the caller's {@code create} rejects a bad rule up front. */
    PolicyExpression(String text) {
        this.text = text;
        try {
            this.expression = PARSER.parseExpression(text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("A policy rule is not a valid expression: " + text, e);
        }
    }

    String text() {
        return text;
    }

    /** Whether the expression holds for a subject's variables. A non-boolean result or an evaluation error is a
     *  non-match (logged), so a broken rule enforces nothing rather than gating everything. */
    boolean matches(Map<String, Object> variables) {
        // A fresh read-only context per evaluation: the sandbox that forbids type references, constructors and method
        // invocation, so a rule can only read the bound variables and combine them.
        SimpleEvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding().build();
        variables.forEach(context::setVariable);
        try {
            Boolean result = expression.getValue(context, Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (RuntimeException e) {
            LOGGER.warn(
                    "Policy rule did not evaluate, enforcing nothing for it this pass: " + text, e);
            return false;
        }
    }
}
