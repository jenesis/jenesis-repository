package build.jenesis.repository.compliance;

import module java.base;

/**
 * The one place the ecosystem of a coordinate is named, matched and taken apart: the canonical vocabulary a
 * {@code ComplianceGate.Subject} and an {@code ArtifactLayout} write an ecosystem in, the per-ecosystem
 * package-identifier case rules the coordinate matchers fold on, the primitive each feed declares <em>its</em>
 * vendor's ecosystem names through, and the split of a coordinate into the segments its ecosystem writes it in.
 *
 * <h2>The canonical vocabulary</h2>
 * A coordinate's ecosystem travels through this product as the OSV package-ecosystem name ({@code "Maven"},
 * {@code "npm"}, {@code "PyPI"}, {@code "crates.io"}), which is what every format's {@code ecosystem()} declares and
 * every inspector screens on. The names are constants here rather than string literals scattered over a dozen feed
 * modules because the failure mode of a misspelling is silent: a vendor map keyed on {@code "Pypi"} does not fail,
 * it merely never matches, and that ecosystem quietly stops being screened by that feed. {@link #vocabulary} refuses
 * a key outside {@link #canonical()} for the same reason - a typo becomes a loud construction failure instead of a
 * hole in coverage nobody sees.
 *
 * <h2>A vendor's own names stay with the vendor</h2>
 * What a feed maps a canonical name <em>to</em> is vendor knowledge and stays in the feed's module: GitHub calls PyPI
 * {@code pip}, deps.dev calls it {@code pypi} and Mend's agent model calls it {@code PYTHON}, and which ecosystems a
 * vendor covers at all differs feed by feed. This class owns the shared half - the canonical keys, the lookup
 * discipline, and the "absent means do not query" sentinel - so those genuine divergences stay visible as data in the
 * feed that has them rather than as five subtly different lookups.
 *
 * <h2>Identifier case</h2>
 * A handful of package registries resolve a package identifier case-insensitively - a name is the same package
 * fetched in any case - so a reservation or a version floor keyed on one casing must match every casing, or an
 * upstream namespace-shadow slips a public {@code contoso.internal} past a reserved {@code Contoso.Internal} (a
 * dependency-confusion bypass). The rest key their identifiers case-sensitively, where two casings are two distinct
 * packages, so folding case there would over-reserve a name the tenant never claimed - which is why this is a
 * per-ecosystem rule and not a blanket lower-casing. The case-insensitive set is the registries documented to fold
 * identifier case: NuGet (ids are stored lower-cased), PyPI (PEP 503 normalises a project name to lower case) and
 * Packagist/Composer (a {@code vendor/package} name is lower-cased). Ecosystems whose identifiers are case-sensitive
 * (Maven coordinates, crates.io, Go module paths, RubyGems) are absent, so their matchers keep exact-case semantics.
 */
public final class Ecosystems {

    /** Maven coordinates, written {@code group:artifact}. */
    public static final String MAVEN = "Maven";

    /** npm packages, written {@code name} or {@code @scope/name}. */
    public static final String NPM = "npm";

    /** Python distributions on PyPI. */
    public static final String PYPI = "PyPI";

    /** Go modules, written as the module path. */
    public static final String GO = "Go";

    /** NuGet packages. */
    public static final String NUGET = "NuGet";

    /** Ruby gems. */
    public static final String RUBYGEMS = "RubyGems";

    /** Rust crates. */
    public static final String CRATES_IO = "crates.io";

    /** PHP packages on Packagist, written {@code vendor/package}. */
    public static final String PACKAGIST = "Packagist";

    /** CocoaPods pods. */
    public static final String COCOAPODS = "CocoaPods";

    /** Conan packages. */
    public static final String CONAN = "Conan";

    /** conda packages. */
    public static final String CONDA = "conda";

    /** Debian packages. */
    public static final String DEBIAN = "Debian";

    /** RPM packages. */
    public static final String RPM = "RPM";

    /** Hugging Face models and datasets. */
    public static final String HUGGING_FACE = "Hugging Face";

    /** Every canonical ecosystem name, in the exact spelling a coordinate carries. */
    private static final Set<String> CANONICAL = Set.of(MAVEN, NPM, PYPI, GO, NUGET, RUBYGEMS, CRATES_IO, PACKAGIST,
            COCOAPODS, CONAN, CONDA, DEBIAN, RPM, HUGGING_FACE);

    /** Lower-cased label to its canonical spelling, so a mixed-case label ({@code "nuget"}, {@code "pypi"}) resolves
     *  to the one spelling every vocabulary is keyed on. */
    private static final Map<String, String> BY_LABEL = CANONICAL.stream()
            .collect(Collectors.toUnmodifiableMap(name -> name.toLowerCase(Locale.ROOT), name -> name));

    /** The ecosystems (lower-cased labels) whose registries resolve a package identifier case-insensitively. */
    private static final Set<String> CASE_INSENSITIVE = Set.of("nuget", "pypi", "packagist");

    private Ecosystems() {
    }

