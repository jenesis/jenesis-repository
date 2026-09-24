package build.jenesis.repository.ui;

import module java.base;

/**
 * One console page a module contributes through {@link ConsoleModuleProvider#navEntries()}: its {@code label}, the
 * {@code path} it addresses, the minimum {@link Access} a user needs to see it, the {@link Group} the shell files it
 * under, and the capability it {@code requires}, if any. The shell renders the visible entries rather than a
 * hardcoded list, so a removable console module adds its own page by being installed - its provider is discovered
 * only when the module is on the path, which is itself the capability gate - and the shell names no module's screens.
 *
 * <p><strong>{@code path} means one thing: an application-root-relative path</strong> ({@code /walks},
 * {@code /settings/modules}), unique across installed modules. The shell links it and matches the request against it
 * to decide which page, and so which group, is current: the entry whose path is the longest prefix of the request
 * path, on a segment boundary, is the one a reader is on.
 *
 * <p>Access is a coarse role floor, resolved server-side against the current tenant so the template carries no
 * per-entry condition: {@link Access#USER} shows to any signed-in console user, {@link Access#ADMIN} to a tenant admin
 * (or a super-admin, who is admin everywhere), {@link Access#SUPERADMIN} only to the deployment super-admin.
 *
 * @param requires the capability the page needs beyond its module being installed - a name the console's capability
 *                 service answers, such as {@code audit} - or the empty string when the module's presence is enough.
 *                 It exists for the page whose module is present while the thing it renders is not: the audit
 *                 trail's screen is the console's own, and whether there is a trail to show is another module's.
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
     * The first navigation level: what a page is about, which is the choice a reader makes before choosing a page.
     *
     * <p>A module says which group its page belongs to and the shell decides everything else - the order of the
     * groups, which of them a reader sees, and where each one leads. A group is shown only when it holds a page the
     * reader may open, and it leads to the first of them, so a group can never be a link to nothing.
     *
     * <p>They were two sections before - a bar of daily objects and an "Administration" dropdown of everything else -
     * and the dropdown was where most pages lived: nine of them, reached by opening a menu that closed again on every
     * page. Five groups a reader can see at once replace it, each with its pages beside the content.
     */
    public enum Group {

        /** The artifacts the product serves, and everything about one repository. */
        REPOSITORIES("Repositories"),

        /** The build cache's projects. */
        BUILD_CACHE("Build cache"),

        /** Who may do what: credentials, members, and the record of what they did. */
        ACCESS("Access"),

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
        USER, ADMIN, SUPERADMIN
    }
}
