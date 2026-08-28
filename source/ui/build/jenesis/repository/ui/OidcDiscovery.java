package build.jenesis.repository.ui;

import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.client.registration.ClientRegistration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import module java.base;
import module java.net.http;

/**
 * OpenID Connect / RFC 8414 provider discovery: fetch the issuer's configuration document and turn it into a
 * {@link ClientRegistration.Builder}.
 *
 * <p><b>Why this exists rather than {@code ClientRegistrations.fromIssuerLocation}.</b> That helper is the ONLY
 * reason {@code com.nimbusds:oauth2-oidc-sdk} and its five transitives are on the graph, in both editions - and it
 * uses the SDK as nothing more than a spec-aware JSON parser. Jackson is already aboard, so the document is a
 * record and a builder. Spring has had "Consider removing com.nimbusds:oauth2-oidc-sdk dependency" open since
 * December 2023, labelled as breaking passivity; it was assigned to 7.0.x, the dependency was updated rather than
 * removed, and 7.1.0 still ships it. Waiting is not a plan.
 *
 * <p>This lives in the free console because that is the root: the enterprise OIDC module calls it rather than
 * carrying a second copy, and fixing only enterprise would change nothing, since {@code core/source/ui} keeps
 * pulling the same jars into both bundles.
 *
 * <p><b>The issuer check is not optional.</b> A discovery document names the issuer it belongs to, and it must be
 * the issuer that was asked for. Without that check a hostile or merely misconfigured discovery endpoint can hand
 * back somebody else's authorisation server, and every token this deployment then accepts was minted by whoever
 * that is - so dropping it would be a real security regression wearing the clothes of a dependency cleanup. It is
 * required by OpenID Connect Discovery 1.0 §4.3 and by RFC 8414 §3.3 for exactly this reason.
 */
public final class OidcDiscovery {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * Generous, and deliberately so. A discovery fetch is one incidental round trip on a path whose real work is
     * elsewhere, and it has already been the cause of a suite blaming the product: an OIDC discovery against
     * localhost lost a race with Spring's default read timeout under a full lane and was reported as an
     * "infrastructure failure". A slow provider should make a login slow, not make it fail.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private OidcDiscovery() {
    }

    /**
     * The builder for {@code issuer}, discovered.
     *
     * <p>Three locations are tried, in the order Spring tried them, because real providers differ on which they
     * serve: the OIDC suffix form, then the two RFC 8414 forms that insert the well-known segment BEFORE the
     * issuer's path. An issuer with no path collapses all three onto two URLs, which is the common case.
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
            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build()
                    .send(HttpRequest.newBuilder(location).timeout(TIMEOUT)
                                    .header("Accept", "application/json").GET().build(),
                            HttpResponse.BodyHandlers.ofString());
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
            // See the class comment: this is the whole defence against a discovery endpoint handing back another
            // provider's authorisation server, and it is a requirement of both specifications rather than a
            // hardening extra.
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
        // openid is what marks this an OIDC login rather than a plain OAuth2 one - it selects the id-token flow and
        // the qualified principal, and a spec-compliant provider requires it before UserInfo will answer. A caller
        // that wants more says so; this is the floor, not the set.
        builder.scope("openid");
        return builder;
    }

    /**
     * The client authentication method the provider advertises.
     *
     * <p>{@code client_secret_basic} is the specification's default and what is assumed when the document says
     * nothing, so a provider that omits the field is treated as every client library treats it.
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
        return Map.copyOf(metadata);
    }

    private static Object plain(JsonNode node) {
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            node.forEach(entry -> values.add(plain(entry)));
            return List.copyOf(values);
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
