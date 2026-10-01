package build.jenesis.repository.gc.store;

import module java.base;

import build.jenesis.repository.format.BlobReferences;
import build.jenesis.repository.gc.GarbageCollector;
import build.jenesis.repository.gc.GarbageCollectorProvider;
import build.jenesis.repository.walk.WalkProvider;
import build.jenesis.repository.store.Durations;

/**
 * Provides the {@link MarkSweepGarbageCollector} as {@code mark-sweep}, the default when {@code jenrepo.gc} names no
 * other. It rides the shared artifact walk, so with no walk installed (or one switched off) it resolves to empty and
 * the deployment has no collection - never a collector that enumerates on its own.
 *
 * <p>{@code jenrepo.gc.stride} is the checkpoint stride of the collector's walk passes (default 20000): it bounds the
 * reference batch a mark buffers, the re-work a crash costs, and how often a segment claim is renewed - keep stride
 * times per-item time well under {@code jenrepo.walk.ttl}. A malformed value fails loudly.
 *
 * <p>{@code jenrepo.gc.grace} is an ISO-8601 wall-clock floor on the condemn-to-collect grace, on top of the later-pass
 * rule: {@link GarbageCollector#defaultGrace()} when unset, {@code PT0S} for the generation rule alone. It can only
 * delay a deletion.
 *
 * <p>The installed {@link BlobReferences} formats are resolved here and handed to the collector, so the collector stays
 * a mechanism a test can hand an explicit list.
 */
public final class MarkSweepGarbageCollectorProvider implements GarbageCollectorProvider {

    @Override
    public String name() {
        return "mark-sweep";
    }

    @Override
    public Optional<GarbageCollector> create(UnaryOperator<String> config) {
        String stride = Integer.toString(integer(config, "gc.stride", 20_000));
        Duration grace = duration(config, "gc.grace", GarbageCollector.defaultGrace());
        List<BlobReferences> lenders = BlobReferences.installed();
        return WalkProvider.resolve(key ->
                        "walk.checkpoint".equals(key) ? stride : config.apply(key))
                .map(walk -> new MarkSweepGarbageCollector(walk, grace, lenders));
    }

    private static Duration duration(UnaryOperator<String> config, String key, Duration fallback) {
        String value = config.apply(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        Duration duration;
        try {
            duration = Durations.parse(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Not a duration (PT1H, 6h): " + key + "=" + value, e);
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
