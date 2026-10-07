package build.jenesis.repository.compliance;

import module java.base;
import module tools.jackson.databind;

/**
 * What a content scanner found, read as the advisories the gate decides and the findings ledger records - one mapping
 * every {@link ContentScanner} shares, whatever its report's shape, so two scanners reporting one flaw in one image
 * answer the same advisory.
 *
 * <p>A scanner reports one row per vulnerable package, so one vulnerability in two packages of an image - a CVE in
 * both {@code openssl} and {@code libssl3} - arrives twice. It is one advisory here, keyed by the vulnerability's own
 * identifier, because that identifier is what VEX statements, waivers and the known-exploited catalogue name and what
 * two sources reporting the same flaw merge on. The packages it was found in are not lost: each is named in the
 * description with the version installed, and in {@code fixed} with the version that fixes it, so a reviewer reading
 * the hold reads which package to upgrade to what.
 *
 * <p>Scales are normalised as documented, never guessed. The scanner's own word on the severity scale is read first,
 * since it is the scanner's assessment of the package as the image installs it: {@code Critical}, {@code High},
 * {@code Medium} and {@code Low} map to their bands, and {@code Negligible} - below Low on that scale - to
 * {@link Severity#LOW}, the lowest band this product has. Where the word says nothing ({@code Unknown}, a word the scale
 * does not define, none at all) the CVSS v3 score is read, then the v2 score, through {@link Severity#ofScore}; failing
 * every one the vulnerability is {@link Severity#UNKNOWN}, which a severity floor reads as failing closed. Where one
 * identifier arrives with different severities across its packages, the strongest stands.
 *
 * <p>What the scanner's database says of a vulnerability beyond its identifier - where it was published, each rating
 * with who gave it, its weaknesses - rides each row, and every row of one identifier lends what it says to the one
 * advisory.
 *
 * <p>A report that carries no list of rows at all is refused rather than read as clean: an empty list is what a clean
 * image looks like, and a report whose list went missing - a renamed field, a truncated document - must surface as a
 * failed scan, not as an admission.
 */
public final class ScannerAdvisories {

    private ScannerAdvisories() {
    }

    /** One vulnerability of one package as a scanner reports it: its identifier, the package and the version
     *  installed, the version fixing it, the scanner's severity word, a description, and the CVSS scores it gives -
     *  any of which but the identifier may be {@code null} - and what the scanner's database says of it beyond these,
     *  its attribution, ratings and weaknesses ({@link VulnerabilityRecord#EMPTY} where it says nothing more). */
    public record Row(String id, String pkg, String version, String fixVersion, String severity, String description,
                      Double scoreV3, Double scoreV2, VulnerabilityRecord detail) {

        public Row {
            detail = detail == null ? VulnerabilityRecord.EMPTY : detail;
        }

        /** A row whose scanner says nothing of the vulnerability beyond its identifier, severity and scores. */
        public Row(String id, String pkg, String version, String fixVersion, String severity, String description,
                   Double scoreV3, Double scoreV2) {
            this(id, pkg, version, fixVersion, severity, description, scoreV3, scoreV2, VulnerabilityRecord.EMPTY);
        }
    }

    /** Who gave a rating, by the key the Trivy database files it under. */
    private static final Map<String, String> RATERS = Map.of("nvd", "NVD", "ghsa", "GitHub Advisory Database",
            "redhat", "Red Hat");

