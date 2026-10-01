package build.jenesis.repository.cache.storage;

import module java.base;

import build.jenesis.repository.store.Durations;

/**
 * A build-cache project's policy - its byte cap, which entries a size-cap sweep evicts first, and how long an unused
 * entry is kept - as the three project settings and the one parse of each. The build cache applies it on writes and on
 * its reaper's clock, the console on an operator's sweep; both read the project's effective settings and parse them
 * here.
 *
 * @param size the byte cap, {@code 0} for none
 * @param lru whether a size-cap sweep evicts the least recently used entries first (else the most recently used)
 * @param ttl how long an unused entry is kept, {@code null} for ever
 */
public record ProjectPolicy(long size, boolean lru, Duration ttl) {

    /** The size cap's setting key. */
    public static final String SIZE = "project-size";

    /** The sweep order's setting key. */
    public static final String LRU = "project-lru";

    /** The unused-entry lifetime's setting key. */
    public static final String TTL = "project-ttl";

    /** No cap, least recently used first, kept for ever - a project nothing configures. */
    public static final ProjectPolicy NONE = new ProjectPolicy(0, true, null);

    /**
     * The policy {@code config} - a project's effective settings, {@code null} for an unset key - describes.
     *
     * @throws IllegalArgumentException naming the first value that does not parse
     */
    public static ProjectPolicy of(UnaryOperator<String> config) {
        return new ProjectPolicy(size(config.apply(SIZE)), lru(config.apply(LRU)), ttl(config.apply(TTL)));
    }

    /** A size cap: a non-negative whole number of bytes, {@code 0} - or unset - for none. */
    public static long size(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        long parsed;
        try {
            parsed = Long.parseLong(value.trim());
        } catch (NumberFormatException _) {
            throw new IllegalArgumentException(SIZE + " is '" + value.trim() + "', which is not a whole number of bytes");
        }
        if (parsed < 0) {
            throw new IllegalArgumentException(SIZE + " is '" + value.trim() + "', which is negative");
        }
        return parsed;
    }

    /** The sweep order: least recently used first unless the value is {@code false}. */
    public static boolean lru(String value) {
        return value == null || !value.trim().equalsIgnoreCase("false");
    }

    /** An unused-entry lifetime: a positive duration, or {@code null} when unset or {@link Durations#NONE}. */
    public static Duration ttl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Optional<Duration> parsed;
        try {
            parsed = Durations.parseOrNone(value);
        } catch (RuntimeException _) {
            throw new IllegalArgumentException(TTL + " is '" + value.trim() + "', which is not a duration (P30D, 30d, "
                    + "PT12H) or none");
        }
        if (parsed.isPresent() && (parsed.get().isZero() || parsed.get().isNegative())) {
            throw new IllegalArgumentException(TTL + " is '" + value.trim() + "', which is not a positive duration");
        }
        return parsed.orElse(null);
    }
}
