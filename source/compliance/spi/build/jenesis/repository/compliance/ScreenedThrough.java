package build.jenesis.repository.compliance;

import module java.base;

/**
 * What a version was screened through, as every surface says it, so a version nothing could screen never reads as
 * clean. A cached copy is screened by its own coordinate through the enabled advisory feeds whose declared coverage
 * ({@link AdvisorySource#ecosystems()}) includes its ecosystem - {@link Basis#FEEDS}, naming them - or through none of
 * them, {@link Basis#UNCOVERED}, when no enabled feed covers it. A published version is asked of no feed and is
 * screened through its closure, {@link Basis#CLOSURE}, or through nothing while it has none, {@link Basis#NOTHING}.
 */
public record ScreenedThrough(Basis basis, List<String> feeds) {

    /** How a version was screened. */
    public enum Basis {
        /** A cached copy, asked of {@link #feeds()} by its coordinate. */
        FEEDS,
        /** A cached copy no enabled feed covers the ecosystem of: unscreened, not clean. */
        UNCOVERED,
        /** A published version, screened through what its closure reaches. */
        CLOSURE,
        /** A published version with no closure: nothing has screened it. */
        NOTHING
    }

    public ScreenedThrough {
        feeds = List.copyOf(feeds);
    }

    /** A cached copy of {@code ecosystem} under the enabled feeds {@code enabled}, by name in their order. */
    public static ScreenedThrough cached(String ecosystem, SequencedMap<String, AdvisorySource> enabled) {
        String canonical = Ecosystems.canonical(ecosystem);
        List<String> covering = new ArrayList<>();
        enabled.forEach((name, feed) -> {
            if (feed.ecosystems().contains(canonical)) {
                covering.add(name);
            }
        });
        return covering.isEmpty() ? new ScreenedThrough(Basis.UNCOVERED, List.of())
                : new ScreenedThrough(Basis.FEEDS, covering);
    }

    /** A cached copy of {@code ecosystem} held by a repository whose effective lookup is {@code repository}, under the
     *  feeds of {@code enabled} it selects ({@link AdvisorySource#selected}): unscreened where its selection names a
     *  feed that is not on, since its screens then fail. */
    public static ScreenedThrough cached(String ecosystem, SequencedMap<String, AdvisorySource> enabled,
                                         UnaryOperator<String> repository) {
        try {
            return cached(ecosystem, AdvisorySource.selected(enabled, repository));
        } catch (IllegalStateException misnamed) {
            return new ScreenedThrough(Basis.UNCOVERED, List.of());
        }
    }

    /** A published version, with a closure or without one. */
    public static ScreenedThrough published(boolean closure) {
        return new ScreenedThrough(closure ? Basis.CLOSURE : Basis.NOTHING, List.of());
    }

    /** Whether nothing screened the version: no feed covers it, or it has no closure. */
    public boolean unscreened() {
        return basis == Basis.UNCOVERED || basis == Basis.NOTHING;
    }
}
