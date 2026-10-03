package build.jenesis.repository.format;

import module java.base;

/** The mark a version can carry over its life, which a format shows its clients in the metadata they read. */
public enum LifecycleMark {

    /** The version is discouraged but still resolvable - npm renders a {@code deprecated} warning. */
    DEPRECATED,

    /** The version is withdrawn - Cargo renders it {@code yanked}, so a resolver skips it unless already pinned. */
    YANKED;

    /** The mark's own word, lower case: {@code deprecated}, {@code yanked}. */
    public String word() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parse a case-insensitive mark name ({@code deprecated} / {@code yanked}), or empty when unrecognised. */
    public static Optional<LifecycleMark> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        for (LifecycleMark mark : values()) {
            if (mark.name().equalsIgnoreCase(trimmed)) {
                return Optional.of(mark);
            }
        }
        return Optional.empty();
    }

    /** {@code marks}, each called by its own word, as a format whose ecosystem has no other word for one declares
     *  what it shows ({@link RepositoryFormat#lifecycleMarks()}). */
    public static Map<LifecycleMark, String> shown(LifecycleMark... marks) {
        Map<LifecycleMark, String> shown = new EnumMap<>(LifecycleMark.class);
        for (LifecycleMark mark : marks) {
            shown.put(mark, mark.word());
        }
        return Collections.unmodifiableMap(shown);
    }
}
