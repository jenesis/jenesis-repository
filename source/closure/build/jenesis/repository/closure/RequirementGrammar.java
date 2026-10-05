package build.jenesis.repository.closure;

import module java.base;

/**
 * How one ecosystem states a requirement on a version, and how it orders versions: what a closure needs to take the
 * newest held version a dependency's requirement admits.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> An implementation is stateless and shared: the closure pass calls it concurrently.</li>
 *   <li><b>Absence sentinel.</b> An ecosystem no installed grammar names is evaluated by {@link #FALLBACK}, which
 *       admits a version only to a requirement that names nothing or names it exactly, and answers {@link
 *       Admission#UNKNOWN} to any range - so a closure records such a dependency as a cut rather than guessing.</li>
 *   <li><b>Read purity.</b> {@link #admits} and {@link #compare} read only their arguments; no I/O.</li>
 *   <li><b>Selection.</b> {@code ALL} grammars are discovered; two naming one ecosystem fail {@link #of} naming both,
 *       since which one evaluated a requirement would otherwise be an accident of the module path.</li>
 *   <li><b>Error visibility.</b> A requirement the grammar cannot parse is {@link Admission#UNKNOWN}, never an
 *       exception, and never an admission.</li>
 * </ol>
 */
public interface RequirementGrammar {

    /** The ecosystem whose requirements this grammar reads, in the canonical spelling a version's document uses. */
    String ecosystem();

    /** Whether {@code version} satisfies {@code requirement} as written. */
    Admission admits(String requirement, String version);

    /** The ecosystem's own order of two versions, negative where {@code left} is older. */
    int compare(String left, String right);

    /** A requirement's answer about one version. */
    enum Admission {
        ADMITS, EXCLUDES, UNKNOWN
    }

    /** The grammar for {@code ecosystem}, {@link #FALLBACK} where none is installed. */
    static RequirementGrammar of(String ecosystem) {
        return InstalledGrammars.GRAMMARS.getOrDefault(ecosystem, FALLBACK);
    }

    /**
     * The grammar an ecosystem with none of its own is read by: an empty requirement admits every version, a
     * requirement equal to a version admits that version and excludes the rest, and anything else - a range, an
     * operator, a placeholder - is {@link Admission#UNKNOWN}. Versions are ordered as {@link ModuleDescriptor.Version}
     * orders them, a numeric-aware comparison, and as text where one does not parse.
     */
    RequirementGrammar FALLBACK = new RequirementGrammar() {
        @Override
        public String ecosystem() {
            return "";
        }

        @Override
        public Admission admits(String requirement, String version) {
            String wanted = requirement == null ? "" : requirement.strip();
            if (wanted.isEmpty()) {
                return Admission.ADMITS;
            }
            if (wanted.equals(version)) {
                return Admission.ADMITS;
            }
            return wanted.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_')
                    ? Admission.EXCLUDES : Admission.UNKNOWN;
        }

        @Override
        public int compare(String left, String right) {
            try {
                return ModuleDescriptor.Version.parse(left).compareTo(ModuleDescriptor.Version.parse(right));
            } catch (IllegalArgumentException unparsed) {
                return left.compareTo(right);
            }
        }
    };
}
