package build.jenesis.repository.compliance.policy;

import module java.base;
import module org.slf4j;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

/**
 * One compiled policy expression, evaluated against a subject's {@link PolicyInput} variables in a sandbox. The
 * language is SpEL, evaluated through {@link SimpleEvaluationContext#forReadOnlyDataBinding()}, which permits property
 * access, relational and boolean operators, collection selection and {@code matches}, and forbids type references
 * ({@code T(...)}), constructors and method invocation - so an operator's rule reads and combines facts but cannot
 * reach a class or execute code.
 *
 * <p>A rule that does not parse throws at construction, so a live settings rebuild rejects it and rolls back (the
 * {@code GatePolicyProvider.create} contract). A rule that fails at evaluation (an unknown variable in an arithmetic
 * comparison, say) does not match and logs one line, so a broken rule enforces nothing rather than blocking every
 * upload.
 */
final class PolicyExpression {

    private static final Logger LOGGER = LoggerFactory.getLogger(PolicyExpression.class);

    private static final SpelExpressionParser PARSER = new SpelExpressionParser();

    private final String text;
    private final Expression expression;

    /** Compile {@code text} as a SpEL boolean expression; {@link IllegalArgumentException} when it does not parse. */
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

    /** Whether the expression holds for a subject's variables; a non-boolean result or an evaluation error is a logged
     *  non-match. */
    boolean matches(Map<String, Object> variables) {
        // A fresh read-only sandbox context per evaluation, so a rule only reads the bound variables.
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
