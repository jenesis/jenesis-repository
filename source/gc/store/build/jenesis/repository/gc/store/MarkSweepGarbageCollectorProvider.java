package build.jenesis.repository.gc.store;

import build.jenesis.repository.format.BlobReferences;
import build.jenesis.repository.gc.GarbageCollector;
import build.jenesis.repository.gc.GarbageCollectorProvider;
import build.jenesis.repository.walk.WalkProvider;

import module java.base;

/**
 * Provides the {@link MarkSweepGarbageCollector} as {@code mark-sweep} - the default selection when no
 * {@code jenreg.gc} names another. It rides the shared artifact walk, so with no walk implementation
 * installed (or one configured off) it resolves to empty and the deployment simply has no garbage collection - the
 * SPI's no-op default, never a collector that enumerates its own way. Settings, read through the config lookup:
 * {@code jenreg.gc.stride} - the checkpoint stride of the collector's own walk passes (default 20000), which
 * bounds three things at once: the reference batch a mark buffers in memory, the re-work a crash costs, and how
 * often a segment claim is renewed (keep stride x per-item time well under {@code jenreg.walk.ttl}). A malformed
 * value fails loudly rather than collecting with a silently-wrong stride.
 *
 * <p>{@code jenreg.gc.grace} - an optional ISO-8601 wall-clock floor on the condemn-to-collect grace (default
 * {@code PT0S}, i.e. purely generation-based: condemn in one pass, collect in the next). Set it to guarantee a blob
 * carries its condemned marker for at least this long before deletion even when generations advance faster than the
 * collection interval - several nodes collecting, or a node re-collecting after a lease expiry. It only ever delays a
 * deletion, so it never reclaims a blob the generation gap would spare.
 *
 * <p>It also resolves the installed {@link BlobReferences} formats once, here, and hands them to the collector: the
 * discovery lives at the provider like every other provider's does, so the collector stays a mechanism a test can hand
 * an explicit list. With no blobs-namespace format installed the list is empty and the mark is the pointer-body-only
 * scan it has always been.
 */
public final class MarkSweepGarbageCollectorProvider implements GarbageCollectorProvider {

    @Override
    public String name() {
        return "mark-sweep";
    }

    @Override
    public Optional<GarbageCollector> create(UnaryOperator<String> config) {
        String stride = Integer.toString(integer(config, "gc.stride", 20_000));
        Duration grace = duration(config, "gc.grace");
        List<BlobReferences> lenders = BlobReferences.installed();
        return WalkProvider.resolve(key ->
                        "walk.checkpoint".equals(key) ? stride : config.apply(key))
                .map(walk -> {
                    MarkSweepGarbageCollector collector = new MarkSweepGarbageCollector(walk, grace, lenders);
                    MarkSweepGarbageCollector.install(collector);     // the discovered observability reads this one
                    return collector;
                });
    }

    private static Duration duration(UnaryOperator<String> config, String key) {
        String value = config.apply(key);
        if (value == null || value.isBlank()) {
            return Duration.ZERO;
        }
        Duration duration;
        try {
            duration = Duration.parse(value.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Not an ISO-8601 duration: " + key + "=" + value, e);
        }
        if (duration.isNegative()) {
            throw new IllegalArgumentException("Must not be negative: " + key + "=" + value);
        }
        return duration;
    }

    private static int integer(UnaryOperator<String> config, String key, int fallback) {
        String value = config.apply(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not an integer: " + key + "=" + value, e);
        }
    }
}
