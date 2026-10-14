package build.jenesis.repository.oidc;

import module java.base;
import build.jenesis.repository.posture.Configuration;
import build.jenesis.repository.posture.SafetyAdvisor;
import build.jenesis.repository.posture.SecurityAdvisory;
import build.jenesis.repository.posture.Severity;
import build.jenesis.repository.server.spi.OidcTrusts;

/**
 * Names, per tenant, the OIDC trusts stored without a subject. Such a trust exchanges no token - a blank subject is
 * refused when a trust is saved and matches nothing when one was stored before that - so a CI job relying on it is
 * refused, and this says which trust and how to fix it before someone reads that from a failed pipeline. It reads the
 * record {@link OidcTrusts#subjectless()} keeps of the trusts this node has read, so a posture read costs nothing and
 * reaches no store; a tenant whose trusts this node has not read since it started is not named until it does.
 */
public final class SubjectlessTrustAdvisor implements SafetyAdvisor {

    static final String ID = "jenrepo.oidc.subject.unset";

    private final Supplier<Map<String, List<String>>> subjectless;

    /** The advisor over the trusts this node has read. */
    public SubjectlessTrustAdvisor() {
        this(OidcTrusts::subjectless);
    }

    /** The advisor over a given record of subjectless trusts, by tenant. */
    public SubjectlessTrustAdvisor(Supplier<Map<String, List<String>>> subjectless) {
        this.subjectless = subjectless;
    }

    @Override
    public List<SecurityAdvisory> advise(Configuration config) {
        List<SecurityAdvisory> advisories = new ArrayList<>();
        for (Map.Entry<String, List<String>> tenant : new TreeMap<>(subjectless.get()).entrySet()) {
            String names = tenant.getValue().stream().map(name -> "'" + name + "'")
                    .collect(Collectors.joining(", "));
            advisories.add(SecurityAdvisory.tenant(ID, Severity.WARN, tenant.getKey(),
                    "OIDC trusts with no subject exchange no token",
                    "The OIDC " + (tenant.getValue().size() == 1 ? "trust " : "trusts ") + names + " name no subject, "
                            + "so no CI job's token matches them and every exchange through them is refused. A CI "
                            + "platform's issuer signs tokens for every one of its users, and the subject is the claim "
                            + "that says whose job a token is from.",
                    "Set each trust's subject to the workload it admits, such as repo:<owner>/<repository>:* for "
                            + "GitHub Actions, or to * only for an issuer that signs for your organisation alone.",
                    "", "", "https://jenesis.build/repository/operations/#" + ID));
        }
        return advisories;
    }
}
