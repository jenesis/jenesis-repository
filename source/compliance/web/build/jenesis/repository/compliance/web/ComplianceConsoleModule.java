package build.jenesis.repository.compliance.web;

import module java.base;
import build.jenesis.repository.ui.ConsoleModuleProvider;
import build.jenesis.repository.ui.RepositoryPage;
import build.jenesis.repository.ui.RepositoryPage.Topic;

/**
 * The screening feature's console surface, contributed through the console's own seam rather than written into it.
 *
 * <p>The screens these modules own - what a scan found, what a maintainer-health sweep recorded, who signed what -
 * read the screening ledgers, and a console that carried them would have to know the screening vocabulary to render
 * them. Contributing them instead means a deployment without this module renders nothing in their place and says
 * so, which is what every absent module already does.
 *
 * <p>It answers to the same name as {@link ComplianceWebModule}, so one setting governs both surfaces of the
 * one concern.
 */
public final class ComplianceConsoleModule implements ConsoleModuleProvider {

    @Override
    public String name() {
        return "compliance";
    }

    @Override
    public Class<?> configuration() {
        return ComplianceConsoleConfig.class;
    }

    /** The screening pages of every repository. The review queue and the refusals need nothing but this module;
     *  each of the others renders a ledger another module keeps, and is listed only where that module is present. */
    @Override
    public List<RepositoryPage> repositoryPages() {
        return List.of(
                new RepositoryPage("Quarantine", "/quarantine", Topic.REVIEW),
                new RepositoryPage("AI review", "/ai-review", Topic.REVIEW, "aiReview"),
                new RepositoryPage("Refused", "/refusals", Topic.REVIEW),
                new RepositoryPage("Vulnerabilities", "/vulnerabilities", Topic.RISK, "advisories"),
                new RepositoryPage("Findings", "/findings", Topic.RISK, "findings"),
                new RepositoryPage("Maintainer health", "/health", Topic.RISK, "maintainerHealth"),
                new RepositoryPage("License enforcement preview", "/blast-radius", Topic.RISK, "licensePolicy"),
                new RepositoryPage("Signers", "/signers", Topic.PROVENANCE));
    }
}
