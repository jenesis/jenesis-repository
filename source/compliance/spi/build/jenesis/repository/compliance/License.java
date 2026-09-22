package build.jenesis.repository.compliance;

import module java.base;

/**
 * A resolved license: its canonical SPDX identifier and a coarse category (permissive, weak-copyleft,
 * strong-copyleft, network-copyleft), or {@link #UNKNOWN} when a declared name/URL matches nothing known. The
 * identification table mirrors the build tool's compliance step, ordered most-specific first so the GPL family
 * resolves before the broad permissive fallbacks rather than after them.
 *
 * <p>Lives in the compliance SPI (not the {@code compliance.licenses} policy module) because license identification
 * is a shared contract: the {@code LicensePolicy}-style gate dimension resolves a declared license to allow/deny it,
 * and a license <em>inventory</em> (the search-index license facet) resolves the same declared license to a queryable
 * SPDX id and category. Both reach it through the SPI rather than either forking the table or one plugin depending on
 * another plugin's implementation.
 *
 * <p>The identification table is data, not code: an ordered classpath resource ({@value #TABLE_RESOURCE}) this record
 * loads once, so a new mapping is a line in that file rather than another branch in a focal method - the operator's
 * "prefer a table over a hardcoded enumeration in a focal class" rule. It is not an SPI: license identification is a
 * fixed, shared table with no third-party extension point, so the resource stays owned by this module.
 */
public record License(String spdxId, String category) {

    public static final License UNKNOWN = new License(null, "unknown");

    /** The classpath resource holding the ordered identification rules. */
    private static final String TABLE_RESOURCE = "/spdx-licenses.tsv";

    /** The rules in resource order (most-specific first), loaded once at class initialisation. */
    private static final List<Rule> TABLE = load();

    public boolean identified() {
        return spdxId != null;
    }

    public static License identify(String name, String url) {
        // A bare SPDX identifier - what an npm/Cargo/gemspec declaration emits ("MIT", "Apache-2.0", "GPL-3.0") -
        // resolves by an exact, case-insensitive match against the canonical spdx-id column FIRST. The substring
        // fallback below only ever matched verbose license names and URLs ("The Apache Software License, Version 2.0",
        // ".../licenses/MIT"); a bare id contains none of those name/URL tokens, so before this every bare-id
        // declaration resolved to UNKNOWN - a proxy-path deny bypass and a mass false-quarantine of bare-MIT packages.
        // A bare id is matched by exact spdx-id, never by a substring token (which would let "mit" match "commit").
        License byId = identifyId(name);
        if (byId != null) {
            return byId;
        }
        String text = ((name == null ? "" : name) + " " + (url == null ? "" : url)).toLowerCase(Locale.ROOT);
        if (text.isBlank()) {
            return UNKNOWN;
        }
        for (Rule rule : TABLE) {
            for (String token : rule.tokens()) {
                if (text.contains(token)) {
                    return rule.license();
                }
            }
        }
        return UNKNOWN;
    }

    /** Resolve a bare SPDX identifier (a {@code name} with no whitespace - {@code "MIT"}, {@code "GPL-3.0"}) against the
     *  canonical spdx-id column, most-specific rule first: an exact case-insensitive match, or a version-suffixed id
     *  folded into its family ({@code "GPL-3.0"} / {@code "GPL-3.0-only"} -> the {@code "GPL"} row), so the table stays a
     *  compact family list rather than one row per SPDX point release. Returns {@code null} when the value is not a bare
     *  identifier (it is blank, or carries whitespace - a verbose name the substring fallback handles) or matches no row,
     *  leaving the caller to fall back. Exact-first, in table order, keeps the specific rows (an {@code AGPL-3.0} or a
     *  {@code BSD-3-Clause} row that precedes the broad {@code GPL} / {@code BSD} family) winning over a family fold. */
    private static License identifyId(String name) {
        if (name == null) {
            return null;
        }
        String id = name.strip();
        if (id.isEmpty() || containsWhitespace(id)) {
            return null;
        }
        for (Rule rule : TABLE) {
            String spdxId = rule.license().spdxId();
            if (id.equalsIgnoreCase(spdxId) || versionOf(id, spdxId)) {
                return rule.license();
            }
        }
        return null;
    }

    /** Whether {@code id} is a version-suffixed member of the {@code family} spdx-id - {@code "GPL-3.0"} of
     *  {@code "GPL"} - i.e. it begins with the family id followed by a {@code '-'}, compared case-insensitively. */
    private static boolean versionOf(String id, String family) {
        return id.length() > family.length()
                && id.charAt(family.length()) == '-'
                && id.regionMatches(true, 0, family, 0, family.length());
    }

    private static boolean containsWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** One identification rule: the license a match resolves to and the already-lower-cased tokens (OR-ed) that
     *  select it. */
    private record Rule(License license, List<String> tokens) {
    }

    /** Parse the ordered table from the classpath, skipping blank and {@code #} comment lines. A missing or malformed
     *  resource is a packaging error, not a runtime condition to tolerate, so it fails fast at class initialisation
     *  rather than silently identifying every license as unknown. */
    private static List<Rule> load() {
        InputStream in = License.class.getResourceAsStream(TABLE_RESOURCE);
        if (in == null) {
            throw new IllegalStateException("license identification table not found on the classpath: " + TABLE_RESOURCE);
        }
        List<Rule> rules = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String stripped = line.strip();
                if (stripped.isEmpty() || stripped.startsWith("#")) {
                    continue;
                }
                String[] fields = line.split("\t");
                if (fields.length != 3) {
                    throw new IllegalStateException("malformed license rule (expected 3 tab-separated fields): " + line);
                }
                rules.add(new Rule(new License(fields[0].strip(), fields[1].strip()),
                        List.of(fields[2].strip().split("\\|"))));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the license identification table " + TABLE_RESOURCE, e);
        }
        return List.copyOf(rules);
    }
}
