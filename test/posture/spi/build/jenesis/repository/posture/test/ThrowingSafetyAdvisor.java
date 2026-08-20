package build.jenesis.repository.posture.test;

import build.jenesis.repository.posture.Configuration;
import build.jenesis.repository.posture.SafetyAdvisor;
import build.jenesis.repository.posture.SecurityAdvisory;

import module java.base;

/**
 * An advisor that breaks the contract the way a real one breaks it - a condition it cannot parse, a lookup that blows
 * up - by throwing instead of answering. It is <em>not</em> {@code provides}-declared: what is under test is the
 * collected report, not what {@code ServiceLoader} finds.
 *
 * <p>Its message deliberately carries text a posture surface must never echo, because that surface enumerates a
 * deployment's weaknesses and would turn a posture read into a credential read.
 */
public final class ThrowingSafetyAdvisor implements SafetyAdvisor {

    /** The message the failure carries - the report may name the exception type, never this. */
    public static final String SECRET = "jenreg.admin-password=hunter2";

    @Override
    public List<SecurityAdvisory> advise(Configuration config) {
        throw new IllegalStateException(SECRET);
    }
}
