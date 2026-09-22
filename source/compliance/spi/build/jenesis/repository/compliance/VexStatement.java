package build.jenesis.repository.compliance;

import module java.base;

/**
 * One VEX claim, normalised out of an OpenVEX statement or a CSAF {@code product_status} entry: the vulnerability it
 * concerns (its primary id and any aliases), the products it applies to (purls or bare coordinates), the applicability
 * {@link VexStatus status} it asserts, an optional machine {@code justification} and human {@code statement}, when it
 * was made, and the id of the source {@code document} it came from (so a gate suppression can name the statement that
 * cleared the finding). It is the ecosystem-neutral shape the gate reads
 * through {@link Vex}, decoupled from either interchange format's wire schema.
 *
 * <p>Matching is two independent questions the gate asks: {@link #covers} - does this statement speak to the advisory
 * (by id or a shared alias) - and {@link #appliesTo} - does it speak to the subject coordinate (by purl or bare
 * coordinate, honouring a version when the product pins one). Both are pure and case-insensitive on identifiers; a
 * statement with no products applies to nothing, so a malformed VEX can never blanket-suppress a tenant's advisories.
 */
public record VexStatement(String vulnerability, List<String> aliases, List<String> products,
                           VexStatus status, String justification, String statement,
                           Instant timestamp, String document) {

    public VexStatement {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        products = products == null ? List.of() : List.copyOf(products);
    }

    /** Whether this statement speaks to an advisory: its vulnerability id or any of its aliases matches the advisory's
     *  own id or one of its {@code cves} aliases, compared case-insensitively (a CVE named by OSV and a GHSA alias both
     *  resolve). */
    public boolean covers(String advisoryId, List<String> advisoryAliases) {
        Set<String> mine = identifiers(vulnerability, aliases);
        Set<String> theirs = identifiers(advisoryId, advisoryAliases);
        for (String identifier : mine) {
            if (theirs.contains(identifier)) {
                return true;
            }
        }
        return false;
    }

    /** Whether this statement applies to a subject coordinate: one of its products matches the subject's purl (built
     *  through {@link PackageUrls}) or its bare coordinate, and - when the product identifier pins a version - that
     *  version equals the subject's. A statement with no products applies to nothing. */
    public boolean appliesTo(String ecosystem, String coordinate, String version) {
        if (products.isEmpty()) {
            return false;
        }
        String purl = PackageUrls.of(ecosystem, coordinate, version);
        String purlName = purl == null ? null : purl.substring(0, purl.lastIndexOf('@'));
        for (String product : products) {
            if (matches(product, purlName, coordinate, version)) {
                return true;
            }
        }
        return false;
    }

    /** Whether one product identifier (a purl, a bare coordinate, or {@code *}) names this subject. */
    private static boolean matches(String product, String purlName, String coordinate, String version) {
        if (product == null || product.isBlank()) {
            return false;
        }
        String trimmed = product.strip();
        if (trimmed.equals("*")) {
            return true;
        }
        // Strip purl qualifiers (?a=b) and subpath (#sub) before splitting off the version, so pinning does not defeat
        // the name comparison.
        String bare = trimmed;
        int qualifier = bare.indexOf('?');
        if (qualifier >= 0) {
            bare = bare.substring(0, qualifier);
        }
        int subpath = bare.indexOf('#');
        if (subpath >= 0) {
            bare = bare.substring(0, subpath);
        }
        String name = bare;
        String pinnedVersion = null;
        int at = bare.lastIndexOf('@');
        if (at > 0) {                                           // a leading @ is an npm scope, not a version separator
            name = bare.substring(0, at);
            pinnedVersion = bare.substring(at + 1);
        }
        boolean nameMatches = (purlName != null && name.equalsIgnoreCase(purlName)) || name.equalsIgnoreCase(coordinate);
        if (!nameMatches) {
            return false;
        }
        return pinnedVersion == null || pinnedVersion.isBlank() || pinnedVersion.equalsIgnoreCase(version);
    }

    /** This statement's instant, or {@link Instant#EPOCH} when it carries none - so the newest claim for a
     *  (vulnerability, product) pair can be picked without a null check. */
    public Instant when() {
        return timestamp == null ? Instant.EPOCH : timestamp;
    }

    private static Set<String> identifiers(String primary, List<String> aliases) {
        Set<String> identifiers = new LinkedHashSet<>();
        if (primary != null && !primary.isBlank()) {
            identifiers.add(primary.strip().toUpperCase(Locale.ROOT));
        }
        for (String alias : aliases) {
            if (alias != null && !alias.isBlank()) {
                identifiers.add(alias.strip().toUpperCase(Locale.ROOT));
            }
        }
        return identifiers;
    }
}
