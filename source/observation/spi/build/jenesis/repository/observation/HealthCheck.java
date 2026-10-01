package build.jenesis.repository.observation;

import module java.base;

/**
 * One self-describing health check: a {@code jenrepo.<feature>.<check>} {@code name}, the {@code description} Actuator
 * and the console show, a {@link Health} {@code status} and an optional plain-text {@code detail}, never a secret. The
 * name is validated against {@link Signals} at construction, so a bad one fails when built, not when scraped.
 */
public record HealthCheck(String name, String description, Health status, String detail) {

    public HealthCheck {
        Signals.require(name);
        description = Objects.requireNonNull(description, "description");
        status = Objects.requireNonNull(status, "status");
        detail = detail == null ? "" : detail;
    }

    /** An {@link Health#UP} check. */
    public static HealthCheck up(String name, String description) {
        return new HealthCheck(name, description, Health.UP, "");
    }

    /** A check reporting {@code status} with a plain-text {@code detail}. */
    public static HealthCheck of(String name, String description, Health status, String detail) {
        return new HealthCheck(name, description, status, detail);
    }
}
