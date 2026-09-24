/**
 * Console sign-in against an LDAP or Active Directory server, and the directory's groups as the console's groups.
 *
 * <p>A person signs in with their directory name and password; the console binds as them to check it, and names them
 * {@code ldap/<username>}. Their directory groups are then reconciled into the deployment's group memberships under
 * the source {@code ldap} on every sign-in, so a role an operator grants a group applies to its members and is gone
 * from someone the directory has removed from it the next time they sign in; a configured administrators group
 * confers super-admin. Nothing is stored about the password, and it is never logged.
 *
 * <p>A bind means the server sees the password the person typed, which single sign-on avoids, and it cannot
 * express a second factor, a step-up or a central session revocation. The module is here because a directory is what
 * many deployments have; where OIDC or SAML is available it is the better choice. It refuses the case that makes the
 * exposure worse: a plaintext {@code ldap://} URL is refused at boot unless StartTLS is on, or an operator says in so
 * many words that the connection is private.
 *
 * <p>Configured at boot from {@code jenreg.ui.ldap.*}; with no {@code url} the module contributes nothing.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.auth.ldap {
    requires build.jenesis.repository.ui;
    requires build.jenesis.repository.server.spi;
    requires build.jenesis.repository.audit;
    requires org.slf4j;
    requires spring.beans;
    requires spring.context;
    requires spring.core;
    requires spring.web;
    requires spring.boot;
    requires spring.security.config;
    requires spring.security.core;
    requires spring.security.web;
    requires spring.security.ldap;
    requires spring.ldap.core;
    requires java.naming;
    requires thymeleaf.spring6;
    exports build.jenesis.repository.auth.ldap to build.jenesis.repository.auth.ldap.test,
            build.jenesis.repository.auth.ldap.e2e;
    provides build.jenesis.repository.ui.ConsoleModuleProvider
            with build.jenesis.repository.auth.ldap.LdapLoginMechanism;
}
