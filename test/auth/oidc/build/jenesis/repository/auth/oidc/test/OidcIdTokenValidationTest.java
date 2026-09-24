package build.jenesis.repository.auth.oidc.test;

import module java.base;
import module org.junit.jupiter.api;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OIDC id-token validation the audit flagged - an expired or wrong-audience id token must be refused, never
 * admitted on its signature alone.
 *
 * <p><strong>The reachable seam, and its limit.</strong> The console does not re-validate the id token in its own
 * code: {@code OidcPrincipalService.loadUser} extends Spring's {@code OidcUserService}, which is handed an
 * {@code OidcIdToken} that Spring's authorization-code machinery has <em>already</em> decoded and validated - the
 * {@code JwtDecoder} that {@code OidcIdTokenDecoderFactory} builds from the client registration
 * {@code OAuth2ClientConfig} discovers ({@code ClientRegistrations.fromIssuerLocation}) runs the signature check
 * (needs the provider's live JWKS) and then this {@link OidcIdTokenValidator} over the claims. That end-to-end path
 * cannot be reached offline - there is no live IdP to sign a token or publish a JWKS - so this test exercises the
 * one part of it that is deterministic and network-free: the claim validator itself, built from a registration
 * shaped exactly as the console builds the generic "oidc" one (issuer set, {@code clientId} the sole audience). It
 * proves an expired and a wrong-audience id token are refused by the layer that guards {@code loadUser}, and pins
 * that the console's registration carries the audience and issuer that make those refusals fire. The tenant
 * authorization {@code loadUser} adds on top of a valid token is proven separately in
 * {@code PrincipalServiceLoadUserTest}.
 */
public class OidcIdTokenValidationTest {

    private static final String ISSUER = "https://idp.example.com";
    private static final String CLIENT_ID = "console";

    /** The claim validator Spring wires behind the id-token decoder, over the generic "oidc" registration the
     *  console configures: issuer discovered from the issuer-uri, {@code clientId} the required audience. */
    private static OidcIdTokenValidator validator() {
        ClientRegistration registration = ClientRegistration.withRegistrationId("oidc")
                .clientId(CLIENT_ID)
                .clientSecret("secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/oidc")
                .authorizationUri(ISSUER + "/authorize")
                .tokenUri(ISSUER + "/token")
                .issuerUri(ISSUER)
                .build();
        return new OidcIdTokenValidator(registration);
    }

    private static Jwt idToken(Instant expiresAt, List<String> audience) {
        return Jwt.withTokenValue("id-token-value")
                .header("alg", "RS256")
                .subject("sub-123")
                .issuer(ISSUER)
                .audience(audience)
                .issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .expiresAt(expiresAt)
                .build();
    }

    @Test
    public void a_well_formed_id_token_is_accepted() {
        OAuth2TokenValidatorResult result = validator().validate(
                idToken(Instant.now().plusSeconds(3600), List.of(CLIENT_ID)));

        assertThat(result.hasErrors()).as("a live, correctly-audienced id token passes the claim validator").isFalse();
    }

    @Test
    public void an_expired_id_token_is_refused() {
        OAuth2TokenValidatorResult result = validator().validate(
                idToken(Instant.now().minusSeconds(3600), List.of(CLIENT_ID)));

        assertThat(result.hasErrors()).as("an id token whose exp is in the past is refused").isTrue();
        assertThat(result.getErrors()).extracting(OAuth2Error::getErrorCode).contains("invalid_id_token");
        assertThat(result.getErrors()).anySatisfy(error ->
                assertThat(error.getDescription()).contains("exp"));
    }

    @Test
    public void a_wrong_audience_id_token_is_refused() {
        OAuth2TokenValidatorResult result = validator().validate(
                idToken(Instant.now().plusSeconds(3600), List.of("some-other-client")));

        assertThat(result.hasErrors()).as("an id token minted for a different audience is refused").isTrue();
        assertThat(result.getErrors()).extracting(OAuth2Error::getErrorCode).contains("invalid_id_token");
        assertThat(result.getErrors()).anySatisfy(error ->
                assertThat(error.getDescription()).contains("aud"));
    }
}
