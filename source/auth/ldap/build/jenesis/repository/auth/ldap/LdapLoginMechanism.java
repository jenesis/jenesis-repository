package build.jenesis.repository.auth.ldap;

import build.jenesis.repository.ui.ConsoleModuleProvider;

/** Directory sign-in as a console module: discovered, and switched on by configuring a directory URL. */
public final class LdapLoginMechanism implements ConsoleModuleProvider {

    /** The mechanism's name, its toggle key and the registration its principals are qualified with. */
    public static final String NAME = "ldap";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Class<?> configuration() {
        return LdapLoginConfig.class;
    }
}
