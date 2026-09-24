package build.jenesis.repository.compliance.web;

import build.jenesis.repository.ui.ConsoleModuleProvider;

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
}
