package build.jenesis.repository.search;

import module java.base;

/**
 * How a repository answers a search, chosen per repository by {@value #SETTING}, off by default. {@link #NAME} looks a
 * coordinate up by the start of its name from the version documents the repository already keeps sorted - a bounded
 * page of point reads and a cursor, nothing built. {@link #FULL_TEXT} is the full-text index a background pass builds
 * and keeps, answering free text over names, descriptions, keywords and authors; its build, storage and pass are a
 * cost, so it is asked for per repository.
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

    /** The mode a repository's effective configuration {@code config} chooses: {@link #NAME} unless the setting says
     *  {@code true}. */
    public static SearchMode of(UnaryOperator<String> config) {
        String value = config.apply(SETTING);
        return Boolean.parseBoolean((value == null || value.isBlank() ? DEFAULT : value).trim()) ? FULL_TEXT : NAME;
    }
}