    /** Every canonical ecosystem name a coordinate may carry - the keys a feed's {@link #vocabulary} is built on. */
    public static Set<String> canonical() {
        return CANONICAL;
    }

    /** The canonical spelling of an ecosystem label, matched case-insensitively ({@code "nuget"} is {@code "NuGet"});
     *  a label outside the vocabulary is answered unchanged, so it still matches nothing and is simply not queried,
     *  and {@code null} stays {@code null}. */
    public static String canonical(String ecosystem) {
        return ecosystem == null ? null : BY_LABEL.getOrDefault(ecosystem.toLowerCase(Locale.ROOT), ecosystem);
    }

    /**
     * One feed's vendor vocabulary: each canonical ecosystem it covers mapped to the name that vendor writes for it.
     * An ecosystem the vendor does not cover is simply absent, which every feed reads as "do not query" - the
     * sentinel that keeps a metered call from being spent on a coordinate the vendor could never answer for.
     *
     * @throws IllegalArgumentException when a key is not a {@link #canonical()} ecosystem name. A misspelled key is
     *                                  otherwise invisible: the map merely never matches, and the ecosystem silently
     *                                  stops being covered by that feed.
     */
    public static Vocabulary vocabulary(Map<String, String> byEcosystem) {
        return new Vocabulary(byEcosystem);
    }

    /** The membership-only shape of a {@link Vocabulary}, for a vendor that names its ecosystems exactly as this
     *  product does and only declares <em>which</em> it analyses. */
    public static Vocabulary coverage(String... ecosystems) {
        Map<String, String> identity = new LinkedHashMap<>();
        for (String ecosystem : ecosystems) {
            identity.put(ecosystem, ecosystem);
        }
        return new Vocabulary(identity);
    }

    /**
     * A coordinate split into the segments its ecosystem writes it in: a Maven {@code group:artifact} into its group
     * and its artifact, a slashed name (an npm {@code @scope/name}, a Packagist {@code vendor/package}, a Go module
     * path) into its slash-separated parts, and a flat name into one segment. This is the same split a purl's
     * namespace, a CPE's vendor field and a vendor's own dependency model each need, and each used to re-derive with
     * its own off-by-one; an empty list for an absent coordinate.
     */
    public static List<String> segments(String ecosystem, String coordinate) {
        if (coordinate == null || coordinate.isBlank()) {
            return List.of();
        }
        return List.of((MAVEN.equals(canonical(ecosystem)) ? coordinate.replace(':', '/') : coordinate).split("/"));
    }

    /** Whether this ecosystem resolves a package identifier case-insensitively, so a coordinate match must fold case. */
    public static boolean caseInsensitiveIdentifiers(String ecosystem) {
        return ecosystem != null && CASE_INSENSITIVE.contains(ecosystem.toLowerCase(Locale.ROOT));
    }

    /** Fold a coordinate to the case its ecosystem matches in: lower-cased where identifiers are case-insensitive, the
     *  coordinate untouched where they are case-sensitive. Folding both a configured matcher and a subject coordinate
     *  makes a reservation bite every casing of a case-folding ecosystem's name and none of a case-sensitive one's. */
    public static String foldIdentifier(String coordinate, String ecosystem) {
        return coordinate != null && caseInsensitiveIdentifiers(ecosystem)
                ? coordinate.toLowerCase(Locale.ROOT)
                : coordinate;
    }

    /** One feed's mapping from the canonical ecosystem names to its vendor's own; see {@link #vocabulary}. */
    public static final class Vocabulary {

        private final Map<String, String> byEcosystem;

        private Vocabulary(Map<String, String> byEcosystem) {
            Map<String, String> copy = new LinkedHashMap<>();
            byEcosystem.forEach((ecosystem, vendor) -> {
                if (!CANONICAL.contains(ecosystem)) {
                    throw new IllegalArgumentException("A feed's ecosystem vocabulary is keyed on the canonical"
                            + " ecosystem names a coordinate carries, and \"" + ecosystem + "\" is not one of "
                            + new TreeSet<>(CANONICAL) + " - a misspelled key does not fail, it silently stops that"
                            + " ecosystem from ever being queried, so it is refused here instead");
                }
                copy.put(ecosystem, Objects.requireNonNull(vendor, "The vendor name for " + ecosystem));
            });
            this.byEcosystem = Collections.unmodifiableMap(copy);
        }

        /** The vendor's name for this ecosystem, or {@code null} when the vendor does not cover it - the sentinel
         *  every feed reads as "do not query". The label is matched through {@link Ecosystems#canonical(String)}. */
        public String of(String ecosystem) {
            return byEcosystem.get(canonical(ecosystem));
        }

        /** Whether the vendor covers this ecosystem at all. */
        public boolean covers(String ecosystem) {
            return of(ecosystem) != null;
        }

        /** The canonical names this vocabulary covers - a diagnostic, so a feed can say what it does not query. */
        public Set<String> covered() {
            return byEcosystem.keySet();
        }
    }
}
