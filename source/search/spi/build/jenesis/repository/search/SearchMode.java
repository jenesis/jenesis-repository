package build.jenesis.repository.search;

import module java.base;

/**
 * How a repository answers a search, chosen per repository by the {@value #SETTING} setting, off by default.
 *
 * <p>{@link #NAME} is a lookup of a coordinate by the start of its name, answered from the version documents the
 * repository already keeps sorted: a bounded page of point reads and a cursor, and nothing built or stored for it.
 * {@link #FULL_TEXT} is the full-text index, built and kept by a background pass, answering free text over names,
 * descriptions, keywords and authors. The index is what a repository pays for - its build, its storage and the
 * pass that keeps it - so it is asked for repository by repository rather than paid for everywhere.
 */
public enum SearchMode {

    /** A lookup of a coordinate by the start of its name, with no index. */
    NAME,

    /** The full-text index. */
    FULL_TEXT;

    /** The repository setting that switches a repository's full-text index on. */
    public static final String SETTING = "full-text-search";

    /** Off: a repository answers by name until it is asked for more. */
    public static final String DEFAULT = "false";

    /** The mode {@code config} - a repository's effective configuration - chooses; {@link #NAME} unless the setting
     *  says {@code true}. */
    public static SearchMode of(UnaryOperator<String> config) {
        String value = config.apply(SETTING);
        return Boolean.parseBoolean((value == null || value.isBlank() ? DEFAULT : value).trim()) ? FULL_TEXT : NAME;
    }
}
