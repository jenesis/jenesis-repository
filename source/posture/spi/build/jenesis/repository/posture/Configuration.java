package build.jenesis.repository.posture;

import module java.base;

/**
 * The effective deployment configuration a {@link SafetyAdvisor} reads - a {@code java.base} view of the
 * {@code jenrepo.*} key space, so an advisor never touches Spring. The distribution wraps the Spring
 * {@code Environment} ({@code Configuration.of(environment::getProperty)}), so every key resolves in its relaxed
 * environment-variable spelling too; a test builds one from a map. The typed helpers read through {@link #value}.
 *
 * <p>An advisor reads a value only to decide whether to advise; it never copies one into an advisory's text.
 */
@FunctionalInterface
public interface Configuration {

    /** The effective value of {@code key}, or {@code null} when it is unset. */
    String value(String key);

    /** The value of {@code key}, or {@code null} - as an {@link Optional}, empty when unset or blank. */
    default Optional<String> optional(String key) {
        String value = value(key);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }

    /** Whether {@code key} is set to a non-blank value. */
    default boolean isSet(String key) {
        return optional(key).isPresent();
    }

    /** {@code key} parsed as a boolean, or {@code defaultValue} when unset: {@code true} only for a literal
     *  {@code true}, anything else is {@code false}. */
    default boolean flag(String key, boolean defaultValue) {
        Optional<String> value = optional(key);
        return value.map(v -> v.equalsIgnoreCase("true")).orElse(defaultValue);
    }

    /** {@code key} parsed as a long, or {@code defaultValue} when unset or unparseable. */
    default long number(String key, long defaultValue) {
        Optional<String> value = optional(key);
        if (value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.get());
        } catch (NumberFormatException _) {
            return defaultValue;
        }
    }

    /** A configuration over a plain {@code key -> value} lookup. */
    static Configuration of(UnaryOperator<String> lookup) {
        Objects.requireNonNull(lookup, "lookup");
        return lookup::apply;
    }

    /** A configuration over a fixed map. */
    static Configuration ofMap(Map<String, String> values) {
        Map<String, String> copy = Map.copyOf(values);
        return copy::get;
    }
}
