package build.jenesis.repository.auth.keylogin;

import build.jenesis.repository.ui.ConsoleModuleProvider;

/**
 * Discovers the key-based sign-in mechanism: the console imports {@link KeyLoginConfig} exactly like a Boot
 * auto-configuration, whose condition keeps every bean away when {@code jenreg.key-login=false} - so this module
 * installed but switched off still means "sign-in not offered", matching the OIDC and LDAP mechanisms. Named
 * {@code key-login}, a {@link ConsoleModuleProvider} sibling to {@code oidc} and {@code ldap}.
 *
 * <p><b>It is on unless switched off</b> ({@link #onByDefault()}, the one definition the condition and the catalogue
 * both read). Off, a deployment that had configured nothing could be signed in to by nobody: the only way in would
 * be an admin key the operator had to invent and pass in beside the switch. On by default, a fresh start prints a
 * {@link FirstRunKey} and the console is reachable from one {@code docker run}. It accepts no key
 * that was not printed, set or issued, so on costs nothing on a deployment that signs people in some other way;
 * switching it off is how that deployment says so.
 *
 * <p>The name is the module's toggle key, and it is deliberately the <em>same</em> spelling as the enablement gate
 * {@link KeyLoginSettingsContributor} catalogues and {@link KeyLoginConfig.KeyLoginEnabled} reads. A toggle one
 * spelling out from those - {@code jenreg.keylogin} beside {@code jenreg.key-login} - would let the documented switch
 * reach the module's beans but never its import, leaving the undocumented one the only way to un-import it.
 */
public final class KeyLoginMechanism implements ConsoleModuleProvider {

    /** The <b>operator</b> spelling: the module name, its {@code jenreg.<name>} toggle, the settings key
     *  {@link KeyLoginSettingsContributor} catalogues and the property {@link KeyLoginConfig.KeyLoginEnabled} reads.
     *  Every one of those four is rendered from this constant, so the four cannot drift apart. */
    public static final String NAME = "key-login";

    /** The <b>durable</b> spelling, and it is a different string on purpose. It qualifies a login principal
     *  ({@code ProviderPrincipal.qualifiedId}) into the user directory and the issued-key index, and it is the mechanism
     *  token on every {@code login} / {@code login.failed} / {@code login.throttled} / {@code keylogin.issue} audit
     *  row. Both are durable, so <b>this one may never be renamed</b>: changing it would orphan every issued key's
     *  qualified principal id and split the audit trail into a before and an after. Two spellings scattered across
     *  files with nothing binding them would let a rename of either quietly take the other's sites with it, so they
     *  are bound here, side by side, with the reason there are two. */
    public static final String QUALIFIER = "keylogin";

    /** The one definition of the switch's default: on. A compile-time text constant, so the settings contributor
     *  declares exactly this and the generated reference - which reads it out of the class file - shows exactly this. */
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
}