    /**
     * The ratings in a per-rater CVSS map as the Trivy database writes it - {@code {"nvd": {"V3Vector": ...,
     * "V3Score": ...}, "redhat": {...}}}, with {@code V40}, {@code V3} and {@code V2} vectors and scores - each by who
     * gave it. Trivy reports the map under {@code CVSS}, and a scanner adapter over Trivy's database under its vendor
     * attributes; anything that is not such a map reads as no ratings.
     */
    public static List<VulnerabilityRecord.Rating> ratings(JsonNode byRater) {
        List<VulnerabilityRecord.Rating> ratings = new ArrayList<>();
        if (byRater == null || !byRater.isObject()) {
            return ratings;
        }
        for (Map.Entry<String, JsonNode> rated : byRater.properties()) {
            VulnerabilityRecord.Source rater = new VulnerabilityRecord.Source(
                    RATERS.getOrDefault(rated.getKey(), rated.getKey()), null);
            for (String version : List.of("V40", "V3", "V2")) {
                JsonNode score = rated.getValue().path(version + "Score");
                VulnerabilityRecord.Rating.stated(rater, score.isNumber() ? score.asDouble() : null,
                        rated.getValue().path(version + "Vector").asString(null),
                        version.equals("V2") ? "CVSSv2" : "other").ifPresent(ratings::add);
            }
        }
        return ratings;
    }

    /**
     * The advisories {@code rows} name, one per vulnerability identifier, in the order the rows first name each.
     *
     * @throws IOException where {@code rows} is {@code null} - the report carried no list - or a row names no
     *                     identifier
     */
    public static List<AdvisorySource.Advisory> advisories(List<Row> rows) throws IOException {
        if (rows == null) {
            throw new IOException("the scanner's report carries no vulnerabilities list");
        }
        Map<String, List<Row>> byId = new LinkedHashMap<>();
        for (Row row : rows) {
            if (row == null || row.id() == null || row.id().isBlank()) {
                throw new IOException("the scanner's report names a vulnerability without an identifier");
            }
            byId.computeIfAbsent(row.id().strip(), _ -> new ArrayList<>()).add(row);
        }
        List<AdvisorySource.Advisory> advisories = new ArrayList<>(byId.size());
        for (Map.Entry<String, List<Row>> entry : byId.entrySet()) {
            advisories.add(advisory(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(advisories);
    }

    private static AdvisorySource.Advisory advisory(String id, List<Row> found) {
        Severity severity = null;
        SequencedSet<String> installed = new LinkedHashSet<>();
        SequencedSet<String> fixes = new LinkedHashSet<>();
        String summary = null;
        VulnerabilityRecord detail = VulnerabilityRecord.EMPTY;
        for (Row row : found) {
            detail = detail.and(row.detail());
            Severity reported = severity(row);
            severity = severity == null ? reported : Severity.strongest(severity, reported);
            String pkg = row.pkg() == null || row.pkg().isBlank() ? "an unnamed package" : row.pkg().strip();
            installed.add(row.version() == null || row.version().isBlank() ? pkg : pkg + " " + row.version().strip());
            if (row.fixVersion() != null && !row.fixVersion().isBlank()) {
                fixes.add(pkg + " " + row.fixVersion().strip());
            }
            if (summary == null && row.description() != null && !row.description().isBlank()) {
                summary = row.description().strip();
            }
        }
        List<String> cves = id.regionMatches(true, 0, "CVE-", 0, 4) ? List.of(id.toUpperCase(Locale.ROOT)) : List.of();
        String description = AdvisorySource.Advisory.description(null,
                String.join(", ", installed) + (summary == null ? "" : ": " + summary));
        return new AdvisorySource.Advisory(id, severity, false, fixes.isEmpty() ? null : String.join(", ", fixes),
                cves, description, List.of(), detail);
    }

    /** One row's severity: the scanner's own word, which is its assessment of the package as installed, read as
     *  every feed's is ({@link Severity#ofWord}); a CVSS score where the word says nothing - {@code Unknown}, an
     *  unrecognised word, none - and {@link Severity#UNKNOWN} where neither does. */
    public static Severity severity(Row row) {
        Severity rated = Severity.ofWord(row.severity(), null);
        if (rated != null) {
            return rated;
        }
        if (row.scoreV3() != null) {
            return Severity.ofScore(row.scoreV3());
        }
        if (row.scoreV2() != null) {
            return Severity.ofScore(row.scoreV2());
        }
        return Severity.UNKNOWN;
    }
}
