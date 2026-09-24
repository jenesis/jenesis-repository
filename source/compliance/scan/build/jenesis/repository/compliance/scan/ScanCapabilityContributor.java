package build.jenesis.repository.compliance.scan;

import module java.base;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;
import build.jenesis.repository.server.spi.CapabilityContributor;

/**
 * Contributes the {@code scan} capability flag to {@code /api/capabilities}: whether the vulnerability-scan
 * maintenance task is installed on this deployment. Resolved from the discovered {@link MaintenanceTaskProvider}
 * registry - identical to the value the neutral server built by hand before the fan-out.
 */
public final class ScanCapabilityContributor implements CapabilityContributor {

    @Override
    public Map<String, Object> capabilities(UnaryOperator<String> effectiveConfig) {
        Map<String, Object> flags = new LinkedHashMap<>();
        flags.put("scan", MaintenanceTaskProvider.installed().contains("scan"));
        return flags;
    }
}
