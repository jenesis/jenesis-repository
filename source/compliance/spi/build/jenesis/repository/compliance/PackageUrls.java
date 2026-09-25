package build.jenesis.repository.compliance;

import module java.base;

/**
 * The external identifier a feed queries an ecosystem-neutral coordinate by, in the two naming schemes the vendors
 * divide into: the canonical <strong>package URL</strong> the purl-keyed feeds (Snyk, VulnCheck, Socket, ...) look a
 * package up as, and the candidate <strong>CPE 2.3</strong> name the CPE-keyed ones (VulnDB) do. Both live here, and
 * both key on {@link Ecosystems}, so the ecosystem-to-scheme mapping and the two schemes' escaping rules exist once
 * rather than once per feed.
 *
 * <p>The Linux distribution ecosystems are left out of the purl mapping deliberately - their purls carry distro
 * namespaces and release qualifiers this product's coordinates do not record - and so are two whose purl names a
 * thing the coordinate is not: an OCI purl is keyed by the manifest digest where the coordinate carries a tag, and a
 * Swift purl by the source repository's URL where the coordinate is the registry's {@code scope.name}. An ecosystem
 * absent from a scheme yields {@code null} there, which every feed reads as "no identifier exists, do not query".
 */
public final class PackageUrls {

    /** Canonical ecosystem name to purl type; an absent one has no purl and is not queried. */
    private static final Ecosystems.Vocabulary TYPES = Ecosystems.vocabulary(Map.ofEntries(
            Map.entry(Ecosystems.MAVEN, "maven"),
            Map.entry(Ecosystems.NPM, "npm"),
            Map.entry(Ecosystems.PYPI, "pypi"),
            Map.entry(Ecosystems.GO, "golang"),
            Map.entry(Ecosystems.NUGET, "nuget"),
            Map.entry(Ecosystems.RUBYGEMS, "gem"),
            Map.entry(Ecosystems.CRATES_IO, "cargo"),
            Map.entry(Ecosystems.PACKAGIST, "composer"),
            Map.entry(Ecosystems.COCOAPODS, "cocoapods"),
            Map.entry(Ecosystems.CONAN, "conan"),
            Map.entry(Ecosystems.CONDA, "conda"),
            Map.entry(Ecosystems.HUGGING_FACE, "huggingface")));

    /** The reverse-domain lead-ins a Maven group starts with; the segment after one names the organization the CPE
     *  vendor field wants ({@code org.apache.logging.log4j} - {@code apache}). */
    private static final Set<String> DOMAIN_PREFIXES = Set.of("com", "org", "net", "io", "dev", "me", "co", "us");

    private PackageUrls() {
    }

    /** The canonical package URL for a coordinate: the purl type from the ecosystem, the coordinate's
     *  {@link Ecosystems#segments segments} as namespace and name (a Maven {@code group:artifact} splitting into
     *  two), and every segment percent-encoded per the purl spec (an npm scope's {@code @} becomes {@code %40});
     *  {@code null} for an ecosystem no purl type exists for. */
    public static String of(String ecosystem, String coordinate, String version) {
        String type = TYPES.of(ecosystem);
        if (type == null) {
            return null;
        }
        StringBuilder purl = new StringBuilder("pkg:").append(type);
        for (String segment : Ecosystems.segments(ecosystem, coordinate)) {
            purl.append('/').append(encoded(segment));
        }
        return purl.append('@').append(encoded(version)).toString();
    }

    /**
     * The candidate CPE 2.3 name for a coordinate, lower-cased per CPE convention: a Maven coordinate contributes its
     * group's organization segment as the vendor and its artifact as the product, a slashed name (a Packagist
     * {@code vendor/package}, an npm {@code @scope/name}) already carries its vendor, and a flat ecosystem name
     * doubles as both (npm's {@code lodash} is NVD's {@code lodash:lodash}).
     *
     * <p>The derivation is honest best-effort and says so: {@code null} for a coordinate no plausible CPE exists for
     * (a Go module path, whose CPE naming has no relation to the module path), and a coordinate whose CPE naming
     * simply diverges yields a name that matches no record - which is a CPE-keyed feed reporting nothing for it, not
     * a wrong answer, and the purl-native feeds still cover it.
     */
    public static String cpe(String ecosystem, String coordinate, String version) {
        String canonical = Ecosystems.canonical(ecosystem);
        if (Ecosystems.GO.equals(canonical)) {
            return null;
        }
        List<String> segments = Ecosystems.segments(ecosystem, coordinate);
        String vendor;
        String product;
        if (Ecosystems.MAVEN.equals(canonical)) {
            if (segments.size() < 2) {
                return null;
            }
            String[] group = segments.getFirst().split("\\.");
            vendor = group.length > 1 && DOMAIN_PREFIXES.contains(group[0]) ? group[1] : group[0];
            product = String.join(":", segments.subList(1, segments.size()));
        } else if (segments.size() == 2) {
            vendor = segments.getFirst().startsWith("@") ? segments.getFirst().substring(1) : segments.getFirst();
            product = segments.get(1);
        } else if (segments.size() == 1) {
            vendor = product = segments.getFirst();
        } else {
            return null;
        }
        return "cpe:2.3:a:" + component(vendor) + ":" + component(product) + ":" + component(version);
    }

    // The purl spec's percent-encoding, hand-applied to the characters that occur in these ecosystems' coordinates:
    // a general URI encoder would also escape the segment separators a purl keeps literal, so none fits directly.
    private static String encoded(String segment) {
        return segment.replace("%", "%25").replace("@", "%40")
                .replace("?", "%3F").replace("#", "%23").replace(" ", "%20");
    }

    // The CPE 2.3 formatted-string escaping for the characters these coordinates carry; the field separators
    // themselves must stay literal, so a general encoder does not fit.
    private static String component(String value) {
        return value.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace(":", "\\:").replace("*", "\\*");
    }
}
