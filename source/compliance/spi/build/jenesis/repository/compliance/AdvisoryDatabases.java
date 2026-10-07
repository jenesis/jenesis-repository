package build.jenesis.repository.compliance;

import module java.base;

/**
 * The databases that publish vulnerability identifiers, known by the prefix each gives its own: a {@code CVE-} is NVD's
 * record, a {@code GHSA-} GitHub's, a {@code RUSTSEC-} RustSec's. {@link #source} names the database an identifier
 * belongs to and the address of its record there, so every source that reports an identifier attributes it the same
 * way - a feed's record of it, a scanner's alias of it.
 */
public final class AdvisoryDatabases {

    /** OSV's page of a record, which every database it aggregates is read through. */
    public static final String OSV_RECORD = "https://osv.dev/vulnerability/";

    private record Database(String prefix, String name, String record) {
    }

    private static final List<Database> KNOWN = List.of(
            new Database("CVE-", "NVD", "https://nvd.nist.gov/vuln/detail/"),
            new Database("GHSA-", "GitHub Advisory Database", "https://github.com/advisories/"),
            new Database("GO-", "Go Vulnerability Database", "https://pkg.go.dev/vuln/"),
            new Database("RUSTSEC-", "RustSec Advisory Database", "https://rustsec.org/advisories/"),
            new Database("PYSEC-", "PyPI Advisory Database", OSV_RECORD),
            new Database("MAL-", "OpenSSF Malicious Packages", OSV_RECORD));

    private AdvisoryDatabases() {
    }

    /** The database that publishes {@code id} and the address of its record there, or empty for an identifier no
     *  database here is known to give. */
    public static Optional<VulnerabilityRecord.Source> source(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String named = id.strip();
        for (Database database : KNOWN) {
            if (named.regionMatches(true, 0, database.prefix(), 0, database.prefix().length())) {
                return Optional.of(new VulnerabilityRecord.Source(database.name(), database.record() + named
                        + (database.prefix().equals("RUSTSEC-") ? ".html" : "")));
            }
        }
        return Optional.empty();
    }

    /** {@code id} as a reference to where it is published, its database named where it is known. */
    public static VulnerabilityRecord.Reference reference(String id) {
        return new VulnerabilityRecord.Reference(id, source(id).orElse(null));
    }

    /** The CWE number a weakness identifier names - {@code CWE-79}, {@code 79} - or empty for one that names none. */
    public static OptionalInt cwe(String weakness) {
        if (weakness == null) {
            return OptionalInt.empty();
        }
        String digits = weakness.strip();
        if (digits.regionMatches(true, 0, "CWE-", 0, 4)) {
            digits = digits.substring(4);
        }
        try {
            int number = Integer.parseInt(digits);
            return number > 0 ? OptionalInt.of(number) : OptionalInt.empty();
        } catch (NumberFormatException noNumber) {
            return OptionalInt.empty();
        }
    }
}
