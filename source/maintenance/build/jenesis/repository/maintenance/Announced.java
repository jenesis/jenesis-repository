package build.jenesis.repository.maintenance;

import module java.base;

/**
 * The log-once idiom the dials share: a rejected {@code key=value} is reported the first time it is seen in this
 * process and never again, because the enabled task list is re-resolved on every settings-convergence tick and a
 * bad dial would otherwise print a line every thirty seconds. Static because the announcement is per deployment,
 * not per dial instance - the same idiom, and the same justification, as the feature-activation notice.
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
