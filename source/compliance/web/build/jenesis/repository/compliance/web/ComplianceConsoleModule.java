package build.jenesis.repository.compliance.web;

import module java.base;
import build.jenesis.repository.ui.ConsoleModuleProvider;
import build.jenesis.repository.ui.NavEntry;
import build.jenesis.repository.ui.RepositoryPage;
import build.jenesis.repository.ui.RepositoryPage.Topic;

/**
 * The screening feature's console screens, contributed through the console's module seam. It answers to the same name
 * as {@link ComplianceWebModule}, so one setting governs both surfaces.
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

    /** The signal sources the gate screens with: deployment-wide, like the feeds they describe, so an operator's. */
    @Override
    public List<NavEntry> navEntries() {
        return List.of(new NavEntry("Signal sources", "/ui/signals", NavEntry.Access.SUPERADMIN,
                NavEntry.Group.OPERATIONS));
    }

    /** The screening pages of every repository; a page rendering another module's ledger is listed only where that
     *  module is present. */
    @Override
    public List<RepositoryPage> repositoryPages() {
        return List.of(
                new RepositoryPage("Quarantine", "/quarantine", Topic.REVIEW),
                new RepositoryPage("AI review", "/ai-review", Topic.REVIEW, "aiReview"),
                new RepositoryPage("Refused", "/refusals", Topic.REVIEW),
                new RepositoryPage("Vulnerabilities", "/vulnerabilities", Topic.RISK, "advisories"),
                new RepositoryPage("Findings", "/findings", Topic.RISK, "findings"),
                new RepositoryPage("Maintainer health", "/health", Topic.RISK, "maintainerHealth"),
                new RepositoryPage("Enforcement preview", "/enforcement-preview", Topic.RISK, "licensePolicy"),
                new RepositoryPage("Signers", "/signers", Topic.PROVENANCE));
    }
}
