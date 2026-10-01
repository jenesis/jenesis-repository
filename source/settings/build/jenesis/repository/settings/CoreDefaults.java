package build.jenesis.repository.settings;

/**
 * The shipped default for each dial the server kernel binds, defined once: {@code RepositoryProperties},
 * {@code LiveConfig} and the {@link CoreSettingsContributor} row all read these rather than holding copies.
 *
 * <p>Each is a compile-time constant in the string form the catalogue publishes, because the settings-reference
 * extractor can only read a constant default; every reader is compiled from source, so an inlined copy cannot drift.
 *
 * <p>A dial belongs here when {@code LiveConfig} reads a {@code RepositoryProperties} field for it. A discovered
 * dimension's default lives on the policy that applies it instead.
 */
public final class CoreDefaults {

    /** A curated malicious-package record is a more certain signal than a severity score, so it does not refuse
     *  less than the vulnerability dimension does. */
    public static final String MALWARE_ACTION = "REJECT";

    /** The secure floor for an artifact whose advisories reach the threshold below. */
    public static final String VULNERABILITY_ACTION = "REJECT";

    /** The severity band at which the vulnerability dimension bites: a fresh deployment with a feed active gates
     *  the most severe CVEs rather than admitting them silently. */
    public static final String VULNERABILITY_THRESHOLD = "CRITICAL";

    /** The secure floor for a coordinate an operator has named on the deny list. */
    public static final String DENY_LIST_ACTION = "REJECT";

    /** Pull-through proxying is on by default. A string, as the catalogue publishes it; the reader wanting a boolean
     *  parses it. */
    public static final String PROXY_ENABLED = "true";

    private CoreDefaults() {
    }
}
