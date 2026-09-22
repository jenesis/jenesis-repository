package build.jenesis.repository.compliance;

/**
 * What the gate decides about an artifact, ordered by strength so an assessment can keep the strongest verdict
 * across all of its findings. {@code ALLOW} publishes normally; {@code QUARANTINE} stores the artifact but
 * withholds it from resolution until a reviewer clears it; {@code REJECT} refuses the upload outright.
 */
public enum Verdict {

    ALLOW,

    QUARANTINE,

    REJECT
}
