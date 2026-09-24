/**
 * OIDC / OAuth2 (GitHub or any OpenID Connect issuer) console sign-in as a removable module: it provides
 * {@link build.jenesis.repository.ui.ConsoleModuleProvider}, so the console imports its configuration through
 * {@code ServiceLoader} discovery and names no mechanism. With this module absent the console starts with sign-in
 * disabled (the login page says so); installed but unconfigured behaves the same until a provider is configured
 * ({@code JENREG_UI_GITHUB_CLIENT_ID} / {@code JENREG_UI_OIDC_ISSUER_URI} and their credentials - the same keys
 * as before the split). Another mechanism is added the same way - as its own module.
 *
 * <p><b>LDAP bind exists beside these two, and what it costs is still true.</b> An LDAP <em>bind</em> - the
 * application taking a username and password and binding to the directory as that user - lets the application see
 * the password, which is the entire difference from SAML and OIDC, where a credential never reaches the relying
 * party. It also puts MFA out of reach (a bind cannot express a challenge), removes step-up and central session
 * revocation, and adds one more place a credential can leak. It was added anyway, as {@code auth/ldap}, because a
 * directory is what many deployments have and every incumbent binds against one; that module refuses a plaintext
 * connection by default and holds nothing about the password. Where SAML or OIDC is
 * available it remains the better choice, and nothing here should steer an operator away from it.
 *
 * <p>An LDAP <em>search</em> is a different thing entirely and is not a sign-in mechanism at all: bind as a
 * service account, find the user, read {@code memberOf}, with authentication happening elsewhere. That half is
 * worth having - Entra ID replaces group claims with a Graph link past a couple of hundred groups, organisations
 * keep tokens small on purpose, and an AD estate's authoritative group data may not reach the federation layer -
 * and it belongs behind the group-source seam as a pull alternative to SCIM's push, carrying no login mechanism
 * and no password handling.
 *
 * @jenesis.release 25
 * @jenesis.exclude spring.security.oauth2.client com.nimbusds/oauth2-oidc-sdk
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.auth.oidc {
    exports build.jenesis.repository.auth.oidc to build.jenesis.repository.auth.oidc.test;
    requires build.jenesis.repository.ui;
    requires spring.beans;
    requires spring.boot;
    requires spring.context;
    requires spring.core;
    requires spring.security.config;
    requires spring.security.core;
    requires spring.security.web;
    requires spring.security.oauth2.client;
    requires spring.security.oauth2.core;
    provides build.jenesis.repository.ui.ConsoleModuleProvider
            with build.jenesis.repository.auth.oidc.OidcLoginMechanism;
}
