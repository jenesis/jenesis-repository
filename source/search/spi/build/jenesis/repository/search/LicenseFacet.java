package build.jenesis.repository.search;

/**
 * One row of the license inventory: a facet {@code kind} ({@code "category"} for a coarse license class, or
 * {@code "license"} for a resolved SPDX id), the facet {@code value} (e.g. {@code permissive} or {@code Apache-2.0}),
 * and the {@code count} of coordinates in this repository carrying it. The {@code /api/licenses} view groups these by
 * kind for its facet columns, and each drills down through {@code /api/search?q=category:<value>} or
 * {@code license:<value>} to the coordinates behind the count.
 */
public record LicenseFacet(String kind, String value, long count) {

    public static final String CATEGORY = "category";
    public static final String LICENSE = "license";
}
