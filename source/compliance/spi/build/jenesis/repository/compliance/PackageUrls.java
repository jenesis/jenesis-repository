package build.jenesis.repository.compliance;

import module java.base;

/**
 * The external identifier a feed queries an ecosystem-neutral coordinate by, in the two naming schemes the vendors
 * divide into: the canonical <strong>package URL</strong> the purl-keyed feeds (Snyk, VulnCheck, Socket, ...) look a
 * package up as, and the candidate <strong>CPE 2.3</strong> name the CPE-keyed ones (VulnDB) do. Both live here, and
 * both key on {@link Ecosystems}, so the ecosystem-to-scheme mapping and the two schemes' escaping rules exist once
 * rather than once per feed.
 *
 * <p>The Linux distribution ecosystems are left out of the purl a coordinate is queried by - their purls carry a
 * distro namespace and release qualifiers this product's coordinates do not record - and so are two whose purl names
 * a thing the coordinate is not: an OCI purl is keyed by the manifest digest where the coordinate carries a tag, and a
 * Swift purl by the source repository's URL where the coordinate is the registry's {@code scope.name}. An ecosystem
 * absent from a scheme yields {@code null} there, which every feed reads as "no identifier exists, do not query".
 *
 * <p>Read the other way, a distribution package's purl does name a coordinate: an {@code apk}, {@code deb} or
 * {@code rpm} purl names a package of {@link Ecosystems#ALPINE}, {@link Ecosystems#DEBIAN} or {@link Ecosystems#RPM}
 * by its name and version, as those formats key what they publish, its distro namespace aside. What the coordinate
 * leaves out - the release, the architecture, the source package, the epoch - is in the purl's
 * {@link #qualifiers}, so a reader that keeps both keeps everything the purl said.
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

    /** Distribution purl types to the ecosystem their packages are published under, read by {@link #parse} alone. */
    private static final Map<String, String> DISTRIBUTIONS = Map.of(
            "apk", Ecosystems.ALPINE,
            "deb", Ecosystems.DEBIAN,
            "rpm", Ecosystems.RPM);

    /** The ecosystems a package URL is made for, so a purl-keyed feed covers exactly these. */
    public static Set<String> covered() {
        return TYPES.covered();
    }

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

    /** A coordinate a package URL names, in the product's ecosystem and coordinate spelling. */
    public record Named(String ecosystem, String coordinate, String version) {
    }

    /**
     * The coordinate {@code purl} names - the inverse of {@link #of}: its type's ecosystem, its namespace and name
     * joined as that ecosystem writes a coordinate (a Maven {@code group:artifact}, a slashed name otherwise) and
     * percent-decoded, and its version; qualifiers and a subpath are dropped, and {@link #qualifiers} reads them. A
     * distribution package's purl names its package by its name alone, the distro namespace being no part of the
     * coordinate. Empty for a purl of a type no ecosystem here is named by, or one without a name or a version.
     */
    public static Optional<Named> parse(String purl) {
        if (purl == null || !purl.startsWith("pkg:")) {
            return Optional.empty();
        }
        String body = purl.substring("pkg:".length());
        for (char end : new char[]{'#', '?'}) {
            int at = body.indexOf(end);
            body = at < 0 ? body : body.substring(0, at);
        }
        int version = body.lastIndexOf('@');
        if (version < 0 || version == body.length() - 1) {
            return Optional.empty();
        }
        String[] segments = body.substring(0, version).split("/");
        if (segments.length < 2) {
            return Optional.empty();
        }
        String type = segments[0].toLowerCase(Locale.ROOT);
        String distribution = DISTRIBUTIONS.get(type);
        if (distribution != null) {
            return Optional.of(new Named(distribution, decoded(segments[segments.length - 1]),
                    decoded(body.substring(version + 1))));
        }
        Optional<String> ecosystem = TYPES.covered().stream().filter(named -> type.equals(TYPES.of(named))).findFirst();
        if (ecosystem.isEmpty()) {
            return Optional.empty();
        }
        List<String> parts = new ArrayList<>();
        for (int i = 1; i < segments.length; i++) {
            parts.add(decoded(segments[i]));
        }
        String coordinate = String.join(Ecosystems.MAVEN.equals(ecosystem.get()) ? ":" : "/", parts);
        return Optional.of(new Named(ecosystem.get(), coordinate, decoded(body.substring(version + 1))));
    }

    /**
     * Every qualifier {@code purl} carries, in the order it writes them: each key lower-cased, as the specification
     * makes them, and each value percent-decoded. A {@code distro} qualifier is written without a leading
     * {@code <namespace>-}, which some scanners prefix and others do not ({@code alpine-3.10.0} and {@code 3.10.0} name
     * one release of {@code pkg:apk/alpine}). Empty for a purl with none, or a text that is not one.
     */
    public static SequencedMap<String, String> qualifiers(String purl) {
        SequencedMap<String, String> qualifiers = new LinkedHashMap<>();
        if (purl == null || !purl.startsWith("pkg:")) {
            return qualifiers;
        }
        String body = purl.substring("pkg:".length());
        int hash = body.indexOf('#');
        body = hash < 0 ? body : body.substring(0, hash);
        int question = body.indexOf('?');
        if (question < 0) {
            return qualifiers;
        }
        String[] path = body.substring(0, question).split("/");
        String namespace = path.length > 2 ? decoded(path[1]).toLowerCase(Locale.ROOT) : "";
        for (String pair : body.substring(question + 1).split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0 || equals == pair.length() - 1) {
                continue;
            }
            String key = pair.substring(0, equals).toLowerCase(Locale.ROOT);
            String value = decoded(pair.substring(equals + 1));
            if (key.equals("distro") && !namespace.isEmpty()
                    && value.toLowerCase(Locale.ROOT).startsWith(namespace + "-")) {
                value = value.substring(namespace.length() + 1);
            }
            qualifiers.putIfAbsent(key, value);
        }
        return qualifiers;
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

    // The purl spec's percent-decoding: every %XX is its byte, a '+' stays a '+', and a malformed escape stays literal.
    private static String decoded(String segment) {
        if (segment.indexOf('%') < 0) {
            return segment;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(segment.length());
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            int hi = c == '%' && i + 2 < segment.length() ? Character.digit(segment.charAt(i + 1), 16) : -1;
            int lo = hi >= 0 ? Character.digit(segment.charAt(i + 2), 16) : -1;
            if (lo >= 0) {
                bytes.write((hi << 4) + lo);
                i += 2;
            } else {
                bytes.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    // The CPE 2.3 formatted-string escaping for the characters these coordinates carry; the field separators
    // themselves must stay literal, so a general encoder does not fit.
    private static String component(String value) {
        return value.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace(":", "\\:").replace("*", "\\*");
    }
}
