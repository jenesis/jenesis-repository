package build.jenesis.repository.ui;

import module java.base;

/**
 * One console page a module contributes through {@link ConsoleModuleProvider#navEntries()}: its label, path, minimum
 * {@link Access}, {@link Group} and required capability. The shell renders the visible entries, so a module adds its
 * page by being installed and the shell names no module's screens.
 *
 * <p>{@code path} is an application-root-relative path ({@code /walks}), unique across modules. The current page is the
 * entry whose path is the longest segment-boundary prefix of the request path.
 *
 * <p>Access is a coarse role floor resolved server-side against the current tenant: {@link Access#USER} for any
 * signed-in user, {@link Access#ADMIN} for a tenant admin or super-admin, {@link Access#SUPERADMIN} for the deployment
 * super-admin alone.
 *
 * @param requires a capability the console answers, such as {@code audit}, that the page needs beyond its module, or
 *                 empty: the audit screen is the console's own, while whether there is a trail is another module's
 */
public record NavEntry(String label, String path, Access access, Group group, String requires) {

    public NavEntry {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(requires, "requires");
    }

    /** A page any signed-in console user may see, needing nothing beyond its module. */
    public NavEntry(String label, String path, Group group) {
        this(label, path, Access.USER, group, "");
    }

    /** A page with an access floor, needing nothing beyond its module. */
    public NavEntry(String label, String path, Access access, Group group) {
        this(label, path, access, group, "");
    }

    /**
     * The first navigation level: what a page is about. A module names its group and the shell decides the rest; a
     * group is shown only when it holds a page the reader may open, and leads to the first of them.
     */
    public enum Group {

        /** The artifacts the product serves, and everything about one repository. */
        REPOSITORIES("Repositories"),

        /** The build cache's projects. */
        BUILD_CACHE("Build cache"),

        /** Who may do what: credentials, members, and the record of what they did. */
        ACCESS("Access"),

        /** The tenants a deployment serving several of them holds, and the one being worked in. */
        TENANTS("Tenants"),

        /** What the deployment is doing: its background passes, its metrics, its posture, a manual upload. */
        OPERATIONS("Operations"),

        /** How the deployment is configured. */
        SETTINGS("Settings");

        private final String label;

        Group(String label) {
            this.label = label;
        }

        /** The name the header shows. */
        public String label() {
            return label;
        }
    }

    /** The minimum role a nav entry is shown to. */
    public enum Access {
        USER, EDITOR, ADMIN, SUPERADMIN
    }
}
