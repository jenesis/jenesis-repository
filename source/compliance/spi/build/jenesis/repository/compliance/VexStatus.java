package build.jenesis.repository.compliance;

import module java.base;

/**
 * The applicability a VEX (Vulnerability Exploitability eXchange) statement asserts for one vulnerability against one
 * product - the four states OpenVEX and CSAF share. {@link #NOT_AFFECTED} and {@link #FIXED} are the two that make a
 * matched advisory <em>non-applicable</em> at the gate ({@link #suppresses()}): the code is not reachable, or the
 * product already carries the fix, so a finding the advisory would otherwise raise is downgraded rather than blocking.
 * {@link #AFFECTED} and {@link #UNDER_INVESTIGATION} assert nothing that suppresses, so a statement in either state
 * leaves the advisory to the gate's normal dimensions (and un-suppresses an older not-affected claim it supersedes).
 */
public enum VexStatus {

    /** The product is not affected by the vulnerability (the vulnerable code is absent or unreachable) - suppresses. */
    NOT_AFFECTED,

    /** The product is affected by the vulnerability - asserts nothing that suppresses. */
    AFFECTED,

    /** The product carries a fix for the vulnerability - suppresses. */
    FIXED,

    /** The vulnerability's applicability to the product is still being investigated - asserts nothing that suppresses. */
    UNDER_INVESTIGATION;

    /** Whether a statement in this status makes a matched advisory non-applicable at the gate: {@code not_affected}
     *  (the vulnerable code is not present or reachable) and {@code fixed} (the product carries the fix) do; the two
     *  open states do not. */
    public boolean suppresses() {
        return this == NOT_AFFECTED || this == FIXED;
    }

    /** Map an OpenVEX {@code status} or a CSAF {@code product_status} key to this enum. OpenVEX names the four states
     *  directly ({@code not_affected}, {@code affected}, {@code fixed}, {@code under_investigation}); CSAF uses
     *  {@code known_not_affected}, {@code known_affected} / {@code first_affected} / {@code last_affected},
     *  {@code fixed} / {@code first_fixed} and {@code under_investigation}. Anything unrecognised (a CSAF
     *  {@code recommended}, a future state) is {@code null}, so a caller skips a statement it cannot classify rather
     *  than mis-suppressing on it. Case- and separator-insensitive (a hyphen or space reads as an underscore). */
    public static VexStatus fromLabel(String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        return switch (label.strip().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_')) {
            case "not_affected", "known_not_affected" -> NOT_AFFECTED;
            case "affected", "known_affected", "first_affected", "last_affected" -> AFFECTED;
            case "fixed", "first_fixed" -> FIXED;
            case "under_investigation" -> UNDER_INVESTIGATION;
            default -> null;
        };
    }
}
