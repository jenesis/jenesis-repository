package build.jenesis.repository.format.homebrew;

import module java.base;

/**
 * A bottle's file name, which is the whole of its coordinate: {@code <name>-<version>.<tag>.bottle.tar.gz}, with an
 * optional rebuild number before the extension ({@code hello-2.12.3.x86_64_linux.bottle.1.tar.gz}). The tag is
 * Homebrew's word for a platform ({@code x86_64_linux}, {@code arm64_sequoia}, {@code all}) and is the stem's last
 * dot-separated component, since a tag carries no dot. Name and version split at the last hyphen followed by a digit,
 * as in the Helm format: a formula name may contain hyphens ({@code python-tk}) and a version begins with a digit.
 */
record Bottle(String name, String version, String tag, String rebuild) {

    private static final String SUFFIX = ".tar.gz";

    private static final String MARKER = ".bottle";

    /** The file name this bottle is served and published as. */
    String file() {
        return name + "-" + version + "." + tag + MARKER + (rebuild.isEmpty() ? "" : "." + rebuild) + SUFFIX;
    }

    /** Parse a bottle file name, or empty when it is not one. */
    static Optional<Bottle> of(String file) {
        if (!file.endsWith(SUFFIX)) {
            return Optional.empty();
        }
        String stem = file.substring(0, file.length() - SUFFIX.length());
        String rebuild = "";
        int marker = stem.lastIndexOf(MARKER);
        if (marker < 0) {
            return Optional.empty();
        }
        String after = stem.substring(marker + MARKER.length());
        if (!after.isEmpty()) {
            if (after.charAt(0) != '.' || !isNumber(after.substring(1))) {
                return Optional.empty();
            }
            rebuild = after.substring(1);
        }
        stem = stem.substring(0, marker);
        int dot = stem.lastIndexOf('.');
        if (dot <= 0 || dot == stem.length() - 1) {
            return Optional.empty();
        }
        String tag = stem.substring(dot + 1);
        String coordinate = stem.substring(0, dot);
        for (int at = coordinate.lastIndexOf('-'); at > 0; at = coordinate.lastIndexOf('-', at - 1)) {
            if (at + 1 < coordinate.length() && Character.isDigit(coordinate.charAt(at + 1))) {
                return Optional.of(new Bottle(coordinate.substring(0, at), coordinate.substring(at + 1), tag,
                        rebuild));
            }
        }
        return Optional.empty();
    }

    private static boolean isNumber(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int at = 0; at < value.length(); at++) {
            if (!Character.isDigit(value.charAt(at))) {
                return false;
            }
        }
        return true;
    }
}
