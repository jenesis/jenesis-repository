package build.jenesis.repository.auth.keylogin;

import build.jenesis.repository.ui.ConsoleModuleProvider;

/**
 * Discovers the key-based sign-in mechanism: the console imports {@link KeyLoginConfig} exactly like a Boot
 * auto-configuration, whose condition keeps every bean away until {@code jenreg.key-login=true} - so this
 * module installed but disabled still means "sign-in not offered", matching the OIDC and LDAP mechanisms. Named
 * {@code key-login}, a {@link ConsoleModuleProvider} sibling to {@code oidc} and {@code ldap}.
 *
 * <p>The name is the module's toggle key, and it is deliberately the <em>same</em> spelling as the enablement gate
 * {@link KeyLoginSettingsContributor} catalogues and {@link KeyLoginConfig.KeyLoginEnabled} reads. It used to be
 * {@code keylogin}, one spelling out from both: the import selector switched on
 * {@code jenreg.keylogin} while every operator-facing surface - the settings catalogue, the modules
 * console toggle, this module's own condition - named {@code jenreg.key-login}, so the documented switch
 * reached the module's beans but never its import, and the undocumented one was the only way to un-import it.
 */
public final class KeyLoginMechanism implements ConsoleModuleProvider {

    /** The <b>operator</b> spelling: the module name, its {@code jenreg.<name>} toggle, the settings key
     *  {@link KeyLoginSettingsContributor} catalogues and the property {@link KeyLoginConfig.KeyLoginEnabled} reads.
     *  Every one of those four is rendered from this constant, so the four cannot drift the way found them. */
    public static final String NAME = "key-login";

    /** The <b>durable</b> spelling, and it is a different string on purpose. It qualifies a login principal
     *  ({@code ProviderPrincipal.qualifiedId}) into the user directory and the issued-key index, and it is the mechanism
     *  token on every {@code login} / {@code login.failed} / {@code login.throttled} / {@code keylogin.issue} audit
     *  row. Both are durable, so <b>this one may never be renamed</b>: changing it would orphan every issued key's
     *  qualified principal id and split the audit trail into a before and an after. What fixed was the
     *  <em>operator</em> spelling leaking into the toggle; what remained was that the two spellings sat in five files
     *  with nothing binding them, so a later rename of either could quietly take the other's sites with it. They are
     *  bound here, side by side, with the reason there are two. */
    public static final String QUALIFIER = "keylogin";

    @Override
    public boolean enabledByDefault() {
        // Disabled unless switched on, matching this module's catalogue entry: a pasted-key sign-in is a demo and
        // simple-deployment on-ramp, and production should prefer OIDC or LDAP.
        return false;
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
