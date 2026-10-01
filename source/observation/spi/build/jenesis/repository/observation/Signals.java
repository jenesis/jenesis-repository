package build.jenesis.repository.observation;

import module java.base;

/**
 * The naming grammar every signal shares, so a health check, a metric and a task status read like the configuration
 * keys beside them: {@code jenrepo.<feature>.<signal...>}, so {@code jenrepo.gc.reclaimed.bytes} lines up with the
 * {@code jenrepo.gc} feature. A name breaking it is rejected at construction: a signal name is a build-time constant,
 * so a broken one is a bug to fail on, never a string to sanitise.
 */
public final class Signals {

    /** The full grammar: dot-separated lowercase segments under the {@code jenrepo} root. */
    public static final Pattern NAME = Pattern.compile("^jenrepo(\\.[a-z][a-z0-9]*)+$");

    private static final Pattern SEGMENT = Pattern.compile("[a-z][a-z0-9]*");

    private Signals() {
    }

    /** Compose {@code jenrepo.<feature>.<segments...>}, validating each segment; {@link IllegalArgumentException} when
     *  one is null, empty or not lowercase {@code [a-z][a-z0-9]*}. */
    public static String name(String feature, String... segments) {
        StringBuilder builder = new StringBuilder("jenrepo.").append(segment(feature));
        for (String segment : segments) {
            builder.append('.').append(segment(segment));
        }
        return builder.toString();
    }

    private static String segment(String segment) {
        if (segment == null || !SEGMENT.matcher(segment).matches()) {
            throw new IllegalArgumentException("Not a lowercase [a-z][a-z0-9]* signal segment: " + segment);
        }
        return segment;
    }

    /** Whether {@code name} is a well-formed signal name. */
    public static boolean valid(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** {@code name} when well-formed, else {@link IllegalArgumentException}: the guard a descriptor's constructor
     *  runs. */
    public static String require(String name) {
        if (!valid(name)) {
            throw new IllegalArgumentException("Not a jenrepo.<feature>.<signal> name: " + name);
        }
        return name;
    }
}
