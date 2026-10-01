package build.jenesis.repository.maintenance;

import module java.base;

/**
 * Reports a rejected {@code key=value} once per process, since the task list is re-resolved on every
 * settings-convergence tick. Static because the announcement is per process, not per dial instance.
 */
final class Announced {

    private static final Set<String> ANNOUNCED = ConcurrentHashMap.newKeySet();

    private Announced() {
    }

    /** Whether {@code key=value} is being reported for the first time. */
    static boolean first(String key, String value) {
        return ANNOUNCED.add(key + "=" + value);
    }
}
