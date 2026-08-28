package build.jenesis.repository.ui;

/**
 * One console navigation link a module contributes through {@link ConsoleModuleProvider#navEntries()}: its {@code label},
 * the {@code path} it addresses, and the minimum {@link Access} a user needs to see it. The shell renders the visible
 * entries with a {@code th:each} rather than a hardcoded {@code <li>}-per-screen list, so a removable console module adds
 * its own nav link by being installed - its provider is discovered only when the module is on the path, which is itself
 * the capability gate - and the shell names no module's screens.
 *
 * <p><strong>{@code path} means one thing: an application-root-relative path</strong> ({@code /scim},
 * {@code /scim/users}), unique across installed modules. The accessor is named for that meaning because the previous
 * name - {@code href} - invited a second reading, and the product's two consumers took one each: the full console shell
 * renders it as a link target. A path addresses any page and a single-page shell could still map path to tab -
 * one did, until the redundant shell was removed - while a section id can never address
 * another page, so the path reading is the one that does not run out. A consumer that toggles in-page sections derives
 * its own id from the path rather than asking a module for one.
 *
 * <p>Access is a coarse role floor, resolved server-side against the current tenant so the template carries no
 * per-entry condition: {@link Access#USER} shows to any signed-in console user, {@link Access#ADMIN} to a tenant admin
 * (or a super-admin, who is admin everywhere), {@link Access#SUPERADMIN} only to the deployment super-admin. A
 * capability finer than module presence stays a core concern the shell resolves for its own entries.
 */
public record NavEntry(String label, String path, Access access, Section section) {

    /** A nav entry any signed-in console user may see, in the primary bar. */
    public NavEntry(String label, String path) {
        this(label, path, Access.USER, Section.PRIMARY);
    }

    /** A nav entry with an access floor, in the primary bar. */
    public NavEntry(String label, String path, Access access) {
        this(label, path, access, Section.PRIMARY);
    }

    /**
     * Where the shell puts the entry.
     *
     * <p>Grouping is the shell's business - a module says it has a screen, not where the screen belongs in
     * someone else's navigation - but the shell cannot infer *what kind* of screen it is, and guessing from the
     * path is the sort of rule that is wrong the first time a module picks a different prefix. So a module
     * declares the kind and the shell decides the placement.
     *
     * <p>The distinction that matters is how often a person needs it. A flat bar of everything is the shape that
     * makes a tool feel like a cockpit: a super-admin saw twelve links with no grouping, of which eight were
     * settings-shaped and reached maybe twice a year, and the four that get daily use were somewhere among them.
     */
    public enum Section {

        /** The objects the product is about, reached constantly: repositories, projects, credentials. */
        PRIMARY,

        /** Configuration and inspection - needed rarely, and grouped so it is one thing to look past. */
        ADMINISTRATION
    }

    /** The minimum role a nav entry is shown to. */
    public enum Access {
        USER, ADMIN, SUPERADMIN
    }
}
