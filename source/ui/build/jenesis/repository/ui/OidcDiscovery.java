package build.jenesis.repository.ui;

import module java.base;
import module java.net.http;

import build.jenesis.repository.net.http.BoundedBody;
import build.jenesis.repository.net.http.ScreenedHttpClient;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OpenID Connect / RFC 8414 provider discovery: fetch the issuer's configuration document and turn it into a
 * {@link ClientRegistration.Builder}. Every OIDC login in the product discovers through this.
 *
 * <p>The document's {@code issuer} must equal the issuer asked for (OpenID Connect Discovery 1.0 §4.3, RFC 8414 §3.3):
 * otherwise a hostile or misconfigured endpoint could hand back another authorisation server, and this deployment
 * would accept tokens it minted.
 */
public final class OidcDiscovery {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Generous, so a slow provider or a loaded machine makes a login slow rather than failed. */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** The most of a discovery document read: a provider's is a few kilobytes of endpoints and supported values. */
    private static final int LARGEST_DOCUMENT = 1024 * 1024;

    private OidcDiscovery() {
    }

    /**
     * The builder for {@code issuer}, discovered from the first of three locations that answers, since providers differ
     * on which they serve: the OIDC suffix form, then the two RFC 8414 forms that insert the well-known segment before
     * the issuer's path.
     */
    public static ClientRegistration.Builder fromIssuerLocation(String issuer) {
        String trimmed = Objects.requireNonNull(issuer, "issuer").trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("An OIDC issuer must not be blank");
        }
        URI base = URI.create(trimmed);
        List<URI> candidates = locations(base);
        List<String> failures = new ArrayList<>();
        for (URI candidate : candidates) {
            Optional<JsonNode> document = fetch(candidate, failures);
            if (document.isPresent()) {
                return build(trimmed, document.get());
            }
        }
        throw new IllegalStateException("Could not discover the OIDC provider at " + trimmed
                + "; tried " + candidates + " - " + String.join("; ", failures));
    }

    /** The three well-known locations, in the order a provider is most likely to serve them. */
    private static List<URI> locations(URI issuer) {
        String path = issuer.getPath() == null ? "" : issuer.getPath();
        String withoutTrailingSlash = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        URI root = issuer.resolve("/");
        return List.of(
                URI.create(trimTrailingSlash(issuer.toString()) + "/.well-known/openid-configuration"),
                root.resolve(".well-known/oauth-authorization-server" + withoutTrailingSlash),
                root.resolve(".well-known/openid-configuration" + withoutTrailingSlash));
    }

    private static Optional<JsonNode> fetch(URI location, List<String> failures) {
        try {
            HttpResponse<String> response = ScreenedHttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .redirectsWithinPrivateNetwork()
                    .build()
                    .send(HttpRequest.newBuilder(location).timeout(TIMEOUT)
                                    .header("Accept", "application/json").GET().build(),
                            BoundedBody.ofString(location, LARGEST_DOCUMENT));
            if (response.statusCode() != 200) {
                failures.add(location + " answered " + response.statusCode());
                return Optional.empty();
            }
            return Optional.of(JSON.readTree(response.body()));
        } catch (IOException | RuntimeException unreachable) {
            failures.add(location + " " + unreachable);
            return Optional.empty();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted discovering the OIDC provider at " + location, interrupted);
        }
    }

    private static ClientRegistration.Builder build(String issuer, JsonNode document) {
        String declared = text(document, "issuer");
        if (declared == null || !declared.equals(issuer)) {
            throw new IllegalStateException("The OIDC provider at " + issuer + " returned a document for issuer "
                    + declared + "; the two must be identical, so this document is not this provider's");
        }
        String authorization = required(document, "authorization_endpoint", issuer);
        String token = required(document, "token_endpoint", issuer);
        ClientRegistration.Builder builder = ClientRegistration.withRegistrationId(issuer)
                .authorizationUri(authorization)
                .tokenUri(token)
                .issuerUri(issuer)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/{action}/oauth2/code/{registrationId}")
                .clientAuthenticationMethod(authentication(document))
                .userNameAttributeName("sub")
                .providerConfigurationMetadata(metadata(document));
        String jwks = text(document, "jwks_uri");
        if (jwks != null) {
            builder.jwkSetUri(jwks);
        }
        String userInfo = text(document, "userinfo_endpoint");
        if (userInfo != null) {
            builder.userInfoUri(userInfo);
        }
        // openid selects the id-token flow and the qualified principal, and a compliant provider requires it before
        // UserInfo answers. A caller adds more.
        builder.scope("openid");
        return builder;
    }

    /**
     * The client authentication method the provider advertises, {@code client_secret_basic} (the specification's
     * default) when the document says nothing.
     */
    private static ClientAuthenticationMethod authentication(JsonNode document) {
        Set<String> supported = new LinkedHashSet<>();
        JsonNode methods = document.get("token_endpoint_auth_methods_supported");
        if (methods != null && methods.isArray()) {
            methods.forEach(method -> supported.add(method.asString()));
        }
        if (supported.isEmpty() || supported.contains("client_secret_basic")) {
            return ClientAuthenticationMethod.CLIENT_SECRET_BASIC;
        }
        if (supported.contains("client_secret_post")) {
            return ClientAuthenticationMethod.CLIENT_SECRET_POST;
        }
        if (supported.contains("none")) {
            return ClientAuthenticationMethod.NONE;
        }
        return ClientAuthenticationMethod.CLIENT_SECRET_BASIC;
    }

    /** The whole document, kept as Spring keeps it, so anything not modelled above is still reachable. */
    private static Map<String, Object> metadata(JsonNode document) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        document.propertyNames().forEach(name -> metadata.put(name, plain(document.get(name))));
        return Collections.unmodifiableMap(metadata);
    }

    /**
     * One metadata value as a plain JDK type. Every JSON shape is handled, nested objects included (Keycloak's
     * {@code mtls_endpoint_aliases}), since an unhandled one fails the whole discovery. Collections are wrapped rather
     * than copied, because a JSON null maps to a null element the copying factories reject.
     */
    private static Object plain(JsonNode node) {
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            node.forEach(entry -> values.add(plain(entry)));
            return Collections.unmodifiableList(values);
        }
        if (node.isObject()) {
            Map<String, Object> values = new LinkedHashMap<>();
            node.propertyNames().forEach(name -> values.put(name, plain(node.get(name))));
            return Collections.unmodifiableMap(values);
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isNumber()) {
            return node.asLong();
        }
        return node.isNull() ? null : node.asString();
    }

    private static String required(JsonNode document, String field, String issuer) {
        String value = text(document, field);
        if (value == null) {
            throw new IllegalStateException("The OIDC document for " + issuer + " declares no " + field
                    + ", so no login can be built from it");
        }
        return value;
    }

    private static String text(JsonNode document, String field) {
        JsonNode value = document.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
