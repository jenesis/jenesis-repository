package build.jenesis.repository.ui.store;

import module java.base;

import build.jenesis.repository.store.Durations;

/**
 * Reads and validates a project's {@code cache.properties} for the well-known keys the cache
 * server understands: {@code size} (total byte cap), {@code lru} (eviction order) and {@code ttl}
 * (idle expiry, an ISO-8601 duration). Operates over the {@link Properties} the storage backend
 * returns. Parsing mirrors {@code Cache.project} / {@code Cache.ttl}.
 */
public final class CacheConfig {

    public static final String FILE = "cache.properties";

    private CacheConfig() {
    }

    public static String size(Properties properties) {
        return orEmpty(properties.getProperty("size"));
    }

    public static boolean lru(Properties properties) {
        String value = properties.getProperty("lru");
        return value == null || !value.trim().equalsIgnoreCase("false");
    }

    public static String ttl(Properties properties) {
        return orEmpty(properties.getProperty("ttl"));
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /** Validate and apply submitted form values; throws {@link IllegalArgumentException} on bad input. */
    public static void apply(Properties properties, String size, String lru, String ttl) {
        if (size != null && !size.isBlank()) {
            long parsed;
            try {
                parsed = Long.parseLong(size.trim());
            } catch (NumberFormatException _) {
                throw new IllegalArgumentException("Size must be a whole number of bytes.");
            }
            if (parsed < 0) {
                throw new IllegalArgumentException("Size must not be negative.");
            }
            properties.setProperty("size", Long.toString(parsed));
        } else {
            properties.remove("size");
        }

        properties.setProperty("lru", lru != null && lru.trim().equalsIgnoreCase("false") ? "false" : "true");

        if (ttl != null && !ttl.isBlank()) {
            Duration duration;
            try {
                duration = Durations.parse(ttl);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("TTL must be a duration, e.g. P30D, 30d or PT12H.");
            }
            if (duration.isNegative() || duration.isZero()) {
                throw new IllegalArgumentException("TTL must be a positive duration.");
            }
            properties.setProperty("ttl", ttl.trim());
        } else {
            properties.remove("ttl");
        }
    }

    /** The configured size cap in bytes, 0 when none is set. A value {@link #apply} would have refused - one written
     *  into the file by hand - is refused here too, naming it, rather than read as "no cap" and left to grow. */
    public static long sizeBytes(Properties properties) {
        String value = properties.getProperty("size");
        if (value == null || value.isBlank()) {
            return 0;
        }
        long parsed;
        try {
            parsed = Long.parseLong(value.trim());
        } catch (NumberFormatException _) {
            throw new IllegalArgumentException(FILE + " sets size=" + value.trim() + ", which is not a whole number "
                    + "of bytes; the size cap is not enforced until it is fixed");
        }
        if (parsed < 0) {
            throw new IllegalArgumentException(FILE + " sets size=" + value.trim() + ", which is negative; the size "
                    + "cap is not enforced until it is fixed");
        }
        return parsed;
    }

    /** The configured ttl, or {@code null} when none is set. A value {@link #apply} would have refused - one written
     *  into the file by hand - is refused here too, naming it: read as "unset" it silently switched stale-entry
     *  expiry off, and a typo became the default with nothing said. */
    public static Duration ttlDuration(Properties properties) {
        String value = properties.getProperty("ttl");
        if (value == null || value.isBlank()) {
            return null;
        }
        Duration duration;
        try {
            duration = Durations.parse(value);
        } catch (RuntimeException _) {
            throw new IllegalArgumentException(FILE + " sets ttl=" + value.trim() + ", which is not a duration "
                    + "(P30D, 30d, PT12H); stale entries are not expired until it is fixed");
        }
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(FILE + " sets ttl=" + value.trim() + ", which is not a positive "
                    + "duration; stale entries are not expired until it is fixed");
        }
        return duration;
    }
}
