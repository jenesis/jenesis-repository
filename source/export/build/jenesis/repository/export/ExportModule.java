package build.jenesis.repository.export;

import build.jenesis.repository.server.kernel.ServerModuleProvider;

/**
 * Announces the export endpoints to the repository server's {@code ServerModuleProvider} discovery, so the server
 * imports {@link ExportConfig} - and with it {@code /api/repository/export} - without naming it.
 */
public final class ExportModule implements ServerModuleProvider {

    @Override
    public String name() {
        return "export";
    }

    @Override
    public Class<?> configuration() {
        return ExportConfig.class;
    }
}
