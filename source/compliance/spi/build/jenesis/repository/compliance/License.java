package build.jenesis.repository.compliance;

import module java.base;

/**
 * A resolved license: its SPDX identifier and a category (permissive, weak-copyleft, strong-copyleft,
 * network-copyleft, or a category an operator defined for a licence of their own), or {@link #UNKNOWN} when a
 * declared name or URL matches nothing known. A declaration is resolved through a {@link LicenseTable} - the built-in
 * rows and whatever an operator configured beside them - never by the record itself, so no caller can identify a
 * licence while ignoring the rows the deployment added.
 */
public record License(String spdxId, String category) {

    public static final License UNKNOWN = new License(null, "unknown");

    public boolean identified() {
        return spdxId != null;
    }
}
