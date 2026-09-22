package build.jenesis.repository.cleanup.task;

import module java.base;
import build.jenesis.repository.gc.GarbageCollectorProvider;
import build.jenesis.repository.server.spi.CapabilityContributor;
import build.jenesis.repository.walk.WalkProvider;

/**
 * Contributes the reclamation engine's capability flags to {@code /api/capabilities}: {@code walk} (an artifact-walk
 * implementation resolves - without one no walk-riding pass enumerates anything) and {@code gc} (a garbage collector
 * resolves - {@code false} means nothing is ever reclaimed, the GC SPI's no-op-by-absence default). Both are
 * exclusive discovered capabilities resolved against the live configuration - a selection or a required setting can
 * turn either off without a module change - so each is re-resolved per read from the effective config rather than
 * pinned. This is the reclamation module's own report; the neutral server no longer reaches into the {@code gc} and
 * {@code walk} SPIs to build these flags.
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
