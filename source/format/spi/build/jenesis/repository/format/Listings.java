package build.jenesis.repository.format;

import module java.base;

/**
 * Helpers for the listing documents a format serves as HTML.
 *
 * <p>A listing page carries names that came from a publisher, so they are escaped before they are written into it.
 * The PyPI Simple index and the raw directory listing each escaped with their own copy of the same switch: a
 * character handled in one and overlooked in the other is an injection into whichever page was not updated.
 */
public final class Listings {

    private Listings() {
    }

    public static String html(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
