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

    /** An artifact whose advisories reach the threshold below is held for review: it is kept and withheld, so a
     *  reviewer can see what it is and decide, where a refusal would leave nothing to look at. */
    public static final String VULNERABILITY_ACTION = "QUARANTINE";

    /** The severity band at which the vulnerability dimension bites: a fresh deployment with a feed active gates
     *  the most severe CVEs rather than admitting them silently. */
    public static final String VULNERABILITY_THRESHOLD = "CRITICAL";

    /** The severity band from which a version's findings mark it as a risk: Low, so anything an advisory scores
     *  marks the version, while the threshold above decides what is held. */
    public static final String VULNERABILITY_RISK_THRESHOLD = "LOW";

    /** The secure floor for a coordinate an operator has named on the deny list. */
    public static final String DENY_LIST_ACTION = "REJECT";

    /** Pull-through proxying is on by default. A string, as the catalogue publishes it; the reader wanting a boolean
     *  parses it. */
    public static final String PROXY_ENABLED = "true";

    /** The requests a minute one tenant's credentials make together on a repository or the build cache, keyless
     *  requests sharing one such ceiling: none, since a tenant's ceiling is a fairness tool between tenants that a
     *  multi-tenant operator sets deliberately, and a runaway client is stopped by its address's ceiling below. */
    public static final String RATE_LIMIT = "0";

    /** The requests a minute one credential makes: no ceiling of its own unless a tenant names one, since the
     *  tenant's ceiling already bounds every credential in it. */
    public static final String RATE_LIMIT_ACCOUNT = "0";

    /** The requests a minute one client address makes: a thousand a second, which no build machine sustains, so it
     *  stops a runaway or abusive client and nothing that is working. */
    public static final String RATE_LIMIT_ADDRESS = "60000";

    private CoreDefaults() {
    }
}
