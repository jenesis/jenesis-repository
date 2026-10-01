package build.jenesis.repository.posture;

import module java.base;

/**
 * The naming grammar every posture advisory id shares - the {@code jenrepo.<feature>.<signal>} convention of the
 * configuration keys and the observation {@code Signals}, so an id reads like the setting it is about. An id is
 * validated at construction ({@link SecurityAdvisory} calls {@link #require}): it is a build-time constant, so a broken
 * one is a bug to fail on rather than a string to sanitise.
 */
public final class Advisories {

    /** The full grammar: dot-separated lowercase segments under the {@code jenrepo} root. */
    public static final Pattern ID = Pattern.compile("^jenrepo(\\.[a-z][a-z0-9]*)+$");

    private Advisories() {
    }

    /** Whether {@code id} is a well-formed advisory id. */
    public static boolean valid(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /** Return {@code id} when well-formed, else throw {@link IllegalArgumentException}. */
    public static String require(String id) {
        if (!valid(id)) {
            throw new IllegalArgumentException("Not a jenrepo.<feature>.<signal> advisory id: " + id);
        }
        return id;
    }
}
