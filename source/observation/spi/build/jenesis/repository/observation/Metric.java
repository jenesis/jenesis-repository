package build.jenesis.repository.observation;

import module java.base;

/**
 * One self-describing metric: a {@code jenrepo.<feature>.<signal>} {@code name}, a {@code description}, its
 * {@link Kind}, the {@code value}, an optional {@code limit} it is measured against (quota available, a rate budget, a
 * capacity) and a {@code unit} ({@code "bytes"}, {@code ""} for a bare count). The limit lets the console show used
 * against available without the reporter computing a percentage. The name is validated against {@link Signals}.
 */
public record Metric(String name, String description, Kind kind, double value, OptionalDouble limit, String unit) {

    /** Whether the metric only ever increases (a {@code COUNTER}) or reads a current level (a {@code GAUGE}). */
    public enum Kind { COUNTER, GAUGE }

    public Metric {
        Signals.require(name);
        description = Objects.requireNonNull(description, "description");
        kind = Objects.requireNonNull(kind, "kind");
        limit = limit == null ? OptionalDouble.empty() : limit;
        unit = unit == null ? "" : unit;
    }

    /** A monotonic counter with no ceiling. */
    public static Metric counter(String name, String description, double value, String unit) {
        return new Metric(name, description, Kind.COUNTER, value, OptionalDouble.empty(), unit);
    }

    /** A point-in-time gauge with no ceiling. */
    public static Metric gauge(String name, String description, double value, String unit) {
        return new Metric(name, description, Kind.GAUGE, value, OptionalDouble.empty(), unit);
    }

    /** A gauge measured against a {@code limit} - data used vs available. */
    public static Metric bounded(String name, String description, double used, double limit, String unit) {
        return new Metric(name, description, Kind.GAUGE, used, OptionalDouble.of(limit), unit);
    }

    /** The fraction of the limit the value occupies ({@code 0..1+}); empty without a positive limit. */
    public OptionalDouble usage() {
        return limit.isPresent() && limit.getAsDouble() > 0
                ? OptionalDouble.of(value / limit.getAsDouble())
                : OptionalDouble.empty();
    }
}
