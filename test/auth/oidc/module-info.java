/**
 * Tests for the OIDC / OAuth2 sign-in module's principal mapping: the provider-qualified id re-keying that makes an
 * authenticated principal's name {@code <provider>/<sub>} - matching the key tenants store members under - is proven
 * over the real {@link build.jenesis.repository.ui.QualifiedOidcUser}, and {@code PrincipalServiceLoadUserTest} drives the
 * two services' {@code loadUser} end-to-end: {@code OidcPrincipalService}/{@code OAuth2PrincipalService} re-key a
 * tenant member to {@code <provider>/<sub>} and refuse a user of no tenant with an {@code OAuth2AuthenticationException}
 * (the fail-closed deny), delegating the decision to the real
 * {@code build.jenesis.repository.ui.identity.LoginAuthorization} over a real filesystem membership store. The OIDC leg builds the
 * user from the id token with no user-info endpoint (no network); the OAuth2 leg fetches a loopback user-info endpoint.
 * That end-to-end leg needs the console security / store packages exported to this test module (see the fix report).
 *
 * @jenesis.release 25
 * @jenesis.exclude spring.security.oauth2.client com.nimbusds/oauth2-oidc-sdk
 * @jenesis.test build.jenesis.repository.auth.oidc
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.auth.oidc.test {
    requires build.jenesis.repository.auth.oidc;
    requires build.jenesis.repository.ui;
    requires build.jenesis.repository.ui.admin;
    requires build.jenesis.repository.ui.identity;
    requires build.jenesis.repository.ui.store;
    requires build.jenesis.repository.cache.storage;
    requires build.jenesis.repository.cache.storage.delegating;
    requires build.jenesis.repository.cache.storage.testkit;
    requires build.jenesis.repository.store.filesystem;
    requires spring.security.core;
    requires spring.security.oauth2.core;
    requires spring.security.oauth2.client;
    requires spring.security.oauth2.jose;
    requires org.junit.jupiter;
    requires org.assertj.core;
    requires jdk.httpserver;
}
