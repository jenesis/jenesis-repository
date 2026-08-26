package build.jenesis.repository.format;

import module java.base;

/**
 * The OCI Distribution tag grammar: {@code [a-zA-Z0-9_][a-zA-Z0-9._-]{0,127}}.
 *
 * <p>It lives in the SPI because two modules need it and neither can see the other's copy. The free OCI format
 * exports its package only to its own test, so the enterprise OCI inventory layout - which resolves
 * {@code oci/<name>/tags/<ref>} store keys and must reject a reference carrying {@code /} or {@code ..} before it
 * addresses a neighbouring space - could not reach it and carried its own transcription of the same rule.
 */
public final class OciTags {

    /** The longest tag the grammar allows: one leading character plus 127 more. */
    private static final int MAX_LENGTH = 128;

    private OciTags() {
    }

    /** Whether {@code reference} is a well-formed tag rather than a digest or a malformed reference. */
    public static boolean isTag(String reference) {
        int length = reference.length();
        if (length == 0 || length > MAX_LENGTH) {
            return false;
        }
        for (int index = 0; index < length; index++) {
            char character = reference.charAt(index);
            boolean alphanumeric = (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9');
            if (index == 0 ? !alphanumeric && character != '_'
                    : !alphanumeric && character != '_' && character != '.' && character != '-') {
                return false;
            }
        }
        return true;
    }
}
