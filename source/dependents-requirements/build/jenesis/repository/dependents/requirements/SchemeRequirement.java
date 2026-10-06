package build.jenesis.repository.dependents.requirements;

import module java.base;
import io.github.nscuro.versatile.VersException;
import io.github.nscuro.versatile.VersionFactory;
import io.github.nscuro.versatile.spi.Version;

/**
 * A requirement grammar whose comparisons are one ecosystem's version order as versatile implements it - the order the
 * ecosystem's own tooling sorts by: PEP 440 for PyPI, NuGet's, RubyGems', dpkg's, rpm's, apk's and Go's. A subclass
 * reads its ecosystem's spelling of a requirement into {@link Bound}s, alternatives of conjunctions, and nothing
 * else: the reading is the grammar's, the order is the library's.
 *
 * <p>A requirement a subclass cannot read, and a version or bound the scheme does not parse, answer
 * {@link Requirements.Verdict#UNKNOWN}.
 */
abstract class SchemeRequirement implements Requirements.Grammar {

    /** How a bound compares a version. {@link #PREFIX} admits the version it names and every version continuing it
     *  past a separator ({@code 1.2} admits {@code 1.2}, {@code 1.2.5} and {@code 1.2rc1}, never {@code 1.20}). */
    enum Op {
        LT, LE, GT, GE, EQ, NE, PREFIX, NOT_PREFIX, IDENTICAL
    }

    /** One comparator of a requirement. */
    record Bound(Op op, String version) {
    }

    private final String scheme;

    SchemeRequirement(String scheme) {
        this.scheme = scheme;
    }

    /** {@code requirement} as alternatives, each a conjunction of bounds - an empty conjunction admitting every
     *  version - or empty where this grammar cannot read it. */
    abstract Optional<List<List<Bound>>> read(String requirement);

    /** The version {@code bound} compares, {@code version} itself unless the ecosystem compares less of it. */
    String compared(String version, Bound bound) {
        return version;
    }

    @Override
    public final Requirements.Verdict admits(String requirement, String version) {
        Optional<List<List<Bound>>> alternatives = read(requirement);
        if (alternatives.isEmpty()) {
            return Requirements.Verdict.UNKNOWN;
        }
        try {
            for (List<Bound> conjunction : alternatives.get()) {
                boolean holds = true;
                for (Bound bound : conjunction) {
                    holds &= holds(bound, compared(version, bound));
                }
                if (holds) {
                    return Requirements.Verdict.ADMITS;
                }
            }
            return Requirements.Verdict.EXCLUDES;
        } catch (IllegalArgumentException unparsed) {
            return Requirements.Verdict.UNKNOWN;
        }
    }

    private boolean holds(Bound bound, String version) {
        return switch (bound.op()) {
            case PREFIX -> prefixed(version, bound.version());
            case NOT_PREFIX -> !prefixed(version, bound.version());
            case IDENTICAL -> version.equals(bound.version());
            case LT -> order(scheme, version, bound.version()) < 0;
            case LE -> order(scheme, version, bound.version()) <= 0;
            case GT -> order(scheme, version, bound.version()) > 0;
            case GE -> order(scheme, version, bound.version()) >= 0;
            case EQ -> order(scheme, version, bound.version()) == 0;
            case NE -> order(scheme, version, bound.version()) != 0;
        };
    }

    /** Whether {@code version} is {@code prefix} or continues it past a separator. */
    static boolean prefixed(String version, String prefix) {
        if (prefix.isEmpty() || version.equals(prefix)) {
            return true;
        }
        if (!version.startsWith(prefix)) {
            return false;
        }
        // A prefix ending in a separator ("1.") continues with anything; one ending in a digit only past a non-digit.
        return !Character.isDigit(prefix.charAt(prefix.length() - 1))
                || !Character.isDigit(version.charAt(prefix.length()));
    }

    /** Two versions in {@code scheme}'s order, raising an {@link IllegalArgumentException} for one it does not
     *  parse. */
    static int order(String scheme, String left, String right) {
        Version parsedLeft;
        Version parsedRight;
        try {
            parsedLeft = VersionFactory.forScheme(scheme, left);
            parsedRight = VersionFactory.forScheme(scheme, right);
        } catch (VersException unserved) {
            throw new IllegalArgumentException(unserved.getMessage(), unserved);
        }
        return Integer.signum(parsedLeft.compareTo(parsedRight));
    }

    /** The release segments of {@code version} up to its first that is not a number, which a pessimistic or
     *  compatible-release operator bumps. */
    static List<String> numericSegments(String version) {
        List<String> segments = new ArrayList<>();
        for (String segment : version.split("\\.")) {
            if (segment.isEmpty() || !segment.chars().allMatch(Character::isDigit)) {
                break;
            }
            segments.add(segment);
        }
        return segments;
    }
}
