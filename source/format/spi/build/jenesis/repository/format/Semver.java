package build.jenesis.repository.format;

import module java.base;

/**
 * Semantic-version ordering, for the formats whose registries define one.
 *
 * <p>A leading {@code v} and {@code +build} metadata are ignored in precedence; release components compare
 * numerically where both sides are numeric and lexically otherwise, a missing component reads as zero, and a
 * pre-release sorts below the same release, identifier by identifier.
 *
 * <p>The npm, Go and NuGet registries all specify this ordering, and each format carried its own verbatim copy of
 * it. Three copies of a comparator is three chances for two ecosystems to disagree about which of two versions is
 * newer - which is not a cosmetic disagreement, since the answer decides what {@code latest} resolves to.
 */
public final class Semver {

    private Semver() {
    }


    public static int compare(String left, String right) {
        String a = normalizeVersion(left);
        String b = normalizeVersion(right);
        String aMain = a;
        String aPre = "";
        int aDash = a.indexOf('-');
        if (aDash >= 0) {
            aMain = a.substring(0, aDash);
            aPre = a.substring(aDash + 1);
        }
        String bMain = b;
        String bPre = "";
        int bDash = b.indexOf('-');
        if (bDash >= 0) {
            bMain = b.substring(0, bDash);
            bPre = b.substring(bDash + 1);
        }
        int main = compareDotted(aMain, bMain, true);
        if (main != 0) {
            return main;
        }
        if (aPre.isEmpty() || bPre.isEmpty()) {
            return Boolean.compare(aPre.isEmpty(), bPre.isEmpty());   // no prerelease outranks a prerelease
        }
        return compareDotted(aPre, bPre, false);
    }

    private static int compareDotted(String left, String right, boolean release) {
        String[] a = left.split("\\.", -1);
        String[] b = right.split("\\.", -1);
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            boolean aHas = i < a.length;
            boolean bHas = i < b.length;
            if (!release) {
                if (!aHas) {
                    return -1;
                }
                if (!bHas) {
                    return 1;
                }
            }
            int cmp = compareIdentifier(aHas ? a[i] : "0", bHas ? b[i] : "0");
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private static String normalizeVersion(String version) {
        String v = version;
        if (!v.isEmpty() && (v.charAt(0) == 'v' || v.charAt(0) == 'V')) {
            v = v.substring(1);
        }
        int plus = v.indexOf('+');
        return plus < 0 ? v : v.substring(0, plus);
    }

    private static int compareIdentifier(String a, String b) {
        boolean aNum = isNumeric(a);
        boolean bNum = isNumeric(b);
        if (aNum && bNum) {
            return new BigInteger(a).compareTo(new BigInteger(b));
        }
        if (aNum != bNum) {
            return aNum ? -1 : 1;   // a numeric identifier ranks below an alphanumeric one (SemVer)
        }
        return a.compareTo(b);
    }

    private static boolean isNumeric(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
