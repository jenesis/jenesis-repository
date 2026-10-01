package build.jenesis.repository.cleanup.task;

import module java.base;
import build.jenesis.repository.gc.GarbageCollectorProvider;
import build.jenesis.repository.server.spi.CapabilityContributor;
import build.jenesis.repository.walk.WalkProvider;

/**
 * Contributes the reclamation capability flags to {@code /api/capabilities}: {@code walk} (an artifact walk resolves;
 * without one no walk-riding pass enumerates anything) and {@code gc} (a collector resolves; {@code false} means
 * nothing is ever reclaimed). Both resolve against the live configuration - a selection or a required setting can turn
 * either off - so each is re-resolved per read. This module reports them, so the server does not reach into the
 * {@code gc} and {@code walk} SPIs.
 */
public final class ReclamationCapabilityContributor implements CapabilityContributor {

    @Override
    public Map<String, Object> capabilities(UnaryOperator<String> effectiveConfig) {
        Map<String, Object> flags = new LinkedHashMap<>();
        flags.put("walk", WalkProvider.resolve(effectiveConfig).isPresent());
        flags.put("gc", GarbageCollectorProvider.resolve(effectiveConfig).isPresent());
        return flags;
    }
}
