package build.jenesis.repository.auth.keylogin;

import module java.base;
import build.jenesis.repository.ui.ConsoleModuleProvider;
import build.jenesis.repository.ui.NavEntry;

/**
 * Discovers the key-based sign-in mechanism: the console imports {@link KeyLoginConfig} like a Boot auto-configuration,
 * whose condition keeps every bean away when {@code jenrepo.key-login=false}. A {@link ConsoleModuleProvider} sibling
 * to {@code oidc} and {@code ldap}.
 *
 * <p><b>On unless switched off</b> ({@link #onByDefault()}): otherwise a deployment that configured nothing could be
 * signed in to only with an admin key the operator invented. On, a fresh start prints a {@link FirstRunKey} and the
 * console is reachable from one {@code docker run}. It accepts no key that was not printed, set or issued, so on costs
 * nothing where people sign in another way; switching it off says so.
 *
 * <p>The name is the module's toggle key and the same spelling the settings gate and
 * {@link KeyLoginConfig.KeyLoginEnabled} read, so the documented switch reaches both the import and the beans.
 */
public final class KeyLoginMechanism implements ConsoleModuleProvider {

    /** The <b>operator</b> spelling: the module name, its {@code jenrepo.<name>} toggle, the settings key
     *  {@link KeyLoginSettingsContributor} catalogues and the property {@link KeyLoginConfig.KeyLoginEnabled} reads,
     *  all rendered from this constant. */
    public static final String NAME = "key-login";

    /** The <b>durable</b> spelling, deliberately different. It qualifies a login principal
     *  ({@code ProviderPrincipal.qualifiedId}) in the user directory and the issued-key index, and is the mechanism
     *  token on every {@code login}, {@code login.failed}, {@code login.throttled} and {@code keylogin.issue} audit
     *  row. <b>It may never be renamed</b>: that would orphan every issued key's principal and split the audit trail.
     *  The two spellings sit side by side so a rename of one cannot silently take the other's sites. */
    public static final String QUALIFIER = "keylogin";

    /** The switch's default: on. A compile-time constant, so the settings contributor declares exactly it and the
     *  generated reference reads it from the class file. */
    public static final String ON_BY_DEFAULT = "true";

    /** Whether the switch is on where nothing has said otherwise: {@link #ON_BY_DEFAULT}. */
    public static boolean onByDefault() {
        return Boolean.parseBoolean(ON_BY_DEFAULT);
    }

    @Override
    public boolean enabledByDefault() {
        return onByDefault();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Class<?> configuration() {
        return KeyLoginConfig.class;
    }

    /** The issued keys' screen, under Settings: a login key can bind a principal into any tenant, so it is the
     *  super-admin's. */
    @Override
    public List<NavEntry> navEntries() {
        return List.of(new NavEntry("Login keys", KeyLoginScreenController.ROUTE, NavEntry.Access.SUPERADMIN,
                NavEntry.Group.SETTINGS));
    }
}
