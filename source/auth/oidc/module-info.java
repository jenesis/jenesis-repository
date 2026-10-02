/**
 * OIDC and OAuth2 console sign-in (GitHub or any OpenID Connect issuer), contributed through
 * {@link build.jenesis.repository.ui.ConsoleModuleProvider}. Absent, or installed with no provider configured
 * ({@code JENREPO_UI_GITHUB_CLIENT_ID} / {@code JENREPO_UI_OIDC_ISSUER_URI} and their credentials), the console starts
 * with this sign-in disabled and the login page says so.
 *
 * <p>Here a credential never reaches the console, unlike the LDAP bind of {@code auth/ldap}, which sees the password
 * and cannot express a second factor, a step-up or a central revocation; where an issuer is available, this is the
 * better choice.
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
