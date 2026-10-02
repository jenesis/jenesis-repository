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

    /** The value as a person reads it, in its unit - see {@link #display(double)}. */
    public String displayValue() {
        return display(value);
    }

    /** The limit as a person reads it, in its unit, or empty without one. */
    public String displayLimit() {
        return limit.isPresent() ? display(limit.getAsDouble()) : "";
    }

    /** {@code amount} as a person reads it: bytes in binary units ("4.0 GiB"), any other number with its thousands
     *  grouped and without a fraction it does not have ("4,294,967,296 s"), followed by the unit. */
    public String display(double amount) {
        if (unit.equals("bytes")) {
            String[] units = {"B", "KiB", "MiB", "GiB", "TiB", "PiB"};
            double scaled = amount;
            int index = 0;
            while (Math.abs(scaled) >= 1024 && index < units.length - 1) {
                scaled /= 1024;
                index++;
            }
            return index == 0 ? String.format(Locale.ROOT, "%,.0f B", scaled)
                    : String.format(Locale.ROOT, "%.1f %s", scaled, units[index]);
        }
        String number = amount == Math.rint(amount) && Math.abs(amount) < 1e15
                ? String.format(Locale.ROOT, "%,d", (long) amount)
                : String.format(Locale.ROOT, "%,.2f", amount);
        return unit.isEmpty() ? number : number + " " + unit;
    }

    /** The fraction of the limit the value occupies ({@code 0..1+}); empty without a positive limit. */
    public OptionalDouble usage() {
        return limit.isPresent() && limit.getAsDouble() > 0
                ? OptionalDouble.of(value / limit.getAsDouble())
                : OptionalDouble.empty();
    }
}
